package one.globalconnect.pinpad.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.floor

internal enum class CardEntryGesture { TAP, INSERT, SWIPE }
internal enum class CardEntryModel { CT20, N6_PRO_LITE, GENERIC }

internal fun cardEntryModel(model: String): CardEntryModel {
    val normalized = model.uppercase().replace(Regex("[^A-Z0-9]"), "")
    return when {
        normalized.contains("CT20") -> CardEntryModel.CT20
        normalized.contains("N6PROLITE") || normalized.contains("N6PRO") -> CardEntryModel.N6_PRO_LITE
        else -> CardEntryModel.GENERIC
    }
}

internal fun entryGestures(chip: Boolean, tap: Boolean, swipe: Boolean) = buildList {
    if (tap) add(CardEntryGesture.TAP)
    if (chip) add(CardEntryGesture.INSERT)
    if (swipe) add(CardEntryGesture.SWIPE)
}

/** Vector guide with a fixed drawing coordinate system, scaled to the available space. */
@Composable
internal fun CardEntryGuide(modelName: String, gestures: List<CardEntryGesture>, modifier: Modifier = Modifier) {
    if (gestures.isEmpty()) return
    val model = remember(modelName) { cardEntryModel(modelName) }
    val transition = rememberInfiniteTransition(label = "entry-guide")
    val cycle by transition.animateFloat(0f, gestures.size.toFloat(),
        infiniteRepeatable(tween(2400 * gestures.size, easing = LinearEasing), RepeatMode.Restart),
        label = "entry-cycle")
    val gesture = gestures[floor(cycle).toInt().coerceIn(0, gestures.lastIndex)]
    val progress = cycle % 1f
    val contact = when {
        progress < .12f -> 0f
        progress < .40f -> (progress - .12f) / .28f
        progress < .68f -> 1f
        progress < .94f -> 1f - (progress - .68f) / .26f
        else -> 0f
    }.let { it * it * (3f - 2f * it) }
    Canvas(modifier) {
        val factor = minOf(size.width / 240f, size.height / 260f)
        translate((size.width - 240f * factor) / 2f, (size.height - 260f * factor) / 2f) {
            scale(factor, factor, Offset.Zero) {
                drawTerminal(model)
                val topSwipe = model == CardEntryModel.N6_PRO_LITE
                val tapY = if (model == CardEntryModel.CT20) 78f else 100f
                val landscape = gesture == CardEntryGesture.TAP || (gesture == CardEntryGesture.SWIPE && topSwipe)
                val cardSize = if (landscape) Size(76f, 48f) else Size(48f, 76f)
                val tapScale = if (gesture == CardEntryGesture.TAP) 1.10f - .22f * contact else 1f
                val actualSize = cardSize * tapScale
                val center = when (gesture) {
                    CardEntryGesture.TAP -> Offset(120f, tapY + 45f * (1f - contact))
                    CardEntryGesture.INSERT -> Offset(120f, 216f - 53f * contact)
                    CardEntryGesture.SWIPE -> if (topSwipe) Offset(58f + 124f * progress, 28f)
                        else Offset(185f, 48f + 106f * progress)
                }
                if (gesture == CardEntryGesture.TAP) {
                    contactless(Offset(120f, tapY), 20f, Color.White)
                    if (contact > .95f) drawCircle(Color(0xFF8FD3FF), 29f, Offset(120f, tapY), style = Stroke(2f))
                }
                clipRect(top = if (gesture == CardEntryGesture.INSERT) 174f else 0f, right = 240f, bottom = 260f) {
                    val origin = center - Offset(actualSize.width / 2f, actualSize.height / 2f)
                    drawRoundRect(Color(0xFF168ADD), origin, actualSize, CornerRadius(4f))
                    if (gesture == CardEntryGesture.SWIPE && !topSwipe) {
                        // Magnetic stripe faces the side-mounted reader.
                        drawRect(Color(0xFF20252B), origin + Offset(5f, 0f), Size(10f, actualSize.height))
                        drawRect(Color(0xFFE7EBEF), origin + Offset(25f, 14f), Size(9f, 40f))
                    } else {
                        val chip = if (gesture == CardEntryGesture.INSERT) origin + Offset(18f, 8f)
                            else origin + Offset(10f, 16f)
                        drawRoundRect(Color(0xFFE7C870), chip, Size(12f, 10f), CornerRadius(1.5f))
                        if (gesture == CardEntryGesture.TAP) contactless(center + Offset(19f, 0f), 22f * tapScale, Color.White)
                    }
                }
                if (gesture == CardEntryGesture.SWIPE && topSwipe) {
                    // Redraw the front lip over the card, preserving visible portions outside the housing.
                    clipRect(top = 31f, right = 240f, bottom = 260f) { drawTerminal(model) }
                }
            }
        }
    }
}

private fun DrawScope.drawTerminal(model: CardEntryModel) {
    val keypad = model == CardEntryModel.CT20
    val lite = model == CardEntryModel.N6_PRO_LITE
    val rim = if (keypad) Color(0xFF666C72) else Color(0xFFE7E9EA)
    drawRoundRect(rim, Offset(79f, 16f), Size(82f, 158f), CornerRadius(12f))
    drawRoundRect(Color(0xFF22282D), Offset(83f, 20f), Size(74f, 150f), CornerRadius(10f))
    drawCircle(Color(0xFF707C83), 2.5f, if (lite) Offset(97f, 30f) else Offset(120f, 28f))
    val screen = if (keypad) Rect(90f, 37f, 150f, 116f) else Rect(88f, 40f, 152f, 160f)
    drawRoundRect(Color(0xFF195A7E), screen.topLeft, screen.size, CornerRadius(2f))
    contactless(Offset(120f, if (keypad) 78f else 100f), 15f, Color(0xFFB9E8FA))
    if (keypad) {
        repeat(4) { row ->
            repeat(4) { column ->
                val color = if (column == 3) when (row) {
                    0 -> Color(0xFFEC4F50)
                    1 -> Color(0xFFF6D252)
                    else -> Color(0xFF96DC61)
                } else Color(0xFF11161B)
                if (column != 3 || row != 3) {
                    val height = if (column == 3 && row == 2) 23f else 10f
                    drawRoundRect(color, Offset(88f + column * 17f, 121f + row * 12f), Size(14f, height), CornerRadius(2f))
                    if (column < 3) drawCircle(Color(0xFFDEE4E8), 1f, Offset(95f + column * 17f, 126f + row * 12f))
                }
            }
        }
        drawLine(Color(0xFF8D979E), Offset(162f, 49f), Offset(162f, 147f), 3f)
    }
    if (lite) drawLine(Color(0xFF89939B), Offset(88f, 31f), Offset(152f, 31f), 2f)
    drawLine(Color(0xFF89939B), Offset(108f, 174f), Offset(132f, 174f), 3f)
}

private fun DrawScope.contactless(center: Offset, height: Float, color: Color) {
    repeat(4) { index ->
        val radius = height * (.18f + index * .12f)
        drawArc(color, -55f, 110f, false,
            Offset(center.x - height * .32f - radius, center.y - radius), Size(radius * 2f, radius * 2f),
            style = Stroke(height * .075f))
    }
}
