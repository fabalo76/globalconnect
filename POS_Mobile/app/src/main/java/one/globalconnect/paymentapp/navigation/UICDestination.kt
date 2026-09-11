package one.globalconnect.paymentapp.navigation

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RequestQuote
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import one.globalconnect.paymentapp.cardreader.CardReaderTestScreen
import one.globalconnect.paymentapp.profile.AppConfigScreen
import one.globalconnect.paymentapp.profile.ProfileScreen
import one.globalconnect.paymentapp.profile.AboutScreen
import one.globalconnect.paymentapp.profile.SystemSettingsScreen
import one.globalconnect.paymentapp.records.TransactionHistoryScreen
import one.globalconnect.paymentapp.records.ReportsScreen
import one.globalconnect.paymentapp.records.TotalsReportScreen
import one.globalconnect.paymentapp.records.ReprintSettlementScreen
import one.globalconnect.paymentapp.records.ReprintLastTransactionScreen
import one.globalconnect.paymentapp.transaction.SaleScreen
import one.globalconnect.paymentapp.transaction.CardTransactionScreen
import one.globalconnect.paymentapp.transaction.Transaction
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.profile.PasswordScreen
import one.globalconnect.paymentapp.records.EndOfDay
import one.globalconnect.paymentapp.settlement.SettlementRequest
import one.globalconnect.paymentapp.transaction.BatchScreen
import one.globalconnect.paymentapp.transaction.QuickTipScreen
import one.globalconnect.paymentapp.transaction.TransactionType
import one.globalconnect.paymentapp.transaction.toTransactionString
import one.globalconnect.paymentapp.parallel.echo.EchoTestScreen
import one.globalconnect.paymentapp.transaction.EditTransactionScreen
import one.globalconnect.paymentapp.transaction.AmountPromptConfig
import one.globalconnect.paymentapp.uicpos.pos.host.TransactionConfigRegistry
import one.globalconnect.paymentapp.transaction.hotel.HotelCheckInReportScreen
import one.globalconnect.paymentapp.transaction.hotel.HotelCheckInScreen
import one.globalconnect.paymentapp.transaction.hotel.HotelCheckOutScreen

const val AMOUNT_KEY = "amount"
const val TAX1_KEY = "tax1"
const val TAX2_KEY = "tax2"
const val TIP_KEY = "tip"
const val TRANSACTION_TYPE_KEY = "action"
const val TRANSACTION_ID_KEY = "transactionId"
const val DESTINATION_KEY = "destination"
const val FOLIO_KEY = "folio"
const val CHECK_IN_ID_KEY = "checkInId"
const val ORIGINAL_TRANSACTION_ID_KEY = "originalTxn"

interface UICDestination {
    val icon: ImageVector
    val route: String
//    val label: String
fun getLabel(context: Context): String
}

interface TransactionDestination : UICDestination {
    val transactionType: TransactionType
    val usesCardFlow: Boolean
    val amountPromptConfig: AmountPromptConfig
}


private fun resolveAmountPromptConfig(transactionType: TransactionType): AmountPromptConfig {
    val db = GlobalConnectPaymentApplication.instance.tmsDatabase
    val terminal = db.Terminal.firstOrNull()
    return TransactionConfigRegistry.amountPromptConfigFor(
        transactionType.toTransactionString(),
        terminal,
        db.Acquirer,
    ) ?: AmountPromptConfig(
        askAmount = false,
        askTax1 = false,
        askTax2 = false,
        askTip = false,
    )
}


// <editor-fold desc="Region: TRANSACTIONS">
/**
 * Sale screen that contains a numerical pad and charge button. Also the landing page for the
 * app.
 */
