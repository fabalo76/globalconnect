package one.globalconnect.paymentapp.uicpos.pos.model

data class DiagMgmt(
    // request
    var Id: String?         = "",

    // response
    var IPAddr: String?     = "",
    var MACAddr: String?    = "",
    var SerialNbr: String?  = "",
    var TestResult: String? = ""
)
