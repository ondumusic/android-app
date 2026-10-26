package com.ondumusic.player.ui.components

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

// The wave is drawn in a 60 by 20 box, with the line in the middle
private const val WIDTH = 60f
private const val MIDDLE = 10f
private const val TAU = 2 * PI.toFloat()

/**
 * The web app's loading wave (Ondu comes from "onde"): a wave with a fainter one behind
 * it moving the other way. Same formulas as `Loading.tsx` on the web; keep the two in step.
 */
@Composable
fun LoadingWave(
    modifier: Modifier = Modifier,
    width: Dp = 90.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val context = LocalContext.current
    // "Remove animations" sets the animator scale to 0; slow the wave down rather than replace it.
    val reduceMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(reduceMotion) {
        val start = withFrameNanos { it }
        val speed = if (reduceMotion) 0.25f else 1f
        while (true) withFrameNanos { now -> time = (now - start) / 1e9f * speed }
    }
    val fade = Brush.horizontalGradient(0f to Color.Transparent, 0.25f to Color.Black, 0.75f to Color.Black, 1f to Color.Transparent)

    Canvas(
        modifier
            .size(width, width / 3)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(fade, blendMode = BlendMode.DstIn)
            },
    ) {
        val t = time
        val scale = size.width / WIDTH
        val back = wavePath(scale) { x -> 3 * drift(t, 2.9f, 0.5f) * envelope(x, t) * wave(x, -t, 14f, 1.2f) }
        val front = wavePath(scale) { x -> 4.5f * drift(t, 3.7f, 0.3f) * envelope(x, t) * wave(x, t, 10f, 0.8f) }
        drawPath(back, color.copy(alpha = 0.3f), style = Stroke(2 * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(front, color, style = Stroke(2.5f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// A sine wave moving to the left, for x at time t (seconds)
private fun wave(x: Float, t: Float, wavelength: Float, period: Float) = sin(TAU * (x / wavelength + t / period))

// Slowly moves between 1 - amount and 1, so the height varies over time
private fun drift(t: Float, period: Float, amount: Float) = 1 - amount / 2 + amount / 2 * sin(TAU * t / period)

// Flat at both ends and tallest around the middle, where the tallest point
// slowly sways from side to side.
private fun envelope(x: Float, t: Float): Float {
    val center = WIDTH / 2 + 6 * sin(TAU * t / 4.3f)
    val flatEnds = sin(PI.toFloat() * x / WIDTH).pow(2)
    return flatEnds * exp(-((x - center) / 18).pow(2))
}

private fun wavePath(scale: Float, y: (Float) -> Float) = Path().apply {
    for (i in 0..WIDTH.toInt()) {
        val x = i.toFloat()
        val px = x * scale
        val py = (MIDDLE + y(x)) * scale
        if (i == 0) moveTo(px, py) else lineTo(px, py)
    }
}
