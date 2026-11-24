package com.kai.agent.runtime

import com.kai.agent.agent.*
import com.kai.agent.connections.ConnectionTool
import com.kai.agent.data.*
import com.kai.agent.inference.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ActionReviewContractTest {
    private val schema="""{"type":"object","properties":{"title":{"type":"string"},"text":{"type":"string"}},"required":["title","text"],"additionalProperties":false}"""
    private val tool=ConnectionTool("CREATE_DOCUMENT","Create document","Create a document","fixture","v1",false,schema)
    private fun config(native: Boolean)=ProviderConfig(ProviderId.KOINOS,"https://fixture.example","fixture","model",nativeTools=native)

    @Test fun readinessCannotEndAnOutstandingActionButACompleteProposalCanReachReview() {
        for(native in listOf(false,true)) for(text in listOf(
            "I am ready to create your document. Please confirm this content before I proceed.",
            "The research is ready; approve and I will prepare the action.",
            "I can send that email. Shall I proceed?", "Done.","The results are listed above."
        )) {
            val harness=AgentHarness(native);val recovery=AgentResponseRecovery()
            val registered=harness.config(config(native),listOf("account" to tool))
            val raw=if(native) text else JSONObject().put("type","reply").put("text",text).toString()
            val error=runCatching { harness.parse(Completion(raw,"stop"),recovery,actionOutstanding=true) }.exceptionOrNull()
            assertTrue(error is AgentResponseException && error.actionOutstanding)
            val correction=requireNotNull(recovery.correction(error as AgentResponseException,native))
            assertTrue(correction.contains("PREPARE"));assertTrue(correction.contains("does not execute"))
            assertTrue(correction.contains("needs_input"));assertTrue(correction.contains("actual blocker"))
            if(native) assertFalse(correction.contains("Return exactly ONE complete JSON"))
            val args=JSONObject().put("title","Projects").put("text","Complete reviewed document content.")
            val response=if(native) Completion("","tool_calls",toolCalls=listOf(ToolCall("call",registered.tools.single { it.name.startsWith("action_") }.name,args.toString())))
                else Completion(JSONObject().put("type","action").put("accountId","account").put("tool",tool.id).put("arguments",args).toString(),"stop")
            val proposal=harness.parse(response,recovery,actionOutstanding=true) as AgentDecision.PrepareAction
            assertEquals(args.toString(),proposal.arguments.toString())
            val intent=ActionIntent(toolName=tool.id,accountId="account",ownerId="owner",connectionGeneration="generation",toolVersion=tool.version,toolSchemaJson=schema,argumentsJson=proposal.arguments.toString())
            val pending=AgentTask(title="Review",instructions="Create document",status=TaskStatus.AWAITING_APPROVAL,intent=intent)
            val settings=AppSettings(composioApiKey="fixture-key",composioUserId="owner",connectionGeneration="generation")
            assertTrue(runCatching { TaskRules.transition(pending,TaskStatus.RUNNING,settings) }.isFailure)
            val approved=TaskRules.approve(pending,settings,100)
            assertEquals(TaskStatus.RUNNING,TaskRules.transition(approved,TaskStatus.RUNNING,settings,at=101).status)
        }
    }
    @Test fun actualMissingDetailsAndExplicitIncompleteOutcomesRemainAvailableInBothProtocols() {
        for(native in listOf(false,true)) for((type,text) in listOf(
            "needs_input" to "Which recipient address should I use?",
            "needs_input" to "Which of the two connected accounts should own the document?",
            "partial" to "The directory was unavailable. I retained two source excerpts.",
            "blocked" to "There is no connected document account. Connect one to proceed."
        )) {
            val d=AgentHarness(native).parse(Completion(JSONObject().put("type",type).put("text",text).toString(),"stop"),AgentResponseRecovery(),actionOutstanding=true) as AgentDecision.Reply
            assertEquals(text,d.text)
            assertEquals(if(type=="needs_input") ExecutionState.WAITING_INPUT else ExecutionState.PARTIAL,GoalVerifier.outcome(d,true,false))
        }
    }
    @Test fun existingCorrectionLimitStillBoundsRepeatedReadinessReplies() {
        for(native in listOf(false,true)) {
            val h=AgentHarness(native);val r=AgentResponseRecovery()
            repeat(3) { i ->
                val e=runCatching { h.parse(Completion("Please approve before I prepare anything.","stop"),r,actionOutstanding=true) }.exceptionOrNull() as AgentResponseException
                if(i<2) assertNotNull(r.correction(e,native)) else assertNull(r.correction(e,native))
            }
        }
    }
    @Test fun ordinaryAnswersAndExplicitAnswerOnlyModeDoNotRequireAnAction() {
        for(native in listOf(false,true)) {
            // Callers disable outstanding-action validation for ordinary conversation, accepted work
            // and the explicit answer-only continuation. No new tool is inferred from plain text.
            val d=AgentHarness(native).parse(Completion("Here are the findings; no document was created.","stop"),AgentResponseRecovery(),actionOutstanding=false)
            assertTrue(d is AgentDecision.Reply)
        }
    }
    @Test fun intermediateResearchStepsAreNotMistakenForTheFinalExternalGoal() {
        for(origin in RunOrigin.entries) {
            val run=ExecutionRun("run","chat","msg",origin,GoalContract.infer("Research projects, create a Google doc and email its link"),privacyRevision=1,createdAt=1)
            assertFalse(GoalVerifier.stepGoal(run,"Research public project descriptions",false).requiresEffect)
            assertEquals(setOf("DOCUMENT"),GoalVerifier.stepGoal(run,"Create a Google document with the researched text",false).requiredEffects)
            assertEquals(setOf("EMAIL"),GoalVerifier.stepGoal(run,"Email the returned link to the recipient",false).requiredEffects)
            assertEquals(run.goal,GoalVerifier.stepGoal(run,"Summarize the result",true))
        }
    }
    @Test fun nativeCorrectionsRetainTheReasonAndReceiptInstructions() {
        val r=AgentResponseRecovery()
        val correction=requireNotNull(r.correction(AgentResponseException("Unfinished",outputLimit=true),native=true))
        assertTrue(correction.contains("16,000 UTF-8 bytes"));assertTrue(correction.contains("Reuse any existing document ID"))
        assertTrue(correction.contains("UNKNOWN"));assertTrue(correction.contains("native function"))
        assertFalse(correction.contains("Return exactly ONE complete JSON"))
    }
}
