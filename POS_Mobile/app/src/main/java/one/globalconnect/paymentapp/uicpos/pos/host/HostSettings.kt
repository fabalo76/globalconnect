package one.globalconnect.paymentapp.uicpos.pos.host

data class HostSettings(
    val isTls: Boolean,
    val primary: HostAddress?,
    val secondary: HostAddress?,
    val connectTimeoutSeconds: Int,
    val readTimeoutSeconds: Int,
    val attempts: Int,
    val primaryRetries: Int,
    val secondaryRetries: Int,
    val length: LengthConfig,
)
