package one.globalconnect.paymentapp.transaction

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import one.globalconnect.paymentapp.cardreader.CardEntryInterfaces
import one.globalconnect.paymentapp.cardreader.CardReaderStatus
import one.globalconnect.paymentapp.utils.DeviceCapabilities
import one.globalconnect.paymentapp.utils.PaymentDeviceVisual
import kotlin.math.floor

internal enum class EntryGesture { TAP, INSERT, SWIPE }

private data class DeviceGeometry(
    val body: Rect,
    val screen: Rect,
    val tapPoint: Offset,
    val chipPoint: Offset,
    val swipeStart: Offset,
    val swipeEnd: Offset,
    val hasKeypad: Boolean,
    val hasPrinter: Boolean,
    val topSwipe: Boolean = false,
    val hasDecorativeLight: Boolean = false,
    val rightChipSlot: Boolean = false,
)

/** Compact, model-aware card placement guide drawn entirely with Compose. */
@Composable
internal fun DeviceCardEntryAnimation(
    status: CardReaderStatus,
    interfaces: CardEntryInterfaces,
    modifier: Modifier = Modifier,
) {
    val visual = remember { DeviceCapabilities.paymentDeviceVisual() }
    val gestures = remember(interfaces) { enabledEntryGestures(interfaces) }
    if (gestures.isEmpty()) return
    val transition = rememberInfiniteTransition(label = "card-entry-guide")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = gestures.size.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 2_400 * gestures.size,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "card-entry-cycle",
    )
    val gesture = gestures[floor(cycle).toInt().coerceIn(0, gestures.lastIndex)]
    val progress = cycle % 1f

    val guideHeight = (LocalConfiguration.current.screenHeightDp * 0.38f).coerceIn(220f, 300f)
    Canvas(
        modifier = modifier
            .width((guideHeight * 0.90f).dp)
            .height(guideHeight.dp),
    ) {
        val geometry = geometryFor(visual, size.width, size.height * 0.64f)
        val lightOn = progress % (1f / 2.4f) < (0.5f / 2.4f)
        drawDevice(visual, geometry, lightOn = lightOn)
        drawEntryGuide(
            gesture = gesture,
            progress = progress,
            geometry = geometry,
            status = status,
        )
        if (gesture == EntryGesture.SWIPE && geometry.topSwipe) {
            // The front lip occludes the card only where it overlaps the reader.
            // Outside the housing the whole card remains visible as it passes through.
            clipRect(top = geometry.body.top + geometry.body.height * 0.12f) {
                drawTopReaderDevice(geometry, lightOn)
            }
        }
    }
}

