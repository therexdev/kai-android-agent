package com.kai.agent.account

import com.kai.agent.runtime.CreditPricing
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CreditAllowanceTest {
    @Test fun settingsAndCatalogSnapshotPersistWithoutChangingCreditAuthority() {
        val a=CreditAccount(token="private-token",model="model",wallet="wallet",freeOnly=true,
            pricing=CreditPricing("model","2.5","10",1000),runCreditLimit=5)
        val restored=CreditAccount.read(a.json(true))
        assertEquals(a,restored);assertTrue(restored.inference().freeOnly)
        assertEquals("wallet",restored.inference().creditWallet);assertEquals(a.pricing,restored.inference().creditPricing)
        assertEquals("{}",a.json(false).toString())
    }
    @Test fun existingSettingsDefaultToNoExtraCreditCapAndDoNotInventPrices() {
        val a=CreditAccount.read(JSONObject())
        assertEquals(0,a.runCreditLimit);assertNull(a.pricing)
        assertTrue(runCatching { CreditAccount.read(JSONObject().put("runCreditLimit",-1)) }.isFailure)
    }
}
