package one.globalconnect.pinpad.config

/** N6ProLite PSS SystemUIOperate writes this argument directly to sys.xgd.*.disable. */
internal fun nexgoUiArgument(model: String?, locked: Boolean): Boolean {
    val usesDisableFlag = model.orEmpty().uppercase().filter(Char::isLetterOrDigit) == "N6PROLITE"
    return if (usesDisableFlag) locked else !locked
}
