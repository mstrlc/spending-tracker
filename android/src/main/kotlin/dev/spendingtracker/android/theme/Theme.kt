package dev.spendingtracker.android.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.spendingtracker.android.R

/**
 * The web app's palette (web/src/styles.css), converted from its oklch tokens to sRGB. Dark mode follows the
 * system, like the web's `prefers-color-scheme`.
 */

// Light: white chrome, near-black primary, red only for destructive.
private val LightScheme = lightColorScheme(
    primary = Color(0xFF111111),
    onPrimary = Color(0xFFFAFAFA),
    primaryContainer = Color(0xFF111111),
    onPrimaryContainer = Color(0xFFFAFAFA),
    secondary = Color(0xFFF5F5F5),
    onSecondary = Color(0xFF171717),
    secondaryContainer = Color(0xFFF5F5F5),
    onSecondaryContainer = Color(0xFF171717),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF0A0A0A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0A0A0A),
    surfaceVariant = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFF737373),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF5F5F5),
    surfaceContainerHigh = Color(0xFFF5F5F5),
    surfaceContainerHighest = Color(0xFFEDEDED),
    outline = Color(0xFFE5E5E5),
    outlineVariant = Color(0xFFE5E5E5),
    error = Color(0xFFE7000B),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFE7000B),
    onErrorContainer = Color(0xFFFFFFFF),
)

// Dark: near-black chrome, near-white primary; borders are white at 10%.
private val DarkScheme = darkColorScheme(
    primary = Color(0xFFF5F5F5),
    onPrimary = Color(0xFF171717),
    primaryContainer = Color(0xFFF5F5F5),
    onPrimaryContainer = Color(0xFF171717),
    secondary = Color(0xFF262626),
    onSecondary = Color(0xFFFAFAFA),
    secondaryContainer = Color(0xFF262626),
    onSecondaryContainer = Color(0xFFFAFAFA),
    background = Color(0xFF0A0A0A),
    onBackground = Color(0xFFFAFAFA),
    surface = Color(0xFF171717),
    onSurface = Color(0xFFFAFAFA),
    surfaceVariant = Color(0xFF262626),
    onSurfaceVariant = Color(0xFFA1A1A1),
    surfaceContainerLowest = Color(0xFF0A0A0A),
    surfaceContainerLow = Color(0xFF111111),
    surfaceContainer = Color(0xFF171717),
    surfaceContainerHigh = Color(0xFF262626),
    surfaceContainerHighest = Color(0xFF303030),
    outline = Color(0x1AFFFFFF),
    outlineVariant = Color(0x1AFFFFFF),
    error = Color(0xFFFF6467),
    onError = Color(0xFF171717),
    errorContainer = Color(0xFFFF6467),
    onErrorContainer = Color(0xFF171717),
)

/** Geist, the web app's face (bundled variable fonts; the same files @fontsource serves the web). */
val Geist = FontFamily(Font(R.font.geist_variable))

/** Geist Mono for every amount, like the web's `amount` utility (tabular figures). */
val GeistMono = FontFamily(Font(R.font.geist_mono_variable))

private fun textStyle(weight: FontWeight, size: Float, lineHeight: Float, letterSpacing: Float = 0f) =
    TextStyle(
        fontFamily = Geist,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        letterSpacing = letterSpacing.sp,
    )

val AppTypography = Typography(
    displayLarge = textStyle(FontWeight.Medium, 32f, 40f, -0.5f),
    displayMedium = textStyle(FontWeight.Medium, 28f, 36f, -0.4f),
    headlineLarge = textStyle(FontWeight.Medium, 24f, 32f),
    headlineMedium = textStyle(FontWeight.Medium, 20f, 28f),
    titleLarge = textStyle(FontWeight.Medium, 17f, 24f),
    titleMedium = textStyle(FontWeight.Medium, 15f, 22f),
    bodyLarge = textStyle(FontWeight.Normal, 16f, 24f),
    bodyMedium = textStyle(FontWeight.Normal, 14f, 20f),
    bodySmall = textStyle(FontWeight.Normal, 12f, 16f),
    labelLarge = textStyle(FontWeight.Medium, 14f, 20f),
    labelMedium = textStyle(FontWeight.Medium, 12f, 16f),
    labelSmall = textStyle(FontWeight.Medium, 11f, 16f),
)

/**
 * The small-dose colours that carry meaning (web/src/chips.tsx): the `--tone-*` record/state colours and the
 * eight `--hue-*` that tell categories and people apart. Neutral chrome comes from the [ColorScheme].
 */
data class Palette(
    val hues: List<Color>,
    val income: Color,
    val expense: Color,
    val deduction: Color,
    val transfer: Color,
    val settlement: Color,
    val warning: Color,
) {
    /** The [hues] entry for hue-n (1-based), null-safe. */
    fun hue(n: Int): Color = hues[(n - 1).coerceIn(hues.indices)]
}

private val LightPalette = Palette(
    hues = listOf(
        Color(0xFFC65954), Color(0xFFC48225), Color(0xFF749331), Color(0xFF00906F),
        Color(0xFF0091AD), Color(0xFF4072C5), Color(0xFF8959BA), Color(0xFFC0558D),
    ),
    income = Color(0xFF168B49),
    expense = Color(0xFFC55F1D),
    deduction = Color(0xFFAF4A88),
    transfer = Color(0xFF2971C6),
    settlement = Color(0xFF7A59C3),
    warning = Color(0xFFC78200),
)

private val DarkPalette = Palette(
    hues = listOf(
        Color(0xFFF28881), Color(0xFFEFB062), Color(0xFFA4C665), Color(0xFF5FC8A6),
        Color(0xFF4DC3DD), Color(0xFF7EABF6), Color(0xFFBE95EC), Color(0xFFEE8DBD),
    ),
    income = Color(0xFF54C57A),
    expense = Color(0xFFF49752),
    deduction = Color(0xFFE885BF),
    transfer = Color(0xFF70ADFB),
    settlement = Color(0xFFB298F9),
    warning = Color(0xFFEDB345),
)

val LocalPalette = compositionLocalOf { LightPalette }

/** Linear blend of two colours, `t` = 0 gives [a], 1 gives [b] (the web's color-mix in oklch, approximated in sRGB). */
fun blend(a: Color, b: Color, t: Float): Color {
    fun ch(from: Float, to: Float) = from + (to - from) * t
    return Color(
        red = ch(a.red, b.red),
        green = ch(a.green, b.green),
        blue = ch(a.blue, b.blue),
        alpha = ch(a.alpha, b.alpha),
    )
}

@Composable
fun SpendingTrackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    val palette = if (darkTheme) DarkPalette else LightPalette
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AppTypography,
            content = content,
        )
    }
}

/** androidx.compose.ui.graphics.Color as an ARGB int (kept for tests that assert on token values). */
fun Color.argb(): Int = toArgb()
