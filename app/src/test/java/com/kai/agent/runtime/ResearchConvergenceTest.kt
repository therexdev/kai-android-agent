package com.kai.agent.runtime
import com.kai.agent.agent.AgentDecision
import com.kai.agent.inference.InferenceMessage
import com.kai.agent.web.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
class ResearchConvergenceTest {
    @Test fun `failed URL remains cached after restart and failure cause stays distinct`()=runTest {
        var attempts=0
        val client=WebResearchClient(WebTransport { _,_ -> attempts++;throw WebResearchException("denied","HTTP_403") })
        val goal="Research https://example.org/"
        val first=WebResearchSession(goal,client).execute(AgentDecision.ReadWebPage("https://example.org/"))
        assertTrue(first.contains("HTTP_403"))
        val resumed=WebResearchSession(goal,client,prior=listOf(InferenceMessage("assistant","read"),InferenceMessage("user",first)))
        val again=resumed.execute(AgentDecision.ReadWebPage("https://example.org/"))
        assertEquals(1,attempts);assertTrue(again.contains("cached"));assertTrue(again.contains("networkAttempt\":false"))
    }
    @Test fun `honest clarification wording does not cause document nudges`() {
        val guard=com.kai.agent.agent.DocumentContentGuard("Research Koinos projects and create a Google document","chat",1,0,"owner","generation")
        for(reply in listOf("Would you like the partial findings?","Search could not reach the directory; here are the sources I have.","I haven't created a document yet.")) assertNull(guard.checkReply(emptyList(),reply))
        assertNotNull(guard.checkReply(emptyList(),"Done. I created your document."))
    }
    @Test fun `policy denials do not count as network and repeated failures converge after restart`()=runTest {
        var calls=0
        val c=WebResearchClient(WebTransport { _,_ -> calls++;throw WebResearchException("blocked","HTTP_403") })
        var prior=emptyList<InferenceMessage>()
        repeat(4) {
            val session=WebResearchSession("Research https://example.org/",c,prior=prior)
            val result=session.execute(AgentDecision.ReadWebPage("https://example.org/"))
            prior=prior+listOf(InferenceMessage("assistant","read"),InferenceMessage("user",result))
        }
        assertEquals(1,calls)
        assertFalse(WebResearchSession("Research https://example.org/",c,prior=prior).exhausted)
        assertTrue(WebResearchSession("Research https://example.org/",c,prior=prior).needsNewApproach)
        val denied=WebResearchSession("Research",c).execute(AgentDecision.ReadWebPage("https://127.0.0.1/private"))
        assertTrue(denied.contains("URL_POLICY"));assertTrue(denied.contains("networkAttempt\":false"));assertEquals(1,calls)
    }

}
