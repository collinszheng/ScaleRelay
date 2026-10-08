package com.example.scalerelay.data

/**
 * 一次称重的测量值。**字段刻意都是可空的** ——
 * 协议里「无值」有明确编码，用 0 或 NaN 代替会让上层分不清「没测到」和「测到 0」。
 */
data class S400Measurement(
    /** 实时包（只有体重）还是最终包（带阻抗、profileId、设备时间戳）。 */
    val isFinal: Boolean,
    val weightKg: Double,
    /**
     * 协议里的「稳定位」。
     *
     * ⚠️ **不要拿它判断「是否最终结果」**。真机实测：FINAL 包的这个位是 `0`，
     * 但同一包的设备时间戳解出来正好是采集时刻，读数其实已经锁定。
     * 也就是说这个位的语义与我们原先的假设不同（可能指「已上传云端」之类），**存疑待核对**。
     * 判断「最终结果」请用 [isFinal]。
     */
    val stableFlag: Boolean,
    val profileId: Int?,
    /** 高阻抗（Ω）。本轮不用于写 HC（只写体重），但记录下来以便将来需要。 */
    val impedanceOhm: Double?,
    /** 低阻抗（Ω）。 */
    val impedanceLowOhm: Double?,
    /** 设备时钟给出的 Unix 秒。**只有 FINAL 包有**；LIVE 包为 null。 */
    val deviceTimestampSeconds: Long?,
    /** 原始 CSV，供排错。 */
    val rawCsv: String,
)

/**
 * CMTP 明文的解析。
 *
 * 明文形状（真机抓到的原样）：
 * ```
 * LIVE : 12 20 0f 00 07 08 01 00 01 01 00 05 | a0 | "608,0"
 * FINAL: 5d 20 2e 00 07 08 02 00 01 02 00 50 | a0 | "0,0,1,609,0,2,1791457834,0,...,0,4630,4260"
 * ```
 * 即 `0x00` 前缀 + `0xA0` 标记 + **ASCII CSV**。字段是**零填充的定长 ASCII**
 * （`609` 而不是 `0609`，但排在前面的字段带前导零）。
 *
 * **不猜**：段数不认识就返回 null（如实说「不是测量数据」），
 * 而不是硬套某个格式然后给出一个错误的体重。
 */
object S400MeasurementParser {

    /** 明文里的 CSV 起始标记。 */
    private const val MARKER: Byte = 0xA0.toByte()

    /** LIVE 包的段数：`体重raw, 稳定位`。 */
    private const val LIVE_PARTS = 2

    /** FINAL 包实测 30 段。取「至少 8 段」作为下界，容忍固件将来多加字段。 */
    private const val FINAL_PARTS_MIN = 8

    fun parse(plaintext: ByteArray): S400Measurement? {
        val marker = plaintext.indexOfFirst { it == MARKER }
        if (marker < 0 || marker + 1 >= plaintext.size) return null

        val csv = String(plaintext, marker + 1, plaintext.size - marker - 1, Charsets.US_ASCII)
            .replace("\u0000", "")
            .trim()
        if (csv.isEmpty()) return null

        val parts = csv.split(",")

        if (parts.size == LIVE_PARTS) {
            val weightRaw = parts[0].trim().toIntOrNull() ?: return null
            return S400Measurement(
                isFinal = false,
                weightKg = weightRaw / 10.0,
                stableFlag = parts[1].trim() == "1",
                profileId = null,
                impedanceOhm = null,
                impedanceLowOhm = null,
                deviceTimestampSeconds = null,
                rawCsv = csv,
            )
        }

        if (parts.size >= FINAL_PARTS_MIN) {
            // 字段位置由真机数据逐项确认（见 docs/s400-bridge-design.md · B-4）：
            //   [2] profileId   [3] 体重raw   [4] 稳定位   [6] 设备时间戳
            //   末两位 = 高阻抗raw / 低阻抗raw
            val profileId = parts[2].trim().toIntOrNull()
            val weightRaw = parts[3].trim().toIntOrNull() ?: return null
            val stable = parts[4].trim() == "1"
            val timestamp = parts[6].trim().toLongOrNull()
            val impedanceRaw = parts[parts.size - 2].trim().toIntOrNull()
            val impedanceLowRaw = parts[parts.size - 1].trim().toIntOrNull()

            return S400Measurement(
                isFinal = true,
                weightKg = weightRaw / 10.0,
                stableFlag = stable,
                profileId = profileId,
                impedanceOhm = impedanceRaw?.let { it / 10.0 },
                impedanceLowOhm = impedanceLowRaw?.let { it / 10.0 },
                deviceTimestampSeconds = timestamp,
                rawCsv = csv,
            )
        }

        return null
    }

    private fun ByteArray.indexOfFirst(predicate: (Byte) -> Boolean): Int {
        for (i in indices) if (predicate(this[i])) return i
        return -1
    }
}
