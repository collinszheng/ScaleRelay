package com.example.scalerelay.data

import android.content.Context

/**
 * 配置持久化：秤的 MAC + login token + 是否随开机自启。
 *
 * **刻意用 `SharedPreferences` 而不是 DataStore / Room**：
 * 全部状态就是两个字符串加一个布尔值。引入 DataStore 要加协程流与依赖，
 * 引入 Room 要加 KSP、schema 与迁移 —— 为三项配置付那个代价不划算。
 * 这符合本项目的定位：只做「配置 → 收数 → 写 HC」三件事。
 *
 * ⚠️ **token 是敏感数据**：它能让人连上你家的秤。
 * 所以它只存在本 App 的私有目录里（`allowBackup=false`，不会进云备份），
 * **绝不写入日志**，界面上也只显示「已保存 / 未保存」而不是内容。
 */
class ConfigStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Config(
        val mac: String,
        val token: String,
    ) {
        /** 配置是否完整到可以尝试连接。 */
        val isComplete: Boolean
            get() = S400GattProtocol.isValidMac(mac) && S400GattProtocol.isValidToken(token)
    }

    var mac: String
        get() = prefs.getString(KEY_MAC, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_MAC, value.trim()).apply()

    var token: String
        get() = prefs.getString(KEY_TOKEN, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    /**
     * 是否在打开 App 时自动开始监听。
     *
     * 默认 true：这个 App 的全部价值就是「不用管它，称完自动进 HC」。
     * 默认关掉的话，用户称完发现没数据，会以为坏了。
     */
    var autoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()

    fun load(): Config = Config(mac = mac, token = token)

    fun save(config: Config) {
        prefs.edit()
            .putString(KEY_MAC, config.mac.trim())
            .putString(KEY_TOKEN, config.token.trim())
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    /** 脱敏展示 MAC：只留前三段与最后一段，中间两段隐去。 */
    fun maskedMac(): String {
        val parts = mac.split(":", "-").filter { it.isNotEmpty() }
        if (parts.size != 6) return "未填写"
        return "${parts[0]}:${parts[1]}:${parts[2]}:**:**:**:${parts[5]}"
    }

    /** token 只报长度与是否合法，**绝不回显内容**。 */
    fun tokenSummary(): String = when {
        token.isEmpty() -> "未填写"
        S400GattProtocol.isValidToken(token) -> "已保存（24 位）"
        else -> "格式不对（${token.length} 位，应为 24 位十六进制）"
    }

    private companion object {
        const val PREFS_NAME = "scalerelay_config"
        const val KEY_MAC = "mac"
        const val KEY_TOKEN = "token"
        const val KEY_AUTO_START = "auto_start"
    }
}
