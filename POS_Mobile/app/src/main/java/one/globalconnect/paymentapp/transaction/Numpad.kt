package one.globalconnect.paymentapp.transaction

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import one.globalconnect.paymentapp.ui.theme.color_secondaryThree
import one.globalconnect.paymentapp.utils.DeviceCapabilities
import one.globalconnect.paymentapp.utils.HardwareFunctionKey
import one.globalconnect.paymentapp.utils.HardwareKeyCommand
import one.globalconnect.paymentapp.utils.HardwareKeyListener
import one.globalconnect.paymentapp.utils.HardwareKeyManager
import one.globalconnect.paymentapp.utils.SoundEffect
import one.globalconnect.paymentapp.utils.SoundManager

private const val PLACEHOLDER_KEY = " "
private const val RESET_KEY = "R"
private const val CLEAR_KEY = "C"

private val baseKeys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", PLACEHOLDER_KEY, "0", CLEAR_KEY)
private val keysWithReset = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", RESET_KEY, "0", CLEAR_KEY)

@Composable
fun Numpad(
    onSelected: (String) -> Unit,
    showReset: Boolean = false,
    onEnterPressed: (() -> Unit)? = null,
    onCancelPressed: (() -> Unit)? = null,
    onFunctionKey: ((HardwareFunctionKey) -> Unit)? = null,
    hideOnPhysicalKeypad: Boolean = false,
) {
    HardwareAwareKeypad(
        keys = if (showReset) keysWithReset else baseKeys,
        showReset = showReset,
        isDialog = false,
        onSelected = onSelected,
        onEnterPressed = onEnterPressed,
        onCancelPressed = onCancelPressed,
        onFunctionKey = onFunctionKey,
        hideOnPhysicalKeypad = hideOnPhysicalKeypad,
    )
}

@Composable
fun DialogNumpad(
    onSelected: (String) -> Unit,
    showReset: Boolean = false,
    onEnterPressed: (() -> Unit)? = null,
    onCancelPressed: (() -> Unit)? = null,
    onFunctionKey: ((HardwareFunctionKey) -> Unit)? = null,
    hideOnPhysicalKeypad: Boolean = false,
) {
    HardwareAwareKeypad(
        keys = if (showReset) keysWithReset else baseKeys,
        showReset = showReset,
        isDialog = true,
        onSelected = onSelected,
        onEnterPressed = onEnterPressed,
        onCancelPressed = onCancelPressed,
        onFunctionKey = onFunctionKey,
        hideOnPhysicalKeypad = hideOnPhysicalKeypad,
    )
}

@Composable
private fun HardwareAwareKeypad(
    keys: List<String>,
    showReset: Boolean,
    isDialog: Boolean,
    onSelected: (String) -> Unit,
    onEnterPressed: (() -> Unit)?,
    onCancelPressed: (() -> Unit)?,
    onFunctionKey: ((HardwareFunctionKey) -> Unit)?,
    hideOnPhysicalKeypad: Boolean,
) {
    RegisterHardwareKeyHandler(
        showReset = showReset,
        onSelected = onSelected,
        onEnterPressed = onEnterPressed,
        onCancelPressed = onCancelPressed,
        onFunctionKey = onFunctionKey,
    )

    if (hideOnPhysicalKeypad && DeviceCapabilities.hasPhysicalNumericKeypad()) {
        return
    }

    val visuals = rememberKeyVisuals(isDialog)
    KeypadLayout(
        keys = keys,
        showReset = showReset,
        visuals = visuals,
        onSelected = onSelected,
    )
}

