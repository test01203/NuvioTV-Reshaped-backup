package app.livechannels.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.ExperimentalComposeUiApi
import app.livechannels.Health

/** The TV app's dark palette. */
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
    Font("fonts/dm_sans_variable.ttf", FontWeight.Normal),
    Font("fonts/dm_sans_variable.ttf", FontWeight.Medium),
    Font("fonts/dm_sans_variable.ttf", FontWeight.SemiBold),
    Font("fonts/dm_sans_variable.ttf", FontWeight.Bold),
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
            primary = Palette.TextPrimary,
            onPrimary = Color.Black,
        ),
        typography = AppTypography,
    ) {
        CompositionLocalProvider(
            LocalLayoutDirection provides if (Strings.isHebrew) LayoutDirection.Rtl else LayoutDirection.Ltr,
            LocalContentColor provides Palette.TextPrimary,
        ) {
            Box(Modifier.fillMaxSize().background(Palette.Background)) { content() }
        }
    }
}

/**
 * A row or button in the TV app's style: dim at rest, white with dark text when the mouse is
 * over it or the keyboard is on it. [content] is told which, to dim its secondary texts.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onRightClick: (() -> Unit)? = null,
    selected: Boolean = false,
    shape: RoundedCornerShape = RoundedCornerShape(12.dp),
    content: @Composable BoxScope.(highlighted: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val highlighted = hovered || focused
    val background = when {
        highlighted -> Palette.TextPrimary
        selected -> Palette.Card
        else -> Palette.TextPrimary.copy(alpha = 0.035f)
    }
    var withRightClick = modifier
    if (onRightClick != null) {
        withRightClick = withRightClick.onPointerEvent(PointerEventType.Press) { event ->
            if (event.button == PointerButton.Secondary) onRightClick()
        }
    }
    Box(
        withRightClick
            .scale(if (highlighted) 1.01f else 1f)
            .clip(shape)
            .background(background)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        CompositionLocalProvider(LocalContentColor provides if (highlighted) Color.Black else Palette.TextPrimary) {
            content(highlighted)
        }
    }
}

@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusCard(onClick = onClick, shape = RoundedCornerShape(50), modifier = modifier) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
    }
}

/** Green while the channel broadcasts, amber with few peers, grey when off air. */
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
fun ProgressBar(progress: Float, modifier: Modifier = Modifier, height: Dp = 4.dp, fill: Color = Palette.TextPrimary) {
    Box(modifier.height(height).clip(RoundedCornerShape(50)).background(fill.copy(alpha = 0.2f))) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(height).background(fill))
    }
}

val ScreenPadding = Modifier.padding(horizontal = 40.dp, vertical = 28.dp)
