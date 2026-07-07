package com.uic.uicpaymentapp.records

import java.time.format.DateTimeFormatter


/*data class TransactionsHistory(
    var transactions: List<Transaction> = listOf()
)
*/
/*
data class SummaryUiState(
    var sales: Int = 0,
    var saleTotal: String = "$0.00",
    var refunds: Int = 0,
    var refundTotal: String = "$0.00",
    var voids: Int = 0,
    var voidTotal: String = "$0.00",
    var capturedAuths: Int = 0,
    var capturedAuthTotal: String = "$0.00",
    var needTipAuths: Int = 0,
    var needTipAuthTotal: String = "$0.00",
    var openAuths: Int = 0,
    var openAuthTotal: String = "$0.00",
    var creditRecords: Int = 0,
    var creditTotal: String = "$0.00",
    var authTotal: String = "$0.00",
    var tipTotal: String = "$0.00",
    var openTipTotal: String = "$0.00"
)
*/

val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
val dateTimeFormatterForUsers:  DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd-yyyy HH:mm")
val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
//val truncatedDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd-yy")
val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
val monthDayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")
val verboseMonthDayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM d")
val verboseDateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM d, h:mm a")
//val monthDayTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d h:mm a")