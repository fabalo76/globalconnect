package one.globalconnect.paymentapp.records

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import one.globalconnect.paymentapp.AppViewModelProvider
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.navigation.TopBar
import one.globalconnect.paymentapp.ui.theme.color_black
import one.globalconnect.paymentapp.ui.theme.color_primaryBrand
import one.globalconnect.paymentapp.ui.theme.color_secondaryThree

@Composable
fun ReprintLastTransactionScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReprintLastTransactionViewModel = viewModel(
        factory = AppViewModelProvider.provideFactory(LocalContext.current),
    ),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopBar(
                title = {
                    Text(
                        text = stringResource(id = R.string.reprint_last),
                        fontWeight = FontWeight.Medium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(id = R.string.back),
                        )
                    }
                },
                actions = null,
                modifier = Modifier
                    .height(60.dp)
                    .padding(top = 20.dp),
            )
        },
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(color_secondaryThree)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (uiState) {
                ReprintLastTransactionUiState.Printing -> {
                    CircularProgressIndicator(color = color_primaryBrand)
                    Text(
                        text = stringResource(id = R.string.reprint_last_printing),
                        modifier = Modifier.padding(top = 16.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }

                ReprintLastTransactionUiState.Printed -> ReprintResult(
                    message = stringResource(id = R.string.reprint_last_printed),
                    onBack = onBack,
                )

                ReprintLastTransactionUiState.NoTransactions -> ReprintResult(
                    message = stringResource(id = R.string.reprint_last_no_transactions),
                    onBack = onBack,
                )

                ReprintLastTransactionUiState.Failed -> ReprintResult(
                    message = stringResource(id = R.string.reprint_last_failed),
                    onBack = onBack,
                    onRetry = viewModel::reprint,
                )
            }
        }
    }
}

@Composable
private fun ReprintResult(
    message: String,
    onBack: () -> Unit,
    onRetry: (() -> Unit)? = null,
) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
    )
    if (onRetry != null) {
        Button(
            onClick = onRetry,
            modifier = Modifier
                .padding(top = 24.dp)
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = color_primaryBrand,
                contentColor = color_black,
            ),
        ) {
            Text(text = stringResource(id = R.string.action_retry))
        }
    }
    Button(
        onClick = onBack,
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .height(50.dp),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color_primaryBrand,
            contentColor = color_black,
        ),
    ) {
        Text(text = stringResource(id = R.string.back))
    }
}
