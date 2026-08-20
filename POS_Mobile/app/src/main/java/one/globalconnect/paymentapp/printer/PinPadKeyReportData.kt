package one.globalconnect.paymentapp.printer

import com.nexgo.oaf.apiv3.device.pinpad.WorkKeyTypeEnum

internal data class PinPadKeyStatus(
    val type: String,
    val index: Int,
    val kcv: String? = null,
    val state: String = "LOADED",
)

/**
 * Scans the same PED key families and slot range used by the Nexgo key-injection application.
 * Key material is never returned; master/working keys expose only their KCV, and DUKPT exposes
 * presence only.
 */
internal fun readPinPadKeyStatuses(
    masterKeyKcv: (Int) -> String?,
    workingKeyKcv: (Int, WorkKeyTypeEnum) -> String?,
    dukptLoaded: (Int) -> Boolean,
): List<PinPadKeyStatus> = buildList {
    val tlkKcv = masterKeyKcv(0)
    add(
        PinPadKeyStatus(
            type = "TLK",
            index = 0,
            kcv = tlkKcv,
            state = if (tlkKcv == null) "NOT LOADED" else "LOADED",
        ),
    )

    for (index in 1..10) {
        masterKeyKcv(index)?.let { add(PinPadKeyStatus("TMK", index, it)) }
        workingKeyKcv(index, WorkKeyTypeEnum.PINKEY)?.let {
            add(PinPadKeyStatus("TPK", index, it))
        }
        workingKeyKcv(index, WorkKeyTypeEnum.MACKEY)?.let {
            add(PinPadKeyStatus("TAK", index, it))
        }
        workingKeyKcv(index, WorkKeyTypeEnum.ENCRYPTIONKEY)?.let {
            add(PinPadKeyStatus("TDK", index, it))
        }
        if (dukptLoaded(index)) {
            add(PinPadKeyStatus(type = "TIK", index = index, state = "LOADED"))
        }
    }

    if (size == 1 && tlkKcv == null) {
        add(PinPadKeyStatus(type = "KEY SLOTS", index = 1, state = "NO KEYS LOADED IN SLOTS 1-10"))
    }
}
