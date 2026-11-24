package com.kai.agent.runtime
import com.kai.agent.inference.*
import org.junit.Assert.*
import org.junit.Test
class ModelPolicyTest {
    private fun config(id: String)=ProviderConfig(ProviderId.KOINOS,"https://credits.koinosai.com/platform/api","secret",id,accountSession=true,creditWallet="wallet")
    @Test fun `fallback is ordered and respects billing protocol and credential boundaries`() {
        val primary=config("primary")
        val policy=ModelPolicy(listOf(config("wrong-wallet").copy(creditWallet="other"),config("native").copy(nativeTools=true),config("first"),config("second")))
        val error=InferenceException("reasoning",502,failureCode=FailureCode.REASONING_ONLY)
        assertEquals("first",policy.next(primary,error)?.modelId)
        assertEquals("second",policy.next(config("first"),error)?.modelId)
        assertNull(policy.next(config("second"),error))
        assertNull(ModelPolicy(listOf(config("next"))).next(primary,InferenceException("credits",402)))
    }
    @Test fun `output allowance is retained across restart and never exceeds serving limit`() {
        val account=com.kai.agent.account.CreditAccount(model="model",maxOutput=6000,preferredOutput=8192,fallbackModels=listOf("backup"))
        val restored=com.kai.agent.account.CreditAccount.read(account.json(true))
        assertEquals(6000,restored.inference().maxOutputTokens)
        assertEquals(listOf("backup"),restored.fallbackModels)
    }
    @Test fun `format repair cannot exceed an explicit response allowance`() {
        val c=com.kai.agent.account.CreditAccount(maxOutput=8192,preferredOutput=2048).inference()
        val recovery=com.kai.agent.agent.AgentResponseRecovery()
        recovery.requestConfig(c);recovery.correction(com.kai.agent.agent.AgentResponseException("length",true))
        assertEquals(2048,recovery.requestConfig(c).maxOutputTokens)
    }

}