object dst_Sale : TransactionDestination {
    override val icon = Icons.Filled.CreditCard
    override val route: String = "Sale"
    override fun getLabel(context: Context): String {
        return context.getString(R.string.trans_sale)
    }
    override val transactionType: TransactionType = TransactionType.SALE
    override val usesCardFlow: Boolean = true
    override val amountPromptConfig: AmountPromptConfig
        get() = resolveAmountPromptConfig(transactionType)
    val screen: @Composable ((String, String, String, String, String, String) -> Unit) -> Unit =
        { onPressCharge: (String, String, String, String, String, String) -> Unit ->
            SaleScreen(
                onPressCharge,
                transactionType = transactionType,
                promptConfig = amountPromptConfig
            )
        }
}

/** Sale entry shown after selecting Sale from the hotel-oriented home screen. */
object dst_SaleTransaction : TransactionDestination {
    override val icon = Icons.Filled.CreditCard
    override val route: String = "SaleTransaction"
    override fun getLabel(context: Context): String = context.getString(R.string.trans_sale)
    override val transactionType: TransactionType = TransactionType.SALE
    override val usesCardFlow: Boolean = true
    override val amountPromptConfig: AmountPromptConfig
        get() = resolveAmountPromptConfig(transactionType)
    val screen: @Composable ((String, String, String, String, String, String) -> Unit) -> Unit =
        { onPressCharge: (String, String, String, String, String, String) -> Unit ->
            SaleScreen(
                onPressCharge,
                transactionType = transactionType,
                promptConfig = amountPromptConfig,
            )
        }
}

object dst_Refund : TransactionDestination {
    override val icon = Icons.Filled.CreditCard
    override val route: String = "Refund"
    override fun getLabel(context: Context): String {
        return context.getString(R.string.trans_refund)
    }
    override val transactionType: TransactionType = TransactionType.REFUND
    override val usesCardFlow: Boolean = true
    override val amountPromptConfig: AmountPromptConfig
        get() = resolveAmountPromptConfig(transactionType)
    val screen: @Composable ((String, String, String, String, String, String) -> Unit) -> Unit =
        { onPressCharge: (String, String, String, String, String, String) -> Unit ->
            SaleScreen(
                onPressCharge,
                transactionType = transactionType,
                promptConfig = amountPromptConfig
            )
        }
}

object dst_Cash : TransactionDestination {
    override val icon = Icons.Filled.CreditCard
    override val route: String = "Cash"
    override fun getLabel(context: Context): String {
        return context.getString(R.string.trans_cash)
    }
    override val transactionType: TransactionType = TransactionType.CASH
    override val usesCardFlow: Boolean = false
    override val amountPromptConfig: AmountPromptConfig
        get() = resolveAmountPromptConfig(transactionType)
    val screen: @Composable ((String, String, String, String, String, String) -> Unit) -> Unit =
        { onPressCharge: (String, String, String, String, String, String) -> Unit ->
            SaleScreen(
                onPressCharge,
                transactionType = transactionType,
                promptConfig = amountPromptConfig
            )
        }
}

object dst_Payment : TransactionDestination {
    override val icon = Icons.Filled.CreditCard
    override val route: String = "Payment"
    override fun getLabel(context: Context): String {
        return context.getString(R.string.trans_payment)
    }
    override val transactionType: TransactionType = TransactionType.PAYMENT
    override val usesCardFlow: Boolean = true
    override val amountPromptConfig: AmountPromptConfig
        get() = resolveAmountPromptConfig(transactionType)
    val screen: @Composable ((String, String, String, String, String, String) -> Unit) -> Unit =
        { onPressCharge: (String, String, String, String, String, String) -> Unit ->
            SaleScreen(
                onPressCharge,
                transactionType = transactionType,
                promptConfig = amountPromptConfig
            )
        }
}

object dst_LoyaltySale : TransactionDestination {
    override val icon = Icons.Filled.CreditCard
    override val route: String = "LoyaltySale"
    override fun getLabel(context: Context): String {
        return context.getString(R.string.trans_loyalty_sale)
    }
    override val transactionType: TransactionType = TransactionType.LOYALTY_SALE
    override val usesCardFlow: Boolean = true
    override val amountPromptConfig: AmountPromptConfig
        get() = resolveAmountPromptConfig(transactionType)
    val screen: @Composable ((String, String, String, String, String, String) -> Unit) -> Unit =
        { onPressCharge: (String, String, String, String, String, String) -> Unit ->
            SaleScreen(
                onPressCharge,
                transactionType = transactionType,
                promptConfig = amountPromptConfig
            )
        }
}

