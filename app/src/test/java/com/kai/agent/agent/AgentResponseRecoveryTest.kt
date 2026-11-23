package com.kai.agent.agent

import com.kai.agent.inference.Completion
import com.kai.agent.inference.InferenceMessage
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class AgentResponseRecoveryTest {
    private val action = """{"type":"action","accountId":"a","tool":"CREATE","arguments":{"title":"test","text":"test doc"}}"""

    @Test fun malformedOrMultipleResponsesCannotSelectAnAction() {
        for (raw in listOf(action.dropLast(1), "$action\n$action", "$action\nDone!", "```json\n$action", "```json\n$action\n```\n$action", "")) {
            assertTrue(runCatching { AgentResponseRecovery().parse(Completion(raw, "stop")) }.exceptionOrNull() is AgentResponseException)
        }
    }

    @Test fun oneCompleteFenceIsAcceptedWithoutChangingArguments() {
        val parsed = AgentProtocol.parse("```json\n$action\n```") as AgentDecision.PrepareAction
        assertEquals("a", parsed.accountId)
        assertEquals("test doc", parsed.arguments.getString("text"))
    }

    @Test fun invalidFieldsAreRecoverableButNeverInvented() {
        for (raw in listOf("""{"type":"action","accountId":"a","tool":"CREATE","arguments":"{}"}""",
            """{"type":"reply"}""", """{"type":"find_tools","accountId":""}""", """{"type":"other"}""")) {
            assertTrue(runCatching { AgentResponseRecovery().parse(Completion(raw, "stop")) }.exceptionOrNull() is AgentResponseException)
        }
    }

    @Test fun outputLimitCannotExecuteEvenSyntacticallyCompleteAction() {
        assertTrue(runCatching { AgentResponseRecovery().parse(Completion(action, "length")) }.exceptionOrNull() is AgentResponseException)
    }

    @Test fun filteredOrOversizedResponsesDoNotEnterRecovery() {
        for (completion in listOf(Completion(action, "content_filter"), Completion("x".repeat(AgentProtocol.MAX_RESPONSE + 1), "stop"))) {
            val error = runCatching { AgentResponseRecovery().parse(completion) }.exceptionOrNull()
            assertNotNull(error); assertFalse(error is AgentResponseException)
        }
    }

    @Test fun correctionIsBoundedAndRetainsOriginalRequestSchemaAndUnknownReceipt() {
        val original = listOf(InferenceMessage("system", "Receipt: UNKNOWN; never repeat. Account a only."),
            InferenceMessage("user", "make a Google doc called test and put test doc in the page content and send me the link"))
        val context = AgentTurnContext(original)
        context.append("discovery", "Exact CREATE schema: title and text are required.")
        val recovery = AgentResponseRecovery()
        repeat(2) { context.append(AgentResponseRecovery.REJECTED, requireNotNull(recovery.correction())) }
        assertNull(recovery.correction())
        assertEquals(original, context.messages().take(2))
        assertTrue(context.messages().any { it.content.contains("Exact CREATE schema") })
        assertTrue(context.messages().last().content.contains("not new action authorization"))
        assertTrue(recovery.parse(Completion(action, "stop")) is AgentDecision.PrepareAction)
    }

    @Test fun actionSurroundedByProseRequiresFreshProtocolAndCannotBecomeApprovalText() {
        val recovery = AgentResponseRecovery()
        for (raw in listOf("Okay, I'll update that document.\n\n```json\n$action\n```\nDoes that sound right?",
            "Preparing this: $action", "[$action]")) {
            assertTrue(runCatching { recovery.parse(Completion(raw, "stop")) }.exceptionOrNull() is AgentResponseException)
            assertNull(StreamingReply.read(raw))
        }
        assertNotNull(recovery.correction())
        assertTrue(recovery.parse(Completion(action, "stop")) is AgentDecision.PrepareAction)
    }

    @Test fun ordinaryTextAndExplicitCodeExplanationRepliesRemainReplies() {
        assertEquals(AgentDecision.Reply("Hello there"), AgentProtocol.parse("Hello there"))
        val text = "An action has this shape: $action"
        val raw = JSONObject().put("type", "reply").put("text", text).toString()
        assertEquals(AgentDecision.Reply(text), AgentProtocol.parse(raw))
    }
}
