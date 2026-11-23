package com.kai.agent.account

import com.kai.agent.data.*
import com.kai.agent.diagnostics.DiagnosticRedactor
import com.kai.agent.inference.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CreditAccountTest {
    private fun overviewFixture(wallet: String?=null,primary: String="wallet-B",paid: String="0",linked: Boolean=true): String = JSONObject()
        .put("account",JSONObject().put("id","alice").put("email","alice@example.test").put("primaryCreditWallet",primary)
            .put("wallets",org.json.JSONArray().apply { if(linked) {
                put(JSONObject().put("address","wallet-A").put("label","Website wallet"))
                put(JSONObject().put("address","wallet-B").put("label","Core credits").put("creditAccessOnly",true))
            } }))
        .put("selectedWallet",wallet ?: JSONObject.NULL)
        .put("credits",JSONObject().put("free",JSONObject().put("available","100000").put("state","available"))
            .put("purchased",JSONObject().put("available",paid)))
        .put("models",org.json.JSONArray().put(JSONObject().put("model",DEFAULT_CREDIT_MODEL).put("label","GPT OSS 120B")
            .put("available",true).put("freeEligible",true).put("maxOutput",8192).put("contextTokens",131072)))
        .toString()

    @Test fun automaticOverviewShowsPrimaryConnectedPaidBalanceWithoutPinningTheSavedSource() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(overviewFixture()))
            server.enqueue(MockResponse().setBody(overviewFixture("wallet-B",paid="200000000")))
            val account=CreditAccount(token="fixture-account-session",id="alice")
            val overview=CreditAccountClient(server.url("/").toString().trimEnd('/')).overview(account)
            assertEquals("wallet-B",overview.selectedWallet);assertEquals("wallet-B",overview.primaryCreditWallet)
            assertEquals("2000",creditAmount(overview.paidCredits));assertEquals("Core credits",overview.walletLabel("wallet-B"))
            assertEquals("",account.wallet);assertTrue(overview.allowsPurchasedCredits(account))
            assertNull(server.takeRequest().requestUrl!!.queryParameter("wallet"))
            assertEquals("wallet-B",server.takeRequest().requestUrl!!.queryParameter("wallet"))
        }
    }
    @Test fun explicitCreditSourceNeverSwitchesToAnotherConnectedAccount() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(overviewFixture("wallet-A",paid="300000")))
            val overview=CreditAccountClient(server.url("/").toString().trimEnd('/'))
                .overview(CreditAccount(token="fixture-account-session",id="alice",wallet="wallet-A",freeOnly=false))
            assertEquals("wallet-A",overview.selectedWallet);assertEquals("3",creditAmount(overview.paidCredits))
            assertEquals(1,server.requestCount);assertEquals("wallet-A",server.takeRequest().requestUrl!!.queryParameter("wallet"))
            assertFalse(overview.allowsPurchasedCredits(CreditAccount(id="another-account",wallet="wallet-A")))
            assertFalse(overview.allowsPurchasedCredits(CreditAccount(id="alice",wallet="unlinked-wallet")))
        }
    }
    @Test fun invalidPrimaryCanNeverSelectAnUnlinkedCreditAccount() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(overviewFixture(primary="unlinked-wallet")))
            server.enqueue(MockResponse().setBody(overviewFixture("wallet-A",primary="unlinked-wallet")))
            val overview=CreditAccountClient(server.url("/").toString().trimEnd('/')).overview(CreditAccount(token="fixture-session",id="alice"))
            assertEquals("",overview.primaryCreditWallet);server.takeRequest()
            assertEquals("wallet-A",server.takeRequest().requestUrl!!.queryParameter("wallet"))
        }
    }
    @Test fun accountWithoutConnectedWalletsRemainsFreeOnly() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(overviewFixture(linked=false)))
            val overview=CreditAccountClient(server.url("/").toString().trimEnd('/')).overview(CreditAccount(token="fixture-session",id="alice"))
            assertEquals(1,server.requestCount);assertEquals("",overview.primaryCreditWallet)
            assertFalse(overview.allowsPurchasedCredits(CreditAccount(id="alice")))
        }
    }
    @Test fun automaticCreditRequestsPreserveTheFreeOnlyChoiceAndNeverPinWalletlessFunding() = runBlocking {
        val bodies=mutableListOf<JSONObject>()
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            val buffer=okio.Buffer();chain.request().body!!.writeTo(buffer);bodies+=JSONObject(buffer.readUtf8())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").header("Content-Type","text/event-stream")
                .body("data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        for(freeOnly in listOf(true,false)) {
            val account=CreditAccount(token="fixture-session",id="alice",freeOnly=freeOnly)
            InferenceClient(http).stream(account.inference(),listOf(InferenceMessage("user","Hello"))) {}
        }
        assertEquals(2,bodies.size)
        bodies.forEachIndexed { index,body ->
            assertFalse(body.has("creditWallet"));assertEquals(index==0,body.getBoolean("freeOnly"))
            assertEquals(DEFAULT_CREDIT_MODEL,body.getString("model"))
        }
    }
    @Test fun gptOssIsTheDefaultWithoutReplacingSavedChoices() {
        assertEquals(DEFAULT_CREDIT_MODEL,CreditAccount().model)
        assertEquals(DEFAULT_CREDIT_MODEL,CreditAccount.read(JSONObject().put("model","")).model)
        assertEquals(DEFAULT_CREDIT_MODEL,CreditAccount.read(JSONObject()).model)
        val selected=CreditAccount(model="another-model",contextTokens=32768,maxOutput=8192)
        assertEquals(selected,CreditAccount.read(selected.json(true)))
    }
    @Test fun encryptedSerializationRetainsSessionButExportAndReportsExcludeCredentials() {
        val a=CreditAccount(token="session-secret-test-token",id="account",email="user@example.test",linkSecret="link-secret-test-value")
        val state=KaiState(settings=AppSettings(creditAccount=a))
        val privateJson=KaiJson.encode(state,true)
        assertEquals(a,KaiJson.decode(privateJson).settings.creditAccount)
        val exported=KaiJson.encode(state,false)
        assertFalse(exported.contains(a.token));assertFalse(exported.contains(a.linkSecret))
        assertEquals(CreditAccount(),KaiJson.decode(exported).settings.creditAccount)
        val filtered=DiagnosticRedactor(state.settings).text("token ${a.token} link ${a.linkSecret}")
        assertFalse(filtered.contains(a.token));assertFalse(filtered.contains(a.linkSecret))
        assertFalse(a.toString().contains(a.token))
    }
    @Test fun deviceLoginOnlySendsSecretToPollNeverBrowser() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"userCode":"ABCD-2345","deviceSecret":"ds_long-random-device-secret","expiresInSec":600}"""))
            server.enqueue(MockResponse().setBody("""{"pending":true}"""))
            server.enqueue(MockResponse().setBody("""{"pending":false,"token":"ss_test-session-secret-long","account":{"id":"alice","email":"alice@example.test"}}"""))
            val client=CreditAccountClient(server.url("/").toString().trimEnd('/'))
            val pending=client.start();assertFalse(client.loginUrl(pending).contains(pending.linkSecret))
            assertNull(client.poll(pending));val signed=client.poll(pending)!!;assertEquals("alice",signed.id);assertEquals("",signed.linkSecret)
            assertEquals("/auth/device/start",server.takeRequest().path)
            assertEquals(pending.linkSecret,JSONObject(server.takeRequest().body.readUtf8()).getString("deviceSecret"))
        }
    }
    @Test fun redirectDoesNotForwardSessionAndExpiredSessionIsExplicit() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location","https://untrusted.invalid/"))
            val client=CreditAccountClient(server.url("/").toString().trimEnd('/'))
            try { client.overview(CreditAccount(token="secret",id="alice"));fail() }catch(e:AccountFailure){assertEquals(302,e.status)}
            assertEquals(1,server.requestCount)
            server.enqueue(MockResponse().setResponseCode(401))
            try { client.overview(CreditAccount(token="secret",id="alice"));fail() }catch(e:AccountFailure){assertEquals(401,e.status)}
        }
    }
    @Test fun accountStreamUsesSharedCreditRouteAndServerLimitsWithoutApiKeyOrModelDiscovery() = runBlocking {
        var captured: Request?=null
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            captured=chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").header("Content-Type","text/event-stream")
                .body(("data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n").toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val a=CreditAccount(token="test-session",id="alice",wallet="wallet-A",model="better-model",contextTokens=32768,maxOutput=512,freeOnly=false)
        val client=InferenceClient(http)
        assertFalse(client.usesCompactAgentContext(a.inference()))
        val text=StringBuilder()
        client.stream(a.inference(),listOf(InferenceMessage("user","Hi"))) { text.append(it) }
        assertEquals("Hello",text.toString())
        val req=captured!!;assertEquals("$CREDIT_ORIGIN/platform/api/chat",req.url.toString())
        assertEquals("Bearer test-session",req.header("Authorization"));assertNotNull(req.header("Idempotency-Key"))
        val buffer=okio.Buffer();req.body!!.writeTo(buffer);val body=JSONObject(buffer.readUtf8())
        assertEquals("wallet-A",body.getString("creditWallet"));assertFalse(body.getBoolean("freeOnly"))
        assertEquals(512,body.getInt("max_tokens"));assertFalse(body.has("context_tokens"));assertFalse(body.has("apiKey"))
    }
    @Test fun accountAccessFailuresPointToAccountInsteadOfRemovedApiSettings() = runBlocking {
        for(code in listOf(400,401,402,403,404,429)) {
            val http=OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Rejected")
                    .body("{\"error\":\"insufficient purchased credits\"}".toResponseBody("application/json".toMediaType())).build()
            }.build()
            try {
                InferenceClient(http).stream(CreditAccount(token="fixture-session",model="fixture").inference(),listOf(InferenceMessage("user","Hi"))) {}
                fail("Request should fail")
            } catch(e:InferenceException) {
                assertTrue(e.message.orEmpty().contains("Account"))
                assertFalse(e.message.orEmpty().contains("API key"))
            }
        }
    }
    @Test fun memoryAndTaskOutputOverridesCannotExceedTheSelectedModelsLimit() {
        val config=CreditAccount(maxOutput=64,contextTokens=32768).inference().copy(maxOutputTokens=4096)
        assertEquals(64,InferenceBudget.prepare(config,listOf(InferenceMessage("user","Hi")),32768).outputTokens)
    }
    @Test fun accountCredentialCannotBeSentToUserEndpointAndCreditsAreNotTokens() {
        try { EndpointPolicy.validateRoute(CreditAccount(token="secret").inference().copy(endpoint="https://evil.invalid"));fail() }catch(_:InferenceException){}
        assertEquals("12.34567",creditAmount("1234567"));assertEquals("0",creditAmount("0"))
    }
}
