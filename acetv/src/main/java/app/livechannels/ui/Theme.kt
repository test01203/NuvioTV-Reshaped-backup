package app.livechannels.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import app.livechannels.Health
import app.livechannels.R

/** A dark palette. */
object Palette {
    val Background = Color(0xFF0C0D0F)
    val Elevated = Color(0xFF17191D)
    val Card = Color(0xFF20242A)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFB3B3B3)
    val TextTertiary = Color(0xFF808080)
    val Green = Color(0xFF34C759)
    val Amber = Color(0xFFFFB020)
    val Grey = Color(0xFF8E8E93)
    val Error = Color(0xFFFF6B6B)
}

private val DmSans = FontFamily(
    Font(R.font.dm_sans_variable, FontWeight.Normal),
    Font(R.font.dm_sans_variable, FontWeight.Medium),
    Font(R.font.dm_sans_variable, FontWeight.SemiBold),
    Font(R.font.dm_sans_variable, FontWeight.Bold),
)

private val AppTypography = Typography().let { base ->
    fun TextStyle.dm() = copy(fontFamily = DmSans)
    base.copy(
        headlineSmall = base.headlineSmall.dm().copy(fontWeight = FontWeight.SemiBold, fontSize = 24.sp),
        titleMedium = base.titleMedium.dm().copy(fontWeight = FontWeight.Medium),
        bodyLarge = base.bodyLarge.dm(),
        bodyMedium = base.bodyMedium.dm(),
        bodySmall = base.bodySmall.dm(),
        labelSmall = base.labelSmall.dm(),
        labelMedium = base.labelMedium.dm(),
    )
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Palette.Background,
            surface = Palette.Elevated,
            onSurface = Palette.TextPrimary,
            onBackground = Palette.TextPrimary,
        ),
        typography = AppTypography,
    ) {
        Box(Modifier.fillMaxSize().background(Palette.Background)) { content() }
    }
}

/** A focusable row or button in a TV style: dim at rest, white with dark text when focused. */
@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    shape: RoundedCornerShape = RoundedCornerShape(12.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Palette.Card else Palette.TextPrimary.copy(alpha = 0.035f),
            contentColor = Palette.TextPrimary,
            focusedContainerColor = Palette.TextPrimary,
            focusedContentColor = Color.Black,
            pressedContainerColor = Palette.TextSecondary,
            pressedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        content = content,
    )
}

@Composable
fun HealthDot(health: Health, modifier: Modifier = Modifier) {
    val color = when (health) {
        Health.Active -> Palette.Green
        Health.Weak -> Palette.Amber
        Health.Offline -> Palette.Grey
        Health.Unknown -> return
    }
    Box(modifier.size(10.dp).clip(CircleShape).background(color))
}

@Composable
fun ProgressBar(progress: Float, modifier: Modifier = Modifier, height: Dp = 4.dp, fill: Color = Palette.Green) {
    val shape = RoundedCornerShape(height / 2)
    Box(modifier.height(height).clip(shape).background(Color.White.copy(alpha = 0.15f))) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(height).clip(shape).background(fill).align(Alignment.CenterStart))
    }
}

val ScreenPadding = Modifier.padding(horizontal = 48.dp, vertical = 28.dp)