@Composable
private fun KeypadLayout(
    keys: List<String>,
    showReset: Boolean,
    visuals: KeyVisuals,
    onSelected: (String) -> Unit,
) {
    val rows = remember(keys) { keys.chunked(3) }
    val configuration = LocalConfiguration.current
    val isCompactScreen = configuration.screenHeightDp < 600
    val verticalPadding = if (isCompactScreen) 4.dp else 8.dp
    val rowSpacing = if (isCompactScreen) 4.dp else 8.dp
    Column(
        modifier = Modifier
            .fillMaxWidth(1f)
            .wrapContentHeight()
            .background(color_secondaryThree)
            .padding(horizontal = 12.dp, vertical = verticalPadding),
        verticalArrangement = Arrangement.spacedBy(rowSpacing)
    ) {
        rows.forEach { rowKeys ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowKeys.forEach { key ->
                    when {
                        key == PLACEHOLDER_KEY -> {
                            Spacer(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(visuals.buttonHeight)
                            )
                        }

                        key == RESET_KEY && !showReset -> {
                            Spacer(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(visuals.buttonHeight)
                            )
                        }

                        else -> {
                            KeyButton(
                                text = key,
                                sound = getSoundEffect(key),
                                visuals = visuals,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(visuals.buttonHeight),
                                onClick = { onSelected(key) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RegisterHardwareKeyHandler(
    showReset: Boolean,
    onSelected: (String) -> Unit,
    onEnterPressed: (() -> Unit)?,
    onCancelPressed: (() -> Unit)?,
    onFunctionKey: ((HardwareFunctionKey) -> Unit)?,
) {
    val currentShowReset by rememberUpdatedState(showReset)
    val currentOnSelected by rememberUpdatedState(onSelected)
    val currentOnEnterPressed by rememberUpdatedState(onEnterPressed)
    val currentOnCancelPressed by rememberUpdatedState(onCancelPressed)
    val currentOnFunctionKey by rememberUpdatedState(onFunctionKey)

    DisposableEffect(Unit) {
        val listener = HardwareKeyListener { command ->
            when (command) {
                is HardwareKeyCommand.Digit -> {
                    val key = command.value.toString()
                    SoundManager.play(getSoundEffect(key))
                    currentOnSelected(key)
                    true
                }

                HardwareKeyCommand.Backspace -> {
                    SoundManager.play(getSoundEffect(CLEAR_KEY))
                    currentOnSelected(CLEAR_KEY)
                    true
                }

                HardwareKeyCommand.Reset -> {
                    val key = if (currentShowReset) RESET_KEY else CLEAR_KEY
                    SoundManager.play(getSoundEffect(key))
                    currentOnSelected(key)
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

                HardwareKeyCommand.DecimalPoint -> false

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

@Composable
private fun rememberKeyVisuals(isDialog: Boolean): KeyVisuals {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.coerceAtLeast(0)
    val screenHeight = configuration.screenHeightDp.coerceAtLeast(0)
    val isCompactScreen = screenHeight < 600
    val screenWidthDp = screenWidth.dp
    val heightFactor = when {
        isDialog -> 0.55f
        isCompactScreen -> 0.55f
        else -> 0.68f
    }
    val baseHeight = (screenWidthDp / 3) * heightFactor
    val minHeight = if (isDialog) 48.dp else if (isCompactScreen) 44.dp else 60.dp
    val maxHeight = if (isDialog) 72.dp else if (isCompactScreen) 64.dp else 96.dp
    val buttonHeight = baseHeight.coerceIn(minHeight, maxHeight)

    val textSize: TextUnit = when {
        buttonHeight <= 56.dp -> 26.sp
        buttonHeight <= 64.dp -> 30.sp
        buttonHeight <= 72.dp -> 32.sp
        else -> 36.sp
    }
    val iconSize: Dp = when {
        buttonHeight <= 56.dp -> 24.dp
        buttonHeight <= 64.dp -> 28.dp
        buttonHeight <= 72.dp -> 32.dp
        else -> 36.dp
    }

    return KeyVisuals(buttonHeight = buttonHeight, textSize = textSize, iconSize = iconSize)
}

private data class KeyVisuals(
    val buttonHeight: Dp,
    val textSize: TextUnit,
    val iconSize: Dp,
)

@Composable
private fun KeyButton(
    text: String,
    sound: SoundEffect,
    visuals: KeyVisuals,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = {
            SoundManager.play(sound)
            onClick()
        },
        modifier = modifier,
        shape = RoundedCornerShape(10),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground
        ),
    ) {
        when (text) {
            CLEAR_KEY -> {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Backspace,
                    contentDescription = "Backspace",
                    modifier = Modifier.size(visuals.iconSize)
                )
            }

            RESET_KEY -> {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Undo,
                    contentDescription = "Reset",
                    modifier = Modifier.size(visuals.iconSize)
                )
            }

            else -> {
                Text(
                    text = text,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = visuals.textSize,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Determines the correct sound effect based on the key pressed.
 */
fun getSoundEffect(key: String): SoundEffect {
    return when (key) {
        PLACEHOLDER_KEY -> SoundEffect.NONE
        RESET_KEY, CLEAR_KEY -> SoundEffect.KEY_DELETE
        else -> SoundEffect.KEY_TICK
    }
}
