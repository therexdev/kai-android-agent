package com.kai.agent.web

import com.kai.agent.agent.AgentDecision
import com.kai.agent.agent.AgentProtocol
import com.kai.agent.inference.InferenceMessage
import java.net.InetAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WebResearchTest {
    private val html = """<div class="result"><a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2Ffact">Real title</a><div class="result__snippet">A public fact.</div></div>"""
    private fun client(block: suspend (String,Map<String,String>) -> WebDocument) = WebResearchClient(WebTransport(block))
    @Test fun capturedLivePublicSearchUsesTheProductionParser() {
        val path=System.getProperty("kai.liveWebFixture") ?: return
        val hits=WebResearchClient.parseDuckDuckGo(java.io.File(path).readText(),"https://html.duckduckgo.com/html/")
        assertTrue("Live search returned no parsed public HTTPS results",hits.size>=3)
        assertTrue(hits.any { it.url.contains("developer.android.com") })
        assertTrue(hits.all { it.title.isNotBlank() })
    }
    @Test fun searchProtocolNeedsNoConnectedAccount() {
        assertEquals(AgentDecision.WebSearch("current facts"),AgentProtocol.parse("""{"type":"web_search","query":"current facts"}"""))
        assertEquals(AgentDecision.ReadWebPage("https://example.org/fact"),AgentProtocol.parse("""{"type":"read_web_page","url":"https://example.org/fact"}"""))
        assertTrue(runCatching { AgentProtocol.parse("""{"type":"web_search","query":""}""") }.isFailure)
    }
    @Test fun suppliedDocumentTextIsRedirectedWithoutSendingAWebQuery() = runBlocking {
        val request = "make a Google doc called test and put test doc in the page content and send me the link"
        var calls = 0
        val session = WebResearchSession(request, client { _,_ -> calls++; error("No web access expected") })
        assertFalse(session.requiresSearch)
        assertNotNull(session.correction(AgentDecision.WebSearch("Google docs API")))
        assertTrue(runCatching { session.execute(AgentDecision.WebSearch("test doc")) }.isFailure)
        assertEquals(0, calls)
        assertNull(session.correction(AgentDecision.FindTools("docs-account", "create document")))
        val resumed = WebResearchSession(request, prior = listOf(InferenceMessage("user", WebResearchSession.DIRECT_CONTENT_CORRECTION)))
        assertTrue(runCatching { resumed.correction(AgentDecision.WebSearch("test")) }.isFailure)
    }
    @Test fun literalDocumentPolicyLeavesOpenEndedResearchAndOtherRequestsAvailable() {
        assertTrue(WebResearchSession.suppliedDocumentContent("Create a Google doc titled \"test\" containing \"test doc\" and return the link"))
        assertTrue(WebResearchSession.suppliedDocumentContent("Create a doc titled \"research\" containing \"search the web\""))
        listOf("Create a document about solar panels", "Research solar panels and make a document",
            "make a Google doc called test and put the latest news in the page content and send me the link",
            "make a Google doc called test and put information about cats in the document",
            "make a Google doc called test and put my name in the document",
            "make a Google doc called test and put test doc in the page content and then research cats",
            "Search my docs for test").forEach { assertFalse(it, WebResearchSession.suppliedDocumentContent(it)) }
        assertTrue(WebResearchSession.explicitResearch("Research solar panels and make a document"))
    }

    @Test fun defaultSearchReturnsRealAttributedUrlsAndSnippets() = runBlocking {
        val c=client { url,headers -> assertTrue(headers.isEmpty());WebDocument(url,html,"text/html") }
        val result=c.search("public facts",smallContext=true)
        assertEquals("ok",result.getString("status"));assertEquals("DuckDuckGo",result.getString("engine"))
        val hit=result.getJSONArray("results").getJSONObject(0)
        assertEquals("https://example.org/fact",hit.getString("url"));assertEquals("A public fact.",hit.getString("snippet"))
    }
    @Test fun challengeUsesFallbackAndNeverClaimsAnEmptySearchSucceeded() = runBlocking {
        val urls=mutableListOf<String>()
        val c=client { url,_ -> urls+=url; WebDocument(url,if(url.contains("html.duck")) "<form id='challenge-form'>Solve challenge</form>" else "<a class='result-link' href='https://example.org/'>Result</a>","text/html") }
        val result=c.search("facts")
        assertTrue(result.getBoolean("fallbackUsed"));assertEquals(2,urls.size)
        val unavailable=client { url,_ -> WebDocument(url,"<h1>No results</h1>","text/html") }.search("no matching facts")
        assertEquals("unavailable",unavailable.getString("status"))
    }
    @Test fun optionalKeyOnlyGoesToBraveAndFallbackIsKeyless() = runBlocking {
        val c=client { url,headers ->
            if(url.startsWith("https://api.search.brave.com/")) { assertEquals("fixture-secret",headers["X-Subscription-Token"]);throw WebResearchException("rate limited") }
            assertTrue(headers.isEmpty());WebDocument(url,html,"text/html")
        }
        val result=c.search("facts","fixture-secret")
        assertEquals("DuckDuckGo",result.getString("engine"));assertFalse(result.toString().contains("fixture-secret"))
    }
    @Test fun htmlCleanupRemovesActiveAndNavigationContentAndBoundsExcerpt() = runBlocking {
        val c=client { url,headers -> assertTrue(headers.isEmpty());WebDocument(url,"<title>Fact</title><nav>Sign out</nav><script>steal()</script><article>"+"Readable fact. ".repeat(500)+"</article>","text/html") }
        val result=c.read("https://example.org/fact",true)
        assertEquals(1500,result.getString("text").length);assertTrue(result.getBoolean("truncated"))
        assertFalse(result.getString("text").contains("steal"));assertFalse(result.getString("text").contains("Sign out"))
    }
    @Test fun privateAndCredentialBearingUrlsAreRejectedBeforeNetwork() {
        listOf("http://example.org","https://localhost/","https://127.0.0.1/","https://10.0.0.1/","https://[::1]/","https://[::ffff:127.0.0.1]/","https://secret@example.org/","https://example.org:8443/","https://router.local/").forEach {
            assertTrue(it,runCatching { PublicWebPolicy.url(it) }.isFailure)
        }
        listOf("0.0.0.0","100.64.0.1","169.254.169.254","172.16.0.1","192.168.2.1","198.18.0.1","224.0.0.1","fc00::1","2001:db8::1","2002:7f00:1::").forEach {
            assertFalse(it,PublicWebPolicy.isPublic(InetAddress.getByName(it)))
        }
        assertTrue(PublicWebPolicy.isPublic(InetAddress.getByName("1.1.1.1")))
        assertTrue(PublicWebPolicy.isPublic(InetAddress.getByName("2606:4700:4700::1111")))
    }
    @Test fun missingSearchGetsOneCorrectionBeforeAnyResearchAction() {
        val session=WebResearchSession("Search for information about solar panels and email it to me")
        assertTrue(session.awaitingSearch)
        assertNotNull(session.correction(AgentDecision.Reply("I have no web-browsing function")))
        assertTrue(runCatching { session.correction(AgentDecision.PrepareAction("a","SEND",JSONObject(),"send research")) }.isFailure)
        assertFalse(WebResearchSession.explicitResearch("Search my inbox for emails from Mike"))
        assertFalse(WebResearchSession.explicitResearch("Do not search the web"))
    }
    @Test fun sourcesGatePageReadsAndPersistAcrossTaskResume() = runBlocking {
        var calls=0
        val c=client { url,_ -> calls++;WebDocument(url,html,"text/html") }
        val session=WebResearchSession("Search the web for facts",c)
        val denied=session.execute(AgentDecision.ReadWebPage("https://unrequested.example/"))
        assertTrue(denied.contains("URL_POLICY"));assertEquals(0,calls)
        val receipt=session.execute(AgentDecision.WebSearch("facts"))
        val resumed=WebResearchSession("Search the web for facts",c,prior=listOf(InferenceMessage("assistant","{\"type\":\"web_search\",\"query\":\"facts\"}"),InferenceMessage("user",receipt)))
        assertFalse(resumed.awaitingSearch);assertTrue(resumed.hasEvidence)
        assertTrue(resumed.withSources("An answer").contains("https://example.org/fact"))
        resumed.execute(AgentDecision.ReadWebPage("https://example.org/fact"));assertEquals(2,calls)
    }
    @Test fun failedResearchCannotBecomeAClaimOfSuccessOrOutgoingAction() = runBlocking {
        val c=client { _,_ -> throw WebResearchException("offline") }
        val session=WebResearchSession("Search the web and email facts",c)
        session.execute(AgentDecision.WebSearch("facts"))
        assertFalse(session.withSources("I successfully researched this").contains("successfully"))
        assertTrue(runCatching { session.correction(AgentDecision.PrepareAction("a","SEND",JSONObject(),"send")) }.isFailure)
    }
    @Test fun cancellationStopsSearchWithoutFallback() = runBlocking {
        var calls=0
        val c=client { _,_ -> calls++;throw CancellationException("cancelled") }
        assertTrue(runCatching { c.search("facts") }.exceptionOrNull() is CancellationException);assertEquals(1,calls)
    }
    @Test fun approvedActionContinuationCanReportItsReceiptWithoutRepeatingResearch() {
        val session=WebResearchSession("Search the web and email it",requireFreshResearch=false)
        assertNull(session.correction(AgentDecision.Reply("The reviewed email was submitted.")))
    }
    @Test fun savedTaskCorrectionDoesNotLoopForeverAfterResume() {
        val prior=listOf(InferenceMessage("user","Phone runtime: this request needs public web research. Use web_search."))
        val resumed=WebResearchSession("Search the web for facts",prior=prior)
        assertTrue(runCatching { resumed.correction(AgentDecision.Reply("I cannot browse")) }.isFailure)
    }
    @Test fun searchAttemptsAreBoundedEvenAfterFailures() = runBlocking {
        var calls=0
        val session=WebResearchSession("Search online",client { _,_ -> calls++;throw WebResearchException("offline") })
        repeat(6){session.execute(AgentDecision.WebSearch("facts"))};assertEquals(2,calls) // One search action; two configured search-provider attempts.
    }

    @Test fun ecosystemLinksRemainNavigableWithoutPretendingTheirPagesWereRead() = runBlocking {
        val visited=mutableListOf<String>()
        val c=client { url,_ -> visited+=url;WebDocument(url,"<html><title>Blockchain directory</title><nav><a href='/ecosystem'>Ecosystem</a></nav><main>Blockchain projects <a href='https://project.example/'>Project</a><a href='http://127.0.0.1/private'>Unsafe</a></main></html>","text/html") }
        val session=WebResearchSession("Research Koinos blockchain projects https://koinos.io/",c)
        val receipt=session.execute(AgentDecision.ReadWebPage("https://koinos.io/"))
        val data=JSONObject(receipt.removePrefix(WebResearchSession.PREFIX))
        assertEquals(2,data.getJSONArray("links").length())
        assertFalse(session.withSources("Directory evidence").contains("project.example"))
        session.execute(AgentDecision.ReadWebPage("https://project.example/"))
        assertEquals(listOf("https://koinos.io/","https://project.example/"),visited)
        val resumed=WebResearchSession("Research Koinos projects",c,prior=listOf(InferenceMessage("assistant",""),InferenceMessage("tool",receipt,toolCallId="call_1")))
        resumed.execute(AgentDecision.ReadWebPage("https://koinos.io/ecosystem"))
        assertEquals(3,visited.size)
    }
    @Test fun ambiguousProjectSearchKeepsBlockchainScopeAndRejectsUnrelatedPages() = runBlocking {
        var searchUrl=""
        val c=client { url,_ -> searchUrl=url;WebDocument(url,html,"text/html") }
        val session=WebResearchSession("Look up Koinos projects",c)
        session.execute(AgentDecision.WebSearch("Koinos projects"))
        assertTrue(searchUrl.contains("blockchain"))
        val result=session.execute(AgentDecision.ReadWebPage("https://example.org/fact"))
        assertTrue(result.contains("off_topic"))
        assertFalse(session.hasEvidence)
    }

    @Test fun documentReferencesUseCanonicalRetrievedUrlsAndRejectInventedLinks() = runBlocking {
        val c=client { url,_ -> WebDocument(url,if(url.contains("duckduckgo")) html else "<html><title>Blockchain projects</title><main>Koinos blockchain <a href='https://project.example/'>Project</a></main></html>","text/html") }
        val session=WebResearchSession("Look up Koinos projects",c)
        session.execute(AgentDecision.WebSearch("Koinos projects"))
        session.execute(AgentDecision.ReadWebPage("https://example.org/fact"))
        fun action(url: String)=AgentDecision.PrepareAction("docs","GOOGLEDOCS_CREATE_DOCUMENT",JSONObject().put("text","Project: $url — source-backed blockchain project."),"")
        assertNull(session.correction(action("https://project.example")))
        assertNotNull(session.correction(action("https://invented.example/")))
    }
}
