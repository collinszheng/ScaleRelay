package com.example.scalerelay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.scalerelay.service.ScaleMonitorService
import com.example.scalerelay.ui.components.ConfigField
import com.example.scalerelay.ui.components.GroupDivider
import com.example.scalerelay.ui.components.PrimaryButton
import com.example.scalerelay.ui.components.SecondaryButton
import com.example.scalerelay.ui.components.SectionLabel
import com.example.scalerelay.ui.components.SettingsGroup
import com.example.scalerelay.ui.components.SettingsRow
import com.example.scalerelay.ui.theme.ScaleRelayTheme
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 唯一的界面：配置页 + 运行状态。
 *
 * 布局刻意照搬 WeightDiary 的设置页骨架（顶栏居中标题 + 分组卡片 + `SectionLabel`），
 * 因为本项目的硬性要求是「界面风格参考体重日记，保证风格统一」，
 * 而**统一最可靠的实现方式是共用同一套组件与 token**，不是照规范重画一遍。
 * 见 `ui/components/SettingsComponents.kt` 的许可证说明。
 *
 * 内容只有三块（相对 `三工具整合开发方案.docx` §8.4.9 的 8 个页面是**刻意砍到最小**）：
 * 运行状态 / 秤的配置 / 健康数据共享，外加一组「使用说明」。
 * 没有历史、没有图表、没有多用户 —— 那些 WeightDiary 全都有。
 *
 * 原先还有第四块「关于」，**已删除**：那句「为什么需要这个 App」是一次性的背景交代，
 * 看过一遍就永远不需要再看，却占了整整一张卡片。真正需要它的人在 README 里能看到。
 */
