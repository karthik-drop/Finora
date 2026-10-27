package com.finora.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.finora.app.data.repo.ThemeMode

// Brand: a deep evergreen with a restrained brass accent. Rebrand by editing these two ramps.
private val Evergreen = Color(0xFF0E4D45)
private val EvergreenLight = Color(0xFF7FD1C2)
private val Brass = Color(0xFFB08A4A)
private val BrassLight = Color(0xFFE2C38C)

private val LightColors = lightColorScheme(
    primary = Evergreen, onPrimary = Color.White,
    primaryContainer = Color(0xFFCFEDE6), onPrimaryContainer = Color(0xFF00201B),
    secondary = Brass, onSecondary = Color.White,
    secondaryContainer = Color(0xFFF4E6CC), onSecondaryContainer = Color(0xFF2B1E06),
    tertiary = Color(0xFF4A5D8A), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDCE2F9), onTertiaryContainer = Color(0xFF0C1A3A),
    background = Color(0xFFF7F7F4), onBackground = Color(0xFF151A19),
    surface = Color(0xFFF7F7F4), onSurface = Color(0xFF151A19),
    surfaceVariant = Color(0xFFE6E9E6), onSurfaceVariant = Color(0xFF515A57),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF1F2EF),
    surfaceContainerHigh = Color(0xFFEBEDEA),
    surfaceContainerHighest = Color(0xFFE5E8E4),
    outline = Color(0xFF8A9390), outlineVariant = Color(0xFFDCE0DD),
    error = Color(0xFFB3261E), onError = Color.White, errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    primary = EvergreenLight, onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF0F4F46), onPrimaryContainer = Color(0xFFB4F1E4),
    secondary = BrassLight, onSecondary = Color(0xFF3F2D07),
    secondaryContainer = Color(0xFF5A4419), onSecondaryContainer = Color(0xFFF7E1B5),
    tertiary = Color(0xFFB6C4F2), onTertiary = Color(0xFF1F2E57),
    tertiaryContainer = Color(0xFF34446F), onTertiaryContainer = Color(0xFFDCE2F9),
    background = Color(0xFF0D1110), onBackground = Color(0xFFE2E6E4),
    surface = Color(0xFF0D1110), onSurface = Color(0xFFE2E6E4),
    surfaceVariant = Color(0xFF2A302E), onSurfaceVariant = Color(0xFFB9C1BE),
    surfaceContainerLowest = Color(0xFF090C0B),
    surfaceContainerLow = Color(0xFF151A19),
    surfaceContainer = Color(0xFF191F1D),
    surfaceContainerHigh = Color(0xFF212725),
    surfaceContainerHighest = Color(0xFF2B3230),
    outline = Color(0xFF838C89), outlineVariant = Color(0xFF353C3A),
    error = Color(0xFFF2B8B5), onError = Color(0xFF601410), errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFF9DEDC),
)

/** Money semantics, kept apart from Material roles so "income" is never just "primary". */
@Immutable
data class FinanceColors(val income: Color, val expense: Color, val neutral: Color, val warning: Color, val chart: List<Color>)

private val LightFinance = FinanceColors(
    income = Color(0xFF1B7F5A), expense = Color(0xFFB4413C), neutral = Color(0xFF5B6764), warning = Color(0xFFB7791F),
    chart = listOf(Color(0xFF0E4D45), Color(0xFFB08A4A), Color(0xFF4A5D8A), Color(0xFF8E5572), Color(0xFF3E8E7E), Color(0xFFC96F4A), Color(0xFF6B7FB8), Color(0xFF9AA5A1)),
)
private val DarkFinance = FinanceColors(
    income = Color(0xFF6FD3A6), expense = Color(0xFFF2A09A), neutral = Color(0xFFA6B1AE), warning = Color(0xFFF0C36D),
    chart = listOf(Color(0xFF7FD1C2), Color(0xFFE2C38C), Color(0xFFB6C4F2), Color(0xFFE0A6C4), Color(0xFF8FD6C4), Color(0xFFF1A884), Color(0xFFA9B8EA), Color(0xFF8D9895)),
)

val LocalFinanceColors = staticCompositionLocalOf { LightFinance }

private val Sans = FontFamily.Default

/** One family, separated by weight and size. Figures use tabular numerals so columns align. */
val FinoraTypography = Typography(
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp, fontFeatureSettings = "tnum"),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
)

val FinoraShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Amount style: tabular figures so amounts in a list line up digit for digit. */
val AmountStyle = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun FinoraTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors: ColorScheme = if (dark) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalFinanceColors provides if (dark) DarkFinance else LightFinance) {
        MaterialTheme(colorScheme = colors, typography = FinoraTypography, shapes = FinoraShapes, content = content)
    }
}

object Finance {
    val colors: FinanceColors @Composable get() = LocalFinanceColors.current
}
