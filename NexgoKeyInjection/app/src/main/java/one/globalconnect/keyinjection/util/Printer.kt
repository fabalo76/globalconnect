package one.globalconnect.keyinjection.util
import android.graphics.Typeface
import android.util.Log
import com.nexgo.oaf.apiv3.SdkResult
import com.nexgo.oaf.apiv3.device.printer.AlignEnum
import com.nexgo.oaf.apiv3.device.printer.GrayLevelEnum
import com.nexgo.oaf.apiv3.device.printer.LineOptionEntity
import com.nexgo.oaf.apiv3.device.printer.OnPrintListener
import com.nexgo.oaf.apiv3.device.printer.Printer
import one.globalconnect.keyinjection.App
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Available font sizes exposed by the printer. The value represents the
 * configured text size in scale-independent pixels.
 */
enum class FontSize(val sp: Int) {
    MIN(18),
    TINY(20),
    SMALL(24),
    MEDIUM(28),
    LARGE(30),
    MASSIVE(34);

    companion object {
        /**
         * Find the exact [FontSize] that matches [value], if any.
         *
         * @param value font size in scale-independent pixels
         * @return matching enum or `null` when no exact value exists
         */
        fun fromSp(value: Int): FontSize? = entries.find { it.sp == value }

        /**
         * Find the [FontSize] whose value is closest to [value].
         *
         * @param value font size in scale-independent pixels
         * @return nearest enum value
         */
        fun nearestTo(value: Int): FontSize =
            entries.minBy { kotlin.math.abs(it.sp - value) }
    }
}

/**
 * Simple wrapper around the Nexgo printer APIs. It provides an easy way to
 * print lines of text and handles common setup/teardown boilerplate.
 */
object Printer {
    private val tag = "Printer"

    /** Lazily obtain the printer instance. */
    private var printer: Printer? = null
    /** Track whether the device has been initialized. */
    private var initialized = false

    private val dottedSpacer = "-".repeat(22)

    // Constants defining initial character limits per font type and size
    private const val CHARSPERLINE_MINFONTSIZE = 42
    private const val CHARSPERLINE_TINYFONTSIZE = 38
    private const val CHARSPERLINE_SMALLFONTSIZE = 32
    private const val CHARSPERLINE_MEDIUMFONTSIZE = 27
    private const val CHARSPERLINE_LARGEFONTSIZE = 25
    private const val CHARSPERLINE_MASSIVEFONTSIZE = 22


    /**
     * Initialise the printer hardware once.
     */
    fun init() {
        try {
            Logger.d(tag, "Initializing Printer")
            printer = App.deviceEngine.printer
            val ret = printer!!.initPrinter()
            if (ret == SdkResult.Success) {
                initialized = true
                printer!!.setGray(GrayLevelEnum.LEVEL_3)
                Logger.d(tag, "Printer Initialized Successfully")
            } else {
                Logger.e(tag, "Init failed Error: [$ret]")
            }
        } catch (e: Exception) {
            Logger.e(tag, "init failed", e)
        }
    }



    /**
     * Return the number of characters that fit on a line for the given [size].
     *
     * @param size font size used for the line
     * @return maximum number of characters that fit on a line
     */
    private fun getLineWidth(size: FontSize): Int {
        return when (size) {
            FontSize.MIN -> CHARSPERLINE_MINFONTSIZE
            FontSize.TINY -> CHARSPERLINE_TINYFONTSIZE
            FontSize.SMALL -> CHARSPERLINE_SMALLFONTSIZE
            FontSize.MEDIUM -> CHARSPERLINE_MEDIUMFONTSIZE
            FontSize.LARGE -> CHARSPERLINE_LARGEFONTSIZE
            FontSize.MASSIVE -> CHARSPERLINE_MASSIVEFONTSIZE
        }
    }
    /**
     * Print each entry from [lines] on its own line. Any printer errors are
     * logged. The actual print job must be started separately via [start].
     */
    fun printLines(lines: List<String>, fontSize: FontSize = FontSize.SMALL) {
        try {
            lines.forEach {
                Logger.d(tag, "Printing: $it Font: $fontSize")
                printer!!.appendPrnStr("$it\n", fontSize.sp, AlignEnum.LEFT, false)
            }
        } catch (e: Exception) {
            Logger.e(tag, "printLines failed", e)
        }
    }

    /**
     * Print a single [line] of text using the provided [fontSize].
     */
    fun printLine(line: String, fontSize: FontSize = FontSize.SMALL) =
        printLines(listOf(line), fontSize)

    /**
     * Print [text] centered on the line for the given [fontSize]. If the text
     * exceeds the line width it is printed as-is.
     */
    fun printCentered(text: String, fontSize: FontSize = FontSize.SMALL) {
        printer!!.appendPrnStr(text, fontSize.sp, AlignEnum.CENTER, false)
    }

    /**
     * Print [text] right aligned within the line for the given [fontSize].
     */
    fun printRight(text: String, fontSize: FontSize = FontSize.SMALL) {
        printer!!.appendPrnStr(text, fontSize.sp, AlignEnum.RIGHT, false)
    }

    /**
     * Print [text] with [centerChar] used to split left and right portions that
     * are then justified to the line width for [fontSize]. If [centerChar] is
     * not present the text is printed as-is.
     */
    fun printJustified(
        text: String,
        centerChar: Char = '|',
        fontSize: FontSize = FontSize.SMALL
    ) {
        val width = getLineWidth(fontSize)
        val parts = text.split(centerChar, limit = 2)
        val line = if (parts.size == 2) {
            val left = parts[0]
            val right = parts[1]
            val spaces = width - left.length - right.length
            if (spaces > 0) left + " ".repeat(spaces) + right else left + right
        } else {
            text
        }
        printLine(line, fontSize)
    }

