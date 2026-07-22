package one.globalconnect.paymentapp.profile

import android.net.Uri
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import one.globalconnect.paymentapp.navigation.DESTINATION_KEY
import one.globalconnect.paymentapp.uicpos.pos.model.SysParam

class PasswordViewModel(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val userInput: MutableLiveData<String> by lazy {
        MutableLiveData<String>("")
    }

    val obscuredInput: MutableLiveData<String> by lazy {
        MutableLiveData<String>("")
    }

    val destination: String = Uri.decode(checkNotNull(savedStateHandle[DESTINATION_KEY]))

    fun checkPassword(): Boolean {
        val input = userInput.value
        if (input.isNullOrEmpty()) {
            return false
        }
        val sysParam = SysParam.getInstance()
        return input == sysParam.EmployeePassword || input == sysParam.AdminPassword
    }

    fun clearInputs() {
        userInput.value = ""
        obscuredInput.value = ""
    }

    fun inputDigit(newCharacter: String) {
        val digit = newCharacter.toIntOrNull() ?: return
        userInput.value = userInput.value.plus(digit)
        userInput.value?.let {
            val userInputSnapshot = it
            obscuredInput.value =
                "*".repeat(userInputSnapshot.length - 1) + userInputSnapshot.last()
        }
    }

    fun deleteDigit() {
        var userInputSnapshot = userInput.value ?: ""
        if (userInputSnapshot.isBlank()) {
            return
        } else {
            userInputSnapshot = userInputSnapshot.dropLast(1)
            userInput.value = userInputSnapshot
            if (userInputSnapshot.isEmpty()) {
                obscuredInput.value = userInput.value
            } else {
                obscuredInput.value =
                    "*".repeat(userInputSnapshot.length - 1) + userInputSnapshot.last()
            }
        }
    }
}
