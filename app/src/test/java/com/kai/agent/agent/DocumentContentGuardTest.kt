package com.kai.agent.agent

import com.kai.agent.connections.*
import com.kai.agent.data.*
import org.junit.Assert.*
import org.junit.Test

class DocumentContentGuardTest {
    private val request = "make a Google doc called test and put test doc in the page content and send me the link"
    private fun guard(text: String = request, boundary: Long = 0) = DocumentContentGuard(text, "chat", 100, boundary, "owner", "generation")
    private fun task(tool: String = "GOOGLEDRIVE_CREATE_FILE", args: String = """{"name":"test","mimeType":"application/vnd.google-apps.document"}""", requestText: String = request): AgentTask {
        val intent = ActionIntent(toolName=tool, accountId="drive", argumentsJson=args, ownerId="owner", connectionGeneration="generation")
        return AgentTask(title="fixture", instructions=requestText, conversationId="chat", createdAt=101, updatedAt=103,
            submittedAt=102, status=TaskStatus.ACCEPTED, intent=intent,
            receipts=listOf(ActionReceipt(intent.id, providerId="doc-real", status="ACCEPTED", url="https://docs.google.com/document/d/doc-real/edit", observedAt=103)))
    }
    private fun tool(id: String, read: Boolean = false) = ConnectionTool(id, "fixture", "", "googledocs", "v1", read, "{}")

    @Test fun extractsOnlyLiteralSuppliedBodyRequests() {
        assertEquals("test doc", guard().text)
        assertEquals("this is a test doc", guard("can you write \"this is a test doc\" in that google doc?").text)
        assertEquals("test", guard("Create a Google doc named \"hello\" containing \"test\"").text)
        assertTrue(guard("Research current news and write a Google document about it").active)
        assertFalse(guard("Tell me how to write into a document").active)
    }
    @Test fun capturedMetadataOnlyCreationRequiresBodyStepAndNeverRepeatsCreation() {
        val guard = guard(); val tasks = listOf(task())
        val check = requireNotNull(guard.checkReply(tasks))
        assertTrue(check.correction!!.contains("BODY_TEXT_NOT_SUBMITTED"))
        assertTrue(check.correction.contains("documentCreated\":true"))
        assertTrue(check.correction.contains("doc-real"))
        assertTrue(check.fallback.contains("body text has not been confirmed saved"))
        assertTrue(runCatching { guard.validateAction(tool("GOOGLEDOCS_CREATE_DOCUMENT"), tasks) }.exceptionOrNull() is ConnectionServiceException)
        guard.validateAction(tool("GOOGLEDOCS_INSERT_TEXT"), tasks)
    }
    @Test fun acceptedBodyWriteAllowsReplyButBlocksDuplicateAppendAndAllowsReadVerification() {
        val tasks = listOf(task(), task("GOOGLEDOCS_BATCH_UPDATE_DOCUMENT", """{"documentId":"doc-real","requests":[{"insertText":{"text":"test doc","endOfSegmentLocation":{}}}]}"""))
        val guard=guard()
        assertNull(guard.checkReply(tasks))
        assertTrue(runCatching { guard.validateAction(tool("GOOGLEDOCS_INSERT_TEXT"), tasks) }.exceptionOrNull() is ConnectionServiceException)
        guard.validateAction(tool("GOOGLEDOCS_GET_DOCUMENT", true), tasks)
    }
    @Test fun failedUnknownOrPendingWritesNeverCountAsSavedText() {
        val write = task("GOOGLEDOCS_INSERT_TEXT", """{"documentId":"doc-real","text":"test doc"}""")
        for (status in listOf(TaskStatus.FAILED, TaskStatus.UNKNOWN, TaskStatus.RUNNING, TaskStatus.AWAITING_APPROVAL)) {
            val check = requireNotNull(guard().checkReply(listOf(write.copy(status=status))))
            if (status != TaskStatus.FAILED) assertNull(check.correction) else assertNotNull(check.correction)
        }
        assertNotNull(guard().checkReply(listOf(write.copy(receipts=emptyList()))))
    }
    @Test fun otherConversationOwnerGenerationAndForgottenOrEarlierWritesCannotCompleteThisRequest() {
        val write = task("GOOGLEDOCS_CREATE_DOCUMENT", """{"title":"test","text":"test doc"}""")
        for (candidate in listOf(write.copy(conversationId="other"), write.copy(createdAt=99),
            write.copy(intent=write.intent!!.copy(ownerId="other")), write.copy(intent=write.intent.copy(connectionGeneration="old")))) {
            assertNotNull(guard().checkReply(listOf(candidate)))
        }
        assertNotNull(guard(boundary=104).checkReply(listOf(write)))
        assertNull(guard().checkReply(listOf(write)))
    }
    @Test fun metadataFooterOrWrongTextIsNotBodyEvidenceAndCorrectionsAreBounded() {
        for ((name,args) in listOf("GOOGLEDRIVE_UPDATE_FILE" to """{"description":"test doc"}""",
            "GOOGLEDOCS_CREATE_HEADER" to """{"text":"test doc"}""", "GOOGLEDOCS_INSERT_TEXT" to """{"text":"wrong text"}""")) {
            assertNotNull(guard().checkReply(listOf(task(name,args))))
        }
        val guard=guard(); val tasks=listOf(task())
        repeat(2) { assertNotNull(guard.checkReply(tasks)!!.correction) }
        val end=guard.checkReply(tasks)!!
        assertNull(end.correction); assertTrue(end.fallback.contains("not been confirmed saved"))
    }
    @Test fun quotedFollowupDoesNotTreatEarlierEmptyDocumentAsACompletedEdit() {
        val followup="can you write \"this is a test doc\" in that google doc?"
        val guard=guard(followup)
        assertNotNull(guard.checkReply(listOf(task())))
        assertNull(guard.checkReply(listOf(task("GOOGLEDOCS_INSERT_TEXT", """{"documentId":"doc-real","text":"this is a test doc"}""", followup))))
    }
    @Test fun missingAccountQuestionIsAllowedButAnUpdatingPromiseOrApprovalCodeIsNotCompletion() {
        assertNull(guard().checkReply(emptyList(), "Which Google account should I use?"))
        assertNotNull(guard().checkReply(emptyList(), "Updating the document now."))
        assertNotNull(guard().checkReply(emptyList(), "Could you approve {\"type\":\"action\"}?"))
    }
}