private fun geometryFor(
    visual: PaymentDeviceVisual,
    width: Float,
    height: Float,
): DeviceGeometry = when (visual) {
    PaymentDeviceVisual.CT20,
    PaymentDeviceVisual.CT20P -> DeviceGeometry(
        body = Rect(width * 0.35f, height * 0.08f, width * 0.65f, height * 0.90f),
        screen = Rect(width * 0.39f, height * 0.189f, width * 0.61f, height * 0.60f),
        tapPoint = Offset(width * 0.50f, height * 0.402f),
        chipPoint = Offset(width * 0.50f, height * 0.90f),
        swipeStart = Offset(width * 0.655f, height * 0.25f),
        swipeEnd = Offset(width * 0.655f, height * 0.76f),
        hasKeypad = true,
        hasPrinter = false,
    )
    PaymentDeviceVisual.N80 -> DeviceGeometry(
        body = Rect(width * 0.32f, height * 0.12f, width * 0.68f, height * 0.92f),
        screen = Rect(width * 0.36f, height * 0.28f, width * 0.64f, height * 0.57f),
        tapPoint = Offset(width * 0.50f, height * 0.23f),
        chipPoint = Offset(width * 0.50f, height * 0.92f),
        swipeStart = Offset(width * 0.69f, height * 0.30f),
        swipeEnd = Offset(width * 0.69f, height * 0.76f),
        hasKeypad = true,
        hasPrinter = true,
    )
    PaymentDeviceVisual.N96,
    PaymentDeviceVisual.N82 -> DeviceGeometry(
        body = Rect(width * 0.345f, height * 0.04f, width * 0.655f, height * 0.96f),
        screen = Rect(width * 0.362f, height * 0.27f, width * 0.638f, height * 0.93f),
        tapPoint = Offset(width * 0.50f, height * 0.095f),
        chipPoint = if (visual == PaymentDeviceVisual.N82) {
            Offset(width * 0.655f, height * 0.65f)
        } else Offset(width * 0.50f, height * 0.96f),
        swipeStart = Offset(width * 0.28f, height * 0.16f),
        swipeEnd = Offset(width * 0.72f, height * 0.16f),
        hasKeypad = false,
        hasPrinter = true,
        topSwipe = true,
        hasDecorativeLight = visual == PaymentDeviceVisual.N96,
        rightChipSlot = visual == PaymentDeviceVisual.N82,
    )
    PaymentDeviceVisual.N6,
    PaymentDeviceVisual.N6_PRO,
    PaymentDeviceVisual.N60_PRO -> DeviceGeometry(
        body = Rect(width * 0.34f, height * 0.08f, width * 0.66f, height * 0.91f),
        screen = Rect(width * 0.37f, height * 0.17f, width * 0.63f, height * 0.72f),
        tapPoint = Offset(width * 0.50f, height * 0.445f),
        chipPoint = Offset(width * 0.50f, height * 0.91f),
        swipeStart = Offset(width * 0.67f, height * 0.25f),
        swipeEnd = Offset(width * 0.67f, height * 0.72f),
        hasKeypad = false,
        hasPrinter = false,
    )
    PaymentDeviceVisual.GENERIC -> DeviceGeometry(
        body = Rect(width * 0.32f, height * 0.10f, width * 0.68f, height * 0.92f),
        screen = Rect(width * 0.35f, height * 0.27f, width * 0.65f, height * 0.70f),
        tapPoint = Offset(width * 0.50f, height * 0.23f),
        chipPoint = Offset(width * 0.50f, height * 0.92f),
        swipeStart = Offset(width * 0.69f, height * 0.28f),
        swipeEnd = Offset(width * 0.69f, height * 0.75f),
        hasKeypad = false,
        hasPrinter = true,
    )
}

