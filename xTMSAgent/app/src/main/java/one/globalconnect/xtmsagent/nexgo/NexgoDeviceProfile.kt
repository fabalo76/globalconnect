package one.globalconnect.xtmsagent.nexgo

enum class NexgoCapability(val wireName: String) {
    BOOT_ANIMATION("bootAnimation"),
    SHUTDOWN_ANIMATION("shutdownAnimation"),
    STANDALONE_BOOT_SOUND("standaloneBootSound"),
    DEFAULT_WALLPAPER("defaultWallpaper"),
    POWER_LOGO("powerLogo"),
    IMMEDIATE_REBOOT("immediateReboot"),
    SCHEDULED_REBOOT("scheduledReboot"),
    DEVICE_OWNER("deviceOwner"),
    EXTENDED_SYSTEM_CONTROLS("extendedSystemControls"),
}

enum class NexgoCapabilityState(val wireValue: String) {
    SUPPORTED("supported"),
    PENDING_VALIDATION("pendingValidation"),
    RUNTIME_PROBE_REQUIRED("runtimeProbeRequired"),
    UNSUPPORTED("unsupported"),
}

data class NexgoDisplaySpec(
    val width: Int,
    val height: Int,
)

data class NexgoDeviceProfile(
    val modelKey: String,
    val reportedModel: String,
    val display: NexgoDisplaySpec?,
    val expectedCommandBase: Int?,
    val detectedCommandBase: Int?,
    val capabilities: Map<NexgoCapability, NexgoCapabilityState>,
) {
    val isKnownModel: Boolean
        get() = expectedCommandBase != null

    val commandProfileVerified: Boolean
        get() = expectedCommandBase != null && detectedCommandBase == expectedCommandBase
}

object NexgoProfileResolver {
    private const val COMMAND_BASE_70 = 70_000_000
    private const val COMMAND_BASE_80 = 80_000_000
    private const val COMMAND_BASE_90 = 90_000_000

    fun resolve(
        modelProperty: String?,
        buildModel: String?,
        commandBaseProperty: String?,
    ): NexgoDeviceProfile {
        val reportedModel = modelProperty.normalizedOrNull()
            ?: buildModel.normalizedOrNull()
            ?: "UNKNOWN"
        val detectedBase = commandBaseProperty?.trim()?.toIntOrNull()

        return when (canonicalModel(reportedModel)) {
            "CT20P" -> ct20pProfile(reportedModel, detectedBase ?: COMMAND_BASE_70)

            "N6S" -> profile(
                modelKey = "N6S",
                reportedModel = reportedModel,
                display = NexgoDisplaySpec(720, 1280),
                expectedCommandBase = COMMAND_BASE_80,
                detectedCommandBase = detectedBase ?: COMMAND_BASE_80,
                standaloneBootSound = NexgoCapabilityState.PENDING_VALIDATION,
                powerLogo = NexgoCapabilityState.SUPPORTED,
            )

            "N82" -> profile(
                modelKey = "N82",
                reportedModel = reportedModel,
                display = NexgoDisplaySpec(480, 854),
                expectedCommandBase = COMMAND_BASE_80,
                detectedCommandBase = detectedBase ?: COMMAND_BASE_80,
                standaloneBootSound = NexgoCapabilityState.PENDING_VALIDATION,
                powerLogo = NexgoCapabilityState.SUPPORTED,
            )

            "N96" -> profile(
                modelKey = "N96",
                reportedModel = reportedModel,
                display = NexgoDisplaySpec(720, 1600),
                expectedCommandBase = COMMAND_BASE_90,
                detectedCommandBase = detectedBase ?: COMMAND_BASE_90,
                standaloneBootSound = NexgoCapabilityState.SUPPORTED,
                powerLogo = NexgoCapabilityState.SUPPORTED,
                extendedControls = NexgoCapabilityState.RUNTIME_PROBE_REQUIRED,
            )

            else -> NexgoDeviceProfile(
                modelKey = "UNKNOWN",
                reportedModel = reportedModel,
                display = null,
                expectedCommandBase = null,
                detectedCommandBase = detectedBase,
                capabilities = NexgoCapability.entries.associateWith {
                    NexgoCapabilityState.UNSUPPORTED
                },
            )
        }
    }

    private fun profile(
        modelKey: String,
        reportedModel: String,
        display: NexgoDisplaySpec,
        expectedCommandBase: Int,
        detectedCommandBase: Int,
        standaloneBootSound: NexgoCapabilityState,
        powerLogo: NexgoCapabilityState,
        extendedControls: NexgoCapabilityState = NexgoCapabilityState.UNSUPPORTED,
    ): NexgoDeviceProfile {
        val capabilities = NexgoCapability.entries.associateWith { capability ->
            when (capability) {
                NexgoCapability.BOOT_ANIMATION,
                NexgoCapability.SHUTDOWN_ANIMATION,
                NexgoCapability.DEFAULT_WALLPAPER,
                NexgoCapability.IMMEDIATE_REBOOT,
                NexgoCapability.SCHEDULED_REBOOT,
                NexgoCapability.DEVICE_OWNER,
                -> NexgoCapabilityState.SUPPORTED

                NexgoCapability.STANDALONE_BOOT_SOUND -> standaloneBootSound
                NexgoCapability.POWER_LOGO -> powerLogo
                NexgoCapability.EXTENDED_SYSTEM_CONTROLS -> extendedControls
            }
        }
        return NexgoDeviceProfile(
            modelKey = modelKey,
            reportedModel = reportedModel,
            display = display,
            expectedCommandBase = expectedCommandBase,
            detectedCommandBase = detectedCommandBase,
            capabilities = capabilities,
        )
    }

    private fun ct20pProfile(
        reportedModel: String,
        detectedCommandBase: Int,
    ) = NexgoDeviceProfile(
        modelKey = "CT20P",
        reportedModel = reportedModel,
        display = NexgoDisplaySpec(480, 800),
        expectedCommandBase = COMMAND_BASE_70,
        detectedCommandBase = detectedCommandBase,
        capabilities = NexgoCapability.entries.associateWith { capability ->
            when (capability) {
                NexgoCapability.BOOT_ANIMATION,
                NexgoCapability.SHUTDOWN_ANIMATION,
                NexgoCapability.STANDALONE_BOOT_SOUND,
                NexgoCapability.DEFAULT_WALLPAPER,
                NexgoCapability.IMMEDIATE_REBOOT,
                NexgoCapability.DEVICE_OWNER,
                NexgoCapability.EXTENDED_SYSTEM_CONTROLS,
                -> NexgoCapabilityState.SUPPORTED

                NexgoCapability.POWER_LOGO -> NexgoCapabilityState.SUPPORTED
                NexgoCapability.SCHEDULED_REBOOT -> NexgoCapabilityState.RUNTIME_PROBE_REQUIRED
            }
        },
    )

    private fun canonicalModel(value: String): String {
        val normalized = value.uppercase().replace("-", "").replace("_", "")
        return when {
            normalized.startsWith("CT20P") -> "CT20P"
            normalized == "N6" || normalized.startsWith("N6S") -> "N6S"
            normalized.startsWith("N82") -> "N82"
            normalized.startsWith("N96") -> "N96"
            else -> normalized
        }
    }

    private fun String?.normalizedOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
