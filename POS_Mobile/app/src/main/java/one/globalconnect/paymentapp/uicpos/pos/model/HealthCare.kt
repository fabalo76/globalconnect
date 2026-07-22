package one.globalconnect.paymentapp.uicpos.pos.model

data class HealthCare(
    var AccType: String         = "",
    var PrescriptionAmt: String = "",
    var VisionAmt: String       = "",
    var DentalAmt: String       = "",
    var ClinicAmt: String       = "",
    var QualifiedIIAS: String   = "",
    var CustomerId: String      = ""
)