package com.uic.uicpaymentapp.profile

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uic.uicpaymentapp.UICApplication
import com.uic.uicpaymentapp.uicpos.pos.model.SysParam
import com.uic.uicpaymentapp.uicpos.pos.repository.SystemParameterRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.uic.uicpaymentapp.R
import com.uic.uicpaymentapp.uicpos.pos.model.SignatureMode
import com.uic.uicpaymentapp.uicpos.pos.model.toTransactionMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow


class SystemSettingsViewModel(
    private val systemParameterRepository: SystemParameterRepository,
) : ViewModel() {

    private var _systemUiState = MutableStateFlow(SystemUiState())
    var systemUiState: StateFlow<SystemUiState> = _systemUiState

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                systemParameterRepository.getSysParam()
                    .collect { collectedSysParam ->
                        Log.d(
                            "SystemSettingsViewModel",
                            "Recieved sysparam of id ${collectedSysParam?.id}"
                        )
                        if (collectedSysParam == null) {
                            Log.d(
                                "SystemSettingsViewModel",
                                "No system parameters detected. Adding now..."
                            )
                            systemParameterRepository.addNew(SysParam())
                        } else {
                            Log.d(
                                "SystemSettingsViewModel",
                                "Assigning sys param of id ${collectedSysParam.id} to _sysparam"
                            )
                            _systemUiState.value = collectedSysParam.toSystemUiState()
                            Log.d(
                                "SystemSettingsViewModel",
                                "Sysparam value is  ${collectedSysParam.id}"
                            )
                            SysParam.updateInstance(collectedSysParam)
                        }
                    }
            }
        }
    }

    fun saveSystemSetting(
        systemSettingType: SystemSettingType,
        newValue: String
    ) {
        _systemUiState.value = when (systemSettingType) {
            SystemSettingType.CmdTout -> _systemUiState.value.copy(cmdTout = _systemUiState.value.cmdTout.copy(text = newValue))

            SystemSettingType.Password -> _systemUiState.value.copy(password = _systemUiState.value.password.copy(text = newValue))

            SystemSettingType.TransactionMode -> _systemUiState.value.copy(transactionMode = _systemUiState.value.transactionMode.copy(text = newValue))

            SystemSettingType.TipMethod -> _systemUiState.value.copy(tipMethod = _systemUiState.value.tipMethod.copy(text = newValue))

            SystemSettingType.SignatureMode -> _systemUiState.value.copy(signatureMode = _systemUiState.value.signatureMode.copy(text = newValue))

            SystemSettingType.Language -> _systemUiState.value.copy(language = _systemUiState.value.language.copy(text = newValue))

            SystemSettingType.BatchPrint -> _systemUiState.value.copy(batchPrint = _systemUiState.value.batchPrint.copy(text = newValue))

            SystemSettingType.DetailedBatchReport -> _systemUiState.value.copy(detailedBatchReport = _systemUiState.value.detailedBatchReport.copy(text = newValue))

            SystemSettingType.OptionsEnabled -> _systemUiState.value.copy(optionsEnabled = _systemUiState.value.optionsEnabled.copy(text = newValue))

            SystemSettingType.TipSetting1 ->  _systemUiState.value.copy(tipOption1 = _systemUiState.value.tipOption1.copy(text = newValue))

            SystemSettingType.TipSetting2 ->  _systemUiState.value.copy(tipOption2 = _systemUiState.value.tipOption2.copy(text = newValue))

            SystemSettingType.TipSetting3 ->  _systemUiState.value.copy(tipOption3 = _systemUiState.value.tipOption3.copy(text = newValue))

            SystemSettingType.TipSetting4 ->  _systemUiState.value.copy(tipOption4 = _systemUiState.value.tipOption4.copy(text = newValue))
        }
    }

    private val _initializeState = MutableStateFlow<InitializeState>(InitializeState.Idle)
    val initializeState: StateFlow<InitializeState> = _initializeState

    fun requestInitialize() {
        viewModelScope.launch {
            _initializeState.value = InitializeState.Loading
            val app = UICApplication.instance
            val count = try {
                app.container.transactionRepository
                    .getOpenAndNeedTipTransactionNumber()
                    .first()
            } catch (e: Exception) {
                Log.e("SystemSettingsViewModel", "requestInitialize: could not check transactions", e)
                _initializeState.value = InitializeState.Error(
                    app.getString(R.string.setting_initialize_err_generic)
                )
                return@launch
            }
            if (count > 0) {
                _initializeState.value = InitializeState.Error(
                    app.getString(R.string.setting_initialize_err_batch_not_empty, count)
                )
                return@launch
            }
            app.requestParamsFromUicHome()
            _initializeState.value = InitializeState.Requested
        }
    }

    fun writeToDb(): Boolean {
        val sysParam = _systemUiState.value.toSysParam()
        sysParam ?: return false
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (systemParameterRepository.getSysParamList().firstOrNull() == null) {
                    systemParameterRepository.addNew(sysParam)
                } else {
                    Log.d(
                        "SystemSettingsViewModel",
                        "Attempting to update: ${_systemUiState.value}"
                    )
                    sysParam.id = 1
                    systemParameterRepository.update(sysParam)
                    SysParam.updateInstance(sysParam)
                    Log.d(
                        "SystemSettingsViewModel",
                        "New sysparam instance: ${SysParam.getInstance()}"
                    )
                }
            }
        }
        return true
    }
}

