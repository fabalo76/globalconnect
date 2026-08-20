package one.globalconnect.xtmsagent.params

import org.junit.Assert.assertEquals
import org.junit.Test

class ParameterApplicationResultTest {

    @Test
    fun `completed is the only successful terminal result`() {
        assertEquals(
            ParameterApplicationResult.COMPLETED,
            parameterApplicationResult("completed"),
        )
    }

    @Test
    fun `deferred remains nonterminal`() {
        assertEquals(
            ParameterApplicationResult.DEFERRED,
            parameterApplicationResult("deferred"),
        )
    }

    @Test
    fun `missing or unknown result is failed`() {
        assertEquals(ParameterApplicationResult.FAILED, parameterApplicationResult(null))
        assertEquals(ParameterApplicationResult.FAILED, parameterApplicationResult("unknown"))
    }
}