    /**
     * Start printing any buffered commands.
     *
     * @return human readable status description from the printer.
     */
    suspend fun start(): String = suspendCancellableCoroutine { cont ->
        // fallback if printer is null
        val p = printer
        if (p == null) {
            cont.resume(statusCodeToString(SdkResult.Printer_Print_Fail))
            return@suspendCancellableCoroutine
        }

        val listener = OnPrintListener { result ->
            // translate and return once we have the result
            val msg = statusCodeToString(result)
            if (cont.isActive) cont.resume(msg)
        }

        try {
            p.startPrint(true, listener)
        } catch (t: Throwable) {
            if (cont.isActive) cont.resume(statusCodeToString(SdkResult.Printer_Print_Fail))
        }
    }
    /**
     * Convert a [Typeface] instance into a descriptive string for logging.
     */
    private fun typefaceToString(typeface: Typeface): String {
        return when (typeface) {
            Typeface.DEFAULT -> "Default"
            Typeface.DEFAULT_BOLD -> "Default Bold"
            Typeface.MONOSPACE -> "Monospace"
            Typeface.SERIF -> "Serif"
            Typeface.SANS_SERIF -> "Sans-Serif"
            else -> "Unknown"
        }
    }

    /**
     * Print a test line for each [FontSize] using a 40 character sequence so
     * the maximum line width can be determined visually.
     */
    fun testLineWidth() {
        val grayLevel = GrayLevelEnum.LEVEL_0
        try {
            init()
            val fonts = listOf(
                Typeface.DEFAULT to listOf(
                    FontSize.MIN,
                    FontSize.TINY,
                    FontSize.SMALL,
                    FontSize.MEDIUM,
                    FontSize.LARGE,
                    FontSize.MASSIVE
                ),
                // Typeface.DEFAULT_BOLD to listOf(MINFONTSIZE, TINYFONTSIZE, SMALLFONTSIZE, MEDIUMFONTSIZE ,LARGEFONTSIZE, MASSIVEFONTSIZE),
                //Typeface.MONOSPACE to listOf(MINFONTSIZE, TINYFONTSIZE, SMALLFONTSIZE, MEDIUMFONTSIZE ,LARGEFONTSIZE, MASSIVEFONTSIZE),
                //Typeface.SERIF to listOf(MINFONTSIZE, TINYFONTSIZE, SMALLFONTSIZE, MEDIUMFONTSIZE ,LARGEFONTSIZE, MASSIVEFONTSIZE),
                //Typeface.SANS_SERIF to listOf(MINFONTSIZE, TINYFONTSIZE, SMALLFONTSIZE, MEDIUMFONTSIZE ,LARGEFONTSIZE, MASSIVEFONTSIZE),
            )
            //printer.setTypeface(Typeface.DEFAULT);
            printer!!.appendPrnStr("Printer Test Start", 24, AlignEnum.CENTER, true)
            printer!!.appendPrnStr("Font DEFAULT (24)", 24, AlignEnum.CENTER, true)
            printer!!.appendPrnStr("Gray Level: $grayLevel", 24, AlignEnum.CENTER, true)


            // Test printing different typefaces and font sizes
            for ((typeface, sizes) in fonts) {
                // printer.setTypeface(typeface)
                for (size in sizes) {
                    val charLimit = getLineWidth(size)
                    val testLine = (1..charLimit).joinToString("") { (it % 10).toString() }
                    printer!!.appendPrnStr("Font: ${typefaceToString(typeface)}, Size: $size", size.sp, AlignEnum.LEFT, false)
                    printer!!.appendPrnStr(testLine, size.sp, AlignEnum.LEFT, false)
                    printer!!.appendPrnStr(testLine, size.sp, AlignEnum.LEFT, true)
                }
            }

            // Test alignment
            printer!!.appendPrnStr("Alignment Test", 30, AlignEnum.CENTER, true)
            printer!!.appendPrnStr("LEFT", 24, AlignEnum.LEFT, false)
            printer!!.appendPrnStr("CENTER", 24, AlignEnum.CENTER, false)
            printer!!.appendPrnStr("RIGHT", 24, AlignEnum.RIGHT, false)

            // Test justified printing
            printer!!.appendPrnStr("(15)Justify Left", "Justify Right", 24, LineOptionEntity().apply { marginLeft = 15 })

            printer!!.appendPrnStr("Printer Test End", 30, AlignEnum.CENTER, true)

            val listener = OnPrintListener { result ->
                when (result) {
                    SdkResult.Success -> Log.d(tag, "Printer test completed successfully.")
                    else -> Log.e(tag, "Printer test failed: $result")
                }
            }

            printer!!.startPrint(true, listener)

        } catch (e: Exception) {
            Logger.e(tag, "testLineWidth failed", e)
        }
    }

    /** Convert printer status codes to human readable strings. */
    private fun statusCodeToString(status: Int): String = when (status) {
        SdkResult.Success -> "Success"
        SdkResult.Printer_Print_Fail -> "Print failed"
        SdkResult.Printer_PaperLack -> "Out of paper"
        SdkResult.Printer_UnFinished -> "Printing not finished"
        SdkResult.Printer_TooHot -> "Printer overheated"
        else -> "Unknown status: $status"
    }
}