data class SystemUiState(
    var cmdTout: SystemSetting = SystemSetting(
        label = UICApplication.instance.resources.getString(R.string.setting_command_timeout),
        type = SystemSettingType.CmdTout,
        maxLength = 3
    ),
    var password: SystemSetting = SystemSetting(
        label = UICApplication.instance.resources.getString(R.string.password),
        type = SystemSettingType.Password,
        maxLength = 6
    ),
    var transactionMode: SystemSetting = SystemSetting(
        label = UICApplication.instance.resources.getString(R.string.restaurant_mode),
        type = SystemSettingType.TransactionMode,
        maxLength = 5
    ),
    var tipMethod: SystemSetting = SystemSetting(
        label = UICApplication.instance.resources.getString(R.string.tip_methods),
        type = SystemSettingType.TipMethod,
        maxLength = 5
    ),
    var signatureMode: SystemSetting = SystemSetting(
        label = UICApplication.instance.resources.getString(R.string.setting_signature_required),
        type = SystemSettingType.SignatureMode,
        maxLength = 10
    ),
    var language: SystemSetting = SystemSetting(
        label = UICApplication.instance.resources.getString(R.string.language),
        type = SystemSettingType.Language,
        maxLength = 15
    ),
    var optionsEnabled: SystemSetting = SystemSetting(
        label = "Tip Options Enabled",
        type = SystemSettingType.OptionsEnabled,
        maxLength = 10
    ),
    var batchPrint: SystemSetting = SystemSetting(
        label = "Auto-print Batch Report",
        type = SystemSettingType.BatchPrint,
        maxLength = 10
    ),

    var detailedBatchReport: SystemSetting = SystemSetting(
        label = "Print Transactions on Report",
        type = SystemSettingType.DetailedBatchReport,
        maxLength = 10
    ),

    var tipOption1: SystemSetting = SystemSetting(
        label = UICApplication.instance.resources.getString(R.string.setting_tip_option_1),
        type = SystemSettingType.TipSetting1,
        maxLength = 3,
        text = "15"
    ),
    var tipOption2: SystemSetting = SystemSetting(
        label = UICApplication.instance.resources.getString(R.string.setting_tip_option_2),
        type = SystemSettingType.TipSetting2,
        maxLength = 3,
        text = "18"
    ),
    var tipOption3: SystemSetting = SystemSetting(
        label = UICApplication.instance.resources.getString(R.string.setting_tip_option_3),
        type = SystemSettingType.TipSetting3,
        maxLength = 3,
        text = "20"
    ),
    var tipOption4: SystemSetting = SystemSetting(
        label = UICApplication.instance.resources.getString(R.string.setting_tip_option_4),
        type = SystemSettingType.TipSetting4,
        maxLength = 3,
        text = "22"
    ),
)

