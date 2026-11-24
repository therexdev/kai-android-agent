package com.kai.agent.runtime
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*
class EffectExecutorTest {
    @Test fun `admission refusal never dispatches or invents an uncertain effect`()=runTest {
        var dispatch=0;var uncertain=0
        try { EffectExecutor.submit<Int>({error("revoked")},{dispatch++;1},{},{uncertain++}) } catch(_: Exception) {}
        assertEquals(0,dispatch);assertEquals(0,uncertain)
    }
    @Test fun `ambiguous transport failure is recorded once and never retried`()=runTest {
        var dispatch=0;var uncertain=0
        try { EffectExecutor.submit<Int>({},{dispatch++;throw java.io.IOException("disconnect")},{},{uncertain++}) } catch(_: Exception) {}
        assertEquals(1,dispatch);assertEquals(1,uncertain)
    }
    @Test fun `cancellation after submission still records uncertainty`()=runTest {
        var failed=false
        try { EffectExecutor.submit<Int>({},{throw CancellationException("cancel")},{},{failed=true}) } catch(_: CancellationException) {}
        assertTrue(failed)
    }
}
