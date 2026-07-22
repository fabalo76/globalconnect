package one.globalconnect.paymentapp.uicpos.pos.util

import org.xmlpull.v1.XmlSerializer
import java.io.StringWriter

fun XmlSerializer.document(docName: String = "UTF-8",
                           xmlStringWriter: StringWriter = StringWriter(),
                           init: XmlSerializer.() -> Unit): String {
    startDocument(docName, true)
    xmlStringWriter.buffer.setLength(0) //  refreshing string writer due to reuse
    setOutput(xmlStringWriter)
    init()
    endDocument()
    return xmlStringWriter.toString()
}

//  element
fun XmlSerializer.element(name: String, init: XmlSerializer.() -> Unit) {
    startTag("", name)
    init()
    endTag("", name)
}

//  element with attribute & content
fun XmlSerializer.element(name: String,
                          content: String,
                          init: XmlSerializer.() -> Unit) {
    startTag("", name)
    init()
    text(content)
    endTag("", name)
}

//  element with content
fun XmlSerializer.element(name: String, content: String) =
    element(name) {
        text(content)
    }

fun XmlSerializer.attribute(name: String, value: String) =
    attribute("", name, value)
