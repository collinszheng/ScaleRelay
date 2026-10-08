package com.example.scalerelay.data

import android.content.Context
import android.util.Log

/**
 * 待写入 Health Connect 的测量队列（**持久化**）。
 *
 * ## 为什么需要它
 *
 * S400 **不保存历史**，它只在被踩的那一刻把数据推给当时连着的客户端。
 * 所以任何一次「数据已经到达 App 但没写进 HC」都会造成**永久丢失** ——
 * 秤那边拿不回来了。
 *
 * 实测确认的两类丢失点，性质完全不同：
 *
 * | 丢失点 | 能否挽救 |
 * |---|---|
 * | **App 没在运行**时的称重 | ❌ 救不了 —— 数据从没到过 App，秤不留历史 |
 * | **App 在运行、但写 HC 失败** | ✅ **能救** —— 数据已经到过 App |
 *
 * 本类负责第二类：**收到 FINAL 先落盘，写成功后删除**。
 * 顺序不能反 —— 先写 HC 再落盘的话，进程在两次操作之间被杀就丢了。
 *
 * ## 为什么不用 Room / DataStore
 *
 * 需要的就是「存几条、读出来、按 key 删」。`SharedPreferences` 足够，
 * 而 Room 要 KSP + schema + 迁移，DataStore 要协程流。
 * 这个项目全程遵循「只引入能说清用途的依赖」。
 *
 * ## 格式
 *
 * key = 设备时间戳（秒）；value = 竖线分隔的几个字段。
 * 竖线分隔而不是 JSON：字段是固定几个数字，手写序列化比引 JSON 库省事，
 * 而且**解析失败时容易发现**（`toDoubleOrNull` 直接返回 null），
 * 不会像 JSON 那样把「少了一个字段」变成静默的默认值。
 */
class PendingStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 一条待写入的记录。字段与 [S400Measurement] 对齐，但只保留写 HC 需要的。 */
    data class Entry(
        val deviceTimestampSeconds: Long,
        val weightKg: Double,
        val profileId: Int?,
        val impedanceOhm: Double?,
        val impedanceLowOhm: Double?,
        /** 重试次数，用于退避与「一直失败」的可见性。 */
        val attempts: Int,
    ) {
        /** 换回测量对象，交给 [HealthConnectWriter]。 */
        fun toMeasurement(): S400Measurement = S400Measurement(
            isFinal = true,
            weightKg = weightKg,
            stableFlag = false,
            profileId = profileId,
            impedanceOhm = impedanceOhm,
            impedanceLowOhm = impedanceLowOhm,
            deviceTimestampSeconds = deviceTimestampSeconds,
            rawCsv = "(来自待写入队列)",
        )
    }

    /**
     * 落盘一条待写入的记录。
     *
     * **幂等**：同一个设备时间戳重复落盘会覆盖而不是新增（重连、重复 FINAL 都不会堆积）。
     */
    fun add(measurement: S400Measurement, nowMillis: Long): Entry {
        val ts = measurement.deviceTimestampSeconds ?: (nowMillis / 1000)
        val entry = Entry(
            deviceTimestampSeconds = ts,
            weightKg = measurement.weightKg,
            profileId = measurement.profileId,
            impedanceOhm = measurement.impedanceOhm,
            impedanceLowOhm = measurement.impedanceLowOhm,
            attempts = 0,
        )
        prefs.edit().putString(keyOf(ts), serialize(entry)).apply()
        trimIfNeeded()
        Log.i(TAG, "已落盘待写入：${entry.weightKg}kg @ ts=$ts（当前队列 ${count()} 条）")
        return entry
    }

    fun remove(deviceTimestampSeconds: Long) {
        prefs.edit().remove(keyOf(deviceTimestampSeconds)).apply()
    }

    /** 记一次失败（attempts +1），用于退避与展示。 */
    fun markAttempt(entry: Entry) {
        val next = entry.copy(attempts = entry.attempts + 1)
        prefs.edit().putString(keyOf(next.deviceTimestampSeconds), serialize(next)).apply()
    }

    /** 按时间戳升序返回全部待写入记录（先写早的）。 */
    fun all(): List<Entry> {
        val out = ArrayList<Entry>(prefs.all.size)
        for ((key, value) in prefs.all) {
            if (key == KEY_COUNT_MARKER) continue
            val text = value as? String ?: continue
            deserialize(text)?.let { out.add(it) }
        }
        return out.sortedBy { it.deviceTimestampSeconds }
    }

    fun count(): Int = all().size

    fun clear() {
        prefs.edit().clear().apply()
    }

    // ================= 内部 =================

    private fun keyOf(ts: Long) = "$KEY_PREFIX$ts"

    private fun serialize(e: Entry): String = listOf(
        e.deviceTimestampSeconds.toString(),
        e.weightKg.toString(),
        e.profileId?.toString() ?: "",
        e.impedanceOhm?.toString() ?: "",
        e.impedanceLowOhm?.toString() ?: "",
        e.attempts.toString(),
    ).joinToString("|")

    private fun deserialize(text: String): Entry? {
        val parts = text.split("|")
        if (parts.size < 6) return null
        val ts = parts[0].toLongOrNull() ?: return null
        val weight = parts[1].toDoubleOrNull() ?: return null
        return Entry(
            deviceTimestampSeconds = ts,
            weightKg = weight,
            profileId = parts[2].toIntOrNull(),
            impedanceOhm = parts[3].toDoubleOrNull(),
            impedanceLowOhm = parts[4].toDoubleOrNull(),
            attempts = parts[5].toIntOrNull() ?: 0,
        )
    }

    /**
     * 上限保护：真的长期写不进去时，队列不能无限增长。
     * 超过上限就丢**最早**的（最新的体重更有价值）。
     */
    private fun trimIfNeeded() {
        val entries = all()
        if (entries.size <= MAX_ENTRIES) return
        val toDrop = entries.take(entries.size - MAX_ENTRIES)
        val editor = prefs.edit()
        toDrop.forEach { editor.remove(keyOf(it.deviceTimestampSeconds)) }
        editor.apply()
        Log.w(TAG, "待写入队列超过 $MAX_ENTRIES 条，丢弃了最早的 ${toDrop.size} 条")
    }

    private companion object {
        const val PREFS_NAME = "scalerelay_pending"
        const val KEY_PREFIX = "ts_"
        const val KEY_COUNT_MARKER = "__count"
        const val MAX_ENTRIES = 200
        const val TAG = S400GattClient.TAG
    }
}
