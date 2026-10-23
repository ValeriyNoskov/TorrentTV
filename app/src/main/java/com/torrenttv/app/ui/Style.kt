@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.torrenttv.app.ui

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.Typography
import com.torrenttv.app.R

/**
 * Единый шрифт приложения — Inter Variable (OFL, открытый).
 * Вариативные оси: одно семейство покрывает все насыщенности.
 */
val InterFamily = FontFamily(
    Font(
        R.font.inter_variable, FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        R.font.inter_variable, FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    Font(
        R.font.inter_variable, FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600)),
    ),
    Font(
        R.font.inter_variable, FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)),
    ),
)

/** Типографика приложения: Inter везде, лаконичная шкала размеров. */
val AppTypography: Typography = Typography().let { base ->
    fun with(scale: androidx.compose.ui.text.TextStyle) = scale.copy(fontFamily = InterFamily)
    Typography(
        displayLarge = with(base.displayLarge),
        displayMedium = with(base.displayMedium),
        displaySmall = with(base.displaySmall),
        headlineLarge = with(base.headlineLarge),
        headlineMedium = with(base.headlineMedium),
        headlineSmall = with(base.headlineSmall),
        titleLarge = with(base.titleLarge),
        titleMedium = with(base.titleMedium),
        titleSmall = with(base.titleSmall),
        bodyLarge = with(base.bodyLarge),
        bodyMedium = with(base.bodyMedium),
        bodySmall = with(base.bodySmall),
        labelLarge = with(base.labelLarge),
        labelMedium = with(base.labelMedium),
        labelSmall = with(base.labelSmall),
    )
}
