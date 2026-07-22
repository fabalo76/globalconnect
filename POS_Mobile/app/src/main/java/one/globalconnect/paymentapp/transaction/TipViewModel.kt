package one.globalconnect.paymentapp.transaction

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import one.globalconnect.paymentapp.dao.TransactionRepository
import one.globalconnect.paymentapp.navigation.AMOUNT_KEY
import one.globalconnect.paymentapp.navigation.TRANSACTION_ID_KEY
import one.globalconnect.paymentapp.navigation.TRANSACTION_TYPE_KEY
import one.globalconnect.paymentapp.uicpos.pos.model.SysParam
import one.globalconnect.paymentapp.uicpos.pos.model.TransactionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode

const val ONRECEIPT = false
const val ONSCREEN = true


class TipViewModel(
    savedStateHandle: SavedStateHandle,
    private val transactionRepository: TransactionRepository,
) : ViewModel() {
    val sysParam = SysParam.getInstance()
    val tipArray = doubleArrayOf(
        sysParam.TipOption1.toDouble().div(100),
        sysParam.TipOption2.toDouble().div(100),
        sysParam.TipOption3.toDouble().div(100),
        sysParam.TipOption4.toDouble().div(100)
    )

    val transaction: MutableLiveData<Transaction> by lazy {
        MutableLiveData<Transaction>(Transaction())
    }

    val subtotal: MutableLiveData<String> by lazy {
        MutableLiveData<String>("0.00")
    }

    val tip: MutableLiveData<String> by lazy {
        MutableLiveData<String>("0.00")
    }

    val subtotalPlusTip: MutableLiveData<String> by lazy {
        MutableLiveData<String>("0.00")
    }

    private val transactionId: String = savedStateHandle[TRANSACTION_ID_KEY] ?: ""
    private val amount: String = savedStateHandle[AMOUNT_KEY] ?: "0.00"
    private val transactionType: String = savedStateHandle[TRANSACTION_TYPE_KEY] ?: TransactionType.ERROR.toTransactionString()


    // Immediately calculate a 20% tip for the requested transaction for UI initialization
    init {
        if (sysParam.transactionMode == TransactionMode.Cafe) {
            subtotal.value = amount
            transaction.value =
                Transaction(type = transactionType.transactionStringToTransactionType(),
                    totalAmount = amount, subTotal = amount)
            subtotalPlusTip.value = amount
        } else {
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    val retrievedTransaction: Transaction =
                        transactionRepository.getTransactionFromId(transactionId.toInt())
                            ?: return@withContext
                    val subtotalSnapshot = BigDecimal(
                        retrievedTransaction.totalAmount
                    )
                        .minus(BigDecimal(retrievedTransaction.tipAmount))
                        .setScale(2, RoundingMode.HALF_DOWN)
                    val tipSnapshot = retrievedTransaction.tipAmount
                    val subtotalPlusTipSnapshot = retrievedTransaction.totalAmount
                    transaction.postValue(retrievedTransaction)
                    subtotal.postValue(subtotalSnapshot.toPlainString())
                    tip.postValue(tipSnapshot)
                    subtotalPlusTip.postValue(subtotalPlusTipSnapshot)

                }
            }
        }
    }

    fun updateTip(newValue: String) {
        val transactionSnapshot = transaction.value ?: return
        val formattedTip = BigDecimal(newValue).setScale(2, RoundingMode.HALF_DOWN)
        val totalByTipSnapshot = BigDecimal(transactionSnapshot.totalAmount).plus(formattedTip)
            .setScale(2, RoundingMode.HALF_DOWN).toPlainString()
        tip.postValue(formattedTip.toPlainString())
        subtotalPlusTip.postValue(
            BigDecimal(transactionSnapshot.totalAmount).plus(formattedTip)
                .setScale(2, RoundingMode.HALF_DOWN).toPlainString()
        )
        if (transactionId != "") {
            viewModelScope.launch {
                transactionRepository.update(
                    transactionSnapshot.copy(
                        totalAmount = totalByTipSnapshot,
                        tipAmount = formattedTip.toPlainString(),
                        checkStatus = CheckStatus.Open
                    )
                )
            }
        }
    }
}

