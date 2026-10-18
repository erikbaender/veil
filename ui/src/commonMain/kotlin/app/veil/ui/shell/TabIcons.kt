package app.veil.ui.shell

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import kotlin.math.min

/**
 * Bottom-bar icons in the style of Instagram's: soft, rounded geometry on a 24-unit grid,
 * outlined when idle and filled (with cut-out details) when selected. Every shape is drawn in a
 * single colour so the bar's tint applies cleanly; details are holes, never white overlays.
 */
internal object TabIcons {
    private const val THIN = 2f
    private const val BOLD = 2.4f

    private class P(val x: Float, val y: Float)

    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private fun ImageVector.Builder.stroke(width: Float, block: PathBuilder.() -> Unit) = path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block,
    )

    /** Filled shape; subpaths inside it become holes (even-odd). */
    private fun ImageVector.Builder.solid(block: PathBuilder.() -> Unit) = path(
        fill = SolidColor(Color.Black),
        pathFillType = PathFillType.EvenOdd,
        pathBuilder = block,
    )

    /** Closed polygon whose corners are rounded with the given radii (one per point). */
    private fun PathBuilder.rounded(points: List<P>, radii: List<Float>) {
        val n = points.size
        for (i in 0 until n) {
            val p = points[i]
            val prev = points[(i + n - 1) % n]
            val next = points[(i + 1) % n]
            val lenPrev = hypot(prev.x - p.x, prev.y - p.y)
            val lenNext = hypot(next.x - p.x, next.y - p.y)
            val r = min(radii[i], min(lenPrev, lenNext) / 2f)
            val ax = p.x + (prev.x - p.x) / lenPrev * r
            val ay = p.y + (prev.y - p.y) / lenPrev * r
            val bx = p.x + (next.x - p.x) / lenNext * r
            val by = p.y + (next.y - p.y) / lenNext * r
            if (i == 0) moveTo(ax, ay) else lineTo(ax, ay)
            quadTo(p.x, p.y, bx, by)
        }
        close()
    }

    /** A slim rounded slot along a segment, used as a cut-out line inside filled icons. */
    private fun PathBuilder.slot(x1: Float, y1: Float, x2: Float, y2: Float, width: Float) {
        val len = hypot(x2 - x1, y2 - y1)
        val nx = -(y2 - y1) / len * width / 2f
        val ny = (x2 - x1) / len * width / 2f
        rounded(
            listOf(P(x1 + nx, y1 + ny), P(x2 + nx, y2 + ny), P(x2 - nx, y2 - ny), P(x1 - nx, y1 - ny)),
            listOf(width / 2f, width / 2f, width / 2f, width / 2f),
        )
    }

    // Home: soft house with a rounded roof peak and a door notch.
    private val housePoints = listOf(
        P(12f, 2.9f), P(20.6f, 9.7f), P(20.6f, 21f), P(14.6f, 21f), P(14.6f, 15f),
        P(9.4f, 15f), P(9.4f, 21f), P(3.4f, 21f), P(3.4f, 9.7f),
    )
    private val houseRadii = listOf(2.2f, 1.6f, 2f, 0.6f, 1.2f, 1.2f, 0.6f, 2f, 1.6f)

    val home = icon("home") { stroke(THIN) { rounded(housePoints, houseRadii) } }
    val homeSelected = icon("home.selected") {
        solid { rounded(housePoints, houseRadii) }
        stroke(1.2f) { rounded(housePoints, houseRadii) }
    }

    // Reels: a stack of portrait video cards (swipe to the next) with a play triangle on the front one.
    private fun PathBuilder.frontCard() {
        moveTo(6.8f, 6.6f); horizontalLineTo(12f)
        arcTo(3.4f, 3.4f, 0f, false, true, 15.4f, 10f); verticalLineTo(17.8f)
        arcTo(3.4f, 3.4f, 0f, false, true, 12f, 21.2f); horizontalLineTo(6.8f)
        arcTo(3.4f, 3.4f, 0f, false, true, 3.4f, 17.8f); verticalLineTo(10f)
        arcTo(3.4f, 3.4f, 0f, false, true, 6.8f, 6.6f); close()
    }

    /** The part of the card behind that shows above and beside the front one. */
    private fun PathBuilder.backCard() {
        moveTo(8.2f, 6.6f); verticalLineTo(6.2f)
        arcTo(3.4f, 3.4f, 0f, false, true, 11.6f, 2.8f); horizontalLineTo(17f)
        arcTo(3.4f, 3.4f, 0f, false, true, 20.4f, 6.2f); verticalLineTo(14.4f)
        arcTo(3.4f, 3.4f, 0f, false, true, 17f, 17.8f); horizontalLineTo(15.4f)
    }
    private val playPoints = listOf(P(7.2f, 10.8f), P(12.3f, 13.9f), P(7.2f, 17f))
    private val playRadii = listOf(0.9f, 0.9f, 0.9f)

    val reels = icon("reels") {
        stroke(THIN) {
            frontCard()
            backCard()
        }
        solid { rounded(playPoints, playRadii) }
    }
    val reelsSelected = icon("reels.selected") {
        solid {
            frontCard()
            rounded(playPoints, playRadii)
        }
        stroke(1f) { frontCard() }
        stroke(BOLD - 0.2f) { backCard() }
    }

    // Messages: rounded paper plane with a crease from the nose towards the inner wing.
    private val planePoints = listOf(P(21.2f, 2.9f), P(12.9f, 21.1f), P(2.8f, 9.4f))
    private val planeRadii = listOf(2.2f, 2.2f, 2.2f)

    val messages = icon("messages") {
        stroke(THIN) {
            rounded(planePoints, planeRadii)
            moveTo(19.4f, 4.7f); lineTo(10.4f, 12f)
        }
    }
    val messagesSelected = icon("messages.selected") {
        solid {
            rounded(planePoints, planeRadii)
            slot(18.6f, 5.4f, 11.2f, 11.4f, 1.5f)
        }
        stroke(1.2f) { rounded(planePoints, planeRadii) }
    }

    // Search: magnifying glass.
    private fun ImageVector.Builder.lens(width: Float) = stroke(width) {
        moveTo(17.6f, 10.6f)
        arcTo(7.1f, 7.1f, 0f, true, true, 3.4f, 10.6f)
        arcTo(7.1f, 7.1f, 0f, true, true, 17.6f, 10.6f)
        close()
        moveTo(15.8f, 15.8f)
        lineTo(20.9f, 20.9f)
    }

    val search = icon("search") { lens(THIN) }
    val searchSelected = icon("search.selected") { lens(BOLD + 0.5f) }

    // Profile: head and shoulders inside a circle.
    private fun ImageVector.Builder.avatar(width: Float) = stroke(width) {
        moveTo(21.2f, 12f)
        arcTo(9.2f, 9.2f, 0f, true, true, 2.8f, 12f)
        arcTo(9.2f, 9.2f, 0f, true, true, 21.2f, 12f)
        close()
        moveTo(15.2f, 9.8f)
        arcTo(3.2f, 3.2f, 0f, true, true, 8.8f, 9.8f)
        arcTo(3.2f, 3.2f, 0f, true, true, 15.2f, 9.8f)
        close()
        moveTo(6.3f, 18.7f)
        curveTo(7.5f, 16.4f, 9.6f, 15.3f, 12f, 15.3f)
        curveTo(14.4f, 15.3f, 16.5f, 16.4f, 17.7f, 18.7f)
    }

    val profile = icon("profile") { avatar(THIN) }
    val profileSelected = icon("profile.selected") { avatar(BOLD + 0.5f) }
}
