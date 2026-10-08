package com.example.scalerelay.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.UUID

/**
 * S400 的 GATT 客户端：Mi Home v2 登录 + 加密 CMTP 测量通道。
 *
 * **这条路是实测出来的唯一可行路径**：S400 从不通过 BLE 广播发送测量数据
 * （206 个广播样本里 object 位恒为 0）。详见 `docs/s400-bridge-design.md`。
 *
 * 协议依据 `nokistin/xiaomi-s400-live`（Apache-2.0）的协议描述**独立重写**，非拷贝。
 * 认证流程与全部指令字面量都经真机逐字验证（一次通过）。
 *
 * 状态机：
 * ```
 * 写 UPNP  ← CMD_LOGIN
 * 写 AVDTP ← CMD_SEND_KEY
 * 等 AVDTP → RCV_RDY
 * 写 AVDTP ← appRandom(16 随机字节)
 * 等 AVDTP → RCV_OK
 * 等 AVDTP → deviceRandom 多帧 → 收齐 16 字节
 * 校验 remoteInfo == HMAC-SHA256(deviceKey, deviceRandom ‖ appRandom)   ← 这一步判定 token
 * ourInfo = HMAC(appKey, appRandom ‖ deviceRandom)
 * 写 AVDTP ← CMD_SEND_INFO → RCV_RDY → 写 ourInfo → RCV_OK
 * 等 UPNP  → CFM_LOGIN_OK → READY
 * ```
 */