object dst_LoyaltyBalance : UICDestination {
    override val icon = Icons.AutoMirrored.Filled.ShowChart
    override val route: String = "LoyaltyBalance"
    override fun getLabel(context: Context): String =
        context.getString(R.string.trans_loyalty_balance)
}

object dst_QuotaSale : TransactionDestination {
    override val icon = Icons.Filled.CreditCard
    override val route: String = "QuotaSale"
    override fun getLabel(context: Context): String {
        return context.getString(R.string.trans_quota_sale)
    }
    override val transactionType: TransactionType = TransactionType.QUOTA_SALE
    override val usesCardFlow: Boolean = true
    override val amountPromptConfig: AmountPromptConfig
        get() = resolveAmountPromptConfig(transactionType)
    val screen: @Composable ((String, String, String, String, String, String) -> Unit) -> Unit =
        { onPressCharge: (String, String, String, String, String, String) -> Unit ->
            SaleScreen(
                onPressCharge,
                transactionType = transactionType,
                promptConfig = amountPromptConfig
            )
        }
}

object dst_CheckIn : UICDestination {
    override val icon = Icons.AutoMirrored.Filled.Login
    override val route: String = "HotelCheckIn"
    override fun getLabel(context: Context): String {
        return context.getString(R.string.trans_check_in)
    }
    val screen: @Composable (
        (String, String, String, String, String, String) -> Unit,
        () -> Unit,
    ) -> Unit =
        { onSubmit: (String, String, String, String, String, String) -> Unit, onCancel: () -> Unit ->
            HotelCheckInScreen(
                onSubmit = { base, tax1, tax2, tip, folio ->
                    onSubmit(TransactionType.CHECKIN.toTransactionString(), base, tax1, tax2, tip, folio)
                },
                onCancel = onCancel,
            )
        }
}

object dst_CheckOut : UICDestination {
    override val icon = Icons.AutoMirrored.Filled.Logout
    override val route: String = "HotelCheckOut"
    override fun getLabel(context: Context): String {
        return context.getString(R.string.trans_check_out)
    }
    val screen: @Composable (
        Int?,
        (String, String, String, String, String, String, Int, String) -> Unit,
        () -> Unit,
    ) -> Unit =
        { selectedCheckInId: Int?, onSubmit: (String, String, String, String, String, String, Int, String) -> Unit, onCancel: () -> Unit ->
            HotelCheckOutScreen(
                selectedCheckInId = selectedCheckInId,
                onSubmit = { base, tax1, tax2, tip, folio, checkInId, originalTxnId ->
                    onSubmit(
                        TransactionType.CHECKOUT.toTransactionString(),
                        base,
                        tax1,
                        tax2,
                        tip,
                        folio,
                        checkInId,
                        originalTxnId,
                    )
                },
                onCancel = onCancel,
            )
        }
}

object dst_ExtrasSale : TransactionDestination {
    override val icon = Icons.Filled.CreditCard
    override val route: String = "ExtrasSale"
    override fun getLabel(context: Context): String {
        return context.getString(R.string.trans_extras_sale)
    }
    override val transactionType: TransactionType = TransactionType.EXTRAS_SALE
    override val usesCardFlow: Boolean = true
    override val amountPromptConfig: AmountPromptConfig
        get() = resolveAmountPromptConfig(transactionType)
    val screen: @Composable ((String, String, String, String, String, String) -> Unit) -> Unit =
        { onPressCharge: (String, String, String, String, String, String) -> Unit ->
            SaleScreen(
                onPressCharge,
                transactionType = transactionType,
                promptConfig = amountPromptConfig
            )
        }
}


