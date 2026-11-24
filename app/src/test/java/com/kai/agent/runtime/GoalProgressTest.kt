package com.kai.agent.runtime

import com.kai.agent.agent.AgentTurnContext
import com.kai.agent.data.*
import com.kai.agent.inference.InferenceMessage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GoalProgressTest {
    private val request="Research projects, put the findings in a Google doc, and email the link to reader@example.test"
    private val run=ExecutionRun("run","chat","message",RunOrigin.CHAT,GoalContract.infer(request),privacyRevision=1,createdAt=100)
    private val doc=AgentTask(id="doc",title="Create document",conversationId="chat",createdAt=101,submittedAt=102,status=TaskStatus.ACCEPTED,
        intent=ActionIntent(id="intent",toolName="GOOGLEDOCS_CREATE_DOCUMENT",accountId="docs-account",executionId="run",
            argumentsJson=JSONObject().put("title","Projects").put("text","Researched body with source links").toString()),
        receipts=listOf(ActionReceipt("intent",status="ACCEPTED",summary="accepted",url="https://docs.google.com/document/d/fixture/edit")))
    private fun data(text: String)=JSONObject(text.substringAfter('\n'))

    @Test fun acceptedDocumentDoesNotLoseRemainingEmailAfterApproval() {
        val progress=GoalProgress.render(listOf(doc),run,listOf(ToolReference("mail-account","GMAIL_SEND_EMAIL","v1")))
        val d=data(progress)
        assertEquals(listOf("EMAIL"),d.getJSONArray("missingAcceptedEffects").let { (0 until it.length()).map(it::getString) })
        assertTrue(d.getJSONArray("acceptedOperations").getJSONObject(0).getBoolean("bodyTextIncluded"))
        assertTrue(progress.contains("/fixture/edit"));assertTrue(progress.contains("GMAIL_SEND_EMAIL"))
        assertFalse(GoalVerifier.acceptedEffect(listOf(doc),"chat",100,run))
    }
    @Test fun unknownUnsubmittedAndOtherRunOperationsCannotSatisfyProgress() {
        for(t in listOf(doc.copy(status=TaskStatus.UNKNOWN),doc.copy(submittedAt=null),doc.copy(intent=doc.intent!!.copy(executionId="other")))) {
            val d=data(GoalProgress.render(listOf(t),run,emptyList()))
            assertEquals(0,d.getJSONArray("acceptedOperations").length())
            assertEquals(2,d.getJSONArray("missingAcceptedEffects").length())
        }
    }
    @Test fun titleOnlyDocumentDoesNotClaimBodyWasSubmitted() {
        val t=doc.copy(intent=doc.intent!!.copy(argumentsJson="{\"title\":\"Projects\"}"))
        assertFalse(data(GoalProgress.render(listOf(t),run,emptyList())).getJSONArray("acceptedOperations").getJSONObject(0).getBoolean("bodyTextIncluded"))
    }
    @Test fun allCompactionLevelsRetainCurrentProgressWithoutChangingArchive() {
        val context=AgentTurnContext(listOf(InferenceMessage("system","phone policy"),InferenceMessage("user",request)),managed=true)
        repeat(8) { context.append("discovery","irrelevant catalog $it") }
        val archive=context.archive()
        context.progressContext=GoalProgress.render(listOf(doc),run,emptyList())
        for(level in 0..3) assertTrue(context.project(level).any { it.content.contains("phone_goal_progress") && it.content.contains("EMAIL") })
        assertEquals(archive,context.archive())
    }
    @Test fun unloadedEmailSchemaSurvivesCheckpointsAndRemainsVisibleAmongUnrelatedTools() {
        val mail=ToolReference("mail-account","GMAIL_SEND_EMAIL","v1")
        val oldDoc=ToolReference("docs-account","GOOGLEDOCS_CREATE_DOCUMENT","v1")
        val newDoc=oldDoc.copy(version="v2")
        val catalog=(1..40).map { ToolReference("sheets-account","GOOGLESHEETS_GET_$it","v1") }
        val retained=GoalProgress.retainedTools(catalog+mail+oldDoc,listOf(newDoc))
        assertTrue(mail in retained);assertTrue(newDoc in retained);assertFalse(oldDoc in retained)
        val again=GoalProgress.retainedTools(retained,listOf(newDoc))
        val visible=data(GoalProgress.render(listOf(doc),run,again)).getJSONArray("discoveredTools")
        assertTrue((0 until visible.length()).any { visible.getJSONObject(it).getString("tool")==mail.tool })
        assertEquals(42,again.size)
    }
}
