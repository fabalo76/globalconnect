package one.globalconnect.xtmsagent.licensing

/** These PSS implementations write the argument directly to sys.xgd.*.disable. */
internal fun nexgoUiArgument(model: String?, locked: Boolean): Boolean {
    return if (usesNexgoDisableFlags(model)) locked else !locked
}

internal fun usesNexgoDisableFlags(model: String?): Boolean =
    model.orEmpty().uppercase().filter(Char::isLetterOrDigit) in setOf("N6PROLITE", "CT20", "CT20P", "N96")
