package one.globalconnect.paymentapp.uicpos.pos.model

data class RespError (
    var CurrState: String? = "",
    var ErrCode: String? = "",
    var ErrText: String? = ""
)