package one.globalconnect.paymentapp.transaction

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import one.globalconnect.paymentapp.AppViewModelProvider
import one.globalconnect.paymentapp.BuildConfig

@Composable
fun BrandingAnimationScreen(onComplete: () -> Unit) {
    val context = LocalContext.current
    val viewModelFactory = remember { AppViewModelProvider.provideFactory(context) }
    val viewModel: FinishedPaymentViewModel = viewModel(factory = viewModelFactory)
    val transaction by viewModel.transaction.observeAsState(Transaction())

    if (BuildConfig.BRANDING_ANIMATION_FULL_SCREEN) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            ),
        ) {
            val dialogView = LocalView.current
            SideEffect {
                val window = (dialogView.parent as? DialogWindowProvider)?.window
                if (window != null) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                    window.setLayout(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                    )
                    WindowCompat.setDecorFitsSystemWindows(window, false)
                    WindowInsetsControllerCompat(window, dialogView).apply {
                        hide(WindowInsetsCompat.Type.systemBars())
                        systemBarsBehavior =
                            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    }
                }
            }
            BrandingContent(viewModel, transaction, onComplete)
        }
    } else {
        BrandingContent(viewModel, transaction, onComplete)
    }
}

@Composable
private fun BrandingContent(
    viewModel: FinishedPaymentViewModel,
    transaction: Transaction,
    onComplete: () -> Unit,
) {
    if (transaction.id != 0) {
        val acquirer = viewModel.tmsAcquirer
        val terminal = viewModel.tmsTerminal
        BrandingAnimationOverlay(
            cardType = buildBrandingCandidateText(transaction),
            merchantName = terminal?.MerchantTitle1.orEmpty(),
            merchantId = acquirer?.MerchID.orEmpty(),
            city = terminal?.MerchantTitle2.orEmpty(),
            countryCode = acquirer?.CountryCode?.takeIf { it > 0 }?.toString() ?: "840",
            onComplete = onComplete,
        )
    } else {
        Box(Modifier.fillMaxSize().background(Color.Black))
    }
}

private fun buildBrandingCandidateText(transaction: Transaction): String =
    listOf(
        transaction.cardType,
        transaction.cardRangeName,
        transaction.applicationName,
        transaction.applicationLabel,
        transaction.AID,
    ).filter { it.isNotBlank() }.joinToString(" ")
