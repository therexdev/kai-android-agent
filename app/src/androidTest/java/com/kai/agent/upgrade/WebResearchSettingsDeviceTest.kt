package com.kai.agent.upgrade

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kai.agent.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebResearchSettingsDeviceTest {
    @Test fun searchAndPageParsingRunOnAndroidWithNoConnectedAccount() = runBlocking<Unit> {
        val client=com.kai.agent.web.WebResearchClient(com.kai.agent.web.WebTransport { url,headers ->
            assertTrue(headers.isEmpty())
            com.kai.agent.web.WebDocument(url,if(url.contains("duckduckgo")) "<a class='result__a' href='https://example.org/report'>Report</a>" else "<script>ignore this</script><article>Verified Android parser fixture.</article>","text/html")
        })
        val search=client.search("public parser fixture")
        assertEquals("ok",search.getString("status"))
        val page=client.read(search.getJSONArray("results").getJSONObject(0).getString("url"))
        assertEquals("Verified Android parser fixture.",page.getString("text"))
    }
    @Test fun searchKeySurvivesEncryptedReopenAndIsExcludedFromExports() = runBlocking<Unit> {
        UpgradeFixture.requireEmulator()
        UpgradeFixture.backupProfile()
        try {
            var handle=KaiStores.acquire(UpgradeFixture.context)
            try {
                val store=handle.store;store.load()
                store.updateSettings { it.copy(braveSearchApiKey="synthetic-brave-key",composioUserId="fixture-owner",composioApiKey="synthetic-composio-key",selectedCharacterId="spark") }
            } finally { handle.close() }
            handle=KaiStores.acquire(UpgradeFixture.context)
            try {
                val store=handle.store;store.load()
                assertEquals("synthetic-brave-key",store.state.value.settings.braveSearchApiKey)
                assertEquals("fixture-owner",store.state.value.settings.composioUserId)
                assertEquals("spark",store.state.value.settings.selectedCharacterId)
                val exported=KaiJson.encode(store.state.value,includeSecrets=false)
                assertFalse(exported.contains("synthetic-brave-key"));assertFalse(exported.contains("synthetic-composio-key"))
                assertFalse(store.state.value.settings.toString().contains("synthetic-brave-key"))
            } finally { handle.close() }
        } finally { UpgradeFixture.restoreProfile() }
    }
}
