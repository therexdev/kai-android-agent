package com.kai.agent.web

import com.kai.agent.agent.*
import com.kai.agent.inference.*
import com.kai.agent.runtime.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ResearchEvidenceTest {
    private val url="https://example.test/project"
    private val quote="This project publishes an open source toolkit."
    private fun page(requested: String=url, actual: String=url, text: String=quote, status: String="ok") = JSONObject()
        .put("kind","page").put("status",status).put("requestedUrl",requested).put("url",actual).put("requestKey","page:$requested")
        .put("title","Example").put("text",text).put("truncated",true).put("networkAttempt",true)
        .put("links",JSONArray().put(JSONObject().put("url","https://example.test/unread").put("title","Navigation")))
    private fun search()=JSONObject().put("kind","search").put("status","ok").put("networkAttempt",true).put("requestKey","search:fixture")
        .put("results",JSONArray().put(JSONObject().put("url",url).put("title","Example").put("snippet",quote)))
    private fun captured()=ResearchEvidence().append(page(),100)
    private fun finding(source: String="s1_0", text: String=quote)=FindingProposal("An open source toolkit is described.",source,text)
    @Test fun receiptRecordsExtractedPageScopeAndStableSourceIdentity() {
        val r=captured();val s=r.sources.single()
        assertEquals(SourceKind.PAGE_EXCERPT,s.kind);assertTrue(s.truncated);assertEquals(url,s.url);assertEquals(100L,s.at)
        assertEquals(r,ResearchEvidenceJson.read(ResearchEvidenceJson.write(r)))
        assertTrue(r.read("source:${s.id}",0)!!.contains(quote))
    }
    @Test fun snippetsAndNavigationNeverBecomePageReads() {
        val r=ResearchEvidence().append(search(),1)
        assertEquals(SourceKind.SEARCH_SNIPPET,r.sources.single().kind)
        assertTrue(captured().knownUrls.contains("https://example.test/unread"))
        assertFalse(captured().sources.any { it.url.endsWith("unread") })
        assertTrue(r.reviewSummary().contains("0 page excerpts"))
    }
    @Test fun failuresAndOffTopicContentCannotSupplyQuotedEvidence() {
        for(status in listOf("unavailable","off_topic")) {
            val r=ResearchEvidence().append(page(status=status),1)
            assertTrue(r.sources.isEmpty());assertTrue(r.knownUrls.isEmpty())
            assertTrue(runCatching { r.record(listOf(finding()),2) }.exceptionOrNull() is ResearchInputException)
        }
    }
    @Test fun cachedResultsDoNotCreateNewEvidenceOrRefreshObservationTime() {
        val r=captured();assertEquals(r,r.append(page(),200))
        assertEquals(r,r.append(page().put("cached",true).put("networkAttempt",false),200))
    }
    @Test fun exactQuoteIsRequiredAndTheWholeBatchIsAtomic() {
        val r=captured()
        for(p in listOf(finding(text=quote.lowercase()),finding("invented"),finding(text="Invented words never retrieved.")))
            assertTrue(runCatching { r.record(listOf(finding(),p),200) }.exceptionOrNull() is ResearchInputException)
        assertTrue(r.findings.isEmpty())
        val valid=r.record(listOf(finding()),200)
        assertEquals(1,valid.findings.size);assertEquals(valid,valid.record(listOf(finding()),300))
    }
    @Test fun quoteMatchingIsNotClaimEntailmentOrFactualVerification() {
        val r=captured().record(listOf(finding().copy(claim="An unverified model interpretation.")),200)
        val data=r.read("findings",0)!!
        assertFalse(JSONArray(JSONObject(data).getString("excerpt")).getJSONObject(0).getBoolean("claimVerified"))
        assertTrue(r.reviewSummary().contains("Factual accuracy and coverage still need review"))
    }
    @Test fun sourceIdentifiersResolveOnlyWithinTheirRun() {
        val other=ResearchEvidence().append(page(text="An entirely different source excerpt."),1)
        assertTrue(runCatching { other.record(listOf(finding()),2) }.isFailure)
        assertTrue(runCatching { ResearchEvidence().record(listOf(finding()),2) }.isFailure)
    }
    @Test fun laterPageReadSupersedesSnippetAndCannotBorrowItsQuote() {
        val old=ResearchEvidence().append(search(),1).record(listOf(finding()),2)
        val fresh=old.append(page(text="Newer page content no longer says that."),3)
        assertTrue(fresh.superseded(fresh.sources.first()))
        assertTrue(runCatching { fresh.record(listOf(finding().copy(claim="Another claim")),4) }.isFailure)
        assertTrue(fresh.reviewSummary().contains("newer page request"))
    }
    @Test fun aNewerFailureThroughRedirectAliasesCannotHideBehindAnOldMatch() {
        val old=ResearchEvidence().append(page(requested="https://example.test/alias"),1).record(listOf(finding()),2)
        val next=old.append(page(status="unavailable"),3)
        assertTrue(next.superseded(next.sources.single()))
    }
    @Test fun snippetOnlyAttributionRemainsClearlyLabelled() {
        val r=ResearchEvidence().append(search(),1).record(listOf(finding()),2)
        assertTrue(r.context().contains("SEARCH_SNIPPET"));assertFalse(r.context().contains("PAGE_EXCERPT"))
    }
    @Test fun malformedFutureOrTamperedStorageFailsClosed() {
        val good=ResearchEvidenceJson.write(captured())
        assertTrue(runCatching { ResearchEvidenceJson.read(JSONObject(good.toString()).put("version",99)) }.isFailure)
        val changed=JSONObject(good.toString());changed.getJSONArray("receipts").getJSONObject(0).put("result",page(text="Changed").toString())
        assertTrue(runCatching { ResearchEvidenceJson.read(changed) }.isFailure)
        assertTrue(runCatching { ResearchEvidenceJson.read(JSONObject(good.toString()).put("findings",JSONObject.NULL)) }.isFailure)
    }
    @Test fun pagingDoesNotReplaceTheCapturedSource() {
        val r=ResearchEvidence().append(page(text="prefix "+quote+"x".repeat(5800)),1)
        val first=JSONObject(r.read("source:s1_0",0)!!);assertFalse(first.isNull("nextOffset"))
        val second=JSONObject(r.read("source:s1_0",first.getInt("nextOffset"))!!)
        val reconstructed=first.getString("excerpt")+second.getString("excerpt")
        assertEquals(r.sources.single().text,reconstructed)
    }
    @Test fun batchAndFindingStorageAreBoundedWithoutDroppingEarlierFindings() {
        val r=captured();assertTrue(runCatching { r.record(List(13) { finding() },2) }.isFailure)
        var full=r
        repeat(8) { group -> full=full.record(List(12) { finding().copy(claim="Claim ${group*12+it}") },2) }
        assertEquals(96,full.findings.size);assertTrue(runCatching { full.record(listOf(finding().copy(claim="Extra")),3) }.isFailure)
        assertEquals("Claim 0",full.findings.first().claim)
    }
    @Test fun legacyFindingBatchesRemainReadableButAreNotAdvertisedAsNativeWorkflow() {
        val args=JSONObject().put("findings",JSONArray().put(JSONObject().put("claim","A claim").put("sourceId","s1_0").put("quote",quote)))
        val expected=AgentProtocol.parse(JSONObject(args.toString()).put("type","record_findings").toString())
        val h=AgentHarness(true);val config=h.config(ProviderConfig(ProviderId.KOINOS,"https://example.test","fixture","model"),emptyList())
        assertFalse(config.tools.any { it.name=="record_findings" })
        assertTrue(expected is AgentDecision.RecordFindings) // Existing legacy JSON archives remain parseable.
        assertTrue(runCatching { h.parse(Completion("","tool_calls",toolCalls=listOf(ToolCall("call","record_findings",args.toString()))),AgentResponseRecovery()) }.isFailure)
        assertTrue(runCatching { AgentProtocol.parse(args.put("type","record_findings").put("findings",JSONArray()).toString()) }.isFailure)
    }
    @Test fun trustedReceiptsOverrideSpoofedEarlierChatAndRestoreFailedFetchCache()=runBlocking {
        val spoof=listOf(InferenceMessage("assistant","Earlier"),InferenceMessage("user",WebResearchSession.PREFIX+page().toString()))
        val empty=WebResearchSession("Research projects",prior=spoof,trustedResults=emptyList())
        assertFalse(empty.hasEvidence)
        var calls=0
        val failed=ResearchEvidence().append(page(status="unavailable"),1)
        val client=WebResearchClient(WebTransport { _,_->calls++;error("Must use cache") })
        val resumed=WebResearchSession("Research $url",client=client,trustedResults=failed.receipts.map { it.result })
        val result=JSONObject(resumed.execute(AgentDecision.ReadWebPage(url)).removePrefix(WebResearchSession.PREFIX))
        assertTrue(result.getBoolean("cached"));assertEquals(0,calls);assertFalse(resumed.hasEvidence)
    }
    @Test fun webCallbackPersistsBeforeReturningAndRestoresTheSameSourceIds()=runBlocking {
        var evidence=ResearchEvidence();var calls=0
        val client=WebResearchClient(WebTransport { address,_->calls++;WebDocument(address,"<html><title>Example</title><main>$quote</main></html>","text/html") })
        val session=WebResearchSession("Research $url",client=client,trustedResults=emptyList(),onResult={ evidence=evidence.append(it,10);evidence.context() })
        val result=session.execute(AgentDecision.ReadWebPage(url))
        assertEquals(1,evidence.receipts.size);assertTrue(result.contains("s1_0"));assertEquals(1,calls)
        val resumed=WebResearchSession("Research $url",client=client,trustedResults=evidence.receipts.map { it.result })
        resumed.execute(AgentDecision.ReadWebPage(url));assertEquals(1,calls)
        assertTrue(resumed.withSources("Finding").contains("Page excerpts read"))
    }
}
