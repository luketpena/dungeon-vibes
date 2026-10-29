package com.dungeonvibes.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.dungeonvibes.R

/*
 * The app's two fonts, bundled in res/font (both SIL Open Font License; see LICENSE-fonts.txt).
 * Headers: Lilita One, chunky and a little funky. Everything else: Rubik.
 * Rubik is trimmed to Latin characters; anything else falls back to the system font.
 */

/** Lilita One has a single weight, so header styles always ask for Normal (never a faked bold). */
val HeaderFont = FontFamily(Font(R.font.lilita_one, FontWeight.Normal))

/** Rubik, as static instances of the variable font at the weights Material's styles use. */
val ContentFont = FontFamily(
    Font(R.font.rubik_regular, FontWeight.Normal),
    Font(R.font.rubik_medium, FontWeight.Medium),
    Font(R.font.rubik_semibold, FontWeight.SemiBold),
    Font(R.font.rubik_bold, FontWeight.Bold),
)

private fun TextStyle.header() = copy(fontFamily = HeaderFont, fontWeight = FontWeight.Normal)
private fun TextStyle.content() = copy(fontFamily = ContentFont)

/**
 * Material's type scale (sizes and line heights unchanged) with our fonts: display, headline and
 * title styles are headers (screen titles, dialog titles, section headers, tab labels); body and
 * label styles are content (values, hints, input labels, buttons).
 */
val AppTypography: Typography = Typography().run {
    Typography(
        displayLarge = displayLarge.header(),
        displayMedium = displayMedium.header(),
        displaySmall = displaySmall.header(),
        headlineLarge = headlineLarge.header(),
        headlineMedium = headlineMedium.header(),
        headlineSmall = headlineSmall.header(),
        titleLarge = titleLarge.header(),
        titleMedium = titleMedium.header(),
        titleSmall = titleSmall.header(),
        bodyLarge = bodyLarge.content(),
        bodyMedium = bodyMedium.content(),
        bodySmall = bodySmall.content(),
        labelLarge = labelLarge.content(),
        labelMedium = labelMedium.content(),
        labelSmall = labelSmall.content(),
    )
}