object dst_EndOfDay : UICDestination {
    override val icon = Icons.AutoMirrored.Filled.ReceiptLong
    override val route: String = "EndOfDay"
    //    override val label: String = GlobalConnectPaymentApplication.instance.resources.getString(R.string.end_of_day)
    override fun getLabel(context: Context): String {
        return context.getString(R.string.end_of_day)
    }
    val screen: @Composable ((SettlementRequest) -> Unit) -> Unit =
        { onStartSettlement: (SettlementRequest) -> Unit ->
            EndOfDay(onStartSettlement = onStartSettlement)
        }
}


object dst_TransactionFinished : UICDestination {
    override val icon = Icons.Filled.PointOfSale
    override val route: String = "TransactionFinished/{$TRANSACTION_ID_KEY}"
    //    override val label: String = "Transaction Finished"
    override fun getLabel(context: Context): String {
        return context.getString(R.string.transaction_finished)
    }
}

object dst_CardTransaction : UICDestination {
    override val icon = Icons.Filled.PointOfSale
    override val route: String = "CardTransaction/{$TRANSACTION_TYPE_KEY}/{$AMOUNT_KEY}/?$TAX1_KEY={$TAX1_KEY}&$TAX2_KEY={$TAX2_KEY}&$TIP_KEY={$TIP_KEY}&$FOLIO_KEY={$FOLIO_KEY}&$CHECK_IN_ID_KEY={$CHECK_IN_ID_KEY}&$ORIGINAL_TRANSACTION_ID_KEY={$ORIGINAL_TRANSACTION_ID_KEY}"
    override fun getLabel(context: Context): String {
        return context.getString(R.string.trans_sale)
    }
    val screen: @Composable ((String, TransactionType) -> Unit, () -> Unit) -> Unit =
        { onNavigateToResult: (String, TransactionType) -> Unit, onCancel: () -> Unit ->
            CardTransactionScreen(
                onNavigateToResult = onNavigateToResult,
                onCancel = onCancel,
            )
        }
}


object dst_PostTipRestaurant : UICDestination {
    override val icon: ImageVector
        get() = TODO("Not yet implemented")
    override val route: String
        get() = "PostTipScreen/{$TRANSACTION_ID_KEY}"
    override fun getLabel(context: Context): String {
        return context.getString(R.string.posttip)
    }

}

object dst_TipScreen : UICDestination {
    override val icon = Icons.Filled.PointOfSale
    override val route: String = "TipScreen?$TRANSACTION_ID_KEY={$TRANSACTION_ID_KEY}" +
            "&$TRANSACTION_TYPE_KEY={$TRANSACTION_TYPE_KEY}&$AMOUNT_KEY={$AMOUNT_KEY}"
    //    override val label: String = "Tip Screen"
    override fun getLabel(context: Context): String {
        return context.getString(R.string.tip_screen)
    }
}

object dst_EditTransaction : UICDestination {
    override val icon = Icons.Filled.PointOfSale
    override val route: String = "Edit/{$TRANSACTION_ID_KEY}"
    override fun getLabel(context: Context): String {
        return context.getString(R.string.edit)
    }
    val screen: @Composable (() -> Unit) -> Unit =
        { popMethod: () -> Unit ->
            EditTransactionScreen(
                onBackButtonPressed = popMethod
            )
        }
}

object dst_CheckInReport : UICDestination {
    override val icon = Icons.AutoMirrored.Filled.List
    override val route: String = "HotelCheckInReport"
    override fun getLabel(context: Context): String {
        return context.getString(R.string.hotel_check_in_report_title)
    }
    val screen: @Composable ((() -> Unit), (Transaction) -> Unit) -> Unit = { onBack, onCheckOut ->
        HotelCheckInReportScreen(onBack = onBack, onCheckOut = onCheckOut)
    }
}

