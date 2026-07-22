package one.globalconnect.paymentapp

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import one.globalconnect.paymentapp.profile.InfoMgmtViewModel
import one.globalconnect.paymentapp.profile.PasswordViewModel
import one.globalconnect.paymentapp.profile.ProfileViewModel
import one.globalconnect.paymentapp.profile.SystemSettingsViewModel
import one.globalconnect.paymentapp.records.TransactionHistoryViewModel
import one.globalconnect.paymentapp.records.ReportViewModel
import one.globalconnect.paymentapp.records.TotalsReportViewModel
import one.globalconnect.paymentapp.records.ReprintSettlementViewModel
import one.globalconnect.paymentapp.records.ReprintLastTransactionViewModel
import one.globalconnect.paymentapp.transaction.CardTransactionViewModel
import one.globalconnect.paymentapp.transaction.TransactionDetailsViewModel
import one.globalconnect.paymentapp.records.EndOfDayViewModel
import one.globalconnect.paymentapp.records.QuickTipViewModel
import one.globalconnect.paymentapp.signature.SignatureViewModel
import one.globalconnect.paymentapp.transaction.FinishedPaymentViewModel
import one.globalconnect.paymentapp.transaction.TipViewModel
import one.globalconnect.paymentapp.transaction.BatchViewModel
import one.globalconnect.paymentapp.transaction.hotel.HotelCheckInReportViewModel
import one.globalconnect.paymentapp.transaction.hotel.HotelCheckInViewModel
import one.globalconnect.paymentapp.transaction.hotel.HotelCheckOutViewModel

/**
 * Provides Factory to create instance of ViewModel for the entire Payment app
 */
object AppViewModelProvider {
    fun  provideFactory(context: Context) = viewModelFactory {
        // Initializer for PaymentViewModel
        initializer {
            InfoMgmtViewModel(
                uicApplication().container.infoMgmtRepository,
                context
            )
        }

        initializer {
            EndOfDayViewModel(
                uicApplication().container.transactionRepository,
                uicApplication().container.tmsDatabase,
            )
        }

        initializer {
            BatchViewModel(
                uicApplication().container.transactionRepository,
                uicApplication().container.profileRepository,
                context
            )
        }

        initializer {
            HotelCheckInViewModel(
                uicApplication().container.tmsDatabase,
            )
        }

        initializer {
            HotelCheckOutViewModel(
                uicApplication().container.transactionRepository,
            )
        }

        initializer {
            HotelCheckInReportViewModel(
                uicApplication().container.transactionRepository,
                uicApplication().container.profileRepository,
                uicApplication().container.signatureRepository,
                context,
            )
        }

        initializer {
            TransactionDetailsViewModel(
                this.createSavedStateHandle(),
                uicApplication().container.transactionRepository,
                uicApplication().container.profileRepository,
                uicApplication().container.signatureRepository,
                context,
                uicApplication().container.tmsDatabase,
            )
        }

        // Initializer for FinishedPaymentViewModel
        initializer {
            FinishedPaymentViewModel(
                this.createSavedStateHandle(),
                uicApplication().container.transactionRepository,
                uicApplication().container.profileRepository,
                uicApplication().container.signatureRepository
            )
        }

        initializer {
            TipViewModel(
                this.createSavedStateHandle(),
                uicApplication().container.transactionRepository
            )
        }

        initializer {
            PasswordViewModel(
                this.createSavedStateHandle(),
            )
        }

        initializer {
            TransactionHistoryViewModel(
                uicApplication().container.transactionRepository
            )
        }

        initializer {
            SignatureViewModel(
                this.createSavedStateHandle(),
                uicApplication().container.signatureRepository,
                uicApplication().container.transactionRepository
            )
        }

        initializer {
            ReportViewModel(
                uicApplication().container.transactionRepository,
                uicApplication().container.profileRepository,
                uicApplication().container.tmsDatabase,
            )
        }

        initializer {
            TotalsReportViewModel(
                uicApplication().container.transactionRepository,
                uicApplication().container.profileRepository,
                uicApplication().container.tmsDatabase,
            )
        }

        initializer {
            ReprintSettlementViewModel(
                uicApplication().container.settlementStateRepository,
                uicApplication().container.tmsDatabase,
            )
        }

        initializer {
            ReprintLastTransactionViewModel(
                uicApplication().container.transactionRepository,
                uicApplication().container.profileRepository,
                uicApplication().container.signatureRepository,
            )
        }

        initializer {
            ProfileViewModel(
                uicApplication().container.profileRepository,
            )
        }

        initializer {
            SystemSettingsViewModel(
                uicApplication().container.systemParametersRepository,
            )
        }

        initializer {
            QuickTipViewModel(
                uicApplication().container.transactionRepository,
                uicApplication().container.tmsDatabase,
            )
        }

        initializer {
            CardTransactionViewModel(
                this.createSavedStateHandle(),
                uicApplication().container.transactionRepository,
                uicApplication().container.tmsDatabase,
                uicApplication().container.profileRepository,
                uicApplication().container.settlementStateRepository,
            )
        }
    }
}

/**
 * Extension function to queries for [Application] object and returns an instance of
 * [GlobalConnectPaymentApplication].
 */
fun CreationExtras.uicApplication(): GlobalConnectPaymentApplication =
    (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as GlobalConnectPaymentApplication)