data class SystemSetting(
    var text: String = "NaN",
    val label: String,
    val type: SystemSettingType,
    val maxLength: Int
)

fun SystemUiState.getSystemSetting(systemSettingType: SystemSettingType): String {
    return when (systemSettingType) {
        SystemSettingType.Language -> language.text
        SystemSettingType.CmdTout -> cmdTout.text
        SystemSettingType.Password -> password.text
        SystemSettingType.TransactionMode -> transactionMode.text
        SystemSettingType.TipMethod -> tipMethod.text
        SystemSettingType.SignatureMode -> signatureMode.text
        SystemSettingType.OptionsEnabled -> optionsEnabled.text
        SystemSettingType.BatchPrint -> batchPrint.text
        SystemSettingType.DetailedBatchReport -> detailedBatchReport.text
        SystemSettingType.TipSetting1 -> tipOption1.text
        SystemSettingType.TipSetting2 -> tipOption2.text
        SystemSettingType.TipSetting3 -> tipOption3.text
        SystemSettingType.TipSetting4 -> tipOption4.text
    }
}

fun SysParam.toSystemUiState(): SystemUiState {
    val systemUiState = SystemUiState()
    systemUiState.cmdTout.text = this.CmdTout
    systemUiState.password.text = this.EmployeePassword
    systemUiState.transactionMode.text = this.transactionMode.toString()
    systemUiState.tipMethod.text = this.TipMethod.toString()
    systemUiState.signatureMode.text = when (this.signatureMode) {
        SignatureMode.None -> false.toString()
        SignatureMode.OnScreen -> true.toString()
        SignatureMode.OnReceipt -> true.toString()
    }
    systemUiState.detailedBatchReport.text = DetailedBatchReport.toString()
    systemUiState.batchPrint.text = BatchPrint.toString()
    systemUiState.optionsEnabled.text = OptionsEnabled.toString()
    systemUiState.tipOption1.text = TipOption1
    systemUiState.tipOption2.text = TipOption2
    systemUiState.tipOption3.text = TipOption3
    systemUiState.tipOption4.text = TipOption4
    systemUiState.language.text = this.language.toString()
    return systemUiState
}

fun SystemUiState.toSysParam(): SysParam? {
    val sysParam = SysParam()
    if (validateInput(SystemSettingType.CmdTout, cmdTout.text)) {
        sysParam.CmdTout = cmdTout.text
    } else {
        return null
    }
    if (validateInput(SystemSettingType.Password, password.text)) {
        sysParam.EmployeePassword = password.text
    } else {
        return null
    }
    if (validateInput(SystemSettingType.TransactionMode, transactionMode.text)) {
        sysParam.transactionMode = transactionMode.text.toTransactionMode()
    } else {
        return null
    }
    if (validateInput(SystemSettingType.TipMethod, tipMethod.text)) {
        sysParam.TipMethod = tipMethod.text.toBoolean()
    } else {
        return null
    }
    sysParam.signatureMode = when (signatureMode.text.toBoolean()) {
        true -> SignatureMode.OnScreen
        false -> SignatureMode.None
    }
    sysParam.BatchPrint = batchPrint.text.toBoolean()

    sysParam.DetailedBatchReport = detailedBatchReport.text.toBoolean()

    sysParam.OptionsEnabled = optionsEnabled.text.toBoolean()

    if (validateInput(SystemSettingType.TipSetting1, tipOption1.text)) {
        sysParam.TipOption1 = tipOption1.text
    } else {
        return null
    }
    if (validateInput(SystemSettingType.TipSetting2, tipOption2.text)) {
        sysParam.TipOption2 = tipOption2.text
    } else {
        return null
    }
    if (validateInput(SystemSettingType.TipSetting3, tipOption3.text)) {
        sysParam.TipOption3 = tipOption3.text
    } else {
        return null
    }
    if (validateInput(SystemSettingType.TipSetting4, tipOption4.text)) {
        sysParam.TipOption4 = tipOption4.text
    } else {
        return null
    }
    sysParam.id = 1
    return sysParam
}

