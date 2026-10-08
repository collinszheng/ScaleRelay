package com.example.scalerelay.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 只是为了让 Material3 组件的默认圆角不至于和设计稿冲突。
 * 业务代码请用 `ScaleRelayTheme.dimens.radiusXxx`，不要用这里的档位。
 * 派生自 WeightDiary（MIT），见 NOTICE.md。
 */
internal val ScaleRelayShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)
