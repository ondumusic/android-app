package com.ondumusic.player.ui.player

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * How far the mini player has grown into the full player: 0 is the bar, 1 the whole screen.
 * Dragging, the back gesture and taps all move this one value, so they can hand over to each
 * other mid-way.
 */
@Stable
class PlayerExpansion(private val scope: CoroutineScope, initiallyExpanded: Boolean) {
    var fraction by mutableFloatStateOf(if (initiallyExpanded) 1f else 0f)
        private set

    /** Where the player is headed, which is what the rest of the app should act on. */
    var expanded by mutableStateOf(initiallyExpanded)
        private set

    private var settle: Job? = null

    fun expand() = settleTo(true)
    fun collapse() = settleTo(false)

    fun hide() {
        settle?.cancel()
        expanded = false
        fraction = 0f
    }

    fun dragBy(delta: Float) {
        settle?.cancel()
        fraction = (fraction + delta).coerceIn(0f, 1f)
    }

    /** [velocity] in fractions per second, positive towards expanded. */
    fun release(velocity: Float) = settleTo(
        when {
            velocity > FLING -> true
            velocity < -FLING -> false
            // A short way from where it started is enough to commit, as with a sheet.
            expanded -> fraction > 0.8f
            else -> fraction > 0.2f
        }
    )

    internal fun peek(progress: Float) {
        settle?.cancel()
        fraction = 1f - progress * BACK_PEEK
    }

    private fun settleTo(open: Boolean) {
        expanded = open
        settle?.cancel()
        settle = scope.launch {
            animate(fraction, if (open) 1f else 0f, animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)) { value, _ ->
                fraction = value
            }
        }
    }
}

private const val FLING = 1f

/** How much of the way down the back gesture previews before it commits. */
private const val BACK_PEEK = 0.25f

/** The margins [MiniPlayer] keeps around its card. */
private val MiniMarginH = 8.dp
private val MiniMarginV = 4.dp
private val MiniCorner = 16.dp

/**
 * The full player, grown out of the mini player's card: it rises from where the bar sits,
 * widens to the edges and loses its corners as it goes, while the bar fades out on top of it.
 * [miniBounds] is where the mini player sits in the window, margins included.
 */
@Composable
fun ExpandingPlayer(expansion: PlayerExpansion, miniBounds: Rect) {
    val density = LocalDensity.current
    val card = remember(miniBounds, density) {
        with(density) {
            val h = MiniMarginH.toPx()
            val v = MiniMarginV.toPx()
            Rect(miniBounds.left + h, miniBounds.top + v, miniBounds.right - h, miniBounds.bottom - v)
        }
    }
    val travel = card.top.coerceAtLeast(1f)
    val showMini by remember { derivedStateOf { expansion.fraction < MINI_FADE } }

    PredictiveBackHandler(enabled = expansion.expanded) { events ->
        try {
            events.collect { expansion.peek(it.progress) }
            expansion.collapse()
        } catch (e: CancellationException) {
            expansion.expand()
            throw e
        }
    }

    Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black, alpha = 0.4f * expansion.fraction) })
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val f = expansion.fraction
                translationY = lerp(card.top, 0f, f)
                clip = true
                shape = CardShape(card, f, MiniCorner.toPx())
            }
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta -> expansion.dragBy(-delta / travel) },
                onDragStopped = { velocity -> expansion.release(-velocity / travel) },
            ),
    ) {
        FullPlayerScreen(
            onClose = expansion::collapse,
            coversStatusBar = { expansion.fraction > 0.9f },
            contentAlpha = { ((expansion.fraction - MINI_FADE / 2) / 0.5f).coerceIn(0f, 1f) },
        )
        if (showMini) {
            MiniPlayer(
                onOpen = expansion::expand,
                modifier = Modifier
                    .offset { IntOffset(miniBounds.left.roundToInt(), (miniBounds.top - card.top).roundToInt()) }
                    .requiredSize(with(density) { miniBounds.size.toDpSize() })
                    .graphicsLayer { alpha = 1f - expansion.fraction / MINI_FADE },
            )
        }
    }
}

/** By this far up the bar has faded out entirely. */
private const val MINI_FADE = 0.3f

/** The mini player's card at 0, the whole window at 1; in the layer's own coordinates, which start at the card's top. */
private class CardShape(private val card: Rect, private val f: Float, private val corner: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = lerp(corner, 0f, f)
        return Outline.Rounded(
            RoundRect(
                left = lerp(card.left, 0f, f),
                top = 0f,
                right = lerp(card.right, size.width, f),
                // The bottom reaches the window's edge early, taking over the navigation bar
                // rather than lifting off it.
                bottom = lerp(card.bottom, size.height, (f / 0.35f).coerceAtMost(1f)) - lerp(card.top, 0f, f),
                cornerRadius = CornerRadius(r, r),
            )
        )
    }
}
