package com.example.scalerelay.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.scalerelay.MainActivity
import com.example.scalerelay.R
import com.example.scalerelay.data.ConfigStore
import com.example.scalerelay.data.HealthConnectWriter
import com.example.scalerelay.data.PendingStore
import com.example.scalerelay.data.S400GattClient
import com.example.scalerelay.data.S400Measurement
import com.example.scalerelay.data.ScaleConnectionState
import com.example.scalerelay.data.ScaleFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 常驻前台服务：盯着秤，收到 FINAL 测量就写 HC。
 *
 * 为什么必须是**前台服务**：BLE 扫描与长连接都在后台会被系统冻结，
 * 而「站上秤自动进 HC」要求 App 在用户没打开它的时候也在工作。
 *
 * 服务类型用 `connectedDevice` —— 它正是为「与外部设备保持连接」这一类场景准备的，
 * 且 Android 14+ 要求声明对应的 `FOREGROUND_SERVICE_CONNECTED_DEVICE` 权限。
 *
 * ## 状态怎么传给界面
 *
 * 用伴生对象上的 `StateFlow`（进程内单例）。这不是「最干净」的做法
 * （干净做法是 bindService 或注入容器），但它是**能少引入一整套依赖**的做法：
 * 状态只有一项、只有一个消费者（主界面），为此引 Hilt 或写一套 binder 不划算。
 * 这一点与 WeightDiary 的取舍一致（它也是手写依赖注入、明确不上 Hilt）。
 */
class ScaleMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var config: ConfigStore
    private lateinit var healthConnect: HealthConnectWriter
    private lateinit var pending: PendingStore
    private var gatt: S400GattClient? = null
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var scanning = false

    /** 必须留着回调引用才能 stopScan —— 传 null 或新建一个都不会停掉正在跑的扫描。 */
    private var scanCallback: ScanCallback? = null

    /** 收到 FINAL 后用设备时间戳做去重键，避免同一秒重复写。 */
    private var lastWrittenKey: String? = null

    // ================= 对外状态 =================
    //
    // ⚠️ 状态**只有一个来源**：伴生对象上的 runtimeState。
    //
    // 我第一版在实例上也放了一份 `_state`，于是「界面观察的」和「服务更新的」
    // 是两个不同的 MutableStateFlow —— 界面永远看不到更新。
    // 这类错误不会编译失败、也不会崩，只会「什么都不显示」，
    // 是最难查的一类。所以这里刻意不提供实例自己的状态字段。

    private val sharedState: MutableStateFlow<RuntimeState> get() = runtimeState

    data class RuntimeState(
        val running: Boolean = false,
        val connection: ScaleConnectionState = ScaleConnectionState.Idle,
        /** 运行期给用户看的一句话（成功写入、失败原因、提示）。 */
        val message: String? = null,
        val messageIsError: Boolean = false,
        /** 最近一次成功写入 HC 的体重与时刻。 */
        val lastWrittenKg: Double? = null,
        val lastWrittenAt: Instant? = null,
        /** 收到的最后一次测量（含 LIVE），用于界面实时显示。 */
        val lastMeasurement: S400Measurement? = null,
        val measurementsSeen: Int = 0,
        val writesSucceeded: Int = 0,
        /**
         * 还在等写入 HC 的条数。
         *
         * **正常运行时应当恒为 0** —— 收到就写、写完就删。
         * 它非 0 意味着「数据保住了但还没送出去」，这是本 App 唯一能提供的保证，
         * 所以必须在界面上可见（否则用户不知道有东西卡住了）。
         */
        val pendingCount: Int = 0,
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        config = ConfigStore(this)
        healthConnect = HealthConnectWriter(this)
        pending = PendingStore(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startMonitoring()
        }
        // START_STICKY：被系统杀掉后自动重启。这个 App 的定位就是「不用管它」，
        // 不自启的话用户不会知道它已经停了。
        return START_STICKY
    }

    private fun startMonitoring() {
        if (runtimeState.value.running) return

        startForegroundWithNotification(ScaleConnectionState.Connecting.describe())

        val cfg = config.load()
        if (!cfg.isComplete) {
            updateState {
                it.copy(
                    running = true,
                    connection = ScaleConnectionState.Error("配置不完整", "请先填写秤的 MAC 与登录令牌"),
                    message = "配置不完整：请先填写秤的 MAC 与登录令牌",
                    messageIsError = true,
                )
            }
            updateNotification("配置不完整")
            return
        }

        if (!hasBluetoothPermissions()) {
            updateState {
                it.copy(
                    running = true,
                    connection = ScaleConnectionState.Error("缺少蓝牙权限", "请在主界面授予「附近的设备」权限"),
                    message = "缺少蓝牙权限",
                    messageIsError = true,
                )
            }
            updateNotification("缺少蓝牙权限")
            return
        }

        val adapter = bluetoothAdapter()
        if (adapter == null || !adapter.isEnabled) {
            updateState {
                it.copy(
                    running = true,
                    connection = ScaleConnectionState.Error("蓝牙未开启", "请打开蓝牙后重试"),
                    message = "蓝牙未开启",
                    messageIsError = true,
                )
            }
            updateNotification("蓝牙未开启")
            return
        }

        updateState { it.copy(running = true, message = null, messageIsError = false) }

        // 启动时先把上次没写成功的补上 —— 这是排在最前面的动作，
        // 因为它代表「数据已到手但还没送出去」，比连上新数据更紧急。
        //
        // 队列为空时**不做任何事**：那时没什么可冲刷的，
        // 而且空队列还去查 HC 只会产生无意义的 IPC。
        updatePendingCount()
        val backlog = pending.count()
        if (backlog > 0) {
            Log.i(TAG, "启动时发现 $backlog 条待写入记录，先行冲刷")
            updateState {
                it.copy(
                    message = "有 $backlog 条称重还没写进健康数据，正在重试…",
                    messageIsError = false,
                )
            }
            flushPending()
        }

        startScanForScale(adapter)
    }

    // ================= 扫描找秤 =================

    @SuppressLint("MissingPermission")
    private fun startScanForScale(adapter: BluetoothAdapter) {
        if (scanning) return
        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            updateState {
                it.copy(
                    connection = ScaleConnectionState.Error("拿不到蓝牙扫描器", null),
                    message = "拿不到蓝牙扫描器",
                    messageIsError = true,
                )
            }
            return
        }

        val targetMac = config.mac
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        updateState { it.copy(connection = ScaleConnectionState.Connecting) }
        Log.i(TAG, "开始扫描，寻找 $targetMac")

        scanning = true
        try {
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val device = result.device ?: return
                    val mac = device.address ?: return
                    if (!mac.equals(targetMac, ignoreCase = true)) return

                    Log.i(TAG, "找到秤 $mac（rssi=${result.rssi}dBm），停止扫描并连接")
                    stopScan()
                    connect(device)
                }

                override fun onScanFailed(errorCode: Int) {
                    scanning = false
                    Log.e(TAG, "扫描失败 errorCode=$errorCode")
                    updateState {
                        it.copy(
                            connection = ScaleConnectionState.Error("扫描失败（$errorCode）", null),
                            message = "扫描失败，错误码 $errorCode",
                            messageIsError = true,
                        )
                    }
                }
            }
            scanCallback = callback
            scanner?.startScan(null, settings, callback)
        } catch (t: Throwable) {
            scanning = false
            Log.e(TAG, "启动扫描失败", t)
            updateState {
                it.copy(
                    connection = ScaleConnectionState.Error("启动扫描失败", t.message),
                    message = "启动扫描失败：${t.javaClass.simpleName}",
                    messageIsError = true,
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        val callback = scanCallback ?: return
        if (!scanning) return
        try {
            scanner?.stopScan(callback)
        } catch (t: Throwable) {
            Log.w(TAG, "stopScan 抛异常：${t.javaClass.simpleName}")
        }
        scanning = false
        scanCallback = null
    }

    // ================= 连接与收数 =================

    private fun connect(device: BluetoothDevice) {
        val cfg = config.load()

        val client = S400GattClient(this, object : S400GattClient.Listener {

            override fun onStateChanged(state: ScaleConnectionState) {
                Log.i(TAG, "状态 → ${state.describe()}")
                updateState { it.copy(connection = state) }
                updateNotification(state.describe())
            }

            override fun onMeasurement(measurement: S400Measurement) {
                handleMeasurement(measurement)
            }

            override fun onFailure(failure: ScaleFailure) {
                Log.e(TAG, "失败：${failure.describe()} / ${failure.hint()}")
                updateState {
                    it.copy(
                        connection = ScaleConnectionState.Error(failure.describe(), failure.hint()),
                        message = "${failure.describe()} —— ${failure.hint()}",
                        messageIsError = true,
                    )
                }
                updateNotification("出错：${failure.describe()}")
            }

            override fun onDisconnected() {
                Log.i(TAG, "与秤断开")
                updateState {
                    it.copy(
                        connection = ScaleConnectionState.Idle,
                        message = "与秤断开连接。正在重新寻找…",
                        messageIsError = false,
                    )
                }
                // 断线自动重扫。秤在不用时会休眠并断开，这是常态，不是错误。
                scheduleRescan()
            }
        })

        gatt = client
        client.connect(device, cfg.token)
    }

    /** 收到测量：LIVE 只更新界面，FINAL 才落盘并写 HC。 */
    private fun handleMeasurement(measurement: S400Measurement) {
        updateState {
            it.copy(
                lastMeasurement = measurement,
                measurementsSeen = it.measurementsSeen + 1,
            )
        }

        if (!measurement.isFinal) return

        // ⚠️ **先落盘，再写 HC。** 顺序不能反 ——
        // S400 不保存历史，这条数据一旦丢了就永久拿不回来，
        // 而「已到达 App 但没写进 HC」正是本 App 唯一能挽救的那类丢失。
        val entry = pending.add(measurement, System.currentTimeMillis())
        updatePendingCount()

        // 去重：同一个设备时间戳已经写成功过就不再写（重连、重复 FINAL 都会走到这里）
        val key = entry.deviceTimestampSeconds.toString()
        if (key == lastWrittenKey) {
            Log.i(TAG, "该记录已写入过，清理队列项：$key")
            pending.remove(entry.deviceTimestampSeconds)
            updatePendingCount()
            return
        }

        attemptWrite(entry)
    }

    /**
     * 尝试把一条待写入记录送进 HC。
     *
     * 成功 → 从队列删除。失败 → 留在队列里等下次重试（不删）。
     */
    private fun attemptWrite(entry: PendingStore.Entry) {
        scope.launch(Dispatchers.IO) {
            val outcome = healthConnect.write(config.mac, entry.toMeasurement())
            launch(Dispatchers.Main) {
                when (outcome) {
                    is HealthConnectWriter.Outcome.Written -> {
                        lastWrittenKey = entry.deviceTimestampSeconds.toString()
                        pending.remove(entry.deviceTimestampSeconds)
                        updatePendingCount()
                        Log.i(TAG, "写入成功并出队：${entry.weightKg}kg @ ${outcome.time}")
                        updateState {
                            it.copy(
                                lastWrittenKg = entry.weightKg,
                                lastWrittenAt = outcome.time,
                                writesSucceeded = it.writesSucceeded + 1,
                                message = "已写入 ${entry.weightKg} kg（${timeText(outcome.time)}）",
                                messageIsError = false,
                            )
                        }
                        updateNotification("已记录 ${entry.weightKg} kg")
                        // 成功了就顺手把积压的其它记录也冲一遍（网络式失败往往是整批的）
                        flushPending()
                    }

                    is HealthConnectWriter.Outcome.Failed -> {
                        // **不出队** —— 数据留在盘上，等重试。
                        pending.markAttempt(entry)
                        updatePendingCount()
                        Log.e(TAG, "写入失败（第 ${entry.attempts + 1} 次，仍留在队列）：${outcome.reason}")
                        updateState {
                            it.copy(
                                message = "写入健康数据失败：${outcome.reason}" +
                                    "（数据已保留，稍后自动重试）",
                                messageIsError = true,
                            )
                        }
                        updateNotification("写入失败，已保留待重试")
                        scheduleRetry()
                    }

                    HealthConnectWriter.Outcome.SkippedNotFinal -> {
                        // 队列里存的一定是 FINAL，走到这里说明状态异常，如实上报而不是静默删掉
                        Log.w(TAG, "队列里的记录被判定为非 FINAL，已跳过（未删除）")
                    }
                }
            }
        }
    }

    /** 把队列里所有还没写成功的记录依次尝试写入。 */
    private fun flushPending() {
        scope.launch(Dispatchers.Main) {
            val entries = pending.all()
            if (entries.isEmpty()) return@launch
            Log.i(TAG, "开始冲刷待写入队列，共 ${entries.size} 条")
            for (entry in entries) {
                // 逐条串行，避免并发写 HC 造成顺序错乱
                val outcome = withContext(Dispatchers.IO) {
                    healthConnect.write(config.mac, entry.toMeasurement())
                }
                if (outcome is HealthConnectWriter.Outcome.Written) {
                    lastWrittenKey = entry.deviceTimestampSeconds.toString()
                    pending.remove(entry.deviceTimestampSeconds)
                } else if (outcome is HealthConnectWriter.Outcome.Failed) {
                    pending.markAttempt(entry)
                    Log.w(TAG, "队列项仍失败：${outcome.reason}")
                }
            }
            updatePendingCount()
        }
    }

    private fun updatePendingCount() {
        val n = pending.count()
        updateState { it.copy(pendingCount = n) }
    }

    // ================= 重试调度 =================

    private var retryRunnable: Runnable? = null

    /**
     * 安排一次退避重试。
     *
     * 退避到 5 分钟封顶：HC 写入失败通常是「权限被撤销」「HC 服务异常」这类
     * 需要人来处理的原因，密集重试没有意义，但完全不重试就会永久丢数据。
     */
    private fun scheduleRetry() {
        retryRunnable?.let { handler.removeCallbacks(it) }
        val count = pending.count()
        if (count == 0) return
        val runnable = Runnable {
            if (runtimeState.value.running) {
                Log.i(TAG, "退避重试：队列 $count 条")
                flushPending()
                // 若仍有剩余，再排一次
                handler.postDelayed({
                    if (pending.count() > 0) scheduleRetry()
                }, RETRY_MAX_MS)
            }
        }
        retryRunnable = runnable
        handler.postDelayed(runnable, RETRY_BASE_MS)
    }

    private var rescanRunnable: Runnable? = null

    private fun scheduleRescan() {
        rescanRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { if (runtimeState.value.running) startMonitoringRestart() }
        rescanRunnable = runnable
        handler.postDelayed(runnable, RESCAN_DELAY_MS)
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun startMonitoringRestart() {
        gatt = null
        val adapter = bluetoothAdapter() ?: return
        if (!adapter.isEnabled) return
        startScanForScale(adapter)
    }

    // ================= 通知 =================

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ScaleMonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.action_stop), stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun startForegroundWithNotification(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ================= 收尾 =================

    private fun stopEverything() {
        Log.i(TAG, "停止监听")
        rescanRunnable?.let { handler.removeCallbacks(it) }
        retryRunnable?.let { handler.removeCallbacks(it) }
        stopScan()
        gatt?.disconnect()
        gatt = null
        updateState { it.copy(running = false, connection = ScaleConnectionState.Stopped, message = null) }
    }

    override fun onDestroy() {
        stopEverything()
        scope.cancel()
        super.onDestroy()
    }

    private fun updateState(transform: (RuntimeState) -> RuntimeState) {
        runtimeState.value = transform(runtimeState.value)
    }

    private fun hasBluetoothPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun bluetoothAdapter(): BluetoothAdapter? =
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun timeText(instant: Instant): String =
        LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm:ss"))

    companion object {
        const val TAG = "ScaleRelay"
        private const val CHANNEL_ID = "scalerelay_monitor"
        private const val NOTIFICATION_ID = 1001
        private const val RESCAN_DELAY_MS = 10_000L

        /** 首次重试的等待。 */
        private const val RETRY_BASE_MS = 60_000L

        /** 退避上限。HC 写入失败通常要人来处理，密集重试没意义。 */
        private const val RETRY_MAX_MS = 5 * 60_000L

        const val ACTION_STOP = "com.example.scalerelay.STOP"

        /**
         * 进程内共享的运行状态。界面直接观察它，不需要 bindService。
         * 见类注释里对这个取舍的说明。
         *
         * 只读版本给界面用；写入口只在服务内部（`updateState`）。
         */
        private val runtimeStateInternal = MutableStateFlow(RuntimeState())

        internal val runtimeState: MutableStateFlow<RuntimeState> get() = runtimeStateInternal

        val state: StateFlow<RuntimeState> = runtimeStateInternal.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, ScaleMonitorService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScaleMonitorService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