@Composable
fun ConfigScreen(
    state: UiState,
    onMacChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onSave: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRequestHealthConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ScaleRelayTheme.colors
    val typo = ScaleRelayTheme.typography
    val dimen = ScaleRelayTheme.dimens

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            // 背景铺满含状态栏区域，只把内容做 insets 避让
            // （WeightDiary 决策 C9；targetSdk 35+ 在 Android 15 上强制边到边，
            //  不避让会让内容顶到屏幕最上沿并与标题重叠）
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        // ─────────── 顶栏 ───────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(dimen.topBarHeight),
        ) {
            Text(
                text = "ScaleRelay",
                style = typo.screenTitle,
                color = colors.textPrimary,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = dimen.pageHorizontal),
        ) {
            Spacer(Modifier.height(4.dp))

            // ─────────── 运行状态 ───────────
            SectionLabel("运行状态")
            SettingsGroup {
                Column(modifier = Modifier.padding(dimen.cardPadding)) {
                    Text(
                        text = state.connection.describe(),
                        style = typo.valueMedium,
                        color = when {
                            !state.running -> colors.textSecondary
                            state.connection is com.example.scalerelay.data.ScaleConnectionState.Error ->
                                colors.stateDanger
                            state.connection is com.example.scalerelay.data.ScaleConnectionState.WaitingForMeasurement ->
                                colors.stateOk
                            else -> colors.textPrimary
                        },
                        fontWeight = FontWeight.Medium,
                    )

                    // 运行期的实时数字。只留「最近一次收到/写入」这类**会变**的事实，
                    // 累计计数（已收到 N 条）对用户没有决策价值，删掉。
                    state.message?.let { msg ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = msg,
                            style = typo.cardLabel,
                            color = if (state.messageIsError) colors.stateDanger else colors.textSecondary,
                        )
                    }

                    state.lastMeasurement?.let { m ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "最近测量 ${m.weightKg} kg" + if (m.isFinal) "" else "（实时）",
                            style = typo.cardLabel,
                            color = colors.textSecondary,
                        )
                    }

                    state.lastWrittenAt?.let { at ->
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "已写入健康数据 · " +
                                LocalDateTime.ofInstant(at, ZoneId.systemDefault())
                                    .format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                            style = typo.cardLabel,
                            color = colors.textSecondary,
                        )
                    }

                    // 待写入队列。**正常恒为 0** —— 它非 0 意味着「数据保住了但还没送出去」，
                    // 是本 App 唯一能提供的保证，所以必须可见。
                    if (state.pendingCount > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${state.pendingCount} 条待写入，会自动重试",
                            style = typo.cardLabel,
                            color = colors.stateWarn,
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    if (state.running) {
                        SecondaryButton(text = "停止监听", onClick = onStop)
                    } else {
                        PrimaryButton(
                            text = "开始监听",
                            onClick = onStart,
                            enabled = state.configSaved,
                        )
                    }
                    if (!state.configSaved) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "请先填写并保存秤的地址与登录令牌",
                            style = typo.cardLabel,
                            color = colors.textSecondary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(dimen.sectionGap))

            // ─────────── 秤的配置 ───────────
            SectionLabel("米家体脂秤 S400")
            SettingsGroup {
                ConfigField(
                    label = "蓝牙地址（MAC）",
                    value = state.macInput,
                    onValueChange = onMacChange,
                    // 占位例子里**绝不能出现任何真实设备地址** ——
                    // 我第一版填的就是这台秤的真实 MAC，等于把用户隐私当成了「示例」。
                    // 这里用 AA:BB:CC:DD:EE:FF：它在 IEEE 的地址分配里不可能是任何厂商的真实 OUI。
                    placeholder = "AA:BB:CC:DD:EE:FF",
                    // 不再写「用 Xiaomi-cloud-tokens-extractor 提取」——
                    // 那句话在下面「使用说明」第 1 步里已经有了。同一件事说两遍是噪音。
                    description = null,
                    contentDescription = "秤的蓝牙地址",
                )
                GroupDivider()
                ConfigField(
                    label = "登录令牌（24 位十六进制）",
                    value = state.tokenInput,
                    onValueChange = onTokenChange,
                    // 占位文字只说明格式，不复述上面的标签
                    placeholder = "24 个 0-9 或 a-f",
                    // 只在**填错**时才给提示。格式正确时不显示任何字 ——
                    // 原来的「格式正确」是一句纯噪音：字段有内容且保存按钮可点，用户自然知道对了。
                    description = state.tokenError,
                    contentDescription = "秤的登录令牌",
                )
                GroupDivider()
                Column(modifier = Modifier.padding(dimen.cardPadding)) {
                    PrimaryButton(
                        text = "保存",
                        onClick = onSave,
                        enabled = state.canSave,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = "令牌只保存在本机，不上传、不写日志。",
                style = typo.cardLabel,
                color = colors.textSecondary,
                modifier = Modifier.padding(start = 4.dp),
            )

            Spacer(Modifier.height(dimen.sectionGap))

            // ─────────── 健康数据共享 ───────────
            SectionLabel("健康数据共享")
            SettingsGroup {
                SettingsRow(
                    title = "授权写入体重",
                    description = state.healthConnectDescription,
                    onClick = onRequestHealthConnect,
                )
            }

            Spacer(Modifier.height(dimen.sectionGap))

            // ─────────── 使用说明 ───────────
            SectionLabel("使用说明")
            SettingsGroup {
                Column(modifier = Modifier.padding(dimen.cardPadding)) {
                    UsageStep("1", "用电脑上的 Xiaomi-cloud-tokens-extractor 提取秤的 MAC 与登录令牌")
                    UsageStep("2", "填到上面并保存，再点「开始监听」")
                    UsageStep("3", "完全退出米家 App —— 它会抢走秤的连接")
                    UsageStep("4", "赤脚站上秤等读数稳定。之后每次称重都会自动进来")
                }
            }

            Spacer(Modifier.height(dimen.sectionGap))
        }

        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

@Composable
private fun UsageStep(index: String, text: String) {
    val colors = ScaleRelayTheme.colors
    val typo = ScaleRelayTheme.typography

    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text(
            text = "$index.",
            style = typo.cardLabel,
            color = colors.accent,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.padding(horizontal = 4.dp))
        Text(
            text = text,
            style = typo.cardLabel,
            color = colors.textSecondary,
        )
    }
}

/**
 * 界面状态。由 [com.example.scalerelay.MainActivity] 组装，
 * 数据来源是 [com.example.scalerelay.data.ConfigStore] 与
 * [ScaleMonitorService.state] 与 Health Connect 的授权状态。
 */
data class UiState(
    val macInput: String = "",
    val tokenInput: String = "",
    /**
     * **只在令牌格式不对时**才有的提示。
     *
     * 刻意不叫 `tokenHint` 那种「总是有一句话」的字段 ——
     * 格式正确时这里必须是 null，否则界面上会多出一句「格式正确」，
     * 那是纯噪音：字段有内容、保存按钮可点，用户自然知道对了。
     */
    val tokenError: String? = null,
    val canSave: Boolean = false,
    val configSaved: Boolean = false,
    val running: Boolean = false,
    val connection: com.example.scalerelay.data.ScaleConnectionState =
        com.example.scalerelay.data.ScaleConnectionState.Idle,
    val message: String? = null,
    val messageIsError: Boolean = false,
    val lastMeasurement: com.example.scalerelay.data.S400Measurement? = null,
    val lastWrittenAt: java.time.Instant? = null,
    /** 还在等写入 HC 的条数。正常运行时恒为 0。 */
    val pendingCount: Int = 0,
    val healthConnectDescription: String = "",
)
