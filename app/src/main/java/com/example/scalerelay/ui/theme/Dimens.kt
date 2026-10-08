package com.example.scalerelay.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 尺寸与间距。派生自 WeightDiary（MIT）的 `ui/theme/Dimens.kt`，见 NOTICE.md。
 *
 * 全部走 8dp 栅格的变体，不要在各处写魔法数字。
 * 图表 / 悬浮按钮的尺寸在 ScaleRelay 里用不到，**已删除**。
 */
@Immutable
data class ScaleRelayDimens(
    // ── 间距 ──
    val pageHorizontal: Dp = 16.dp,
    val cardGap: Dp = 12.dp,
    val cardPadding: Dp = 16.dp,
    val sectionGap: Dp = 20.dp,

    // ── 尺寸 ──
    val topBarHeight: Dp = 56.dp,

    /** 无障碍最小触摸目标 */
    val minTouchTarget: Dp = 48.dp,

    // ── 圆角 ──
    val radiusCard: Dp = 20.dp,
    val radiusButton: Dp = 12.dp,
    val radiusTabContainer: Dp = 10.dp,
    val radiusField: Dp = 12.dp,
)

internal val DefaultDimens = ScaleRelayDimens()

val LocalScaleRelayDimens = staticCompositionLocalOf { DefaultDimens }