object dst_OpenCheckIns : UICDestination {
    override val icon = Icons.AutoMirrored.Filled.List
    override val route: String = "HotelOpenCheckIns"
    override fun getLabel(context: Context): String = context.getString(R.string.hotel_check_in_report_title)
    val screen: @Composable ((() -> Unit), (Transaction) -> Unit) -> Unit = { onBack, onCheckOut ->
        HotelCheckInReportScreen(onBack = onBack, onCheckOut = onCheckOut)
    }
}

object dst_IncrementalCheckIn : UICDestination {
    override val icon = Icons.Filled.RequestQuote
    override val route: String = "HotelIncrementalCheckIn"
    override fun getLabel(context: Context): String = context.getString(R.string.hotel_home_incremental_check_in)
    val screen: @Composable ((() -> Unit), (Transaction) -> Unit) -> Unit = { onBack, onCheckOut ->
        HotelCheckInReportScreen(
            onBack = onBack,
            onCheckOut = onCheckOut,
            title = stringResource(id = R.string.hotel_home_incremental_check_in),
        )
    }
}

// </editor-fold> TRANSACTIONS


// <editor-fold desc="Region: UTILITARY SCREENS">

/**
 * Password screen. Is thrown up as intermediary layer before navigating to password-protected
 * screens. Access control rules and documentation can be found on the team's Confluence page.
 */
object dst_PasswordScreen: UICDestination {
    override val icon: ImageVector
        get() = TODO("Not yet implemented")
    override val route: String
        get() = "Password/{$DESTINATION_KEY}"
    override fun getLabel(context: Context): String {
        return context.getString(R.string.password)
    }
    val screen: @Composable ((String) -> Unit) -> Unit=
        { onNavigateToNext: (String) -> Unit ->
            PasswordScreen(onNavigateToNext)
        }

}

/**
 * Reports screen. Contains an expandable summary tab and a truncated transaction history tab
 */
object dst_Reports : UICDestination {
    override val icon = Icons.AutoMirrored.Filled.ReceiptLong
    override val route: String = "Reports"
    //    override val label: String = GlobalConnectPaymentApplication.instance.resources.getString(R.string.reports)
    override fun getLabel(context: Context): String {
        return context.getString(R.string.reports)
    }
    val screen: @Composable (() -> Unit) -> Unit =
        { _: () -> Unit ->
            ReportsScreen()
        }
}

object dst_ReportTotals : UICDestination {
    override val icon = Icons.Filled.BarChart
    override val route: String = "Reports/Totals"
    override fun getLabel(context: Context): String {
        return context.getString(R.string.totals_report)
    }
    val screen: @Composable (() -> Unit) -> Unit = { onBack ->
        ReportsScreen(
            titleResId = R.string.totals_report,
            onBack = onBack,
            printTransactions = false,
        )
    }
}

object dst_ReportAudit : UICDestination {
    override val icon = Icons.AutoMirrored.Filled.FactCheck
    override val route: String = "Reports/Audit"
    override fun getLabel(context: Context): String {
        return context.getString(R.string.audit_report)
    }
    val screen: @Composable (() -> Unit) -> Unit = { onBack ->
        ReportsScreen(
            titleResId = R.string.audit_report,
            onBack = onBack,
            printTransactions = true,
        )
    }
}

object dst_OfflinePinChange : UICDestination {
    override val icon = Icons.Filled.VpnKey
    override val route: String =
        "CardTransaction/${TransactionType.OFFLINE_PIN_CHANGE.toTransactionString()}/0.00/" +
            "?$TAX1_KEY=0.00&$TAX2_KEY=0.00&$TIP_KEY=0.00&$FOLIO_KEY=" +
            "&$CHECK_IN_ID_KEY=&$ORIGINAL_TRANSACTION_ID_KEY="

    override fun getLabel(context: Context): String =
        context.getString(R.string.trans_offline_pin_change)
}

object dst_PinUnblock : UICDestination {
    override val icon = Icons.Filled.VpnKey
    override val route: String =
        "CardTransaction/${TransactionType.PIN_UNBLOCK.toTransactionString()}/0.00/" +
            "?$TAX1_KEY=0.00&$TAX2_KEY=0.00&$TIP_KEY=0.00&$FOLIO_KEY=" +
            "&$CHECK_IN_ID_KEY=&$ORIGINAL_TRANSACTION_ID_KEY="