class S400GattClient(
    context: Context,
    private val listener: Listener,
) {

    interface Listener {
        fun onStateChanged(state: ScaleConnectionState)
        /** 收到测量值。产品行为由调用方决定（写 HC）。 */
        fun onMeasurement(measurement: S400Measurement)
        fun onFailure(failure: ScaleFailure)
        fun onDisconnected()
    }

    private enum class AuthStep {
        IDLE,
        WAIT_KEY_READY,
        WAIT_KEY_OK,
        WAIT_DEVICE_RANDOM_HEADER,
        RECEIVE_DEVICE_RANDOM,
        WAIT_REMOTE_INFO_HEADER,
        RECEIVE_REMOTE_INFO,
        WAIT_INFO_READY,
        WAIT_INFO_OK,
        WAIT_LOGIN_RESULT,
        READY,
    }

    private class WriteRequest(
        val uuid: UUID,
        val value: ByteArray,
        val completed: (() -> Unit)?,
        val delayAfterMs: Long,
    )

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val random = SecureRandom()
    private val writeQueue = ArrayDeque<WriteRequest>()

    private var gatt: BluetoothGatt? = null
    private var authStep = AuthStep.IDLE
    private var writeInProgress = false
    private var currentWrite: WriteRequest? = null

    private var token: ByteArray? = null
    private var appRandom: ByteArray? = null
    private var deviceRandom: ByteArray? = null
    private var ourInfo: ByteArray? = null
    private var sessionKeys: S400Crypto.SessionKeys? = null

    private var receiveExpectedFrames = 0
    private var receiveFrames = 0
    private var receiveBuffer: ByteArrayOutputStream? = null

    private var cmtpExpectedFrames = 0
    private var cmtpBuffer = ByteArrayOutputStream()

    private var intentionalDisconnect = false
    private var finished = false

    /** 最近一次成功写入 HC 的记录 id，供界面显示。由外部设置。 */
    var lastWrittenRecordTime: Long? = null

    fun isReady(): Boolean = authStep == AuthStep.READY && sessionKeys != null

    // ================= 连接 =================

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice, tokenHex: String) {
        if (!S400GattProtocol.isValidToken(tokenHex)) {
            fail(ScaleFailure.WrongToken("令牌不是 24 个十六进制字符（实际 ${tokenHex.length} 个）"))
            return
        }
        token = S400GattProtocol.hex(tokenHex)

        emitState(ScaleConnectionState.Connecting)
        gatt = try {
            device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (t: Throwable) {
            fail(ScaleFailure.Connection("无法发起连接：${t.javaClass.simpleName}"))
            null
        }
        if (gatt == null) {
            fail(ScaleFailure.Connection("Android 无法创建 GATT 连接"))
            return
        }
        touchTimeout()
    }

    fun disconnect() {
        intentionalDisconnect = true
        handler.removeCallbacksAndMessages(null)
        runCatching {
            gatt?.disconnect()
            gatt?.close()
        }
        gatt = null
        authStep = AuthStep.IDLE
    }

    private val timeoutRunnable = Runnable {
        if (authStep != AuthStep.READY) {
            fail(ScaleFailure.Timeout(stageLabel()))
            disconnect()
        }
    }

    private fun touchTimeout() {
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, S400GattProtocol.TIMEOUT_MS)
    }

    private fun stageLabel(): String = when (authStep) {
        AuthStep.IDLE -> "连接/服务发现"
        AuthStep.WAIT_LOGIN_RESULT -> "等待登录结果"
        AuthStep.READY -> "已就绪"
        else -> "认证步骤 $authStep"
    }

    // ================= GATT 回调 =================

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) return

            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                appLog("已连接，开始服务发现")
                touchTimeout()
                if (!g.discoverServices()) {
                    fail(ScaleFailure.Protocol("discoverServices() 返回 false"))
                }
                return
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val wasIntentional = intentionalDisconnect
                gatt = null
                authStep = AuthStep.IDLE
                appLog("连接已断开 (status=$status${if (wasIntentional) ", 我方主动" else ""})")
                if (!wasIntentional && !finished) {
                    emitState(ScaleConnectionState.Idle)
                    listener.onDisconnected()
                }
                return
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail(ScaleFailure.Connection("GATT 错误 $status"))
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail(ScaleFailure.Protocol("服务发现失败 status=$status"))
                return
            }

            // 先核对必需特征是否都在。**在订阅之前**判断，这样「不是这台设备」
            // 和「订阅失败」能被区分开 —— 两者的处置完全不同。
            val missing = S400GattProtocol.requiredNotify.filter { findCharacteristic(it) == null }
            if (missing.isNotEmpty()) {
                fail(
                    ScaleFailure.NotS400(
                        "缺少必需特征：" + missing.joinToString { it.toString().take(8) },
                    ),
                )
                return
            }
            appLog("服务发现完成，必需特征齐备")
            subscribeIndex = 0
            subscribeNext()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (g !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail(ScaleFailure.Protocol("订阅失败（写 CCCD status=$status）"))
                return
            }
            subscribeNext()
        }

        @Deprecated("API < 33 的旧回调")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handleChanged(ch.uuid, ch.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            handleChanged(ch.uuid, value)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (g !== gatt || !writeInProgress) return
            writeInProgress = false
            val done = currentWrite
            currentWrite = null
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail(ScaleFailure.Protocol("写特征失败 status=$status"))
                return
            }
            done?.completed?.invoke()
            val delay = done?.delayAfterMs ?: 0L
            if (delay > 0) handler.postDelayed({ drainWrites() }, delay) else drainWrites()
        }
    }

    // ================= 订阅 =================

    private var subscribeIndex = 0

    @SuppressLint("MissingPermission")
    private fun subscribeNext() {
        val g = gatt ?: return

        while (subscribeIndex < S400GattProtocol.NOTIFY_CHARACTERISTICS.size) {
            val uuid = S400GattProtocol.NOTIFY_CHARACTERISTICS[subscribeIndex++]
            val required = uuid in S400GattProtocol.requiredNotify
            val ch = findCharacteristic(uuid)

            if (ch == null) {
                if (required) {
                    fail(ScaleFailure.NotS400("缺少必需特征 $uuid"))
                    return
                }
                continue
            }

            val supportsNotify = ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            val supportsIndicate = ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            if (!supportsNotify && !supportsIndicate) {
                if (required) {
                    fail(ScaleFailure.NotS400("必需特征 $uuid 不支持通知"))
                    return
                }
                continue
            }

            if (!g.setCharacteristicNotification(ch, true)) {
                if (required) {
                    fail(ScaleFailure.Protocol("无法开启 $uuid 的通知"))
                    return
                }
                continue
            }

            val cccd = ch.getDescriptor(S400GattProtocol.CCCD)
            if (cccd == null) {
                if (required) {
                    fail(ScaleFailure.Protocol("$uuid 没有 CCCD 描述符"))
                    return
                }
                continue
            }

            val value = if (supportsIndicate && !supportsNotify) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }

            if (!writeDescriptor(cccd, value)) {
                if (required) fail(ScaleFailure.Protocol("写 CCCD 失败（$uuid）"))
            }
            return
        }

        appLog("订阅完成，准备认证")
        handler.postDelayed({ beginAuthentication() }, S400GattProtocol.DEVICE_SETTLE_MS)
    }

    // ================= 认证 =================

    private fun beginAuthentication() {
        if (gatt == null) return
        authStep = AuthStep.IDLE
        appRandom = ByteArray(16).also { random.nextBytes(it) }
        emitState(ScaleConnectionState.Authenticating)
        touchTimeout()

        enqueueWrite(S400GattProtocol.UPNP, S400GattProtocol.CMD_LOGIN) {
            authStep = AuthStep.WAIT_KEY_READY
            touchTimeout()
            enqueueWrite(S400GattProtocol.AVDTP, S400GattProtocol.CMD_SEND_KEY)
        }
    }

    private fun handleChanged(uuid: UUID, value: ByteArray) {
        if (value.isEmpty()) return
        when (uuid) {
            S400GattProtocol.AVDTP -> handleAvdtp(value)
            S400GattProtocol.UPNP -> handleUpnp(value)
            S400GattProtocol.CMTP -> handleCmtp(value)
        }
    }

    private fun handleAvdtp(value: ByteArray) {
        touchTimeout()
        when (authStep) {
            AuthStep.WAIT_KEY_READY -> {
                if (!value.contentEquals(S400GattProtocol.RCV_RDY)) {
                    fail(ScaleFailure.Protocol("等待 KEY_READY 时收到意外应答"))
                    return
                }
                authStep = AuthStep.WAIT_KEY_OK
                writeParcel(S400GattProtocol.AVDTP, appRandom!!, null)
            }

            AuthStep.WAIT_KEY_OK -> {
                if (!value.contentEquals(S400GattProtocol.RCV_OK)) {
                    fail(ScaleFailure.Protocol("设备未确认 appRandom"))
                    return
                }
                authStep = AuthStep.WAIT_DEVICE_RANDOM_HEADER
            }

            AuthStep.WAIT_DEVICE_RANDOM_HEADER -> {
                if (!beginMultiframe(value)) {
                    fail(ScaleFailure.Protocol("deviceRandom 头非法"))
                    return
                }
                authStep = AuthStep.RECEIVE_DEVICE_RANDOM
                enqueueWrite(S400GattProtocol.AVDTP, S400GattProtocol.RCV_RDY)
            }

            AuthStep.RECEIVE_DEVICE_RANDOM -> {
                if (!appendMultiframe(value)) return
                val dr = receiveBuffer!!.toByteArray()
                if (dr.size != 16) {
                    fail(ScaleFailure.Protocol("deviceRandom 长度 ${dr.size}，期望 16"))
                    return
                }
                deviceRandom = dr
                authStep = AuthStep.WAIT_REMOTE_INFO_HEADER
                enqueueWrite(S400GattProtocol.AVDTP, S400GattProtocol.RCV_OK)
            }

            AuthStep.WAIT_REMOTE_INFO_HEADER -> {
                if (!beginMultiframe(value)) {
                    fail(ScaleFailure.Protocol("remoteInfo 头非法"))
                    return
                }
                authStep = AuthStep.RECEIVE_REMOTE_INFO
                enqueueWrite(S400GattProtocol.AVDTP, S400GattProtocol.RCV_RDY)
            }

            AuthStep.RECEIVE_REMOTE_INFO -> {
                if (!appendMultiframe(value)) return
                verifyRemoteInfo(receiveBuffer!!.toByteArray())
            }

            AuthStep.WAIT_INFO_READY -> {
                if (!value.contentEquals(S400GattProtocol.RCV_RDY)) {
                    fail(ScaleFailure.Protocol("设备未就绪接收 ourInfo"))
                    return
                }
                authStep = AuthStep.WAIT_INFO_OK
                writeParcel(S400GattProtocol.AVDTP, ourInfo!!, null)
            }

            AuthStep.WAIT_INFO_OK -> {
                if (!value.contentEquals(S400GattProtocol.RCV_OK)) {
                    fail(ScaleFailure.Protocol("设备未确认 ourInfo"))
                    return
                }
                authStep = AuthStep.WAIT_LOGIN_RESULT
            }

            else -> Unit
        }
    }

    /**
     * 判定 token 是否正确的那一步。
     *
     * 设备发来的 `remoteInfo` 应当是 `HMAC-SHA256(deviceKey, deviceRandom ‖ appRandom)`。
     * 对不上就是 **token 不对**（或秤被重置/重新加回过米家）——
     * 这必须与「连不上」「认证超时」区分开，因为用户的下一步动作完全不同。
     */
    private fun verifyRemoteInfo(remoteInfo: ByteArray) {
        val app = appRandom
        val device = deviceRandom
        val tk = token
        if (app == null || device == null || tk == null) {
            fail(ScaleFailure.Protocol("内部状态异常：缺密钥材料"))
            return
        }

        val keys = try {
            S400Crypto.deriveLoginKeys(tk, app, device)
        } catch (t: Throwable) {
            fail(ScaleFailure.Protocol("派生会话密钥失败：${t.javaClass.simpleName}"))
            return
        }
        sessionKeys = keys

        val expected = S400Crypto.hmacSha256(keys.deviceKey, device + app)
        if (!remoteInfo.contentEquals(expected)) {
            appLog("remoteInfo 不匹配：deviceKey 指纹=${S400Crypto.fingerprint(keys.deviceKey)}")
            fail(
                ScaleFailure.WrongToken(
                    "设备发来的 remoteInfo 与本地算出的 HMAC 不一致" +
                        "（本地期望 ${expected.size} 字节，收到 ${remoteInfo.size} 字节）",
                ),
            )
            return
        }

        appLog("remoteInfo HMAC 校验通过 —— token 正确")
        ourInfo = S400Crypto.hmacSha256(keys.appKey, app + device)
        authStep = AuthStep.WAIT_INFO_READY
        touchTimeout()
        enqueueWrite(S400GattProtocol.AVDTP, S400GattProtocol.RCV_OK) {
            enqueueWrite(S400GattProtocol.AVDTP, S400GattProtocol.CMD_SEND_INFO)
        }
    }

    private fun handleUpnp(value: ByteArray) {
        if (authStep != AuthStep.WAIT_LOGIN_RESULT) return
        touchTimeout()
        if (!value.contentEquals(S400GattProtocol.CFM_LOGIN_OK)) {
            fail(ScaleFailure.Protocol("登录失败，设备返回了非预期的应答"))
            return
        }
        handler.removeCallbacks(timeoutRunnable)
        authStep = AuthStep.READY
        appLog("认证完成（READY）")
        emitState(ScaleConnectionState.WaitingForMeasurement)
    }

    // ================= CMTP 测量通道 =================

    private fun handleCmtp(value: ByteArray) {
        if (authStep != AuthStep.READY) return

        // 多帧头：前 3 字节为 0 且 [5] 为 0。**帧数在 [4]（单字节）** ——
        // 这与认证阶段的多帧头（帧数在 [4]|[5]<<8）不同，别混用。
        if (value.size >= 6 &&
            value[0] == 0.toByte() && value[1] == 0.toByte() &&
            value[2] == 0.toByte() && value[5] == 0.toByte()
        ) {
            cmtpExpectedFrames = value[4].toInt() and 0xFF
            cmtpBuffer = ByteArrayOutputStream()
            if (cmtpExpectedFrames > 0) {
                enqueueWrite(S400GattProtocol.CMTP, S400GattProtocol.RCV_RDY)
            }
            return
        }

        if (cmtpExpectedFrames <= 0 || value.size < 2 || value[1] != 0.toByte()) return

        val frameNumber = value[0].toInt() and 0xFF
        cmtpBuffer.write(value, 2, value.size - 2)

        if (frameNumber < cmtpExpectedFrames) return

        val encrypted = cmtpBuffer.toByteArray()
        cmtpExpectedFrames = 0
        cmtpBuffer = ByteArrayOutputStream()

        val keys = sessionKeys
        if (keys == null) {
            appLog("收到 CMTP 数据但会话密钥为空")
            return
        }

        try {
            val plaintext = S400Crypto.decryptCmtp(keys, encrypted)
            val measurement = S400MeasurementParser.parse(plaintext)
            if (measurement != null) {
                listener.onMeasurement(measurement)
            } else {
                appLog("解出的明文不是测量数据，已忽略（${plaintext.size} 字节）")
            }
        } catch (t: Throwable) {
            // 解密失败**不改状态**：单包解密失败不意味着连接坏了，
            // 下一包很可能正常。把它变成「断连」会让系统在稳定工作时反复重启。
            appLog("CMTP 解密失败：${t.javaClass.simpleName}: ${t.message}")
        }

        enqueueWrite(S400GattProtocol.CMTP, S400GattProtocol.RCV_OK)
    }

    // ================= 多帧与写队列 =================

    private fun beginMultiframe(header: ByteArray): Boolean {
        if (header.size < 6) return false
        if (header[0] != 0.toByte() || header[1] != 0.toByte() || header[2] != 0.toByte()) return false
        receiveExpectedFrames = (header[4].toInt() and 0xFF) or ((header[5].toInt() and 0xFF) shl 8)
        if (receiveExpectedFrames <= 0) return false
        receiveFrames = 0
        receiveBuffer = ByteArrayOutputStream()
        return true
    }

    private fun appendMultiframe(frame: ByteArray): Boolean {
        val buffer = receiveBuffer ?: return false
        if (frame.size < 2) return false
        buffer.write(frame, 2, frame.size - 2)
        receiveFrames++
        return receiveFrames >= receiveExpectedFrames
    }

    private fun enqueueWrite(uuid: UUID, value: ByteArray, completed: (() -> Unit)? = null) {
        if (gatt == null) return
        writeQueue.add(WriteRequest(uuid, value, completed, 0L))
        drainWrites()
    }

    /**
     * 分段写一个数据块：每片 18 字节，片前置 2 字节**小端**帧号。
     * 真机实测：appRandom 分 1 片、ourInfo（32 字节）分 2 片。
     */
    private fun writeParcel(uuid: UUID, data: ByteArray, completed: (() -> Unit)?) {
        val chunkSize = S400GattProtocol.WRITE_CHUNK_SIZE
        val chunks = (data.size + chunkSize - 1) / chunkSize
        for (index in 0 until chunks) {
            val from = index * chunkSize
            val to = minOf(data.size, from + chunkSize)
            val payload = data.copyOfRange(from, to)
            val frameNumber = index + 1
            val framed = ByteArray(payload.size + 2)
            framed[0] = (frameNumber and 0xFF).toByte()
            framed[1] = ((frameNumber shr 8) and 0xFF).toByte()
            System.arraycopy(payload, 0, framed, 2, payload.size)
            enqueueWrite(
                uuid,
                framed,
                if (index == chunks - 1) completed else null,
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun drainWrites() {
        val g = gatt ?: return
        if (writeInProgress) return
        val request = writeQueue.poll() ?: return
        currentWrite = request

        val ch = findCharacteristic(request.uuid)
        if (ch == null) {
            fail(ScaleFailure.Protocol("找不到要写入的特征"))
            return
        }

        val writeType =
            if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }

        val started = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, request.value, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                ch.writeType = writeType
                @Suppress("DEPRECATION")
                ch.value = request.value
                @Suppress("DEPRECATION")
                g.writeCharacteristic(ch)
            }
        } catch (t: Throwable) {
            fail(ScaleFailure.Protocol("写特征抛异常：${t.javaClass.simpleName}"))
            return
        }

        if (!started) {
            fail(ScaleFailure.Protocol("Android 拒绝了写操作"))
            return
        }
        writeInProgress = true
    }

    @SuppressLint("MissingPermission")
    private fun writeDescriptor(descriptor: BluetoothGattDescriptor, value: ByteArray): Boolean = try {
        val g = gatt
        if (g == null) false
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(descriptor)
        }
    } catch (t: Throwable) {
        appLog("写描述符异常：${t.javaClass.simpleName}")
        false
    }

    private fun findCharacteristic(uuid: UUID): BluetoothGattCharacteristic? {
        val g = gatt ?: return null
        for (service in g.services) {
            service.getCharacteristic(uuid)?.let { return it }
        }
        return null
    }

    // ================= 工具 =================

    private fun fail(failure: ScaleFailure) {
        if (finished) return
        finished = true
        appLog("失败：${failure.describe()}")
        emitState(ScaleConnectionState.Error(failure.describe(), failure.hint()))
        listener.onFailure(failure)
        disconnect()
    }

    private fun emitState(state: ScaleConnectionState) {
        handler.post { listener.onStateChanged(state) }
    }

    private fun appLog(message: String) {
        Log.i(TAG, message)
    }

    companion object {
        const val TAG = "ScaleRelay"
    }
}
