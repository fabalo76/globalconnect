package one.globalconnect.paymentapp.transaction.installments
import org.junit.Assert.assertNull
import org.junit.Test
import one.globalconnect.paymentapp.transaction.TransactionType
class InstallmentFlavorIsolationTest {
    @Test fun doesNotEnableBanpaisContract() {
        assertNull(InstallmentContracts.forTransaction(TransactionType.EXTRAS_SALE))
        assertNull(InstallmentContracts.forTransaction(TransactionType.QUOTA_SALE))
    }
}
