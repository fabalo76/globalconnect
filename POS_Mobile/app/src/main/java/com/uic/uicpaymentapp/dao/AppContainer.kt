package com.uic.uicpaymentapp.dao

import android.content.Context
import com.uic.tms.payment_app.TMSDATA
import com.uic.uicpaymentapp.profile.profiledao.OnDeviceProfileRepository
import com.uic.uicpaymentapp.profile.profiledao.ProfileDatabase
import com.uic.uicpaymentapp.profile.profiledao.ProfileRepository
import com.uic.uicpaymentapp.settlement.storage.OnDeviceSettlementStateRepository
import com.uic.uicpaymentapp.settlement.storage.SettlementStateRepository
import com.uic.uicpaymentapp.signature.OnDeviceSignatureRepository
import com.uic.uicpaymentapp.signature.SignatureDatabase
import com.uic.uicpaymentapp.signature.SignatureRepository
import com.uic.uicpaymentapp.systeminfo.InfoMgmtDatabase
import com.uic.uicpaymentapp.systeminfo.InfoMgmtRepository
import com.uic.uicpaymentapp.systeminfo.onDeviceInfoMgmtRepository
import com.uic.uicpaymentapp.uicpos.pos.repository.OnDeviceSysParameterRepository
import com.uic.uicpaymentapp.uicpos.pos.repository.SystemParameterDatabase
import com.uic.uicpaymentapp.uicpos.pos.repository.SystemParameterRepository

/**
 * App container for Dependency injection.
 */
interface AppContainer {
    val transactionRepository: TransactionRepository
    val systemParametersRepository: SystemParameterRepository
    val profileRepository: ProfileRepository
    val infoMgmtRepository: InfoMgmtRepository
    val signatureRepository: SignatureRepository
    val settlementStateRepository: SettlementStateRepository
    val tmsDatabase: TMSDATA // To store TMS Config
    val appVersion : String //To Store the APP Version
}

/**
 * [AppContainer] implementation that provides repositories and holds the TMS database instance.
 */
class AppDataContainer(
    private val context: Context,
    override val tmsDatabase: TMSDATA, // Receive from UICApplication
    override val appVersion: String // Receive from UICApplication
) : AppContainer {

    private val transactionDatabase: TransactionDatabase by lazy {
        TransactionDatabase.getDatabase(context)
    }

    override val transactionRepository: TransactionRepository by lazy {
        OnDeviceTransactionRepository(transactionDatabase.transactionDao())
    }

    override val systemParametersRepository: SystemParameterRepository by lazy {
        OnDeviceSysParameterRepository(SystemParameterDatabase.getDatabase(context).systemParameterDao())
    }

    override val profileRepository: ProfileRepository by lazy {
        OnDeviceProfileRepository(ProfileDatabase.getDatabase(context).profileDao())
    }

    override val infoMgmtRepository: InfoMgmtRepository by lazy {
        onDeviceInfoMgmtRepository(InfoMgmtDatabase.getDatabase(context).infoMgmtDao())
    }

    override val signatureRepository: SignatureRepository by lazy {
        OnDeviceSignatureRepository(SignatureDatabase.getDatabase(context).signatureDao())
    }

    override val settlementStateRepository: SettlementStateRepository by lazy {
        OnDeviceSettlementStateRepository(transactionDatabase.settlementStateDao())
    }
}
