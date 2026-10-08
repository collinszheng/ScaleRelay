package com.example.scalerelay.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.scalerelay.ui.theme.ScaleRelayTheme

/**
 * ScaleRelay 的界面组件。
 *
 * ── 来源与许可证 ──────────────────────────────────────────────────────
 * 本文件派生自 **WeightDiary（MIT）的 `ui/settings/SettingsScreen.kt` 里的私有组件**
 * （`SectionLabel` / `SettingsGroup` / `GroupDivider` / `SettingsRow` / `ChevronRight` /
 *  `BackArrowButton`），按 MIT 许可证要求保留声明，详见仓库根目录 NOTICE.md。
 *
 * 为什么派生而不是照设计规范重写：本项目的硬性要求是「界面风格参考体重日记，
 * 保证风格统一」。**共用同一份实现比照规范重写一遍可靠得多** ——
 * 重写必然漂移，而漂移是渐进的、不会有人发现。
 * MIT 允许其代码进入 GPL-3.0 工程（反向不行），所以这条路在许可证上也是干净的。
 * ─────────────────────────────────────────────────────────────────────
 *
 * 与 WeightDiary 的**唯一差别**：那边的组件是 `private` 且写在设置页文件里，
 * 这里提成公共组件（ScaleRelay 的整个界面就这一个配置页，没有别的消费者）。
 * 视觉参数一个都没改 —— 改了就谈不上「统一」。
 */

@Composable
fun SectionLabel(text: String) {
    val typo = ScaleRelayTheme.typography
    val colors = ScaleRelayTheme.colors

    Text(
        text = text,
        style = typo.cardLabel,
        color = colors.textSecondary,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
    )
}

@Composable
fun SettingsGroup(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(ScaleRelayTheme.dimens.radiusCard)
    val colors = ScaleRelayTheme.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.cardFill)
            .border(0.5.dp, colors.cardBorder, shape),
    ) {
        content()
    }
}

@Composable
fun GroupDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ScaleRelayTheme.dimens.cardPadding)
            .height(0.5.dp)
            .background(ScaleRelayTheme.colors.divider),
    )
}

@Composable
fun SettingsRow(
    title: String,
    description: String,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    val colors = ScaleRelayTheme.colors
    val typo = ScaleRelayTheme.typography
    val dimen = ScaleRelayTheme.dimens

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = dimen.cardPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = typo.body,
                color = if (danger) colors.stateDanger else colors.textPrimary,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = typo.cardLabel,
                color = colors.textSecondary,
            )
        }
        Spacer(Modifier.size(8.dp))
        ChevronRight(tint = colors.textDisabled)
    }
}

/** 列表行右侧的指示箭头 */
@Composable
fun ChevronRight(tint: Color) {
    Canvas(modifier = Modifier.size(10.dp)) {
        val stroke = 1.5.dp.toPx()
        drawLine(
            color = tint,
            start = Offset(size.width * 0.2f, size.height * 0.1f),
            end = Offset(size.width * 0.75f, size.height * 0.5f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.75f, size.height * 0.5f),
            end = Offset(size.width * 0.2f, size.height * 0.9f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** 可访问性良好的文本输入行：灰底圆角，与 WeightDiary 的表单字段同款。 */
@Composable
fun ConfigField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    description: String? = null,
    enabled: Boolean = true,
    contentDescription: String = label,
) {
    val colors = ScaleRelayTheme.colors
    val typo = ScaleRelayTheme.typography
    val dimen = ScaleRelayTheme.dimens

    Column(modifier = Modifier.fillMaxWidth().padding(dimen.cardPadding)) {
        Text(
            text = label,
            style = typo.cardLabel,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(6.dp))

        val fieldShape = RoundedCornerShape(dimen.radiusField)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(fieldShape)
                .background(if (enabled) colors.fieldFill else colors.divider)
                .padding(horizontal = 12.dp)
                .semantics { this.contentDescription = contentDescription },
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = typo.body.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = typo.body,
                            color = colors.textDisabled,
                        )
                    }
                    inner()
                },
            )
        }

        if (description != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = description,
                style = typo.cardLabel,
                color = colors.textSecondary,
            )
        }
    }
}

/** 主色实心按钮，样式与 WeightDiary「保存」按钮一致。 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = ScaleRelayTheme.colors
    val typo = ScaleRelayTheme.typography
    val shape = RoundedCornerShape(ScaleRelayTheme.dimens.radiusButton)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(shape)
            .background(if (enabled) colors.accent else colors.textDisabled)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = typo.body,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** 次要按钮：灰底。用于「停止」这类非主路径动作。 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = ScaleRelayTheme.colors
    val typo = ScaleRelayTheme.typography
    val shape = RoundedCornerShape(ScaleRelayTheme.dimens.radiusButton)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(shape)
            .background(if (enabled) colors.divider else colors.cardFill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = typo.body,
            color = if (enabled) colors.textPrimary else colors.textDisabled,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** 用于放「状态」这类单调文字的样式化容器。 */
@Composable
fun StatusText(text: String, color: Color, style: TextStyle? = null) {
    Text(
        text = text,
        style = style ?: ScaleRelayTheme.typography.caption,
        color = color,
    )
}
