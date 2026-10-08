package com.example.scalerelay

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.scalerelay.data.ConfigStore
import com.example.scalerelay.data.HealthConnectWriter
import com.example.scalerelay.data.ScaleConnectionState
import com.example.scalerelay.service.ScaleMonitorService
import com.example.scalerelay.ui.ConfigScreen
import com.example.scalerelay.ui.UiState
import com.example.scalerelay.ui.theme.ScaleRelayTheme
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * 唯一的活动。承载配置页，并负责一次性的权限申请流程。
 *
 * 权限分两类，来源不同、失败表现也不同：
 * 1. **蓝牙**（`BLUETOOTH_SCAN` / `CONNECT`）—— 运行时权限，普通弹窗。
 * 2. **Health Connect 写入** —— 由 HC 自己的授权页处理，**且要求本 App 声明
 *    rationale intent**，否则 HC 会直接 `finishing`、弹窗根本不出现
 *    （见 `PermissionsRationaleActivity` 与 manifest 里的解释）。
 *
 * 本 Activity 在 Android 13+ 还要申请 `POST_NOTIFICATIONS`，
 * 否则常驻通知不显示 —— 而前台服务没有可见通知在部分 ROM 上会被杀。
 */
class MainActivity : ComponentActivity() {

    private lateinit var config: ConfigStore
    private lateinit var healthConnect: HealthConnectWriter

    /** 蓝牙权限申请（普通运行时权限）。 */
    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            Log.i(ScaleMonitorService.TAG, "蓝牙权限结果 = $granted")
            if (granted.values.all { it }) {
                ScaleMonitorService.start(this)
            } else {
                ScaleMonitorService.runtimeState.value =
                    ScaleMonitorService.runtimeState.value.copy(
                        message = "没有蓝牙权限，无法连接秤",
                        messageIsError = true,
                    )
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.i(ScaleMonitorService.TAG, "通知权限 = $granted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        config = ConfigStore(this)
        healthConnect = HealthConnectWriter(this)

        maybeAskNotificationPermission()

        setContent {
            ScaleRelayTheme {
                AppRoot(
                    config = config,
                    healthConnect = healthConnect,
                    onRequestBluetooth = { requestBluetooth() },
                )
            }
        }
    }

    private fun maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestBluetooth() {
        bluetoothPermissionLauncher.launch(
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
        )
    }
}

@Composable
private fun AppRoot(
    config: ConfigStore,
    healthConnect: HealthConnectWriter,
    onRequestBluetooth: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val runtime by ScaleMonitorService.state.collectAsStateWithLifecycle()
    val activity = context as? ComponentActivity

    // ── 配置的编辑态 ──
    var macInput by remember { mutableStateOf(config.mac) }
    var tokenInput by remember { mutableStateOf(config.token) }

    // ── HC 授权状态（异步查询）──
    // 必须**先声明**再被下面的 launcher 回调引用。
    var hcGranted by remember { mutableStateOf<Boolean?>(null) }
    var hcAvailable by remember { mutableStateOf(healthConnect.isAvailable()) }

    // Health Connect 权限申请必须走 HC 自己的 contract
    val hcPermissionLauncher = activity?.let {
        androidx.activity.compose.rememberLauncherForActivityResult(
            PermissionController.createRequestPermissionResultContract(),
        ) { granted ->
            Log.i(ScaleMonitorService.TAG, "HC 权限结果 = $granted")
            // ⚠️ 这里**必须重新向 HC 查询**，不能只用回调给的集合，也不能什么都不做。
            //
            // 我第一版只调了一个空实现的 invalidateCache()，结果授权成功后界面
            // 仍然显示「未授权 —— 点这里授权」—— 因为 hcGranted 只在「启动」与
            // 「running 变化」这两个时机读取，而授权恰好发生在这之后，于是永远停在旧值。
            // 这类「界面说的和系统状态不一致」的 bug 不崩不报错，
            // 只会让人以为授权失败 —— 比崩溃更难发现。
            scope.launch {
                val nowGranted = healthConnect.hasWritePermission()
                hcGranted = nowGranted
                Log.i(ScaleMonitorService.TAG, "重新查询后的 HC 授权状态 = $nowGranted")
            }
        }
    }

    // 启动时查一次
    LaunchedEffect(Unit) {
        hcAvailable = healthConnect.isAvailable()
        if (hcAvailable) {
            hcGranted = healthConnect.hasWritePermission()
        }
    }

    // 运行状态变化时再查一次：用户可能刚在系统设置里撤销了授权。
    // 不做「每次重组都查」—— 那会在输入框每敲一个字时都发起一次 IPC。
    LaunchedEffect(runtime.running, runtime.message) {
        if (hcAvailable) hcGranted = healthConnect.hasWritePermission()
    }

    val uiState = UiState(
        macInput = macInput,
        tokenInput = tokenInput,
        // 只在**填错**时给提示。空字段与格式正确都不显示任何字。
        tokenError = when {
            tokenInput.isEmpty() -> null
            tokenInput.matches(Regex("[0-9a-fA-F]{24}")) -> null
            else -> "需要 24 位十六进制，现在 ${tokenInput.length} 位"
        },
        canSave = macInput.matches(Regex("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}")) &&
            tokenInput.matches(Regex("[0-9a-fA-F]{24}")),
        configSaved = config.load().isComplete,
        running = runtime.running,
        connection = runtime.connection,
        message = runtime.message,
        messageIsError = runtime.messageIsError,
        lastMeasurement = runtime.lastMeasurement,
        lastWrittenAt = runtime.lastWrittenAt,
        pendingCount = runtime.pendingCount,
        healthConnectDescription = when {
            !hcAvailable -> "此设备不支持健康数据共享"
            hcGranted == null -> "正在检查授权状态…"
            hcGranted == false -> "未授权 —— 点这里授权"
            else -> "已授权"
        },
    )

    // 打开 App 时若已配置且开启自启，自动开始监听。
    // 这个 App 的全部价值就是「不用管它」，所以默认自启。
    LaunchedEffect(Unit) {
        if (config.autoStart && config.load().isComplete && !ScaleMonitorService.state.value.running) {
            if (hasBluetoothPermission(context)) {
                ScaleMonitorService.start(context)
            } else {
                onRequestBluetooth()
            }
        }
    }

    ConfigScreen(
        state = uiState,
        onMacChange = { macInput = it.trim() },
        onTokenChange = { tokenInput = it.trim() },
        onSave = {
            config.save(ConfigStore.Config(macInput, tokenInput))
            ScaleMonitorService.runtimeState.value =
                ScaleMonitorService.runtimeState.value.copy(
                    message = "配置已保存",
                    messageIsError = false,
                )
        },
        onStart = {
            if (config.load().isComplete) {
                if (hasBluetoothPermission(context)) {
                    ScaleMonitorService.start(context)
                } else {
                    onRequestBluetooth()
                }
            }
        },
        onStop = { ScaleMonitorService.stop(context) },
        onRequestHealthConnect = {
            if (hcAvailable) {
                scope.launch {
                    val granted = healthConnect.grantedPermissions()
                    val missing = healthConnect.permissions - granted
                    if (missing.isEmpty()) {
                        hcGranted = true
                        ScaleMonitorService.runtimeState.value =
                            ScaleMonitorService.runtimeState.value.copy(
                                message = "健康数据共享已授权",
                                messageIsError = false,
                            )
                    } else {
                        hcPermissionLauncher?.launch(missing)
                    }
                }
            }
        },
    )
}

private fun hasBluetoothPermission(context: android.content.Context): Boolean =
    androidx.core.content.ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.BLUETOOTH_SCAN,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
        androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
