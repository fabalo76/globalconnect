package one.globalconnect.paymentapp.transaction

import org.junit.Assert.*
import org.junit.Test

class CardBrandResolverTest {
    @Test fun selectedAidTakesPrecedenceOverBin() {
        assertEquals("MASTERCARD", CardBrandResolver.resolve("A0000000041010", "411111"))
        assertEquals("VISA", CardBrandResolver.resolve("a0000000031010", "510000"))
    }
    @Test fun mastercardBinBoundaries() {
        for (bin in listOf("510000", "559999", "222100", "272099"))
            assertEquals("MASTERCARD", CardBrandResolver.resolve(null, bin))
        for (bin in listOf("509999", "560000", "222099", "272100"))
            assertEquals("", CardBrandResolver.resolve(null, bin))
    }
    @Test fun neverTreatsIssuerNamesOrMaskedSuffixAsEvidence() {
        assertEquals("", CardBrandResolver.resolve("MASTER", "************5100"))
        assertEquals("", CardBrandResolver.resolve("VISA", null))
        assertEquals("", CardBrandResolver.resolve(null, "4111"))
    }
    @Test fun fallsBackToBinForUnknownAid() {
        assertEquals("VISA", CardBrandResolver.resolve("A0000099991010", "411111"))
        assertEquals("AMEX", CardBrandResolver.resolve(null, "371234"))
        assertEquals("DISCOVER", CardBrandResolver.resolve(null, "601100"))
    }
    @Test fun unknownAndAmbiguousBinRemainGeneric() {
        assertEquals("", CardBrandResolver.resolve(null, "622126"))
        assertEquals("", CardBrandResolver.resolve(null, "999999"))
    }
    @Test fun validatesAidAndDistinguishesMaestro() {
        assertEquals("", CardBrandResolver.fromAid("A0000000041010garbage"))
        assertEquals("MAESTRO", CardBrandResolver.fromAid("A0000000043060"))
        assertEquals("AMEX", CardBrandResolver.fromAid("A00000002501"))
        assertEquals("JCB", CardBrandResolver.fromAid("A0000000651010"))
        assertEquals("UNIONPAY", CardBrandResolver.fromAid("A000000333010101"))
    }
}
