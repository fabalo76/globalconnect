package one.globalconnect.paymentapp.signature

import androidx.room.Entity
import androidx.room.PrimaryKey
import one.globalconnect.paymentapp.records.dateFormatter
import java.time.LocalDate

@Entity
data class Signature(
    @PrimaryKey(autoGenerate = true) var id: Int = 0,
    val signatureUUID: String = "",
    val date: String = LocalDate.MIN.format(dateFormatter),
    val transactionId: String = ""
)