package one.globalconnect.paymentapp.dao

import android.content.Context
import one.globalconnect.tms.paymentapp.TMSDATA
import one.globalconnect.paymentapp.profile.profiledao.OnDeviceProfileRepository
import one.globalconnect.paymentapp.profile.profiledao.ProfileDatabase
import one.globalconnect.paymentapp.profile.profiledao.ProfileRepository
import one.globalconnect.paymentapp.settlement.storage.OnDeviceSettlementStateRepository
import one.globalconnect.paymentapp.settlement.storage.SettlementStateRepository
import one.globalconnect.paymentapp.signature.OnDeviceSignatureRepository
import one.globalconnect.paymentapp.signature.SignatureDatabase
import one.globalconnect.paymentapp.signature.SignatureRepository
import one.globalconnect.paymentapp.systeminfo.InfoMgmtDatabase
import one.globalconnect.paymentapp.systeminfo.InfoMgmtRepository
import one.globalconnect.paymentapp.systeminfo.onDeviceInfoMgmtRepository
import one.globalconnect.paymentapp.uicpos.pos.repository.OnDeviceSysParameterRepository
import one.globalconnect.paymentapp.uicpos.pos.repository.SystemParameterDatabase
import one.globalconnect.paymentapp.uicpos.pos.repository.SystemParameterRepository

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
    override val tmsDatabase: TMSDATA, // Receive from GlobalConnectPaymentApplication
    override val appVersion: String // Receive from GlobalConnectPaymentApplication
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
