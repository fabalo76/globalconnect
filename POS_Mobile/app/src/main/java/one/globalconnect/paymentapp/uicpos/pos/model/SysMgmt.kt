package one.globalconnect.paymentapp.uicpos.pos.model

data class SysMgmt(
    // request
    var Id: String?             = "",
    var AlarmCount: String?     = "",
    var AlarmDuration: String?  = "",
    var AlarmInterval: String?  = "",

    // response
    var SysLog: String?         = ""
)
