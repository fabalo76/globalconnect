package one.globalconnect.keyinjection.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import one.globalconnect.keyinjection.App
import one.globalconnect.keyinjection.BuildConfig
import one.globalconnect.keyinjection.PasswordManager
import one.globalconnect.keyinjection.R
import one.globalconnect.keyinjection.util.Logger
import kotlinx.coroutines.delay

/**
 * Defines the sequential steps the user progresses through during login and
 * mandatory password changes.
 */
private enum class Step { ENTER_PASS1, CHANGE_PASS1, ENTER_PASS2, CHANGE_PASS2 }

/** Default style for titles shown on authentication screens. */
val labelTextStyle = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold)
/** Style used for password input fields. */
val passwordTextStyle = TextStyle(fontSize = 22.sp, letterSpacing = 8.sp)

/**
 * Handles the full authentication flow. Users are prompted for both passwords
 * and, if necessary, required to change default values. Successful completion
 * triggers [onSuccess].
 *
 * @param passwordManager component used to verify and update passwords
 * @param onSuccess callback invoked once authentication finishes without errors
 *
 * Author: Fabian Ramirez
 */
@Composable
fun LoginScreen(passwordManager: PasswordManager, onSuccess: () -> Unit) {
    val TAG = "LoginScreen"
    var step = remember { mutableStateOf(Step.ENTER_PASS1) }
    var pass = remember { mutableStateOf("") }
    var newPass = remember { mutableStateOf("") }
    var confirm = remember { mutableStateOf("") }
    var message = remember { mutableStateOf<Int?>(null) }
    var cooldown = remember { mutableStateOf(passwordManager.isInCooldown()) }
    val density = LocalDensity.current
    val isKeyboardOpen = WindowInsets.ime.getBottom(density) > 0
    val scrollState = rememberScrollState()
    val passFocusRequester = remember { FocusRequester() }
    val newPassFocusRequester = remember { FocusRequester() }
    val confirmFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val isA80 = App.model.equals("A80", ignoreCase = true)
    val requestChangeOnFirstUse = BuildConfig.REQUEST_PASSWORD_CHANGE_ON_FIRST_USE

    LaunchedEffect(step.value) {
        when (step.value) {
            Step.ENTER_PASS1, Step.ENTER_PASS2 -> {
                passFocusRequester.requestFocus()
                if (isA80) keyboardController?.hide() else keyboardController?.show()
            }
            Step.CHANGE_PASS1, Step.CHANGE_PASS2 -> {
                newPassFocusRequester.requestFocus()
                if (isA80) keyboardController?.hide() else keyboardController?.show()
            }
        }
    }

    LaunchedEffect(cooldown.value) {
        while (cooldown.value > 0) {
            delay(1000)
            cooldown.value = passwordManager.isInCooldown()
        }
    }

    if (cooldown.value > 0) {
        Logger.w(TAG, "In cooldown: ${cooldown.value} ms remaining")
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(stringResource(R.string.locked_try_again, cooldown.value / 1000))
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        TopBanner()
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(32.dp)
                .imePadding(),
            verticalArrangement = if (isKeyboardOpen) Arrangement.Top else Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (step.value) {
                Step.ENTER_PASS1 -> {
                    Text(stringResource(R.string.enter_password1), style = labelTextStyle)
                    val verifyPass1 = {
                        Logger.i(TAG, "Password 1 entered")
                        if (passwordManager.isInCooldown() > 0) {
                            cooldown.value = passwordManager.isInCooldown()
                            Logger.w(TAG, "Attempt during cooldown")
                        } else if (passwordManager.verifyPassword1(pass.value)) {
                            Logger.i(TAG, "Password 1 accepted")
                            pass.value = ""
                            message.value = null
                            if (requestChangeOnFirstUse && !passwordManager.isPassword1Changed()) {
                                step.value = Step.CHANGE_PASS1
                            } else {
                                step.value = Step.ENTER_PASS2
                            }
                        } else {
                            Logger.w(TAG, "Password 1 failed")
                            message.value = R.string.wrong_password
                            cooldown.value = passwordManager.isInCooldown()
                            if (cooldown.value > 0) {
                                Logger.w(TAG, "Cooldown started: ${cooldown.value} ms")
                            }
                        }
                    }
                    OutlinedTextField(
                        pass.value,
                        onValueChange = { pass.value = it },
                        modifier = Modifier
                            .focusRequester(passFocusRequester)
                            .onFocusChanged { if (isA80 && it.isFocused) keyboardController?.hide() },
                        placeholder = { Text(stringResource(R.string.password1_placeholder), style = passwordTextStyle) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { verifyPass1() }),
                        visualTransformation = PasswordVisualTransformation(),
                        textStyle = passwordTextStyle
                    )
                    Spacer(Modifier.height(16.dp))
                    GlobalConnectButton(onClick = verifyPass1, modifier = Modifier.height(60.dp)) {
                        Text(stringResource(R.string.confirm), fontSize = 20.sp)
                    }
                }

                Step.CHANGE_PASS1 -> {
                    Text(stringResource(R.string.set_new_password1), style = labelTextStyle)
                    val savePass1 = {
                        Logger.i(TAG, "Attempting password 1 change")
                        if (newPass.value != confirm.value) {
                            message.value = R.string.passwords_do_not_match
                            Logger.w(TAG, "Password 1 change failed: mismatch")
                        } else if (!passwordManager.updatePassword1(newPass.value)) {
                            message.value = R.string.invalid_password
                            Logger.w(TAG, "Password 1 change rejected")
                        } else {
                            Logger.i(TAG, "Password 1 changed")
                            newPass.value = ""
                            confirm.value = ""
                            message.value = null
                            step.value = Step.ENTER_PASS2
                        }
                    }
                    OutlinedTextField(
                        newPass.value,
                        onValueChange = { newPass.value = it },
                        modifier = Modifier
                            .focusRequester(newPassFocusRequester)
                            .onFocusChanged { if (isA80 && it.isFocused) keyboardController?.hide() },
                        placeholder = { Text(stringResource(R.string.new_password1_placeholder), style = passwordTextStyle) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(onNext = { confirmFocusRequester.requestFocus() }),
                        visualTransformation = PasswordVisualTransformation(),
                        textStyle = passwordTextStyle
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        confirm.value,
                        onValueChange = { confirm.value = it },
                        modifier = Modifier
                            .focusRequester(confirmFocusRequester)
                            .onFocusChanged { if (isA80 && it.isFocused) keyboardController?.hide() },
                        placeholder = { Text(stringResource(R.string.confirm_password1_placeholder), style = passwordTextStyle) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { savePass1() }),
                        visualTransformation = PasswordVisualTransformation(),
                        textStyle = passwordTextStyle
                    )
                    Spacer(Modifier.height(16.dp))
                    GlobalConnectButton(onClick = savePass1, modifier = Modifier.height(60.dp)) {
                        Text(stringResource(R.string.save), fontSize = 20.sp)
                    }
                }

                Step.ENTER_PASS2 -> {
                    Text(stringResource(R.string.enter_password2), style = labelTextStyle)
                    val verifyPass2 = {
                        Logger.i(TAG, "Password 2 entered")
                        if (passwordManager.isInCooldown() > 0) {
                            cooldown.value = passwordManager.isInCooldown()
                            Logger.w(TAG, "Attempt during cooldown")
                        } else if (passwordManager.verifyPassword2(pass.value)) {
                            Logger.i(TAG, "Password 2 accepted")
                            pass.value = ""
                            message.value = null
                            if (requestChangeOnFirstUse && passwordManager.isPassword2Default()) {
                                step.value = Step.CHANGE_PASS2
                            } else {
                                onSuccess()
                            }
                        } else {
                            Logger.w(TAG, "Password 2 failed")
                            message.value = R.string.wrong_password
                            cooldown.value = passwordManager.isInCooldown()
                            if (cooldown.value > 0) {
                                Logger.w(TAG, "Cooldown started: ${cooldown.value} ms")
                            }
                        }
                    }
                    OutlinedTextField(
                        pass.value,
                        onValueChange = { pass.value = it },
                        modifier = Modifier
                            .focusRequester(passFocusRequester)
                            .onFocusChanged { if (isA80 && it.isFocused) keyboardController?.hide() },
                        placeholder = { Text(stringResource(R.string.password2_placeholder), style = passwordTextStyle) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { verifyPass2() }),
                        visualTransformation = PasswordVisualTransformation(),
                        textStyle = passwordTextStyle
                    )
                    Spacer(Modifier.height(16.dp))
                    GlobalConnectButton(onClick = verifyPass2, modifier = Modifier.height(60.dp)) {
                        Text(stringResource(R.string.confirm), fontSize = 20.sp)
                    }
                }

                Step.CHANGE_PASS2 -> {
                    Text(stringResource(R.string.set_new_password2), style = labelTextStyle)
                    val savePass2 = {
                        Logger.i(TAG, "Attempting password 2 change")
                        if (newPass.value != confirm.value) {
                            message.value = R.string.passwords_do_not_match
                            Logger.w(TAG, "Password 2 change failed: mismatch")
                        } else if (!passwordManager.updatePassword2(newPass.value)) {
                            message.value = R.string.invalid_password
                            Logger.w(TAG, "Password 2 change rejected")
                        } else {
                            Logger.i(TAG, "Password 2 changed")
                            newPass.value = ""
                            confirm.value = ""
                            message.value = null
                            onSuccess()
                        }
                    }
                    OutlinedTextField(
                        newPass.value,
                        onValueChange = { newPass.value = it },
                        modifier = Modifier
                            .focusRequester(newPassFocusRequester)
                            .onFocusChanged { if (isA80 && it.isFocused) keyboardController?.hide() },
                        placeholder = { Text(stringResource(R.string.new_password2_placeholder), style = passwordTextStyle) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(onNext = { confirmFocusRequester.requestFocus() }),
                        visualTransformation = PasswordVisualTransformation(),
                        textStyle = passwordTextStyle
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        confirm.value,
                        onValueChange = { confirm.value = it },
                        modifier = Modifier
                            .focusRequester(confirmFocusRequester)
                            .onFocusChanged { if (isA80 && it.isFocused) keyboardController?.hide() },
                        placeholder = { Text(stringResource(R.string.confirm_password2_placeholder), style = passwordTextStyle) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { savePass2() }),
                        visualTransformation = PasswordVisualTransformation(),
                        textStyle = passwordTextStyle
                    )
                    Spacer(Modifier.height(16.dp))
                    GlobalConnectButton(onClick = savePass2, modifier = Modifier.height(60.dp)) {
                        Text(stringResource(R.string.save), fontSize = 20.sp)
                    }
                }
            }
            message.value?.let { Text(stringResource(it), color = Color.Red) }
        }
    }
}