    override fun getLabel(context: Context): String =
        context.getString(R.string.trans_pin_unblock)
}

object dst_ReportReprintLast : UICDestination {
    override val icon = Icons.AutoMirrored.Filled.ReceiptLong
    override val route: String = "Reports/ReprintLast"
    override fun getLabel(context: Context): String {
        return context.getString(R.string.reprint_last)
    }
    val screen: @Composable (() -> Unit) -> Unit = { onBack ->
        ReprintLastTransactionScreen(onBack = onBack)
    }
}

object dst_ReportReprintSettlement : UICDestination {
    override val icon = Icons.Filled.RequestQuote
    override val route: String = "Reports/ReprintSettlement"
    override fun getLabel(context: Context): String {
        return context.getString(R.string.reprint_settlement)
    }
    val screen: @Composable (() -> Unit) -> Unit = { onBack ->
        ReprintSettlementScreen(onBack = onBack)
    }
}

object dst_Signature : UICDestination {
    override val icon: ImageVector
        get() = Icons.AutoMirrored.Filled.ArrowRight
    override val route: String = "Signature/{$TRANSACTION_ID_KEY}"
    override fun getLabel(context: Context): String {
        return context.getString(R.string.signature)
    }
}

object dst_Profile : UICDestination {
    override val icon = Icons.Filled.Person
    override val route: String = "Profile"
    //    override val label: String = GlobalConnectPaymentApplication.instance.resources.getString(R.string.profile)
    override fun getLabel(context: Context): String {
        return context.getString(R.string.profile)
    }
    val screen: @Composable (() -> Unit) -> Unit =
        { onBackPress: () -> Unit ->
            ProfileScreen(
                onBackPressed = onBackPress,
            )
        }
}

// </editor-fold> UTILITARY SCREENS


// <editor-fold desc="Region: TRANSACTIONS/BATCH">

object dst_Transactions : UICDestination {
    override val icon = Icons.Filled.Dashboard
    override val route: String = "Transactions"
    override fun getLabel(context: Context): String {
        return context.getString(R.string.transaction_tab_label)
    }
    val screen: @Composable ((String) -> Unit, () -> Unit) -> Unit =
        { onPressTransaction: (String) -> Unit, onQuickTipNav: () -> Unit ->
            TransactionHistoryScreen(onPressTransaction = onPressTransaction, navigateToQuickTipScreen = onQuickTipNav)
        }
}

object dst_QuickTip : UICDestination {
    override val icon = Icons.AutoMirrored.Filled.LibraryBooks
    override val route: String = "QuickTip"
    override fun getLabel(context: Context): String {
        return context.getString(R.string.quick_tip)
    }
    val screen: @Composable (() -> Unit) -> Unit =
        { onBackPress: ()-> Unit ->
            QuickTipScreen(onBackPress = onBackPress)
        }
}

object dst_Batch : UICDestination {
    override val icon = Icons.AutoMirrored.Filled.LibraryBooks
    override val route: String = "Batch"
    override fun getLabel(context: Context): String {
        return context.getString(R.string.batch)
    }
    val screen: @Composable ((String) -> Unit, () -> Unit) -> Unit =
        { onPressTransaction: (String) -> Unit, onBackPress: ()-> Unit ->
            BatchScreen(onPressTransaction = onPressTransaction, onBackPress = onBackPress)
        }
}

// </editor-fold> TRANSACTIONS/BATCH


// <editor-fold desc="Region: MENU SCREENS">

object dst_NewTransaction: UICDestination {
    override val icon = Icons.AutoMirrored.Filled.LibraryBooks
    override val route: String = "NewTransaction"
    override fun getLabel(context: Context): String {
        return context.getString(R.string.txt_new)
    }
    val screen: @Composable (((UICDestination) -> Unit) -> Unit) =
        { onDestinationSelected: (UICDestination) -> Unit ->
            NewTransactionMenuScreen(onDestinationSelected = onDestinationSelected)
        }
}

