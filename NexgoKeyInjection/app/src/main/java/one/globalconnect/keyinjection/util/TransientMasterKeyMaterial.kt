package one.globalconnect.keyinjection.util

import java.security.SecureRandom
import javax.crypto.spec.DESKeySpec

/** Generates PED-resident TDES parent-master material for clear working-key loading. */
internal object GeneratedParentMasterKeyMaterial {
    private const val DES_COMPONENT_BYTES = 8
    private const val MAX_GENERATION_ATTEMPTS = 128
    private val secureRandom = SecureRandom()

    fun generate(byteLength: Int): ByteArray {
        require(byteLength == 16 || byteLength == 24) {
            "A generated TDES parent master key must be 16 or 24 bytes."
        }

        repeat(MAX_GENERATION_ATTEMPTS) {
            val candidate = ByteArray(byteLength)
            secureRandom.nextBytes(candidate)
            applyOddParity(candidate)

            if (isUsable(candidate)) return candidate
            candidate.fill(0)
        }

        throw IllegalStateException("Unable to generate usable TDES parent-master material.")
    }

    internal fun isUsable(candidate: ByteArray): Boolean {
        if (candidate.size != 16 && candidate.size != 24) return false

        val components = candidate.asList().chunked(DES_COMPONENT_BYTES)
        if (components.distinct().size != components.size) return false

        return components.indices.none { index ->
            DESKeySpec.isWeak(candidate, index * DES_COMPONENT_BYTES)
        }
    }

    private fun applyOddParity(candidate: ByteArray) {
        for (index in candidate.indices) {
            val withoutParity = candidate[index].toInt() and 0xFE
            val parityBit = if (Integer.bitCount(withoutParity) % 2 == 0) 1 else 0
            candidate[index] = (withoutParity or parityBit).toByte()
        }
    }
}