private fun DrawScope.drawDevice(
    visual: PaymentDeviceVisual,
    geometry: DeviceGeometry,
    lightOn: Boolean,
) {
    if (geometry.topSwipe) {
        drawTopReaderDevice(geometry, lightOn)
        return
    }
    if (visual == PaymentDeviceVisual.CT20 || visual == PaymentDeviceVisual.CT20P) {
        drawCt20Device(geometry)
        return
    }
    val bodyColor = when (visual) {
        PaymentDeviceVisual.CT20,
        PaymentDeviceVisual.CT20P -> Color(0xFF263746)
        PaymentDeviceVisual.N60_PRO -> Color(0xFF222831)
        else -> Color(0xFFCBD0D5)
    }
    val outline = Color(0xFF6E7781)
    val screenColor = Color(0xFF0D91C8)

    if (geometry.hasPrinter) {
        drawRoundRect(
            color = Color(0xFFB8BEC5),
            topLeft = Offset(geometry.body.left - size.width * 0.025f, geometry.body.top),
            size = Size(geometry.body.width + size.width * 0.05f, size.height * 0.19f),
            cornerRadius = CornerRadius(size.width * 0.07f),
        )
        drawLine(
            color = outline,
            start = Offset(geometry.body.left + size.width * 0.04f, geometry.body.top + size.height * 0.08f),
            end = Offset(geometry.body.right - size.width * 0.04f, geometry.body.top + size.height * 0.08f),
            strokeWidth = size.width * 0.012f,
        )
    }

    drawRoundRect(
        color = bodyColor,
        topLeft = geometry.body.topLeft,
        size = geometry.body.size,
        cornerRadius = CornerRadius(size.width * 0.045f),
    )
    drawRoundRect(
        color = outline,
        topLeft = geometry.screen.topLeft - Offset(size.width * 0.012f, size.width * 0.012f),
        size = Size(geometry.screen.width + size.width * 0.024f, geometry.screen.height + size.width * 0.024f),
        cornerRadius = CornerRadius(size.width * 0.018f),
    )
    drawRoundRect(
        color = screenColor,
        topLeft = geometry.screen.topLeft,
        size = geometry.screen.size,
        cornerRadius = CornerRadius(size.width * 0.012f),
    )


    if (geometry.topSwipe) {
        val capBottom = geometry.swipeStart.y
        drawRoundRect(
            color = Color(0xFFAEB5BC),
            topLeft = geometry.body.topLeft + Offset(size.width * 0.015f, 0f),
            size = Size(geometry.body.width - size.width * 0.03f, capBottom - geometry.body.top),
            cornerRadius = CornerRadius(size.width * 0.025f),
        )
        drawContactlessSymbol(geometry.tapPoint, size.width * 0.09f, Color.White)
        drawRoundRect(
            color = Color(0xFF1676FF).copy(alpha = if (lightOn) 1f else 0.30f),
            topLeft = Offset(geometry.body.left, capBottom),
            size = Size(geometry.body.width, size.height * 0.025f),
            cornerRadius = CornerRadius(size.width * 0.012f),
        )
    }

    if (geometry.hasKeypad) {
        val keypadHeight = geometry.body.bottom - geometry.screen.bottom
        val keyStartY = geometry.screen.bottom + keypadHeight * 0.20f
        val keyStepX = geometry.body.width * 0.24f
        val keyStepY = keypadHeight * 0.20f
        repeat(4) { row ->
            repeat(3) { column ->
                drawCircle(
                    color = if (row == 3) {
                        listOf(Color(0xFFD94A4A), Color(0xFFF2B536), Color(0xFF29A96A))[column]
                    } else {
                        Color(0xFFE7EBEF)
                    },
                    radius = size.width * 0.014f,
                    center = Offset(
                        geometry.body.center.x + (column - 1) * keyStepX,
                        keyStartY + row * keyStepY,
                    ),
                )
            }
        }
    }

    drawLine(
        color = outline,
        start = Offset(geometry.body.center.x - size.width * 0.055f, geometry.body.bottom),
        end = Offset(geometry.body.center.x + size.width * 0.055f, geometry.body.bottom),
        strokeWidth = size.width * 0.014f,
    )
}

/** Same CT20/CT20P housing and keypad as the pinpad guide. */
private fun DrawScope.drawCt20Device(geometry: DeviceGeometry) {
    val b = geometry.body
    fun point(x: Float, y: Float) = Offset(b.left + (x - 79f) / 82f * b.width, b.top + (y - 16f) / 158f * b.height)
    fun rect(color: Color, x: Float, y: Float, w: Float, h: Float, radius: Float) {
        drawRoundRect(color, point(x, y), Size(w / 82f * b.width, h / 158f * b.height), CornerRadius(radius / 82f * b.width))
    }
    rect(Color(0xFF666C72), 79f, 16f, 82f, 158f, 12f)
    rect(Color(0xFF22282D), 83f, 20f, 74f, 150f, 10f)
    drawCircle(Color(0xFF707C83), b.width * 2.5f / 82f, point(120f, 28f))
    drawRoundRect(Color(0xFF195A7E), geometry.screen.topLeft, geometry.screen.size, CornerRadius(b.width * 2f / 82f))
    repeat(4) { row ->
        repeat(4) { column ->
            val color = if (column == 3) when (row) {
                0 -> Color(0xFFEC4F50)
                1 -> Color(0xFFF6D252)
                else -> Color(0xFF96DC61)
            } else Color(0xFF11161B)
            if (column != 3 || row != 3) {
                rect(color, 88f + column * 17f, 121f + row * 12f, 14f,
                    if (column == 3 && row == 2) 23f else 10f, 2f)
                if (column < 3) drawCircle(Color(0xFFDEE4E8), b.width / 82f, point(95f + column * 17f, 126f + row * 12f))
            }
        }
    }
    drawLine(Color(0xFF8D979E), point(162f, 49f), point(162f, 147f), b.width * 3f / 82f)
    drawLine(Color(0xFF89939B), point(108f, 174f), point(132f, 174f), b.width * 3f / 82f)
}

