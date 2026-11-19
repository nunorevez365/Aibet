package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val BetQuantDarkColorScheme = darkColorScheme(
    primary = NeonEmerald,
    onPrimary = OnEmerald,
    primaryContainer = EmeraldContainer,
    onPrimaryContainer = NeonEmerald,
    secondary = ElectricCyan,
    onSecondary = OnCyan,
    secondaryContainer = CyanContainer,
    onSecondaryContainer = ElectricCyan,
    tertiary = AmberGold,
    onTertiary = OnAmber,
    tertiaryContainer = AmberContainer,
    onTertiaryContainer = AmberGold,
    error = CrimsonLoss,
    onError = OnCrimson,
    errorContainer = CrimsonContainer,
    onErrorContainer = CrimsonLoss,
    background = ObsidianVoid,
    onBackground = TextPrimary,
    surface = DeepSlateNavy,
    onSurface = TextPrimary,
    surfaceVariant = ElevatedCardDark,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = ObsidianVoid,
    surfaceContainerLow = DeepSlateNavy,
    surfaceContainer = ElevatedCardDark,
    surfaceContainerHigh = SurfaceContainerHigh,
    surfaceContainerHighest = SurfaceContainerHighest,
    outline = HairlineBorder,
    outlineVariant = HairlineBorder.copy(alpha = 0.55f)
)

val BetQuantShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun BetQuantTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = BetQuantDarkColorScheme,
        typography = Typography,
        shapes = BetQuantShapes,
        content = content
    )
}
