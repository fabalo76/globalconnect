package one.globalconnect.paymentapp.utils

import android.os.Build

enum class PaymentDeviceVisual {
    CT20,
    CT20P,
    N80,
    N96,
    N82,
    N60_PRO,
    N6,
    N6_PRO,
    GENERIC,
}

object DeviceCapabilities {
    private val physicalKeypadModelPattern = Regex("(?:^|[^A-Z0-9])(CT20P?|N80)(?:$|[^A-Z0-9])")
    private val inAppCardReaderLightsModelPattern =
        Regex("(?:^|[^A-Z0-9])(CT20P?|N80|N60[ _-]?PRO)(?:$|[^A-Z0-9])")

    fun hasPhysicalNumericKeypad(): Boolean = hasPhysicalNumericKeypad(
        model = Build.MODEL,
        device = Build.DEVICE,
        product = Build.PRODUCT,
    )

    internal fun hasPhysicalNumericKeypad(
        model: String?,
        device: String? = null,
        product: String? = null,
    ): Boolean = sequenceOf(model, device, product)
        .filterNotNull()
        .map { it.trim().uppercase() }
        .any(physicalKeypadModelPattern::containsMatchIn)

    fun usesInAppCardReaderLights(): Boolean = usesInAppCardReaderLights(
        model = Build.MODEL,
        device = Build.DEVICE,
        product = Build.PRODUCT,
    )

    internal fun usesInAppCardReaderLights(
        model: String?,
        device: String? = null,
        product: String? = null,
    ): Boolean = sequenceOf(model, device, product)
        .filterNotNull()
        .map { it.trim().uppercase() }
        .any(inAppCardReaderLightsModelPattern::containsMatchIn)

    fun paymentDeviceVisual(): PaymentDeviceVisual = paymentDeviceVisual(
        model = Build.MODEL,
        device = Build.DEVICE,
        product = Build.PRODUCT,
    )

    internal fun paymentDeviceVisual(
        model: String?,
        device: String? = null,
        product: String? = null,
    ): PaymentDeviceVisual {
        val identifiers = sequenceOf(model, device, product)
            .filterNotNull()
            .map { it.trim().uppercase() }
            .toList()
        return when {
            identifiers.any { Regex("(?:^|[^A-Z0-9])CT20P(?:$|[^A-Z0-9])").containsMatchIn(it) } ->
                PaymentDeviceVisual.CT20P
            identifiers.any { Regex("(?:^|[^A-Z0-9])CT20(?:$|[^A-Z0-9])").containsMatchIn(it) } ->
                PaymentDeviceVisual.CT20
            identifiers.any { Regex("(?:^|[^A-Z0-9])N80(?:$|[^A-Z0-9])").containsMatchIn(it) } ->
                PaymentDeviceVisual.N80
            identifiers.any { Regex("(?:^|[^A-Z0-9])N96(?:$|[^A-Z0-9])").containsMatchIn(it) } ->
                PaymentDeviceVisual.N96
            identifiers.any { Regex("(?:^|[^A-Z0-9])N82(?:$|[^A-Z0-9])").containsMatchIn(it) } ->
                PaymentDeviceVisual.N82
            identifiers.any { Regex("(?:^|[^A-Z0-9])N60[ _-]?PRO(?:$|[^A-Z0-9])").containsMatchIn(it) } ->
                PaymentDeviceVisual.N60_PRO
            identifiers.any { Regex("(?:^|[^A-Z0-9])N6[ _-]?PRO(?:$|[^A-Z0-9])").containsMatchIn(it) } ->
                PaymentDeviceVisual.N6_PRO
            identifiers.any { Regex("(?:^|[^A-Z0-9])N6(?:$|[^A-Z0-9])").containsMatchIn(it) } ->
                PaymentDeviceVisual.N6
            else -> PaymentDeviceVisual.GENERIC
        }
    }
}

