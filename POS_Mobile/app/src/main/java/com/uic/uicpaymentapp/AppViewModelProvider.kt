package com.uic.uicpaymentapp

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.uic.uicpaymentapp.profile.InfoMgmtViewModel
import com.uic.uicpaymentapp.profile.PasswordViewModel
import com.uic.uicpaymentapp.profile.ProfileViewModel
import com.uic.uicpaymentapp.profile.SystemSettingsViewModel
import com.uic.uicpaymentapp.records.TransactionHistoryViewModel
import com.uic.uicpaymentapp.records.ReportViewModel
import com.uic.uicpaymentapp.records.TotalsReportViewModel
import com.uic.uicpaymentapp.records.ReprintSettlementViewModel
import com.uic.uicpaymentapp.transaction.CardTransactionViewModel
import com.uic.uicpaymentapp.transaction.TransactionDetailsViewModel
import com.uic.uicpaymentapp.records.EndOfDayViewModel
import com.uic.uicpaymentapp.records.QuickTipViewModel
import com.uic.uicpaymentapp.signature.SignatureViewModel
import com.uic.uicpaymentapp.transaction.FinishedPaymentViewModel
import com.uic.uicpaymentapp.transaction.TipViewModel
import com.uic.uicpaymentapp.transaction.BatchViewModel
import com.uic.uicpaymentapp.transaction.hotel.HotelCheckInReportViewModel
import com.uic.uicpaymentapp.transaction.hotel.HotelCheckInViewModel
import com.uic.uicpaymentapp.transaction.hotel.HotelCheckOutViewModel

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
 * [UICApplication].
 */
fun CreationExtras.uicApplication(): UICApplication =
    (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as UICApplication)
