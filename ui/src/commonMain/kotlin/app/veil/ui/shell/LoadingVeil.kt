package app.veil.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.sin

/** Seconds for one full sheen pass; the fold motion also repeats on multiples of it. */
private const val CYCLE = 2.4f
private const val TAU = 2f * PI.toFloat()

/**
 * Covers the page while it loads and settles: a sheer fabric with soft folds swaying and a band
 * of light gliding across like light on silk. Every motion is periodic in [CYCLE] so the loop has
 * no seam.
 *
 * Transitions run in two stages through plain black (white on light theme): in, the page fades
 * to black and then the fabric fades in (225 + 450 ms); out, the fabric fades to black and then
 * the black fades to the content (1000 + 500 ms). With [instant] it vanishes at once instead
 * (the user switched to a tab that was already ready).
 */
@Composable
fun LoadingVeil(visible: Boolean, modifier: Modifier = Modifier, instant: Boolean = false) {
    // Opacity of the plain cover and of the fabric drawn on it.
    val cover = remember { Animatable(if (visible) 1f else 0f) }
    val cloth = remember { Animatable(if (visible) 1f else 0f) }
    LaunchedEffect(visible, instant) {
        if (visible) {
            cover.animateTo(1f, tween(durationMillis = 225, easing = LinearEasing))
            cloth.animateTo(1f, tween(durationMillis = 450, easing = LinearOutSlowInEasing))
        } else if (instant) {
            cloth.snapTo(0f)
            cover.snapTo(0f)
        } else {
            cloth.animateTo(0f, tween(durationMillis = 1000, easing = FastOutLinearInEasing))
            cover.animateTo(0f, tween(durationMillis = 500, easing = LinearOutSlowInEasing))
        }
    }
    if (cover.value <= 0f && cloth.value <= 0f) return

    val surface = MaterialTheme.colorScheme.surface
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) { drawRect(surface, alpha = cover.value) }
        if (cloth.value > 0f) Fabric(surface, Modifier.fillMaxSize().graphicsLayer { alpha = cloth.value })
    }
}

@Composable
private fun Fabric(surface: Color, modifier: Modifier) {
    // Phase in [0, 1) of the current cycle; wrapping is seamless because every motion below
    // is a whole number of periods per cycle.
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { phase = (((it - start) / 1_000_000_000.0) / CYCLE % 1.0).toFloat() }
    }

    val dark = 0.2126f * surface.red + 0.7152f * surface.green + 0.0722f * surface.blue < 0.5f
    val fabric = if (dark) Color.White else Color.Black
    val sheen = if (dark) Color(0xFFDCD0FF) else Color(0xFF5B3FB8)

    Canvas(modifier) {
        drawFolds(fabric, dark, phase)
        drawSheen(sheen, dark, phase)
    }
}

/** Soft vertical folds that sway and breathe; one sway per cycle, two breaths per cycle. */
private fun DrawScope.drawFolds(fabric: Color, dark: Boolean, phase: Float) {
    val folds = 6
    val w = size.width
    val half = w / folds * 0.9f
    for (i in 0 until folds) {
        val cx = (i + 0.5f) * w / folds + sin(TAU * phase + i * 1.7f) * w * 0.05f
        val alpha = (if (dark) 0.065f else 0.04f) + (if (dark) 0.035f else 0.02f) * sin(TAU * 2f * phase + i)
        drawRect(
            Brush.horizontalGradient(
                listOf(Color.Transparent, fabric.copy(alpha = alpha), Color.Transparent),
                startX = cx - half,
                endX = cx + half,
            ),
            topLeft = Offset(cx - half, 0f),
            size = Size(half * 2f, size.height),
        )
    }
}

/**
 * A broad diagonal band of light. The gradient repeats every period along its direction and is
 * shifted by exactly one period per cycle, so the end of a cycle is identical to its start.
 */
private fun DrawScope.drawSheen(sheen: Color, dark: Boolean, phase: Float) {
    val w = size.width
    val h = size.height
    // One repeat spans well beyond the screen so only one band is visible at a time.
    val period = Offset(w + h * 0.6f, (w + h * 0.6f) * 0.3f)
    val origin = Offset(-period.x * 0.5f, 0f) + period * phase
    val peak = sheen.copy(alpha = if (dark) 0.2f else 0.1f)
    drawRect(
        Brush.linearGradient(
            0f to Color.Transparent,
            0.3f to Color.Transparent,
            0.5f to peak,
            0.7f to Color.Transparent,
            1f to Color.Transparent,
            start = origin,
            end = origin + period,
            tileMode = TileMode.Repeated,
        ),
    )
}
