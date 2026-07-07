package com.uic.uicpaymentapp.transaction

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uic.uicpaymentapp.ui.theme.color_alert
import com.uic.uicpaymentapp.ui.theme.color_error
import com.uic.uicpaymentapp.ui.theme.color_secondaryThree
import com.uic.uicpaymentapp.ui.theme.color_success
import com.uic.uicpaymentapp.ui.theme.color_white
import com.uic.uicpaymentapp.utils.HardwareFunctionKey
import com.uic.uicpaymentapp.utils.HardwareKeyCommand
import com.uic.uicpaymentapp.utils.HardwareKeyListener
import com.uic.uicpaymentapp.utils.HardwareKeyManager
import com.uic.uicpaymentapp.utils.SoundEffect
import com.uic.uicpaymentapp.utils.SoundManager

data class PercentageOption(
    val label: String,
    val percentage: Int,
)

@Composable
fun AmountKeypad(
    onDigit: (String) -> Unit,
    onDoubleZero: () -> Unit,
    onDecimalPoint: () -> Unit,
    onBackspace: () -> Unit,
    onReset: (() -> Unit)?,
    percentageOptions: List<PercentageOption> = emptyList(),
    onPercentageSelected: ((Int) -> Unit)? = null,
    onEnterPressed: (() -> Unit)? = null,
    onCancelPressed: (() -> Unit)? = null,
    onFunctionKey: ((HardwareFunctionKey) -> Unit)? = null,
) {
    RegisterAmountKeypadHardwareHandler(
        onDigit = onDigit,
        onDoubleZero = onDoubleZero,
        onDecimalPoint = onDecimalPoint,
        onBackspace = onBackspace,
        onReset = onReset,
        onEnterPressed = onEnterPressed,
        onCancelPressed = onCancelPressed,
        onFunctionKey = onFunctionKey,
    )

    val visuals = rememberAmountKeyVisuals()
    val configuration = LocalConfiguration.current
    val swDp = configuration.smallestScreenWidthDp
    val isN62 = swDp >= 480
    val isCompact = swDp < 360
    val verticalPadding = when { isN62 -> 2.dp; isCompact -> 4.dp; else -> 8.dp }
    val rowSpacing = when { isN62 -> 3.dp; isCompact -> 4.dp; else -> 8.dp }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .background(color_secondaryThree)
            .padding(horizontal = 12.dp, vertical = verticalPadding),
        verticalArrangement = Arrangement.spacedBy(rowSpacing)
    ) {
        // Optional percentage row
        if (percentageOptions.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                percentageOptions.forEach { option ->
                    AmountKeyButton(
                        text = option.label,
                        sound = SoundEffect.KEY_STANDARD,
                        visuals = visuals,
                        modifier = Modifier
                            .weight(1f)
                            .height(visuals.buttonHeight),
                        onClick = { onPercentageSelected?.invoke(option.percentage) }
                    )
                }
                // Pad remaining slots to maintain 4-column grid
                repeat(4 - percentageOptions.size.coerceAtMost(4)) {
                    Spacer(
                        modifier = Modifier
                            .weight(1f)
                            .height(visuals.buttonHeight)
                    )
                }
            }
        }

        // Row 1: [1] [2] [3] [C]
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (d in 1..3) {
                AmountKeyButton(
                    text = d.toString(),
                    sound = SoundEffect.KEY_TICK,
                    visuals = visuals,
                    modifier = Modifier.weight(1f).height(visuals.buttonHeight),
                    onClick = { onDigit(d.toString()) }
                )
            }
            AmountKeyButton(
                text = AMOUNT_KEY_BACKSPACE,
                sound = SoundEffect.KEY_DELETE,
                visuals = visuals,
                containerColor = color_alert,
                contentColor = color_white,
                borderColor = color_white,
                modifier = Modifier.weight(1f).height(visuals.buttonHeight),
                onClick = onBackspace,
            )
        }

        // Row 2: [4] [5] [6] [R/Cancel]
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (d in 4..6) {
                AmountKeyButton(
                    text = d.toString(),
                    sound = SoundEffect.KEY_TICK,
                    visuals = visuals,
                    modifier = Modifier.weight(1f).height(visuals.buttonHeight),
                    onClick = { onDigit(d.toString()) }
                )
            }
            if (onReset != null) {
                AmountKeyButton(
                    text = AMOUNT_KEY_RESET,
                    sound = SoundEffect.KEY_INVALID,
                    visuals = visuals,
                    containerColor = color_error,
                    contentColor = color_white,
                    borderColor = color_white,
                    modifier = Modifier.weight(1f).height(visuals.buttonHeight),
                    onClick = onReset,
                )
            } else {
                Spacer(modifier = Modifier.weight(1f).height(visuals.buttonHeight))
            }
        }

        // Rows 3-4: 4 equal-weight columns, each stacking 2 buttons
        // [7][8][9 ][ENTER]
        // [.][0][00][     ]
        val doubleRowHeight = visuals.buttonHeight * 2 + rowSpacing
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(doubleRowHeight),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Pairs: (top key, bottom key)
            data class KeyPair(val top: String, val bottom: String)
            val pairs = listOf(KeyPair("7", "."), KeyPair("8", "0"), KeyPair("9", "00"))

            pairs.forEach { (top, bottom) ->
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(rowSpacing)
                ) {
                    val topClick: () -> Unit = if (top == ".") onDecimalPoint else { { onDigit(top) } }
                    val bottomClick: () -> Unit = when (bottom) {
                        "00" -> onDoubleZero
                        "." -> onDecimalPoint
                        else -> { { onDigit(bottom) } }
                    }
                    AmountKeyButton(
                        text = top,
                        sound = SoundEffect.KEY_TICK,
                        visuals = visuals,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        onClick = topClick,
                    )
                    AmountKeyButton(
                        text = bottom,
                        sound = if (bottom == ".") SoundEffect.KEY_STANDARD else SoundEffect.KEY_TICK,
                        visuals = visuals,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        onClick = bottomClick,
                    )
                }
            }

            // 4th column: double-height Enter button
            if (onEnterPressed != null) {
                AmountKeyButton(
                    text = AMOUNT_KEY_ENTER,
                    sound = SoundEffect.KEY_RETURN,
                    visuals = visuals,
                    containerColor = color_success,
                    contentColor = color_white,
                    borderColor = color_white,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    onClick = onEnterPressed,
                )
            } else {
                Spacer(modifier = Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

// ── Internal constants ──────────────────────────────────────────────────

private const val AMOUNT_KEY_BACKSPACE = "C"
private const val AMOUNT_KEY_RESET = "R"
private const val AMOUNT_KEY_ENTER = "ENTER"

// ── Hardware key handler ────────────────────────────────────────────────

@Composable
private fun RegisterAmountKeypadHardwareHandler(
    onDigit: (String) -> Unit,
    onDoubleZero: () -> Unit,
    onDecimalPoint: () -> Unit,
    onBackspace: () -> Unit,
    onReset: (() -> Unit)?,
    onEnterPressed: (() -> Unit)?,
    onCancelPressed: (() -> Unit)?,
    onFunctionKey: ((HardwareFunctionKey) -> Unit)?,
) {
    val currentOnDigit by rememberUpdatedState(onDigit)
    val currentOnDoubleZero by rememberUpdatedState(onDoubleZero)
    val currentOnDecimalPoint by rememberUpdatedState(onDecimalPoint)
    val currentOnBackspace by rememberUpdatedState(onBackspace)
    val currentOnReset by rememberUpdatedState(onReset)
    val currentOnEnterPressed by rememberUpdatedState(onEnterPressed)
    val currentOnCancelPressed by rememberUpdatedState(onCancelPressed)
    val currentOnFunctionKey by rememberUpdatedState(onFunctionKey)

    DisposableEffect(Unit) {
        val listener = HardwareKeyListener { command ->
            when (command) {
                is HardwareKeyCommand.Digit -> {
                    SoundManager.play(SoundEffect.KEY_TICK)
                    currentOnDigit(command.value.toString())
                    true
                }

                HardwareKeyCommand.DecimalPoint -> {
                    SoundManager.play(SoundEffect.KEY_STANDARD)
                    currentOnDecimalPoint()
                    true
                }

                HardwareKeyCommand.Backspace -> {
                    SoundManager.play(SoundEffect.KEY_DELETE)
                    currentOnBackspace()
                    true
                }

                HardwareKeyCommand.Reset -> {
                    val callback = currentOnReset
                    if (callback != null) {
                        SoundManager.play(SoundEffect.KEY_DELETE)
                        callback()
                    } else {
                        SoundManager.play(SoundEffect.KEY_DELETE)
                        currentOnBackspace()
                    }
                    true
                }

                HardwareKeyCommand.Enter -> {
                    val callback = currentOnEnterPressed
                    if (callback != null) {
                        SoundManager.play(SoundEffect.KEY_RETURN)
                        callback()
                        true
                    } else {
                        false
                    }
                }

                HardwareKeyCommand.Cancel -> {
                    val callback = currentOnCancelPressed
                    if (callback != null) {
                        SoundManager.play(SoundEffect.KEY_INVALID)
                        callback()
                        true
                    } else {
                        false
                    }
                }

                is HardwareKeyCommand.Function -> {
                    val callback = currentOnFunctionKey
                    if (callback != null) {
                        SoundManager.play(SoundEffect.KEY_STANDARD)
                        val functionKey = when (command.index) {
                            1 -> HardwareFunctionKey.F1
                            2 -> HardwareFunctionKey.F2
                            else -> HardwareFunctionKey.Custom(command.index)
                        }
                        callback(functionKey)
                        true
                    } else {
                        false
                    }
                }
            }
        }
        HardwareKeyManager.registerListener(listener)
        onDispose {
            HardwareKeyManager.unregisterListener(listener)
        }
    }
}

// ── Visuals ─────────────────────────────────────────────────────────────

private data class AmountKeyVisuals(
    val buttonHeight: Dp,
    val textSize: TextUnit,
    val iconSize: Dp,
)

@Composable
private fun rememberAmountKeyVisuals(): AmountKeyVisuals {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.coerceAtLeast(0)
    val swDp = configuration.smallestScreenWidthDp
    val isN62Screen = swDp >= 480      // 480dp SW, square — wide but same height as N82
    val isCompactScreen = swDp < 360   // N82/N6

    // 4-column layout → divide by 4 instead of 3
    // N62: wider screen so larger buttons, but height budget same as compact
    val heightFactor = when { isN62Screen -> 0.60f; isCompactScreen -> 0.55f; else -> 0.68f }
    val baseHeight = (screenWidth.dp / 4) * heightFactor
    val minHeight = when { isN62Screen -> 38.dp; isCompactScreen -> 40.dp; else -> 52.dp }
    val maxHeight = when { isN62Screen -> 50.dp; isCompactScreen -> 56.dp; else -> 80.dp }
    val buttonHeight = baseHeight.coerceIn(minHeight, maxHeight)

    val textSize: TextUnit = when {
        buttonHeight <= 48.dp -> 22.sp
        buttonHeight <= 56.dp -> 26.sp
        buttonHeight <= 64.dp -> 28.sp
        else -> 32.sp
    }
    val iconSize: Dp = when {
        buttonHeight <= 48.dp -> 20.dp
        buttonHeight <= 56.dp -> 24.dp
        buttonHeight <= 64.dp -> 28.dp
        else -> 32.dp
    }

    return AmountKeyVisuals(buttonHeight = buttonHeight, textSize = textSize, iconSize = iconSize)
}

// ── Button ──────────────────────────────────────────────────────────────

@Composable
private fun AmountKeyButton(
    text: String,
    sound: SoundEffect,
    visuals: AmountKeyVisuals,
    modifier: Modifier,
    onClick: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.background,
    contentColor: Color = MaterialTheme.colorScheme.onBackground,
    borderColor: Color = Color.Unspecified,
) {
    val shape = RoundedCornerShape(10)
    val borderModifier = if (borderColor != Color.Unspecified) {
        modifier.border(width = 1.5.dp, color = borderColor, shape = shape)
    } else {
        modifier
    }
    Button(
        onClick = {
            SoundManager.play(sound)
            onClick()
        },
        modifier = borderModifier,
        shape = shape,
        contentPadding = if (text == AMOUNT_KEY_ENTER || text == "00")
            PaddingValues(horizontal = 8.dp, vertical = 4.dp)
        else
            ButtonDefaults.ContentPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
    ) {
        when (text) {
            AMOUNT_KEY_BACKSPACE -> {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Backspace,
                    contentDescription = "Backspace",
                    modifier = Modifier.size(visuals.iconSize)
                )
            }

            AMOUNT_KEY_RESET -> {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Cancel",
                    modifier = Modifier.size(visuals.iconSize)
                )
            }

            AMOUNT_KEY_ENTER -> {
                Text(
                    text = "OK",
                    color = contentColor,
                    fontSize = visuals.textSize,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
            }

            else -> {
                Text(
                    text = text,
                    color = contentColor,
                    fontSize = visuals.textSize,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
