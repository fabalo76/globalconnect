package one.globalconnect.paymentapp.admin

data class AdminTicketData(
    val messageText: String,
    val ticketId: String,
    val ticketTitle: String,
    val requestType: String,
    val deviceSerial: String,
    val terminalId: String,
    val laneId: String,
    val createdAt: String,
)
