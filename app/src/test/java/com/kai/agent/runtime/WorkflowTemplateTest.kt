package com.kai.agent.runtime
import com.kai.agent.extensions.*
import com.kai.agent.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class WorkflowTemplateTest {
    @Test fun `import validates version and dependencies without granting authority`() {
        val example=WorkflowTemplate.example();val parsed=WorkflowTemplate.parse(example.json().toString())
        assertEquals(listOf("research","draft","review"),parsed.ordered().map { it.id })
        assertTrue(parsed.steps("ecosystem").first().instructions.contains("ecosystem"))
        for(key in listOf("credentials","approval","shell","apiKey")) {
            val invalid=example.json().put(key,"forbidden")
            assertTrue(runCatching { WorkflowTemplate.parse(invalid.toString()) }.isFailure)
        }
        val cyclic=example.copy(nodes=example.nodes.map { if(it.id=="research") it.copy(dependsOn=listOf("review")) else it })
        assertTrue(runCatching { WorkflowTemplate.parse(cyclic.json().toString()) }.isFailure)
    }
    @Test fun `second review sends one final revision and never schedules a third review`() {
        val template=WorkflowTemplate.example();val steps=template.steps("topic")
        var run=AgentRun(title="Draft",goal="Write draft",conversationId="chat",steps=steps.map { if(it.nodeId!="review") it.copy(status=StepStatus.COMPLETED,output="retained draft") else it })
        val review=run.steps.last();val feedback="""{"verdict":"REVISE","changes":"Add supported source links"}"""
        run=requireNotNull(QualityLoop.revise(run,review,feedback))
        assertEquals(StepStatus.PENDING,run.steps[1].status);assertEquals(1,run.steps[2].revisions)
        assertEquals("Add supported source links",run.steps[1].revisionFeedback)
        assertTrue(run.steps[1].messages.isEmpty())
        run=run.copy(steps=run.steps.map { if(it.nodeId=="draft") it.copy(status=StepStatus.COMPLETED) else it })
        run=requireNotNull(QualityLoop.revise(run,run.steps[2],feedback))
        assertEquals(StepStatus.PENDING,run.steps[1].status);assertEquals(StepStatus.SKIPPED,run.steps[2].status)
        assertEquals(2,run.steps[2].revisions)
    }
    @Test fun `workflow and model choices round trip while runtime credentials remain separate`() {
        val steps=WorkflowTemplate.example().steps("topic").map { it.copy(modelId="chosen-model") }
        val state=KaiState(runs=listOf(AgentRun(title="Draft",goal="Draft",conversationId="chat",steps=steps)),templates=listOf(WorkflowTemplate.example().json().toString()))
        val restored=KaiJson.decode(KaiJson.encode(state))
        assertEquals(steps,restored.runs.single().steps);assertEquals(state.templates,restored.templates)
    }
}
