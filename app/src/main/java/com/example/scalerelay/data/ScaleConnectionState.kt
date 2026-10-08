package com.example.scalerelay.data

/** S400 连接的运行状态。界面直接渲染它，所以每个状态都要能翻译成一句人话。 */
sealed class ScaleConnectionState {

    /** 未连接（服务已启动但还没开始连）。 */
    data object Idle : ScaleConnectionState()

    /** 正在连接 / 服务发现 / 订阅。 */
    data object Connecting : ScaleConnectionState()

    /** 正在做 Mi Home v2 认证。 */
    data object Authenticating : ScaleConnectionState()

    /** 认证完成，正在等测量数据。**这是常驻状态**。 */
    data object WaitingForMeasurement : ScaleConnectionState()

    /** 出错。带一句给用户看的原因。 */
    data class Error(val reason: String, val detail: String?) : ScaleConnectionState()

    /** 用户主动关闭。 */
    data object Stopped : ScaleConnectionState()

    /** 给界面用的一句话描述。 */
    fun describe(): String = when (this) {
        Idle -> "未连接"
        Connecting -> "正在连接秤…"
        Authenticating -> "正在认证…"
        WaitingForMeasurement -> "已连接，等待称重"
        is Error -> "出错：$reason"
        Stopped -> "已停止"
    }

    /** 是否属于「正常工作中」的状态（用于通知与界面着色）。 */
    fun isActive(): Boolean = this is WaitingForMeasurement || this is Connecting || this is Authenticating
}

/**
 * 连接出了问题时的分类。
 *
 * **分类是为了给出正确的下一步**，而不是为了好看：
 * 「token 不对」和「秤没找到」对用户意味着完全不同的动作（重新提取 token vs 检查秤的电源/米家）。
 * 上游把这两件事都叫「认证失败」，那对使用者毫无帮助。
 */
sealed class ScaleFailure {
    /** 蓝牙未开 / 没有适配器 / 缺权限。 */
    data class Bluetooth(val reason: String) : ScaleFailure()

    /** 连不上或中途断开。多半是米家抢了连接，或秤休眠了。 */
    data class Connection(val reason: String) : ScaleFailure()

    /** 设备不提供 S400 的那几个特征 —— 可能不是这台秤。 */
    data class NotS400(val reason: String) : ScaleFailure()

    /** **token 不对**。这是最需要单独区分的一类。 */
    data class WrongToken(val detail: String) : ScaleFailure()

    /** 认证或解密过程中的其它失败。 */
    data class Protocol(val reason: String) : ScaleFailure()

    /** 超时。 */
    data class Timeout(val stage: String) : ScaleFailure()

    /** 给用户看的原因 + 建议动作。 */
    fun describe(): String = when (this) {
        is Bluetooth -> "蓝牙不可用：$reason"
        is Connection -> "连不上秤：$reason"
        is NotS400 -> "这台设备不像 S400：$reason"
        is WrongToken -> "登录令牌不对"
        is Protocol -> "协议错误：$reason"
        is Timeout -> "超时：$stage"
    }

    fun hint(): String = when (this) {
        is Bluetooth -> "请确认蓝牙已打开，且本 App 已获得「附近的设备」权限"
        is Connection -> "请确认秤已唤醒、且米家 App 已完全退出（米家会抢走秤的连接）"
        is NotS400 -> "请确认填写的 MAC 就是 S400 的地址"
        is WrongToken -> "请重新提取登录令牌。注意：给秤恢复出厂设置或重新加回米家都会换掉它"
        is Protocol -> "请把日志发出来排查"
        is Timeout -> "请确认秤在附近并已唤醒，然后重试"
    }
}
