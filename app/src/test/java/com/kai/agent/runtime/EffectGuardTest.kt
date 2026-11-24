package com.kai.agent.runtime
import com.kai.agent.data.*
import com.kai.agent.connections.ConnectionTool
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class EffectGuardTest {
    private val tool=ConnectionTool("WRITE","Write","","service","1",false,"{}")
    private val task=AgentTask(title="Write",conversationId="chat",createdAt=10,updatedAt=10,status=TaskStatus.UNKNOWN,submittedAt=11,
        intent=ActionIntent(toolName="WRITE",accountId="account",argumentsJson="""{"a":1,"b":2}"""))
    @Test fun `new turn cannot replay an uncertain write and safe reads remain available`() {
        assertTrue(runCatching { EffectGuard.validate(listOf(task),"chat",100,"account",tool,JSONObject()) }.isFailure)
        EffectGuard.validate(listOf(task),"chat",100,"account",tool.copy(readOnly=true),JSONObject())
        EffectGuard.validate(listOf(task.copy(intent=task.intent!!.copy(readOnly=true))),"chat",100,"account",tool,JSONObject())
    }
    @Test fun `canonical duplicate arguments cannot repeat an accepted effect in the same run`() {
        val accepted=task.copy(status=TaskStatus.ACCEPTED)
        assertTrue(runCatching { EffectGuard.validate(listOf(accepted),"chat",1,"account",tool,JSONObject("""{"b":2,"a":1}""")) }.isFailure)
        EffectGuard.validate(listOf(accepted),"chat",100,"account",tool,JSONObject("""{"b":2,"a":1}"""))
    }
}
