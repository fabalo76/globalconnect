package one.globalconnect.paymentapp.signature

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.dao.TransactionRepository
import one.globalconnect.paymentapp.navigation.TRANSACTION_ID_KEY
import one.globalconnect.paymentapp.records.dateFormatter
import one.globalconnect.paymentapp.transaction.CheckStatus
import one.globalconnect.paymentapp.transaction.Transaction
import one.globalconnect.paymentapp.transaction.TransactionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDate
import java.util.UUID

class SignatureViewModel(
    savedStateHandle: SavedStateHandle,
    private val signatureRepository: SignatureRepository,
    private val transactionRepository: TransactionRepository,
): ViewModel() {

    val transactionId: String = checkNotNull(savedStateHandle[TRANSACTION_ID_KEY])
    val transaction = MutableLiveData(Transaction())
    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                transaction.postValue(transactionRepository.getTransactionFromId(transactionId.toInt()))
            }
        }
    }
    fun saveSignature(bitmap: Bitmap) {
        val signatureUUID = UUID.randomUUID()
        val file = File(GlobalConnectPaymentApplication.instance.filesDir, signatureUUID.toString())
        try {
            val fos = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    signatureRepository.insert(Signature(
                        signatureUUID = signatureUUID.toString(),
                        date = LocalDate.now().format(dateFormatter),
                        transactionId = transactionId))

                }
            }
        } catch (e: IOException) {
            Log.d("SignatureViewModel", "Failed to save signature")
            e.printStackTrace()
        }
    }

    fun skipSignature() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                transaction.value?.let {
                    if (it.tipAmount == "0.00" && it.type == TransactionType.AUTHONLY) {
                        transactionRepository.update(it.copy(checkStatus = CheckStatus.NeedTip, tipAmount = "0.00", totalAmount = it.subTotal))
                    }
                }
            }
        }
    }
}
