package com.kai.agent.runtime
import com.kai.agent.data.*
import org.junit.Assert.*
import org.junit.Test
class PrivacyTransitionTest {
    @Test fun `new run is admitted while old projection outputs cannot survive Forget`() {
        val chat=Conversation(id="chat",title="T",characterId="kai",createdAt=1,updatedAt=1)
        val run=AgentRun(title="T",goal="Research",conversationId="chat",steps=listOf(RunStep(title="Findings",instructions="Research",output="private old output",status=StepStatus.COMPLETED)))
        val old=KaiState();val initial=old.copy(conversations=listOf(chat),runs=listOf(run))
        assertEquals(RunStatus.QUEUED,ActionContextPrivacy.apply(old,initial).runs.single().status)
        val forgotten=initial.copy(memoryGraph=initial.memoryGraph.copy(deletionEpoch=1))
        val invalid=ActionContextPrivacy.apply(initial,forgotten).runs.single()
        assertEquals(RunStatus.CANCELLED,invalid.status);assertEquals("",invalid.steps.single().output)
    }
    @Test fun `adding a connection does not erase an unrelated research archive`() {
        val chat=Conversation(id="chat",title="T",characterId="kai",createdAt=1,updatedAt=1)
        var state=KaiState(conversations=listOf(chat))
        val record=ExecutionRun("r","chat","m",RunOrigin.CHAT,GoalContract("Research"),privacyRevision=ExecutionRunRepository.privacy(state,"chat"),createdAt=1,archive="retained")
        state=state.copy(executions=listOf(record))
        val added=state.copy(settings=state.settings.copy(mcpServers=listOf(com.kai.agent.connections.mcp.McpServer("s","Service","https://example.org/mcp"))))
        assertEquals("retained",ExecutionRunRepository.invalidate(state,added).executions.single().archive)
    }
}
