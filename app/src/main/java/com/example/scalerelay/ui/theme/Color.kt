package com.example.scalerelay.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/*
 * 原始色值。UI 代码里**不要直接引用这个文件里的私有常量**，
 * 一律通过 `ScaleRelayTheme.colors.<语义名>` 取，这样才能在加深色模式时只改一处。
 *
 * ── 来源与许可证 ──────────────────────────────────────────────────────
 * 本文件（含 ScaleRelay 的 Type.kt / Dimens.kt / Shape.kt / Theme.kt）
 * **派生自 WeightDiary（MIT）的 `ui/theme/`**，按 MIT 许可证要求保留声明，
 * 详见仓库根目录的 NOTICE.md。
 *
 * 为什么是派生而不是照设计规范重写：**「风格统一」这个要求，共用同一份 token 定义
 * 比照规范重写一遍可靠得多** —— 重写必然漂移，而漂移是渐进的、不易察觉的。
 * MIT 允许代码进入 GPL-3.0 工程（反向不行），所以这条路在许可证上也是干净的。
 * ─────────────────────────────────────────────────────────────────────
 */

private val White = Color(0xFFFFFFFF)
private val GreyFA = Color(0xFFFAFAFA)
private val GreyF7 = Color(0xFFF7F7F9)
private val GreyF5 = Color(0xFFF5F5F7)
private val GreyF2 = Color(0xFFF2F2F7)
private val GreyF0 = Color(0xFFF0F0F0)
private val GreyC7 = Color(0xFFC7C7CC)
private val Grey8E = Color(0xFF8E8E93)
private val Grey1C = Color(0xFF1C1C1E)

private val Mint = Color(0xFF3DD68C)
private val MintSoft = Color(0x1F3DD68C) // 12% 透明

/** 状态用色。ScaleRelay 不需要 BMI 四色，只保留「成功 / 警告 / 危险」三档。 */
private val StateOk = Mint
private val StateWarn = Color(0xFFFFCC00)
private val StateDanger = Color(0xFFD0021B)

/**
 * 语义化色板。
 *
 * 命名对应 WeightDiary `docs/02-设计规范.md` 的 token 表。
 * **ScaleRelay 只用到其中一部分**（没有图表、没有 BMI），
 * 但整套照搬是有意的：将来若增减颜色，两边仍能逐项对照，不会出现「这个颜色在哪都找不到」。
 */
@Immutable
data class ScaleRelayColors(
    val background: Color,
    /** 卡片填充。刻意不是纯白 —— 纯白卡片放在纯白背景上，阴影看不见 */
    val cardFill: Color,
    /** 0.5dp 发丝描边，用来替代 elevation */
    val cardBorder: Color,
    /** 选中态卡片填充 */
    val cardSelectedFill: Color,

    val accent: Color,
    /** 强调色 12% 透明 */
    val accentSoft: Color,

    val textPrimary: Color,
    val textSecondary: Color,
    val textDisabled: Color,
    val divider: Color,
    val fieldFill: Color,

    /** 运行状态：正常 / 需要注意 / 出错 */
    val stateOk: Color,
    val stateWarn: Color,
    val stateDanger: Color,
)

internal val LightColors = ScaleRelayColors(
    background = White,
    cardFill = GreyFA,
    cardBorder = GreyF0,
    cardSelectedFill = GreyF7,

    accent = Mint,
    accentSoft = MintSoft,

    textPrimary = Grey1C,
    textSecondary = Grey8E,
    textDisabled = GreyC7,
    divider = GreyF2,
    fieldFill = GreyF5,

    stateOk = StateOk,
    stateWarn = StateWarn,
    stateDanger = StateDanger,
)

val LocalScaleRelayColors = staticCompositionLocalOf { LightColors }