object dst_CardReaderTest : UICDestination {
    override val icon = Icons.Filled.Nfc
    override val route: String = "CardReaderTest"
    override fun getLabel(context: Context): String {
        return context.getString(R.string.card_reader_test)
    }
    val screen: @Composable () -> Unit = {
        CardReaderTestScreen()
    }
}

object dst_EchoTest : UICDestination {
    override val icon = Icons.Filled.PointOfSale
    override val route: String = "EchoTest"
    override fun getLabel(context: Context): String {
        return context.getString(R.string.echo_test_transaction)
    }
    val screen: @Composable () -> Unit = {
        EchoTestScreen()
    }
}

object dst_TerminalCounters : UICDestination {
    override val icon = Icons.Filled.Numbers
    override val route: String = "TerminalCounters"
    override fun getLabel(context: Context): String {
        return context.getString(R.string.terminal_counters)
    }
    val screen: @Composable ((() -> Unit) -> Unit) = { onBack ->
        TerminalCountersScreen(onBack = onBack)
    }
}

object dst_AudioTest : UICDestination {
    override val icon: ImageVector get() = Icons.Filled.Settings
    override val route: String = "AudioTest"
    override fun getLabel(context: Context): String = context.getString(R.string.audio_test)
    val screen: @Composable (() -> Unit) -> Unit = { onBack -> AudioTestScreen(onBack) }
}

object dst_ApplicationInfo : UICDestination {
    override val icon = Icons.Filled.Info
    override val route: String = "ApplicationInfo"
    override fun getLabel(context: Context): String {
        return context.getString(R.string.application_info)
    }
    val screen: @Composable ((() -> Unit) -> Unit) = { onBack ->
        AboutScreen(
            previousScreenName = stringResource(id = R.string.functions),
            onPressBackButton = onBack
        )
    }
}

object dst_AppConfig : UICDestination {
    override val icon = Icons.Filled.Settings
    override val route: String = "AppConfig"
    override fun getLabel(context: Context): String {
        return context.getString(R.string.app_config)
    }
    val screen: @Composable ((() -> Unit) -> Unit) = { onBack ->
        val context = LocalContext.current
        val sharedPreferences = context.getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
        AppConfigScreen(
            context = context,
            sharedPreferences = sharedPreferences,
            previousScreenName = context.getString(R.string.config_menu),
            onBack = onBack,
        )
    }
}

object dst_MoreMenu: UICDestination {
    override val icon = Icons.Filled.Menu
    override val route: String = "MoreMenu"
    override fun getLabel(context: Context): String {
        return context.getString(R.string.more_tab)
    }
    val screen: @Composable (( (UICDestination) -> Unit, (ReportShortcut) -> Unit) -> Unit) =
        { onDestinationSelected, onReportShortcutSelected ->
            MoreMenuScreen(
                onDestinationSelected = onDestinationSelected,
                onReportShortcutSelected = onReportShortcutSelected,
            )
        }
}

object dst_SystemSettings : UICDestination {
    override val icon: ImageVector
        get() = Icons.Filled.Settings
    override val route: String
        get() = "SystemSettings"

    override fun getLabel(context: Context): String {
        return context.getString(R.string.system_settings_2)
    }
    val screen: @Composable () -> Unit = {
        val context = LocalContext.current
        val sharedPreferences = context.getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
        SystemSettingsScreen(
            navigateToProfile = {},
            navigateToAbout = {},
            navigateToLanguage = {},
            navigateToDeviceSettings = {},
            navigateToPassword = {},
            navigateToCommandTimeout = {},
            sharedPreferences = sharedPreferences,
        )
    }
}

// </editor-fold> MENU SCREENS



val navBarScreens = listOf(dst_Sale,dst_Transactions, dst_NewTransaction, dst_MoreMenu)
