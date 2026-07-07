package com.uic.uicpaymentapp.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uic.uicpaymentapp.AppViewModelProvider
import com.uic.uicpaymentapp.R
import com.uic.uicpaymentapp.transaction.Numpad
import com.uic.uicpaymentapp.ui.theme.color_grey90
import com.uic.uicpaymentapp.ui.theme.color_primaryBrand
import com.uic.uicpaymentapp.ui.theme.color_secondaryFive
import com.uic.uicpaymentapp.ui.theme.color_secondaryThree
import com.uic.uicpaymentapp.utils.hideSystemBarsForImmersive
import com.uic.uicpaymentapp.utils.rememberWindowInsetsController

@Composable
fun PasswordScreen(
    onNavigateToNext: (String) -> Unit
) {
    val context = LocalContext.current

    val viewModelFactory = remember { AppViewModelProvider.provideFactory(context) }

    val viewModel: PasswordViewModel = viewModel(factory = viewModelFactory)
    Scaffold(
        bottomBar = {
            Box(
                modifier = Modifier
                    .height(64.dp)
                    .background(color_secondaryThree)
                    .fillMaxSize()){}
        }
    ) { _ ->
        Column(
            Modifier.padding(0.dp,32.dp,0.dp,64.dp)
        ) {
            val input by viewModel.obscuredInput.observeAsState("")
            Text(
                fontSize = 24.sp,
                text = stringResource(id = R.string.enter_password),
                fontWeight = FontWeight.Normal,
                modifier = Modifier
                    .padding(top = 24.dp)
                    .align(Alignment.CenterHorizontally),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.weight(1f))
            Text(
                modifier = Modifier
                    .fillMaxWidth(1f),
                textAlign = TextAlign.Center,
                fontSize = 32.sp,
                text = input
            )
            Spacer(Modifier.weight(1f))
            var showFailedDialog by remember { mutableStateOf(false) }

            fun handleConfirm() {
                if (viewModel.checkPassword()) {
                    onNavigateToNext(viewModel.destination)
                } else {
                    showFailedDialog = true
                    viewModel.clearInputs()
                }
            }

            Numpad(
                onSelected = { pressedKey ->
                    when (pressedKey) {
                        "C" -> viewModel.deleteDigit()
                        else -> viewModel.inputDigit(pressedKey)
                    }
                },
                showReset = false,
                onEnterPressed = {
                    if (input.isNotBlank()) {
                        handleConfirm()
                    }
                }
            )
            if (showFailedDialog) {
                PasswordFailedDialog {
                    showFailedDialog = false
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(color_secondaryThree)
            ){
                Button(
                    modifier = Modifier
                        .fillMaxWidth(1f)
                        .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
                        .height(50.dp),
                    onClick = { handleConfirm() },
                    enabled = input.isNotBlank(),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = color_primaryBrand, // Button Background
                        contentColor = color_secondaryFive, // Text Color
                        disabledContainerColor = color_grey90
                    ),
                ) {
                    Text(
                        textAlign = TextAlign.Center,
                        text = stringResource(id = R.string.confirm_password)
                    )
                }
            }
        }
    }
}

@Composable
fun PasswordFailedDialog(onDismiss: () -> Unit) {
    val windowInsetsController = rememberWindowInsetsController()

    LaunchedEffect(windowInsetsController) {
        windowInsetsController?.hideSystemBarsForImmersive()
    }

    AlertDialog(
        title = {
            Text(text = stringResource(id = R.string.incorrect_password))
        },
        text = {
            Text(text = stringResource(id = R.string.incorrect_password_msg))
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    windowInsetsController?.hideSystemBarsForImmersive()
                    onDismiss()
                }
            ) {
                Text(stringResource(id = R.string.ok))
            }
        }
    )
}
