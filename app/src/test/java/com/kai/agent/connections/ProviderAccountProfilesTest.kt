package com.kai.agent.connections

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProviderAccountProfilesTest {
    private fun profile(kind: String, data: String) = ProviderAccountProfiles.identity(kind, envelope(data))
    private fun envelope(data: String) = JSONObject().put("status", 200).put("data", JSONObject(data))

    @Test fun screenshotServicesUseActualProviderIdentity() {
        assertEquals("Morgan (@morgan)", profile("discord", """{"id":"1234","username":"morgan","global_name":"Morgan"}""")!!.providerDisplayName)
        assertEquals("Bot · @helper", profile("discord", """{"id":"1234","username":"helper","bot":true}""")!!.providerDisplayName)
        assertEquals("My channel (@mychannel)", profile("youtube", """{"items":[{"id":"UC_example","snippet":{"title":"My channel","customUrl":"@mychannel"}}]}""")!!.providerDisplayName)
        assertEquals("owner@example.com", profile("drive", """{"user":{"me":true,"displayName":"Owner","emailAddress":"owner@example.com"}}""")!!.emailAddress)
        for (toolkit in listOf("googledocs", "googlesheets", "googlecalendar")) {
            assertEquals("google", ProviderAccountProfiles.reads(toolkit).first().kind)
        }
        assertEquals("docs@example.com", profile("google", """{"id":"1234","name":"Docs owner","email":"docs@example.com","verified_email":true}""")!!.emailAddress)
    }

    @Test fun otherCurrentUserEndpointsProjectOnlyTheirDocumentedFields() {
        assertEquals("@owner", profile("github", """{"id":1234,"login":"owner","access_token":"private"}""")!!.providerDisplayName)
        assertEquals("work@example.com", profile("microsoft", """{"id":"user-id","displayName":"Work","mail":"work@example.com"}""")!!.emailAddress)
        assertEquals("Music owner", profile("spotify", """{"id":"user-id","display_name":"Music owner"}""")!!.providerDisplayName)
        assertEquals("Workspace · @owner", profile("slack", """{"ok":true,"team_id":"T1","user_id":"U1","team":"Workspace","user":"owner"}""")!!.providerDisplayName)
        assertNull(profile("slack", """{"ok":false,"team_id":"T1","user_id":"U1","team":"Workspace","user":"owner"}"""))
        assertTrue(ProviderAccountProfiles.reads("unknown").isEmpty())
        assertEquals("Google Drive", ProviderAccountProfiles.displayName("googledrive"))
        assertEquals("YouTube", ProviderAccountProfiles.displayName("youtube"))
    }

    @Test fun denialAmbiguityMaskedValuesAndArbitraryNestedSecretsNeverBecomeNames() {
        val valid = envelope("""{"id":123,"login":"owner"}""")
        for (bad in listOf(JSONObject(valid.toString()).put("status", 403), JSONObject(valid.toString()).put("successful", false),
            JSONObject(valid.toString()).put("error", "Denied"), JSONObject(valid.toString()).put("resultType", "blocked"),
            JSONObject(valid.toString()).put("status", "200"))) assertNull(ProviderAccountProfiles.identity("github", bad))
        assertNull(profile("github", """{"credentials":{"login":"secret","id":"123"}}"""))
        assertNull(profile("github", """{"id":123,"login":"[redacted]"}"""))
        assertNull(profile("youtube", """{"items":[{"id":"one","snippet":{"title":"One"}},{"id":"two","snippet":{"title":"Two"}}]}"""))
        assertNull(profile("drive", """{"user":{"me":false,"emailAddress":"other@example.com"}}"""))
        assertEquals("", profile("google", """{"id":"123","name":"Owner","email":"unverified@example.com","verified_email":false}""")!!.emailAddress)
        assertNull(profile("github", JSONObject().put("id",123).put("login","a\u202Eb").toString()))
        assertNull(profile("github", JSONObject().put("id",123).put("login","owner").put("large","x".repeat(33_000)).toString()))
        assertNotNull(ProviderAccountProfiles.identity("github", JSONObject().put("successful",true).put("data", valid)))
    }

    private class Fixture : DirectComposioTransport {
        val bodies=mutableListOf<JSONObject>()
        var owner="owner"
        var toolkit="discord"
        var disabled=false
        var proxyStatus=200
        var profileStarted: CompletableDeferred<Unit>?=null
        var hold: CompletableDeferred<Unit>?=null
        override suspend fun request(path:String,apiKey:String,method:String,query:Map<String,String>,body:JSONObject?):JSONObject = when(path) {
            "/connected_accounts" -> {
                assertEquals("owner",query["user_ids"])
                JSONObject().put("items",JSONArray().put(JSONObject().put("id","acct-one").put("user_id",owner)
                    .put("toolkit",JSONObject().put("slug",toolkit)).put("status","ACTIVE").put("is_disabled",disabled)
                    .put("alias","discord_random-alias")))
            }
            "/tools/execute/proxy" -> {
                assertEquals("POST",method); bodies+=JSONObject(requireNotNull(body).toString())
                profileStarted?.complete(Unit); hold?.await()
                JSONObject().put("status",proxyStatus).put("data",JSONObject().put("id","123").put("username","owner"))
            }
            else -> error("Unexpected path $path")
        }
    }
    private suspend fun ready(fake:Fixture, clock:()->Long = {1000L})=DirectComposioClient(fake,clock).also {
        it.useCredentials(ComposioCredentials("fixture-project-key","owner"));it.refresh()
    }

    @Test fun exactOwnerBoundProxyReadCachesAndRefreshRetainsLabels()=runBlocking<Unit> {
        val fake=Fixture();var now=1000L;val client=ready(fake){now}
        assertEquals("@owner",client.resolveAccountIdentity("acct-one").displayLabel)
        val request=fake.bodies.single()
        assertEquals("acct-one",request.getString("connected_account_id"));assertEquals("owner",request.getString("user_id"))
        assertEquals("https://discord.com/api/v10/users/@me",request.getString("endpoint"));assertEquals("GET",request.getString("method"))
        assertFalse(request.toString().contains("fixture-project-key"))
        assertEquals("@owner",client.refresh().accounts.single().displayLabel)
        client.resolveAccountIdentity("acct-one");assertEquals(1,fake.bodies.size)
        now+=900_001;client.resolveAccountIdentity("acct-one");assertEquals(2,fake.bodies.size)
    }

    @Test fun foreignOrDisabledAccountNeverReachesProxy()=runBlocking<Unit> {
        for(foreign in listOf(true,false)) {
            val fake=Fixture();val client=ready(fake)
            if(foreign)fake.owner="other-owner" else fake.disabled=true
            try {client.resolveAccountIdentity("acct-one");fail("Must revalidate owner and status")}catch(_:ConnectionServiceException){}
            assertTrue(fake.bodies.isEmpty())
        }
    }

    @Test fun deniedProfileKeepsConnectionAndUsesCooldown()=runBlocking<Unit> {
        val fake=Fixture().apply {proxyStatus=403};val client=ready(fake)
        repeat(2){try {client.resolveAccountIdentity("acct-one");fail("Denied profile")}catch(_:ConnectionServiceException){}}
        assertEquals(1,fake.bodies.size);assertTrue(client.refresh().accounts.single().active)
        assertEquals("Connected account",client.refresh().accounts.single().displayLabel)
    }

    @Test fun changingCredentialsCancelsInflightIdentityWithoutReturningOldName()=runBlocking<Unit> {
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val fake=Fixture().apply {profileStarted=started;hold=release};val client=ready(fake)
        val lookup=async {client.resolveAccountIdentity("acct-one")};started.await()
        client.useCredentials(ComposioCredentials("new-key","new-owner"));release.complete(Unit)
        try{lookup.await();fail("Stale result must be cancelled")}catch(_:CancellationException){}
    }

    @Test fun timeoutCancelsReadAndDoesNotCacheCancellationAsFailure()=runBlocking<Unit> {
        val fake=Fixture().apply{hold=CompletableDeferred()};val client=ready(fake)
        assertNull(withTimeoutOrNull(30){client.resolveAccountIdentity("acct-one")})
        fake.hold=null
        assertEquals("@owner",client.resolveAccountIdentity("acct-one").displayLabel)
        assertEquals(2,fake.bodies.size)
    }
}
