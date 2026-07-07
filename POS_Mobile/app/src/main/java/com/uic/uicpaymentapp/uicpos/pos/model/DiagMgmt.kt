package com.uic.uicpaymentapp.uicpos.pos.model

data class DiagMgmt(
    // request
    var Id: String?         = "",

    // response
    var IPAddr: String?     = "",
    var MACAddr: String?    = "",
    var SerialNbr: String?  = "",
    var TestResult: String? = ""
)
