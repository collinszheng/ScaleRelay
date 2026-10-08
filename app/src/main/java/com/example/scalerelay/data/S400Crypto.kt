package com.example.scalerelay.data

import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.CCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Mi Home v2 会话密码学：HKDF-SHA256 派生会话密钥 + AES-CCM 解密 CMTP 载荷。
 *
 * 本类是**按协议描述独立重写**的（依据 `nokistin/xiaomi-s400-live`，Apache-2.0），
 * 不是拷贝 GPL 源码。见 NOTICE.md。
 *
 * ---
 * **两个实现选择，都是被实测决定的**：
 *
 * 1. **HKDF 自己写**：它只是两次 `Mac("HmacSHA256")` 调用（extract + expand），30 行。
 *    已与 BouncyCastle 的 `HKDFBytesGenerator` 对拍：16/32/40/64/100 字节输出**逐字节一致**。
 *
 * 2. **AES-CCM 用 BouncyCastle，不用平台 `Cipher`**：
 *    原打算用平台自带的 `Cipher("AES/CCM/NoPadding")`（API 30+ 声称支持），
 *    但**离线对拍直接否掉了这个假设**：桌面 JVM 报
 *    `NoSuchAlgorithmException: Cannot find any provider supporting AES/CCM/NoPadding`。
 *    Android 上大概率支持，但「大概率」在这里不可接受 —— 若平台上其实没有，
 *    真机上会看到与「密钥不对」**完全一样**的失败，然后去错误的方向排查。
 *    这类「两个不同原因给出同一症状」的情形，是本项目反复吃亏的地方。
 */
object S400Crypto {

    private const val LOGIN_INFO = "mible-login-info"
    private const val HMAC_ALGORITHM = "HmacSHA256"

    /** CMTP 的 MIC/tag 长度：4 字节。 */
    private const val TAG_BITS = 32

    /** 由 HKDF 派生出的 4 段会话材料。 */
    class SessionKeys(
        val deviceKey: ByteArray,
        val appKey: ByteArray,
        val deviceIv: ByteArray,
        val appIv: ByteArray,
    )

    /**
     * HKDF-SHA256(token, salt = appRandom ‖ deviceRandom, info = "mible-login-info") → 64 字节，
     * 依次切出 deviceKey(16) / appKey(16) / deviceIv(4) / appIv(4)。
     *
     * 注意 deviceIv 与 appIv 各只有 **4 字节** —— CCM 的 nonce 是把它们和别的字段拼起来的。
     */
    fun deriveLoginKeys(token: ByteArray, appRandom: ByteArray, deviceRandom: ByteArray): SessionKeys {
        require(token.size == 12) { "token 必须是 12 字节，实际 ${token.size}" }
        require(appRandom.size == 16) { "appRandom 必须是 16 字节，实际 ${appRandom.size}" }
        require(deviceRandom.size == 16) { "deviceRandom 必须是 16 字节，实际 ${deviceRandom.size}" }

        val derived = hkdfSha256(
            ikm = token,
            salt = appRandom + deviceRandom,
            info = LOGIN_INFO.toByteArray(Charsets.US_ASCII),
            length = 64,
        )

        return SessionKeys(
            deviceKey = derived.copyOfRange(0, 16),
            appKey = derived.copyOfRange(16, 32),
            deviceIv = derived.copyOfRange(32, 36),
            appIv = derived.copyOfRange(36, 40),
        )
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        return mac.doFinal(data)
    }

    /**
     * 解密一条 CMTP 载荷。
     *
     * 输入布局（真机验证）：`iteration(2)` ‖ `密文 + MIC(4)`
     *
     * nonce(12) = `deviceIv(4)` ‖ `00000000(4)` ‖ `iteration(2)` ‖ `0000(2)`
     *
     * key = `deviceKey`，AES-CCM、tag 4 字节、**无 associatedData**。
     *
     * 失败时抛异常而**不是**返回 null —— 这样调用方不会把「解密失败」和
     * 「解密成功但内容为空」混为一谈。CMTP 解密失败说明分帧或密钥有问题，必须如实上报。
     */
    fun decryptCmtp(keys: SessionKeys, raw: ByteArray): ByteArray {
        require(raw.size >= 6) { "CMTP 载荷至少 6 字节，实际 ${raw.size}" }

        val iteration = raw.copyOfRange(0, 2)
        val cipherAndTag = raw.copyOfRange(2, raw.size)
        val nonce = keys.deviceIv + ByteArray(4) + iteration + ByteArray(2)

        val ccm = CCMBlockCipher.newInstance(AESEngine.newInstance())
        ccm.init(false, AEADParameters(KeyParameter(keys.deviceKey), TAG_BITS, nonce, null))

        val buffer = ByteArray(ccm.getOutputSize(cipherAndTag.size))
        // CCM 的 processBytes 返回恒为 0，输出长度由 doFinal 回传 ——
        // 别把它当偏移或长度（会丢掉尾部内容，这个坑在验证工具里实测踩过）。
        ccm.processBytes(cipherAndTag, 0, cipherAndTag.size, buffer, 0)
        val written = ccm.doFinal(buffer, 0)
        return buffer.copyOf(written)
    }

    // ================= HKDF-SHA256 =================

    /**
     * RFC 5869 的 HKDF。**只用 HMAC-SHA256**，不引库。
     *
     * 1. extract：PRK = HMAC(salt, ikm)
     * 2. expand：T(i) = HMAC(PRK, T(i-1) ‖ info ‖ i)，拼起来取前 length 字节
     */
    internal fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val hashLen = 32
        require(length <= 255 * hashLen) { "HKDF 输出过长：$length" }

        val prk = hmacSha256(salt, ikm)

        val output = ByteArray(length)
        var previous = ByteArray(0)
        var written = 0
        var counter: Byte = 1

        while (written < length) {
            val mac = Mac.getInstance(HMAC_ALGORITHM)
            mac.init(SecretKeySpec(prk, HMAC_ALGORITHM))
            mac.update(previous)
            mac.update(info)
            mac.update(counter)
            previous = mac.doFinal()

            val take = minOf(hashLen, length - written)
            System.arraycopy(previous, 0, output, written, take)
            written += take
            counter++
        }

        return output
    }

    /** 密钥指纹，用于日志核对「两端算出的是不是同一个密钥」。**不泄露密钥内容。** */
    fun fingerprint(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }
}