/** Shared N96/N82 silhouette; only the N96 has the illuminated printer band. */
private fun DrawScope.drawTopReaderDevice(geometry: DeviceGeometry, lightOn: Boolean) {
    val b = geometry.body
    fun point(x: Float, y: Float) = Offset(b.left + b.width * x, b.top + b.height * y)
    fun panel(x: Float, y: Float, w: Float, h: Float, color: Color, radius: Float) {
        drawRoundRect(color, point(x, y), Size(b.width * w, b.height * h), CornerRadius(b.width * radius))
    }
    panel(-0.025f, 0f, 1.05f, 1f, Color(0xFF9DA3A8), 0.14f)
    drawRoundRect(
        brush = Brush.horizontalGradient(listOf(Color(0xFFE2E3E4), Color.White, Color(0xFFD6D9DC))),
        topLeft = b.topLeft, size = b.size, cornerRadius = CornerRadius(b.width * 0.13f),
    )
    panel(0.035f, 0.005f, 0.93f, 0.12f, Color(0xFFD3D5D8), 0.11f)
    drawContactlessSymbol(geometry.tapPoint, b.width * 0.18f, Color.White)
    // Rounded wraparound band remains blue even between illumination pulses.
    if (geometry.hasDecorativeLight) {
        panel(-0.005f, 0.12f, 1.01f, 0.055f, Color(0xFF1475F5).copy(alpha = if (lightOn) 1f else 0.65f), 0.10f)
    }
    panel(0.02f, 0.163f, 0.96f, 0.075f, Color(0xFFDADCDD), 0.09f)
    val receiptSlot = Path().apply {
        moveTo(point(0.39f, 0.182f).x, point(0.39f, 0.182f).y)
        lineTo(point(0.61f, 0.182f).x, point(0.61f, 0.182f).y)
        lineTo(point(0.55f, 0.199f).x, point(0.55f, 0.199f).y)
        lineTo(point(0.45f, 0.199f).x, point(0.45f, 0.199f).y)
        close()
    }
    drawPath(receiptSlot, Color(0xFF73797E))
    drawRoundRect(Color(0xFFB9C5CD), geometry.screen.topLeft - Offset(1f, 1f),
        Size(geometry.screen.width + 2f, geometry.screen.height + 2f), CornerRadius(b.width * 0.09f))
    drawRoundRect(
        brush = Brush.linearGradient(listOf(Color(0xFFA9DFF5), Color(0xFFEDF9FF), Color(0xFF79C7EC)),
            geometry.screen.topLeft, geometry.screen.bottomRight),
        topLeft = geometry.screen.topLeft, size = geometry.screen.size,
        cornerRadius = CornerRadius(b.width * 0.085f),
    )
    // The centered camera sits in the shallow white notch above the display.
    drawCircle(Color(0xFFF4F5F6), b.width * 0.095f, Offset(b.center.x, geometry.screen.top))
    drawCircle(Color(0xFF414A50), b.width * 0.026f, Offset(b.center.x, geometry.screen.top - b.width * 0.01f))
    repeat(4) { index ->
        drawCircle(Color(0xFF89949C), b.width * 0.014f, point(0.68f + index * 0.075f, 0.229f))
    }
    if (geometry.rightChipSlot) {
        drawLine(Color(0xFF747E85), geometry.chipPoint - Offset(0f, b.width * 0.22f),
            geometry.chipPoint + Offset(0f, b.width * 0.22f), b.width * 0.022f)
    } else {
        drawLine(Color(0xFF747E85), point(0.38f, 1f), point(0.62f, 1f), b.width * 0.022f)
    }
}

