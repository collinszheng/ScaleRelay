package com.example.scalerelay.data

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.kilograms
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 把一次称重写进 Health Connect。
 *
 * ## 只写体重
 *
 * 本项目**刻意只申请并只写 `WRITE_WEIGHT`**。理由（见 `docs/s400-bridge-design.md` §1）：
 * WeightDiary 的数据模型只有「体重 + 体脂率」，其余四类（体水分 / 骨量 / 去脂体重 /
 * 基础代谢）它根本不读，写了也没有出口。而体脂率需要移植 openScale 的 GPL 公式
 * （`S400BodyComposition`），是第二处 GPL 接触点 —— 本轮不做。
 *
 * ## 必须遵守的接口契约（WeightDiary `docs/08` 的规范 + 真机验证过的行为）
 *
 * | # | 契约 | 本文件怎么落实 |
 * |---|---|---|
 * | 1 | WeightDiary 只读 `WeightRecord.weight` | 只写这一类 |
 * | 2 | 体脂与体重按「同一本地日 + 时间最近」配对，所以两者必须**同 time 同 zoneOffset** | 本轮不写体脂，天然满足；将来补体脂时**必须复用同一个 `Instant` 与 `ZoneOffset`** |
 * | 3 | 用 HC 的 `metadata.id` 做去重键 | 给**稳定的 `clientRecordId`**：`s400-<mac>-<epochMs>-weight`。HC 对相同 `clientRecordId` 是**更新**（`metadata.id` 不变），所以重写是幂等的 |
 * | 4 | 丢弃 `measuredAt ≤ 库里最后一条手动记录` | 按真实称重时刻写入即可 |
 * | 5 | `\|w − anchor\| ≤ max(3.0kg, 5%×anchor)` 是接收条件 | 写真实体重即可，不干预 |
 * | 6 | 同一本地日 + 体重差 ≤0.1kg 会「邻近认领」合并 | **已知取舍**，不是 bug |
 * | 7 | `zoneOffset` 必须给真实本地偏移（不是 0） | `ZoneId.systemDefault().rules.getOffset(time)` |
 *
 * ## 两个硬约束（都踩过）
 *
 * 1. **HC 禁止写入未来时间，且在 `WeightRecord` 的构造函数里就抛**
 *    `IllegalArgumentException: Record time must not be in the future` ——
 *    不是插入时校验。所以**构造 record 的代码也必须包在 try 里**，
 *    否则异常会绕过错误处理直接崩进程。本文件因此把「构造」和「插入」放在同一个 try 内。
 * 2. **设备时钟可能快于手机**。设备时间戳来自秤，虽实测与手机一致，
 *    但写之前仍统一 clamp 到 `Instant.now()` —— 这是唯一能保证不触发第 1 条的办法。
 *
 * ## 写在哪一次测量上
 *
 * **只在 FINAL 包写入**（`isFinal == true`）。LIVE 包每秒来一条、且没有可靠时间戳，
 * 拿它写 HC 会瞬间灌进一堆重复记录。FINAL 包自带设备时间戳，且一次称重只发一次。
 */
class HealthConnectWriter(private val context: Context) {

    /** `WRITE_WEIGHT` 的权限对象，需在申请时与 manifest 里的字符串逐字对应。 */
    val permissions: Set<String> = setOf(
        HealthPermission.getWritePermission(WeightRecord::class),
    )

    /** HC 是否可用（本机是系统内置）。 */
    fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(context)

    fun isAvailable(): Boolean = sdkStatus() == HealthConnectClient.SDK_AVAILABLE

    suspend fun grantedPermissions(): Set<String> {
        if (!isAvailable()) return emptySet()
        return HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
    }

    suspend fun hasWritePermission(): Boolean = grantedPermissions().containsAll(permissions)

    /**
     * 让「已授权的权限」缓存失效。
     *
     * **本类刻意不缓存授权状态**（每次都问系统），所以这个方法目前是空实现 ——
     * 它存在的意义是**声明意图**：调用方（界面）在收到 HC 权限回调后应当认为
     * 状态可能已变。留一个空方法比留一个「看起来在缓存但其实没有」的字段好：
     * 后者会让下一个人以为存在缓存、从而写出依赖它的代码。
     *
     * 不缓存的理由：权限可能被用户在系统设置里随时撤销，而缓存的副本会让界面
     * 显示「已授权」而写入却失败 —— 那种不一致比多查一次系统调用贵得多。
     */
    fun invalidateCache() {
        // 有意为空，见 KDoc
    }

    /**
     * 写入一次称重。
     *
     * @param scaleMac 秤的 MAC，用于派生稳定的 clientRecordId（去掉冒号、小写）
     * @param measurement 只接受 `isFinal == true` 的测量；LIVE 会被拒绝并返回
     *   [Outcome.SkippedNotFinal]，以免污染 HC
     */
    suspend fun write(scaleMac: String, measurement: S400Measurement): Outcome {
        if (!isAvailable()) {
            return Outcome.Failed("健康数据共享不可用（sdkStatus=${sdkStatus()}）")
        }
        if (!hasWritePermission()) {
            return Outcome.Failed("没有写入权限，请先在设置里授权")
        }
        if (!measurement.isFinal) {
            // 明确拒绝而不是静默忽略：静默会让「为什么 HC 里没数据」变成谜题。
            return Outcome.SkippedNotFinal
        }

        val normalizedMac = scaleMac.replace(":", "").lowercase()

        // 时间：优先用设备给的时间戳（FINAL 包自带），它才是真实称重时刻。
        // 兜底用当前时刻 —— 但那样 clientRecordId 会不稳定，所以只作为最后手段。
        val deviceSeconds = measurement.deviceTimestampSeconds
        val rawTime = if (deviceSeconds != null) Instant.ofEpochSecond(deviceSeconds) else Instant.now()

        // ⚠️ clamp：HC 在构造函数里就拒绝未来时间。设备时钟快于手机时，
        // 不 clamp 就会在构造 WeightRecord 的那一行抛异常。
        val now = Instant.now()
        val time = if (rawTime.isAfter(now)) now else rawTime

        val offset: ZoneOffset = ZoneId.systemDefault().rules.getOffset(time)
        val clientRecordId = "s400-$normalizedMac-${time.toEpochMilli()}-weight"

        return try {
            // 构造与插入放在同一个 try 内 —— 见类注释的硬约束 1
            val record = WeightRecord(
                time = time,
                zoneOffset = offset,
                weight = measurement.weightKg.kilograms,
                metadata = Metadata.manualEntry(clientRecordId = clientRecordId),
            )

            val response = HealthConnectClient.getOrCreate(context).insertRecords(listOf(record))
            val id = response.recordIdsList.firstOrNull()
            Log.i(S400GattClient.TAG, "已写入 HC：${measurement.weightKg}kg @ $time，记录 id=$id")
            Outcome.Written(time, clientRecordId, id)
        } catch (t: Throwable) {
            // 这里能捕获到「未来时间」那个 IllegalArgumentException ——
            // 它是在构造函数里抛的，正是必须把构造也放进 try 的原因。
            Log.e(S400GattClient.TAG, "写入 HC 失败", t)
            Outcome.Failed("${t.javaClass.simpleName}: ${t.message}")
        }
    }

    sealed class Outcome {
        data class Written(
            val time: Instant,
            val clientRecordId: String,
            val recordId: String?,
        ) : Outcome()

        /** LIVE 包，按设计不写。不是错误。 */
        data object SkippedNotFinal : Outcome()

        data class Failed(val reason: String) : Outcome()
    }
}
