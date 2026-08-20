package one.globalconnect.keyinjection.util

import javax.crypto.spec.DESKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedParentMasterKeyMaterialTest {
    @Test
    fun generate_createsUsableDoubleAndTripleLengthTdesKeys() {
        listOf(16, 24).forEach { length ->
            repeat(32) {
                val key = GeneratedParentMasterKeyMaterial.generate(length)

                assertEquals(length, key.size)
                assertTrue(GeneratedParentMasterKeyMaterial.isUsable(key))
                key.indices.forEach { index ->
                    assertTrue(Integer.bitCount(key[index].toInt() and 0xFF) % 2 == 1)
                }
                key.fill(0)
            }
        }
    }

    @Test
    fun isUsable_rejectsDegenerateAndInvalidMaterial() {
        assertFalse(GeneratedParentMasterKeyMaterial.isUsable(ByteArray(8)))
        assertFalse(GeneratedParentMasterKeyMaterial.isUsable(ByteArray(16) { 0x01 }))

        val generated = GeneratedParentMasterKeyMaterial.generate(16)
        assertFalse(DESKeySpec.isWeak(generated, 0))
        assertFalse(DESKeySpec.isWeak(generated, 8))
        generated.fill(0)
    }
}
