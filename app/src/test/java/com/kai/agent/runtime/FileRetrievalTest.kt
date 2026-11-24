package com.kai.agent.runtime
import com.kai.agent.projects.FileRetrieval
import com.kai.agent.agent.AgentDecision
import com.kai.agent.data.*
import org.junit.Assert.*
import org.junit.Test
class FileRetrievalTest {
    private val a=ProjectDocument(id="a",name="A",mimeType="text/plain",conversationId="chat",text="rareword "+"x".repeat(7000))
    private val secret=a.copy(id="private",conversationId="other",text="private content")
    @Test fun `file scope and deletion precede retrieval and full coverage is explicit`() {
        val state=KaiState(documents=listOf(a,secret))
        val search=FileRetrieval.execute(state,"","chat",AgentDecision.SearchFiles("rareword"))
        assertTrue(search.contains("PARTIAL_RETRIEVAL"));assertFalse(search.contains("private content"))
        val chunk=FileRetrieval.execute(state,"","chat",AgentDecision.ReadFile("a"))
        assertTrue(chunk.contains("PARTIAL_RANGE"));assertTrue(chunk.contains("nextOffset\":3000"))
        assertTrue(FileRetrieval.execute(state,"","chat",AgentDecision.ReadFile("private")).contains("NOT_FOUND_OR_NOT_PERMITTED"))
        assertTrue(FileRetrieval.execute(state.copy(deletedDocumentIds=listOf("a")),"","chat",AgentDecision.ReadFile("a")).contains("NOT_FOUND_OR_NOT_PERMITTED"))
    }
}