fun validateInput(systemSettingType: SystemSettingType, input: String): Boolean {
    when (systemSettingType) {
        SystemSettingType.CmdTout -> {
            return input.toIntOrNull() in 150..300
        }
        SystemSettingType.Password -> {
            return input.length in 3..10
        }
        SystemSettingType.TransactionMode -> {
            return true
        }
        SystemSettingType.TipMethod -> {
            return true
        }
        SystemSettingType.SignatureMode -> {
            return true
        }
        SystemSettingType.Language -> {
            // TODO: Implement language
            return false
        }
        SystemSettingType.OptionsEnabled -> {
            return true
        }
        SystemSettingType.BatchPrint -> {
            return true
        }
        SystemSettingType.DetailedBatchReport -> {
            return true
        }
        SystemSettingType.TipSetting1 -> {
            return input.toIntOrNull() in 0..100
        }
        SystemSettingType.TipSetting2 -> {
            return input.toIntOrNull() in 0..100
        }
        SystemSettingType.TipSetting3 -> {
            return input.toIntOrNull() in 0..100
        }
        SystemSettingType.TipSetting4 -> {
            return input.toIntOrNull() in 0..100
        }
    }
}

sealed class InitializeState {
    data object Idle      : InitializeState()
    data object Loading   : InitializeState()
    data object Requested : InitializeState()
    data class  Error(val message: String) : InitializeState()
}

enum class SystemSettingType {
    CmdTout,
    Password,
    TransactionMode,
    TipMethod,
    SignatureMode,
    BatchPrint,
    DetailedBatchReport,
    Language,
    OptionsEnabled,
    TipSetting1,
    TipSetting2,
    TipSetting3,
    TipSetting4;

    fun toUserLabel(): String {
        return when (this) {
            CmdTout -> UICApplication.instance.resources.getString(R.string.setting_command_timeout)
            Password -> UICApplication.instance.resources.getString(R.string.password)
            TransactionMode -> UICApplication.instance.resources.getString(R.string.setting_device_mode)
            TipMethod -> UICApplication.instance.resources.getString(R.string.tip_methods)
            SignatureMode -> UICApplication.instance.resources.getString(R.string.setting_signature_required)
            Language -> UICApplication.instance.resources.getString(R.string.language)
            BatchPrint -> "Auto-print Batch Report"
            DetailedBatchReport -> "Print Transactions on Report"
            OptionsEnabled -> "Tip Options Enabled"
            TipSetting1 -> UICApplication.instance.resources.getString(R.string.setting_tip_option_1)
            TipSetting2 -> UICApplication.instance.resources.getString(R.string.setting_tip_option_2)
            TipSetting3 -> UICApplication.instance.resources.getString(R.string.setting_tip_option_3)
            TipSetting4 -> UICApplication.instance.resources.getString(R.string.setting_tip_option_4)
        }
    }

    fun getErrorString(): String {
        return when (this) {
            CmdTout -> UICApplication.instance.resources.getString(R.string.setting_connection_30_to_120_input_err)
            Password -> UICApplication.instance.resources.getString(R.string.setting_password_input_err)
            TransactionMode -> UICApplication.instance.resources.getString(R.string.setting_input_err)
            TipMethod -> UICApplication.instance.resources.getString(R.string.setting_input_err)
            SignatureMode -> UICApplication.instance.resources.getString(R.string.setting_input_err)
            Language -> UICApplication.instance.resources.getString(R.string.setting_language_input_err)
            TipSetting1 -> UICApplication.instance.resources.getString(R.string.setting_tip_input_err)
            TipSetting2 -> UICApplication.instance.resources.getString(R.string.setting_tip_input_err)
            TipSetting3 -> UICApplication.instance.resources.getString(R.string.setting_tip_input_err)
            TipSetting4 -> UICApplication.instance.resources.getString(R.string.setting_tip_input_err)
            OptionsEnabled -> UICApplication.instance.resources.getString(R.string.setting_input_err)
            BatchPrint -> UICApplication.instance.resources.getString(R.string.setting_input_err)
            DetailedBatchReport -> UICApplication.instance.resources.getString(R.string.setting_input_err)
        }
    }
}





