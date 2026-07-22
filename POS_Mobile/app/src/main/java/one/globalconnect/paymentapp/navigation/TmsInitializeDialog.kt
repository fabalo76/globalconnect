package one.globalconnect.paymentapp.navigation

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "TmsInitializeDialog"
private const val TIMEOUT_MS = 60_000L

private sealed class InitDialogState {
    object Checking   : InitDialogState()
    object Requesting : InitDialogState()
    object Success    : InitDialogState()
    object TimedOut   : InitDialogState()
    data class Error(val message: String) : InitDialogState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TmsInitializeDialog(onDismiss: () -> Unit) {
    var state: InitDialogState by remember { mutableStateOf(InitDialogState.Checking) }

    LaunchedEffect(Unit) {
        val app = GlobalConnectPaymentApplication.instance

        // Batch check
        val count = try {
            app.container.transactionRepository
                .getTransactionCount()
                .first()
        } catch (e: Exception) {
            Log.e(TAG, "Batch check failed", e)
            state = InitDialogState.Error(
                app.getString(R.string.setting_initialize_err_generic)
            )
            return@LaunchedEffect
        }

        if (count > 0) {
            state = InitDialogState.Error(
                app.getString(R.string.setting_initialize_err_batch_not_empty)
            )
            return@LaunchedEffect
        }

        // Send request and start timeout race
        app.requestParamsFromXtmsAgent()
        state = InitDialogState.Requesting
        Log.i(TAG, "Params request sent — waiting up to ${TIMEOUT_MS}ms")

        val successJob = launch {
            app.paramsUpdateEvent.first()
            Log.i(TAG, "paramsUpdateEvent received — success")
            state = InitDialogState.Success
        }

        delay(TIMEOUT_MS)

        if (state == InitDialogState.Requesting) {
            Log.w(TAG, "Timed out waiting for params")
            successJob.cancel()
            state = InitDialogState.TimedOut
        }
    }

    BasicAlertDialog(onDismissRequest = {}) {
        Card(
            shape = MaterialTheme.shapes.large,
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            modifier = Modifier.fillMaxWidth(0.92f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                Text(
                    text = stringResource(R.string.setting_initialize),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(16.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = when (state) {
                            is InitDialogState.Success    -> MaterialTheme.colorScheme.secondaryContainer
                            is InitDialogState.Requesting,
                            is InitDialogState.Checking   -> MaterialTheme.colorScheme.secondaryContainer
                            else                          -> MaterialTheme.colorScheme.errorContainer
                        }
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        when (val s = state) {
                            is InitDialogState.Checking -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(32.dp),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    text = stringResource(R.string.setting_initialize_checking),
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }

                            is InitDialogState.Requesting -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(32.dp),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    text = stringResource(R.string.setting_initialize_requesting),
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }

                            is InitDialogState.Success -> {
                                Text(
                                    text = stringResource(R.string.setting_initialize_success_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    text = stringResource(R.string.setting_initialize_success_body),
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }

                            is InitDialogState.TimedOut -> {
                                Text(
                                    text = stringResource(R.string.setting_initialize_timeout_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = stringResource(R.string.setting_initialize_timeout_body),
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }

                            is InitDialogState.Error -> {
                                Text(
                                    text = s.message,
                                    style = MaterialTheme.typography.titleMedium,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }

                if (state !is InitDialogState.Checking && state !is InitDialogState.Requesting) {
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.ok))
                    }
                }
            }
        }
    }
}