private fun DrawScope.drawEntryGuide(
    gesture: EntryGesture,
    progress: Float,
    geometry: DeviceGeometry,
    status: CardReaderStatus,
) {
    val accent = when (status) {
        CardReaderStatus.Success -> Color(0xFF20A866)
        CardReaderStatus.SwipeIncorrect,
        CardReaderStatus.MultipleCards,
        is CardReaderStatus.Error -> Color(0xFFD83C45)
        else -> Color(0xFF0B74C8)
    }
    val eased = if (progress < 0.78f) progress / 0.78f else 1f
    // Approach the reader straight on, dwell, then lift away; never sweep sideways.
    val tapContact = when {
        progress < 0.12f -> 0f
        progress < 0.40f -> (progress - 0.12f) / 0.28f
        progress < 0.68f -> 1f
        progress < 0.94f -> 1f - (progress - 0.68f) / 0.26f
        else -> 0f
    }.let { it * it * (3f - 2f * it) }
    val tapScale = if (gesture == EntryGesture.TAP) lerp(1.12f, 0.85f, tapContact) else 1f
    val cardSize = (if (gesture == EntryGesture.INSERT && geometry.rightChipSlot) {
        Size(size.width * 0.30f, size.width * 0.195f)
    } else if (gesture == EntryGesture.TAP || (gesture == EntryGesture.SWIPE && geometry.topSwipe)) {
        Size(size.width * 0.34f, size.width * 0.22f)
    } else {
        Size(size.width * 0.22f, size.width * 0.34f)
    }) * tapScale

    val cardCenter = when (gesture) {
        EntryGesture.TAP -> Offset(
            x = geometry.tapPoint.x,
            y = lerp(geometry.tapPoint.y + size.height * 0.26f,
                maxOf(cardSize.height / 2f + 2f, geometry.tapPoint.y), tapContact),
        )
        EntryGesture.INSERT -> if (geometry.rightChipSlot) Offset(
            x = lerp(geometry.chipPoint.x + cardSize.width / 2f + size.width * 0.02f,
                geometry.chipPoint.x - cardSize.width * 0.15f, tapContact),
            y = geometry.chipPoint.y,
        ) else Offset(
            x = geometry.chipPoint.x,
            y = lerp(
                geometry.chipPoint.y + cardSize.height / 2f + size.height * 0.025f,
                geometry.chipPoint.y - cardSize.height * 0.15f,
                tapContact,
            ),
        )
        EntryGesture.SWIPE -> Offset(
            x = if (geometry.topSwipe) {
                lerp(geometry.swipeStart.x, geometry.swipeEnd.x, eased)
            } else geometry.swipeStart.x + cardSize.width * 0.42f,
            y = if (geometry.topSwipe) {
                maxOf(cardSize.height / 2f + 2f, geometry.swipeStart.y - cardSize.height * 0.42f)
            } else lerp(geometry.swipeStart.y, geometry.swipeEnd.y, eased),
        )
    }

    when (gesture) {
        EntryGesture.TAP -> {
            drawContactlessSymbol(geometry.tapPoint, size.width * 0.10f, Color.White)
            repeat(if (tapContact > 0.95f) 3 else 0) { index ->
                val radius = size.width * (0.035f + index * 0.025f) * (0.75f + progress * 0.25f)
                drawCircle(
                    color = accent.copy(alpha = 0.55f - index * 0.12f),
                    radius = radius,
                    center = geometry.tapPoint,
                    style = Stroke(width = size.width * 0.012f),
                )
            }

        }
        EntryGesture.INSERT -> drawLine(
            color = accent,
            start = geometry.chipPoint - if (geometry.rightChipSlot) Offset(0f, size.width * 0.075f) else Offset(size.width * 0.09f, 0f),
            end = geometry.chipPoint + if (geometry.rightChipSlot) Offset(0f, size.width * 0.075f) else Offset(size.width * 0.09f, 0f),
            strokeWidth = size.width * 0.025f,
        )
        EntryGesture.SWIPE -> if (!geometry.topSwipe) {
            drawLine(
                color = accent,
                start = geometry.swipeStart,
                end = geometry.swipeEnd,
                strokeWidth = size.width * 0.022f,
            )
        }
    }

    // Hide the leading portion as it enters the chip slot.
    clipRect(
        top = if (gesture == EntryGesture.INSERT && !geometry.rightChipSlot) geometry.chipPoint.y else 0f,
        left = if (gesture == EntryGesture.INSERT && geometry.rightChipSlot) geometry.chipPoint.x else 0f,
    ) {
        val cardTopLeft = cardCenter - Offset(cardSize.width / 2f, cardSize.height / 2f)
        drawRoundRect(
            color = accent,
            topLeft = cardTopLeft,
            size = cardSize,
            cornerRadius = CornerRadius(size.width * 0.018f),
        )
        if (gesture == EntryGesture.SWIPE && !geometry.topSwipe) {
            // Back of card: magnetic stripe runs along the device-facing edge.
            drawRect(
                color = Color(0xFF242932),
                topLeft = cardTopLeft + Offset(cardSize.width * 0.08f, 0f),
                size = Size(cardSize.width * 0.22f, cardSize.height),
            )
            drawRect(
                color = Color(0xFFE7EBEF),
                topLeft = cardTopLeft + Offset(cardSize.width * 0.48f, cardSize.height * 0.20f),
                size = Size(cardSize.width * 0.20f, cardSize.height * 0.55f),
            )
        } else {
            if (gesture == EntryGesture.TAP) {
                drawContactlessSymbol(
                    cardCenter + Offset(cardSize.width * 0.24f, 0f),
                    cardSize.height * 0.65f,
                    Color.White,
                )
            }
            val chipSize = Size(size.width * 0.055f, size.width * 0.045f)
            val chipPosition = if (gesture == EntryGesture.INSERT && geometry.rightChipSlot) {
                cardTopLeft + Offset(cardSize.width * 0.12f, (cardSize.height - chipSize.height) / 2f)
            } else if (gesture == EntryGesture.INSERT) {
                cardTopLeft + Offset((cardSize.width - chipSize.width) / 2f, cardSize.height * 0.12f)
            } else {
                cardTopLeft + Offset(cardSize.width * 0.16f, cardSize.height * 0.30f)
            }
            drawRoundRect(
                color = Color(0xFFE7C870),
                topLeft = chipPosition,
                size = chipSize,
                cornerRadius = CornerRadius(size.width * 0.006f),
            )
        }
    }
}

private fun lerp(start: Float, end: Float, fraction: Float): Float =
    start + (end - start) * fraction.coerceIn(0f, 1f)

internal fun enabledEntryGestures(interfaces: CardEntryInterfaces): List<EntryGesture> = buildList {
    if (interfaces.contactless) add(EntryGesture.TAP)
    if (interfaces.chip) add(EntryGesture.INSERT)
    if (interfaces.swipe) add(EntryGesture.SWIPE)
}

private fun DrawScope.drawContactlessSymbol(center: Offset, height: Float, color: Color) {
    repeat(4) { index ->
        val radius = height * (0.18f + index * 0.12f)
        drawArc(
            color = color,
            startAngle = -55f,
            sweepAngle = 110f,
            useCenter = false,
            topLeft = Offset(center.x - height * 0.32f - radius, center.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(width = height * 0.075f),
        )
    }
}



