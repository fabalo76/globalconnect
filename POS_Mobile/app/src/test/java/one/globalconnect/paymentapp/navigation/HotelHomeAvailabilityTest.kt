package one.globalconnect.paymentapp.navigation

import one.globalconnect.tms.paymentapp.TMSDATA
import one.globalconnect.tms.paymentapp.TMS_Acquirer
import one.globalconnect.tms.paymentapp.TMS_Terminal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HotelHomeAvailabilityTest {

    @Test
    fun hotelHome_requiresTerminalAndAcquirerEnablement() {
        assertTrue(
            shouldUseHotelHome(
                TMSDATA(
                    terminal = listOf(
                        TMS_Terminal(
                            enableCheckInOut = true,
                            acquirer = listOf(TMS_Acquirer(enableCheckInOut = true)),
                        ),
                    ),
                ),
            ),
        )

        assertFalse(
            shouldUseHotelHome(
                TMSDATA(
                    terminal = listOf(
                        TMS_Terminal(
                            enableCheckInOut = false,
                            acquirer = listOf(TMS_Acquirer(enableCheckInOut = true)),
                        ),
                    ),
                ),
            ),
        )

        assertFalse(
            shouldUseHotelHome(
                TMSDATA(
                    terminal = listOf(
                        TMS_Terminal(
                            enableCheckInOut = true,
                            acquirer = listOf(TMS_Acquirer(enableCheckInOut = false)),
                        ),
                    ),
                ),
            ),
        )
    }
}
