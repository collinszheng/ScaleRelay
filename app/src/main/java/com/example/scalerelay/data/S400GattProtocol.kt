package com.example.scalerelay.data

import java.util.UUID

/**
 * 小米 S400 的 Mi Home v2 GATT 协议常量。
 *
 * **这些 UUID 不是标准 BLE 服务** —— 它们是复用了 16 位 UUID 空间的厂商用法：
 * UPNP / AVDTP / AVCTP 是蓝牙标准的名字，但 S400 在里面跑的是小米自己的登录协议。
 *
 * 全部由真机 `discoverServices()` 的结果**逐条核对过**（见 docs/s400-bridge-design.md
 * 「路线 B · B-1 实际发现的服务与特征」）。六个特征都挂在 `0xFE95` 这个 MiBeacon 服务下。
 *
 * 协议依据 `nokistin/xiaomi-s400-live`（Apache-2.0）的协议描述**独立实现**，非拷贝。
 */
object S400GattProtocol {

    /** 登录指令通道（也叫 UPNP）。承载 CMD_LOGIN 与最终的 CFM_LOGIN_OK。 */
    val UPNP: UUID = UUID.fromString("00000010-0000-1000-8000-00805f9b34fb")

    /** 认证通道（也叫 AVDTP）。承载全部的密钥交换与 ourInfo/remoteInfo。 */
    val AVDTP: UUID = UUID.fromString("00000019-0000-1000-8000-00805f9b34fb")

    val AVCTP: UUID = UUID.fromString("00000017-0000-1000-8000-00805f9b34fb")
    val VEND1A: UUID = UUID.fromString("0000001a-0000-1000-8000-00805f9b34fb")

    /** 测量数据通道（CMTP）。认证完成后设备从这里推加密的测量数据。 */
    val CMTP: UUID = UUID.fromString("0000001b-0000-1000-8000-00805f9b34fb")

    val VEND1C: UUID = UUID.fromString("0000001c-0000-1000-8000-00805f9b34fb")

    /** 标准 CCCD（客户端特征配置描述符），用于开启通知。 */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /**
     * 需要订阅通知的特征。
     *
     * **必需**的只有 UPNP / AVDTP / CMTP（见 `requiredNotify`）；
     * 其余三个是一并订阅的，真机确认它们存在且支持 notify，但本协议不用。
     * 留着是因为「订阅了但不用」比「以为不需要结果某天发现漏了」代价小。
     */
    val NOTIFY_CHARACTERISTICS: List<UUID> = listOf(UPNP, AVDTP, AVCTP, VEND1A, CMTP, VEND1C)

    val requiredNotify: Set<UUID> = setOf(UPNP, AVDTP, CMTP)

    // ---- 指令字面量（真机逐字验证过）----

    /** 登录请求。 */
    val CMD_LOGIN: ByteArray = hex("24000000")

    /** 请求发送登录密钥。 */
    val CMD_SEND_KEY: ByteArray = hex("0000000b0100")

    /** 请求发送登录信息（我们回 ourInfo）。 */
    val CMD_SEND_INFO: ByteArray = hex("0000000a0200")

    /** 设备说「我准备好了，你可以发」。 */
    val RCV_RDY: ByteArray = hex("00000101")

    /** 设备说「收到并确认」。 */
    val RCV_OK: ByteArray = hex("00000100")

    /** 登录成功。 */
    val CFM_LOGIN_OK: ByteArray = hex("21000000")

    // ---- 时序参数 ----

    /** 连接 + 认证的总超时。真机上整套认证在 1 秒内完成，30 秒足够宽松。 */
    const val TIMEOUT_MS: Long = 30_000L

    /** 全部订阅完成后、开始认证前的等待，让设备稳定。 */
    const val DEVICE_SETTLE_MS: Long = 300L

    /** 写包分片大小。真机实测：appRandom 分 1 片、ourInfo 分 2 片。 */
    const val WRITE_CHUNK_SIZE: Int = 18

    /** 分片之间的间隔（上游为兼容设备处理速度所设）。 */
    const val WRITE_FRAME_DELAY_MS: Long = 50L

    fun isValidMac(mac: String): Boolean =
        mac.matches(Regex("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}"))

    /** login token 必须是 24 个十六进制字符 = 12 字节。 */
    fun isValidToken(token: String): Boolean =
        token.matches(Regex("[0-9a-fA-F]{24}"))

    internal fun hex(value: String): ByteArray {
        val out = ByteArray(value.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(value[i * 2], 16) shl 4) or
                Character.digit(value[i * 2 + 1], 16)).toByte()
        }
        return out
    }
}
