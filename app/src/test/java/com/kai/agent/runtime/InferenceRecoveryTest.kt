package com.kai.agent.runtime
import com.kai.agent.inference.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
class InferenceRecoveryTest {
    @Test fun `provider timing and whole retry ceiling apply and audible text is final`()=runTest {
        val c=ProviderConfig(ProviderId.KOINOS,"https://example.org","key","model")
        val recovery=InferenceRecovery();val limited=InferenceException("limited",429,retryAfterMillis=5000)
        assertFalse(recovery.retry(c,limited,true));assertEquals(0,testScheduler.currentTime)
        assertTrue(recovery.retry(c,limited));assertEquals(5000,testScheduler.currentTime)
        assertTrue(recovery.retry(c,limited));assertEquals(10000,testScheduler.currentTime)
        assertFalse(recovery.retry(c,limited))
        assertFalse(InferenceRecovery().retry(c,InferenceException("cooldown",429,retryAfterMillis=60000)))
        assertFalse(InferenceRecovery().retry(c,InferenceException("reasoning",502,failureCode=FailureCode.REASONING_ONLY)))
    }
}
