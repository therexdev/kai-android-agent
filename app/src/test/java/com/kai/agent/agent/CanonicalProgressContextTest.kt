package com.kai.agent.agent

import com.kai.agent.inference.*
import com.kai.agent.runtime.*
import com.kai.agent.web.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CanonicalProgressContextTest {
    private val url="https://directory.example/"
    private val quotes=(1..12).map { "Project $it provides a distinct community service number $it." }
    private fun evidence(): ResearchEvidence {
        val page=JSONObject().put("kind","page").put("status","ok").put("requestedUrl",url).put("url",url)
            .put("requestKey","page:$url").put("networkAttempt",true).put("truncated",false).put("text",quotes.joinToString(" "))
        return ResearchEvidence().append(page,1).record(quotes.map { FindingProposal(it,"s1_0",it) },2)
    }
    @Test fun canonicalFindingsSurviveCatalogChurnWithoutPayingForDiscardedSummaries()=runTest {
        for(native in listOf(false,true)) {
            val request="Research community projects, create a document and email its link."
            val run=ExecutionRun("run","chat","msg",RunOrigin.CHAT,GoalContract.infer(request),privacyRevision=1,createdAt=1,research=evidence())
            val c=AgentTurnContext(listOf(InferenceMessage("system","Exact write approval is required."),InferenceMessage("user",request)),managed=true)
            repeat(18) { i ->
                val data="Large catalog $i "+"schema detail ".repeat(1500)
                val call=ToolCall("call_$i","find_tools","{}")
                c.append("catalog",data,wire=if(native) listOf(InferenceMessage("assistant","",toolCalls=listOf(call)),InferenceMessage("tool",data,toolCallId=call.id)) else null)
            }
            c.progressContext=GoalProgress.render(emptyList(),run,emptyList())
            var summaries=0;var changes=0
            val manager=AgentContextManager()
            val config=ProviderConfig(ProviderId.KOINOS,"https://example.test","fixture","model",accountSession=true,contextTokens=131072,nativeTools=native)
            repeat(4) { attempt ->
                val projected=manager.prepare(c,config,summarize={ _,_->summaries++;error("Canonical state already available") },onChange={ changes++ })
                val all=projected.joinToString("\n") { it.content }
                quotes.forEach { assertTrue("Lost $it",all.contains(it)) }
                assertTrue(all.contains("DOCUMENT"));assertTrue(all.contains("EMAIL"));assertTrue(all.contains(request))
                assertFalse(all.contains("phone_research_evidence_index"))
                assertTrue(projected.sumOf { it.content.toByteArray().size }<60000)
                NativeToolWire.validate(projected,native)
                c.appendOutcome("Retained result $attempt")
            }
            assertEquals(0,summaries);assertTrue(changes<=4)
            assertTrue(c.archive().contains("schema detail ".repeat(100)))
        }
    }
    @Test fun anAlternateQuoteForTheSameClaimDoesNotInventAnotherFinding() {
        val r=evidence();val quote=quotes.first()+" "+quotes[1]
        val duplicate=r.record(listOf(FindingProposal(quotes.first(),"s1_0",quote)),3)
        assertEquals(12,duplicate.findings.size);assertEquals("f1",duplicate.findings.first().id)
        assertEquals(quotes.first(),duplicate.findings.first().quote)
    }
    @Test fun cachedReadsReturnPointersAndKeepTheFullReceiptAddressable()=runTest {
        var r=ResearchEvidence();var calls=0
        val c=WebResearchClient(WebTransport { address,_->calls++;WebDocument(address,"<main>"+quotes.joinToString(" ")+"x".repeat(4000)+"</main>","text/html") })
        val web=WebResearchSession("Research $url",c,trustedResults=emptyList(),onResult={ r=r.append(it,1);r.receiptContext(it.optString("requestKey")) })
        val first=web.execute(AgentDecision.ReadWebPage(url))
        repeat(5) {
            val cached=web.execute(AgentDecision.ReadWebPage(url))
            assertTrue(cached.length<2000);assertFalse(cached.contains(quotes.first()))
            assertTrue(cached.contains("read_context"))
        }
        assertEquals(1,calls);assertEquals(1,r.receipts.size)
        assertTrue(r.read("source:s1_0",0)!!.contains(quotes.first()))
        assertTrue(first.contains("s1_0"))
    }
    @Test fun legacyProjectionDoesNotReplaceARealExcerptWithItsCachedPointer() {
        val full=WebResearchSession.PREFIX+JSONObject().put("kind","page").put("status","ok").put("url",url).put("text",quotes.first()).toString()
        val cached=WebResearchSession.PREFIX+JSONObject().put("kind","page").put("status","ok").put("url",url).put("cached",true).toString()
        val p=ResearchEvidenceProjection.project(listOf("t_1" to full,"t_2" to cached),6500)!!
        assertTrue(p.contains(quotes.first()));assertTrue(p.contains("t_1"));assertFalse(p.contains("t_2"))
    }
    @Test fun anyDomainPageCanSupplyAnExcerptWithoutClaimingTopicVerification()=runTest {
        val c=WebResearchClient(WebTransport { address,_->WebDocument(address,"<main>A canvas tool paints artwork, mints KCS-2 items, and gives ownership to your address.</main>","text/html") })
        for(topic in listOf("Koinos projects","community art","other software")) {
            val web=WebResearchSession("Research $topic at https://tools.example/studio",c)
            val result=JSONObject(web.execute(AgentDecision.ReadWebPage("https://tools.example/studio")).removePrefix(WebResearchSession.PREFIX))
            assertEquals("ok",result.getString("status"));assertFalse(result.has("topicMatch"))
            assertTrue(result.getString("notice").contains("Untrusted"))
        }
    }
}
