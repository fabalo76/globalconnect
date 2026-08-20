package one.globalconnect.keyinjection.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import one.globalconnect.keyinjection.App
import one.globalconnect.keyinjection.PasswordManager
import one.globalconnect.keyinjection.R

/**
 * Screen that guides the user through setting a new password. Validation is
 * performed to ensure the password is numeric, seven digits long and the
 * confirmation matches.
 *
 * @param passwordManager controller responsible for persisting passwords
 * @param passwordNumber which password is being changed (1 or 2)
 * @param onDone callback invoked when the password is successfully updated
 *
 * Author: Fabian Ramirez
 */
@Composable
fun ChangePasswordScreen(passwordManager: PasswordManager, passwordNumber: Int, onDone: () -> Unit) {
    val newPass = remember { mutableStateOf("") }
    val confirm = remember { mutableStateOf("") }
    val message = remember { mutableStateOf<Int?>(null) }
    val density = LocalDensity.current
    val isKeyboardOpen = WindowInsets.ime.getBottom(density) > 0
    val scrollState = rememberScrollState()
    val newPassFocusRequester = remember { FocusRequester() }
    val confirmFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val isA80 = App.model.equals("A80", ignoreCase = true)

    LaunchedEffect(Unit) {
        newPassFocusRequester.requestFocus()
        if (isA80) keyboardController?.hide() else keyboardController?.show()
    }

    Column(Modifier.fillMaxSize()) {
        TopBanner()
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(32.dp)
                .imePadding(),
            verticalArrangement = if (isKeyboardOpen) Arrangement.Top else Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(if (passwordNumber == 1) R.string.set_new_password1 else R.string.set_new_password2),
                style = labelTextStyle
            )
            val save = {
                if (newPass.value != confirm.value) {
                    message.value = R.string.passwords_do_not_match
                } else {
                    val ok = if (passwordNumber == 1) {
                        passwordManager.updatePassword1(newPass.value)
                    } else {
                        passwordManager.updatePassword2(newPass.value)
                    }
                    if (!ok) {
                        message.value = R.string.invalid_password
                    } else {
                        onDone()
                    }
                }
            }
            OutlinedTextField(
                value = newPass.value,
                onValueChange = { newPass.value = it },
                modifier = Modifier
                    .focusRequester(newPassFocusRequester)
                    .onFocusChanged { if (isA80 && it.isFocused) keyboardController?.hide() },
                placeholder = {
                    Text(
                        stringResource(
                            if (passwordNumber == 1) R.string.new_password1_placeholder else R.string.new_password2_placeholder
                        ),
                        style = passwordTextStyle
                    )
                },
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
                value = confirm.value,
                onValueChange = { confirm.value = it },
                modifier = Modifier
                    .focusRequester(confirmFocusRequester)
                    .onFocusChanged { if (isA80 && it.isFocused) keyboardController?.hide() },
                placeholder = {
                    Text(
                        stringResource(
                            if (passwordNumber == 1) R.string.confirm_password1_placeholder else R.string.confirm_password2_placeholder
                        ),
                        style = passwordTextStyle
                    )
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { save() }),
                visualTransformation = PasswordVisualTransformation(),
                textStyle = passwordTextStyle
            )
            Spacer(Modifier.height(16.dp))
            GlobalConnectButton(
                onClick = save,
                modifier = Modifier.height(60.dp),
            ) {
                Text(stringResource(R.string.save), fontSize = 20.sp)
            }
            message.value?.let { Text(stringResource(it), color = Color.Red) }
        }
    }
}
