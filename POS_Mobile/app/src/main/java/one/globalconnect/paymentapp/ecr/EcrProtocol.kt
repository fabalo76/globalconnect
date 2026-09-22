package one.globalconnect.paymentapp.ecr

import java.io.InputStream
import java.io.EOFException
import java.math.BigDecimal

/** A10 LATAM/UPE payload: ASCII length, binary version/indicator, tagged ASCII fields. */
data class EcrMessage(val command: String, val response: String = "00", val indicator: Int = 0,
    val fields: Map<String, String> = emptyMap(), val more: Boolean = false) {
    fun encode(): ByteArray {
        require(command.length == 2 && response.length == 2)
        val out = java.io.ByteArrayOutputStream()
        out.write(1); out.write(indicator)
        out.write((command + response + if (more) "1" else "0").toByteArray(Charsets.US_ASCII)); out.write(28)
        fields.forEach { (tag, value) ->
            require(tag.length == 2 && value.length <= 999 && value.all { it.code in 32..126 })
            out.write((tag + value.length.toString().padStart(3, '0') + value).toByteArray(Charsets.US_ASCII)); out.write(28)
        }
        val body = out.toByteArray()
        require(body.size <= 9999)
        return body.size.toString().padStart(4, '0').toByteArray(Charsets.US_ASCII) + body
    }
    companion object {
        fun decode(data: ByteArray): EcrMessage {
            require(data.size in 12..10003)
            fun text(a: Int, b: Int) = String(data.copyOfRange(a, b), Charsets.US_ASCII)
            require(text(0,4).all(Char::isDigit) && text(0,4).toInt() == data.size - 4)
            require(data[4].toInt() in listOf(1,49) && data[11].toInt() == 28 && data[10].toInt() in 48..49)
            val indicator = data[5].toInt().let { if (it in 48..50) it - 48 else it }
            require(indicator in 0..2)
            val fields = linkedMapOf<String,String>()
            var pos = 12
            while (pos < data.size) {
                require(pos + 5 <= data.size)
                val tag = text(pos, pos+2)
                val lenText = text(pos+2,pos+5)
                require(lenText.all(Char::isDigit))
                val len = lenText.toInt(); pos += 5
                require(pos + len < data.size && data[pos+len].toInt() == 28)
                val value = text(pos,pos+len)
                require(value.all { it.code in 32..126 } && fields.put(tag,value) == null)
                pos += len+1
            }
            return EcrMessage(text(6,8),text(8,10),indicator,fields,data[10].toInt() == 49)
        }
        fun frame(payload: ByteArray): ByteArray = byteArrayOf(2) + payload + byteArrayOf(3, lrc(payload))
        fun lrc(payload: ByteArray): Byte = payload.fold(3) { v,b -> v xor (b.toInt() and 255) }.toByte()
        /** Length-aware framing: binary header bytes may themselves equal STX/ETX. */
        fun readPayload(input: InputStream): ByteArray {
            fun read(count: Int): ByteArray {
                val bytes = ByteArray(count); var offset = 0
                while (offset < count) { val n = input.read(bytes, offset, count-offset); if (n < 0) throw EOFException(); offset += n }
                return bytes
            }
            val length = read(4)
            require(length.all { it.toInt() in 48..57 })
            val count = String(length, Charsets.US_ASCII).toInt()
            require(count in 8..9999)
            val payload = length + read(count)
            val suffix = read(2)
            require(suffix[0].toInt() == 3 && suffix[1] == lrc(payload))
            return payload
        }
    }
}

data class EcrSale(val id: String, val base: String, val tax1: String, val tax2: String,
    val currency: String?, val printReceipt: Boolean, val message: EcrMessage) {
    val transactionType: one.globalconnect.paymentapp.transaction.TransactionType
        get() = when (message.command) {
            "31" -> one.globalconnect.paymentapp.transaction.TransactionType.LOYALTY_SALE
            "33" -> one.globalconnect.paymentapp.transaction.TransactionType.LOYALTY_BALANCE
            else -> one.globalconnect.paymentapp.transaction.TransactionType.SALE
        }

    companion object {
        fun from(message: EcrMessage): EcrSale {
            require(message.command in setOf("20", "31", "33") && message.indicator == 0 && message.response == "00" && !message.more)
            require(message.fields.keys.all { it in setOf("RQ","80","40","44","45","49","P1") })
            message.validateRequestId()
            val id = message.fields["80"].orEmpty()
            require(id.isNotBlank() && id.length <= 64)
            fun amount(tag: String, required: Boolean = false): String {
                val value = message.fields[tag] ?: if (required) error("Missing amount") else "000000000000"
                require(value.length == 12 && value.all(Char::isDigit))
                return BigDecimal(value).movePointLeft(2).toPlainString()
            }
            val balance = message.command == "33"
            val base = amount("40", !balance)
            if (balance) {
                require(listOf(base, amount("44"), amount("45")).all { BigDecimal(it).signum() == 0 })
            } else require(BigDecimal(base) > BigDecimal.ZERO)
            val currency = message.fields["49"]
            require(currency == null || (currency.length == 3 && currency.all(Char::isDigit)))
            require(message.fields["P1"] in listOf(null,"0","1"))
            return EcrSale(id,base,amount("44"),amount("45"),currency,message.fields["P1"] == "1",message)
        }
    }
}
