package com.example.scalerelay.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * 把设计 token 接进 Compose。派生自 WeightDiary（MIT），见 NOTICE.md。
 *
 * 策略与 WeightDiary 相同：**只用浅色**（那边是决策 C1：深色模式本期不做）。
 * 因为颜色、字号、尺寸全部走 CompositionLocal 而不是硬编码，
 * 将来要加深色只需在这里按 `isSystemInDarkTheme()` 选一套即可，业务代码不用动。
 */
private val MaterialLightScheme = lightColorScheme(
    primary = LightColors.accent,
    onPrimary = Color.White,
    background = LightColors.background,
    onBackground = LightColors.textPrimary,
    surface = LightColors.background,
    onSurface = LightColors.textPrimary,
    surfaceVariant = LightColors.cardFill,
    onSurfaceVariant = LightColors.textSecondary,
    outline = LightColors.cardBorder,
    error = LightColors.stateDanger,
    onError = Color.White,
)

@Composable
fun ScaleRelayTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalScaleRelayColors provides LightColors,
        LocalScaleRelayTypography provides ScaleRelayType,
        LocalScaleRelayDimens provides DefaultDimens,
    ) {
        MaterialTheme(
            colorScheme = MaterialLightScheme,
            shapes = ScaleRelayShapes,
            content = content,
        )
    }
}

/**
 * 设计 token 的统一入口，用法：
 *
 * ```
 * val colors = ScaleRelayTheme.colors
 * val typo   = ScaleRelayTheme.typography
 * val dimen  = ScaleRelayTheme.dimens
 * ```
 */
object ScaleRelayTheme {
    val colors: ScaleRelayColors
        @Composable @ReadOnlyComposable get() = LocalScaleRelayColors.current

    val typography: ScaleRelayTypography
        @Composable @ReadOnlyComposable get() = LocalScaleRelayTypography.current

    val dimens: ScaleRelayDimens
        @Composable @ReadOnlyComposable get() = LocalScaleRelayDimens.current
}
