package com.uic.uicpaymentapp.uicpos.pos.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.uic.uicpaymentapp.R
import com.uic.uicpaymentapp.UICApplication


@Entity(tableName = "SysParam")
data class SysParam(
    val SourceMAC: String          = "",

    val DestMAC:   String          = "",

    @ColumnInfo(name="CurrCode")
    var CurrCode:  String          = "840",

    @ColumnInfo(name="CmdTout")
    var CmdTout:   String          = "150",

    @ColumnInfo(name="CurrencyName")
    var CurrencyName: String       = "$",

    @ColumnInfo(name="DeciPosAmt")
    var DeciPosAmt: Int            = 2,

    @ColumnInfo(name="EmployeePassword")
    var EmployeePassword: String = "123456",

    @ColumnInfo(name="AdminPassword")
    var AdminPassword: String = "123456",

    /** On-screen tipping = true, on-receipt tipping = false */

    @ColumnInfo(name="TipMethod")
    var TipMethod: Boolean = true,

    @ColumnInfo(name="TransactionMode")
    var transactionMode: TransactionMode = TransactionMode.Retail,

    @ColumnInfo(name="SignatureMode")
    var signatureMode: SignatureMode = SignatureMode.OnScreen,

    @ColumnInfo(name="SurchargePercent")
    var surchargePercent: String = "",

    @ColumnInfo(name="Language")
    var language: Language = Language.AndroidDefault,

    @ColumnInfo(name="MerchantId")
    var MerchantId: String = "",

    @ColumnInfo(name="DeviceId")
    var DeviceId: String = "",

    @ColumnInfo(name="TerminalId")
    var TerminalId: String = "",

    @ColumnInfo(name="BatchPrint")
    var BatchPrint: Boolean = true,

    @ColumnInfo(name="DetailedBatchReport")
    var DetailedBatchReport: Boolean = false,

    // Tipping
    @ColumnInfo(name="OptionsEnabled")
    var OptionsEnabled: Boolean = true,

    @ColumnInfo(name="TipOption1")
    var TipOption1: String = "15",

    @ColumnInfo(name="TipOption2")
    var TipOption2: String = "18",

    @ColumnInfo(name="TipOption3")
    var TipOption3: String = "20",

    @ColumnInfo(name="TipOption4")
    var TipOption4: String = "22",
) {

    @PrimaryKey(autoGenerate = true) var id:Int = 0

    companion object {
        @Volatile
        @JvmStatic
        private var INSTANCE: SysParam? = null

        @JvmStatic
        fun getInstance(): SysParam = INSTANCE ?: synchronized(this) {
            INSTANCE ?: SysParam().also { INSTANCE = it }
        }

        fun updateInstance(sysParam: SysParam) {
            INSTANCE = sysParam
        }

    }
}


enum class Language {
    AndroidDefault,
    English,
    Spanish,
    Chinese,
    Korean,
    Thai,
    Vietnamese,
    Tagalog
}

enum class TransactionMode {
    Restaurant,
    Cafe,
    Retail;
    override fun toString(): String {
        return when (this) {
            Restaurant -> UICApplication.instance.resources.getString(R.string.restaurant_mode)
            Retail -> UICApplication.instance.resources.getString(R.string.retail_mode)
            Cafe -> UICApplication.instance.resources.getString(R.string.cafe_mode)
        }
    }
}

fun String.toTransactionMode(): TransactionMode {
    return when (this) {
        TransactionMode.Restaurant.toString() -> TransactionMode.Restaurant
        TransactionMode.Cafe.toString() -> TransactionMode.Cafe
        TransactionMode.Retail.toString() -> TransactionMode.Retail
        else -> TransactionMode.Retail
    }
}

enum class SignatureMode {
    OnScreen,
    OnReceipt,
    None;

    override fun toString(): String {
        return when (this) {
            OnScreen -> UICApplication.instance.resources.getString(R.string.signature_mode_on_screen)
            OnReceipt -> UICApplication.instance.resources.getString(R.string.signature_mode_on_receipt)
            None -> UICApplication.instance.resources.getString(R.string.signature_mode_none)
        }
    }
}

fun String.toSignatureMode(): SignatureMode {
    return when (this) {
        SignatureMode.OnScreen.toString() -> SignatureMode.OnScreen
        SignatureMode.OnReceipt.toString() -> SignatureMode.OnReceipt
        SignatureMode.None.toString() -> SignatureMode.None
        else -> SignatureMode.None
    }
}



