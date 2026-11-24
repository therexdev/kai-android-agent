package com.kai.agent.runtime

import com.kai.agent.data.*
import com.kai.agent.agent.DocumentContentGuard
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DocumentVerificationTest {
    private val markdown = "# Project list\n\n## Example One\n* Open source tools.\n* A community project.\n\n## Example Two\n* More useful tools."
    private val plain = "Project list\nExample One\n● Open source tools.\n● A community project.\nExample Two\n● More useful tools.\n"
    private fun write(text: String = markdown, tool: String = "GOOGLEDOCS_CREATE_DOCUMENT_MARKDOWN", field: String = "markdown_text"): AgentTask {
        val intent = ActionIntent(id="write-intent",toolName=tool,accountId="account",ownerId="owner",connectionGeneration="generation",executionId="run",
            argumentsJson=JSONObject().put(field,text).put("document_id","doc-real").toString())
        return AgentTask(id="write",title="Create",instructions="Research projects and create a document",conversationId="chat",createdAt=101,
            submittedAt=110,updatedAt=120,status=TaskStatus.ACCEPTED,intent=intent,
            receipts=listOf(ActionReceipt(intent.id,"doc-real","ACCEPTED",url="https://docs.google.com/document/d/doc-real/edit",observedAt=120)))
    }
    private fun context(document: JSONObject, truncated: Boolean = false) = JSONObject().put("source","untrusted_connected_app_output")
        .put("reportedStatus","SUCCEEDED").put("truncated",truncated).put("data",JSONObject().put("data",document).toString()).toString()
    private fun read(text: String = plain): AgentTask {
        val intent = write().intent!!.copy(id="read-intent",toolName="GOOGLEDOCS_GET_DOCUMENT_PLAINTEXT",readOnly=true,
            argumentsJson="""{"document_id":"doc-real"}""")
        return write().copy(id="read",intent=intent,status=TaskStatus.SUCCEEDED,createdAt=130,submittedAt=140,updatedAt=150,
            receipts=listOf(ActionReceipt(intent.id,"doc-real","SUCCEEDED",observedAt=150)),
            resultContext=context(JSONObject().put("document_id","doc-real").put("plain_text",text)))
    }
    private fun check(w: AgentTask = write(), r: AgentTask = read()) = DocumentVerification.evaluate(w,listOf(w,r))

    @Test fun markdownPlaintextReadbackIsAttributedToTheLaterRead() {
        val evidence=check()
        assertTrue(evidence.matched);assertEquals("read",evidence.readTaskId);assertEquals(150L,evidence.observedAt)
    }
    @Test fun capturedEscapedMarkdownSeparatorsAndBulletGlyphsMatch() {
        assertTrue(check(write(markdown.replace("\n","\\n"))).matched)
    }
    @Test fun headingsEmphasisAndLinkLabelsAreVisibleTextOnly() {
        assertTrue(check(write("# Heading\n\n**Bold** and *italic* with [label](https://example.com)."),read("Heading\nBold and italic with label.")).matched)
    }
    @Test fun wholeDocumentMustMatchIncludingLastParagraphAndExtraContent() {
        for(actual in listOf(plain.substringBefore("Example Two"),plain+"Unrequested addition.",plain.replace("useful","incorrect"))) {
            assertEquals(DocumentVerification.Status.TEXT_DIFFERS,check(r=read(actual)).status)
        }
    }
    @Test fun numbersCaseAndPunctuationAreNotDiscarded() {
        for(actual in listOf("Cost is 11.","cost is 10.","Cost is 10!"))
            assertFalse(check(write("Cost is 10.","GOOGLEDOCS_CREATE_DOCUMENT","text"),read(actual)).matched)
        assertFalse(check(write("# Costs\n\nPay all."),read("Costs\n999. Pay all.")).matched)
    }
    @Test fun literalTextAndUnicodeWhitespaceAreComparedWithoutMarkdownRemoval() {
        assertTrue(check(write("Café  costs 10.","GOOGLEDOCS_CREATE_DOCUMENT","text"),read("Cafe\u0301\u00a0costs 10.\n")).matched)
        assertFalse(check(write("* literal", "GOOGLEDOCS_CREATE_DOCUMENT","text"),read("literal")).matched)
    }
    @Test fun insertionMayBeContainedButCreationCannotBeAPartialMatch() {
        val text="An inserted passage."
        assertTrue(check(write(text,"GOOGLEDOCS_INSERT_TEXT","text"),read("Before. $text After.")).matched)
        assertFalse(check(write(text,"GOOGLEDOCS_CREATE_DOCUMENT","text"),read("Before. $text After.")).matched)
    }
    @Test fun metadataAndRequestEchoesCannotSupplyBodyEvidence() {
        val document=JSONObject().put("document_id","doc-real").put("title",plain).put("request",JSONObject().put("plain_text",plain))
        assertEquals(DocumentVerification.Status.UNVERIFIED,check(r=read().copy(resultContext=context(document))).status)
    }
    @Test fun wrongDocumentRequestOrResponseCannotVerify() {
        val r=read()
        assertFalse(check(r=r.copy(intent=r.intent!!.copy(argumentsJson="""{"document_id":"different"}"""))).matched)
        assertFalse(check(r=r.copy(resultContext=context(JSONObject().put("document_id","different").put("plain_text",plain)))).matched)
    }
    @Test fun differentOwnerAccountGenerationConversationRunOrTransportCannotVerify() {
        val r=read();val i=r.intent!!
        val candidates=listOf(r.copy(conversationId="other"),r.copy(runId="other"),r.copy(instructions="other")) +
            listOf(i.copy(ownerId="other"),i.copy(accountId="other"),i.copy(connectionGeneration="old"),i.copy(executionId="other"),i.copy(transportId="mcp"),i.copy(readOnly=false)).map { r.copy(intent=it) }
        candidates.forEach { assertFalse(check(r=it).matched) }
        assertFalse(check(write().copy(intent=write().intent!!.copy(executionId="")),r.copy(intent=i.copy(executionId=""))).matched)
    }
    @Test fun readMustBeDispatchedAfterWriteAcknowledgementWithItsOwnReceipt() {
        val r=read()
        for(time in listOf(100L,120L)) assertFalse(check(r=r.copy(submittedAt=time)).matched)
        assertFalse(check(r=r.copy(receipts=emptyList())).matched)
        assertFalse(check(r=r.copy(receipts=listOf(r.receipts.single().copy(operationId="wrong")))).matched)
        assertFalse(check(r=r.copy(receipts=listOf(r.receipts.single().copy(observedAt=130)))).matched)
    }
    @Test fun unsuccessfulOrAmbiguousWritesAndReadsCannotVerify() {
        for(status in listOf(TaskStatus.FAILED,TaskStatus.UNKNOWN,TaskStatus.RUNNING,TaskStatus.AWAITING_APPROVAL)) {
            assertFalse(check(write().copy(status=status)).matched)
            assertFalse(check(r=read().copy(status=status)).matched)
        }
        assertFalse(check(write().copy(submittedAt=null)).matched)
        assertFalse(check(write().copy(receipts=emptyList())).matched)
    }
    @Test fun truncatedMalformedAndPartialReadbacksRemainUnverified() {
        val r=read()
        val envelope=JSONObject(r.resultContext)
        for(raw in listOf("{broken",envelope.put("truncated",true).toString(),"""{"data":{"plain_text":"$plain"}}"""))
            assertEquals(DocumentVerification.Status.UNVERIFIED,check(r=r.copy(resultContext=raw)).status)
        assertFalse(check(r=r.copy(intent=r.intent!!.copy(argumentsJson="""{"document_id":"doc-real","tab_ids":["tab"]}"""))).matched)
        for (flag in listOf("include_tabs_content","include_headers","include_footers","include_footnotes"))
            assertFalse(check(r=r.copy(intent=r.intent!!.copy(argumentsJson=JSONObject().put("document_id","doc-real").put(flag,true).toString()))).matched)
    }
    @Test fun newestReadSupersedesAnEarlierMatchIncludingFailureAndTruncation() {
        val w=write();val r=read()
        for(later in listOf(read("Changed content."),r.copy(status=TaskStatus.FAILED),r.copy(resultContext="{}"))) {
            assertFalse(DocumentVerification.evaluate(w,listOf(w,r,later.copy(id="later",submittedAt=160))).matched)
        }
        assertFalse(DocumentVerification.evaluate(w,listOf(w,r,r.copy(id="same-time"))).matched)
    }
    @Test fun unsupportedToolsTabsImagesHtmlAndCodeNeverGetGenericStringMatching() {
        assertFalse(check(w=write(tool="GOOGLEDOCS_UPDATE_HEADER")).matched)
        for(text in listOf("![image](https://example.com/a.png)","<b>text</b>","`text`","| a | b |\n|---|---|\n| c | d |"))
            assertEquals(DocumentVerification.Status.UNVERIFIED,check(write(text),read(text)).status)
    }
    @Test fun singleBatchBodyInsertionIsSupportedButFooterAndMultipleRequestsAreNot() {
        val w=write("An inserted passage.","GOOGLEDOCS_INSERT_TEXT","text")
        val request=JSONObject().put("insertText",JSONObject().put("text","An inserted passage.").put("location",JSONObject().put("index",1)))
        val args=JSONObject().put("documentId","doc-real").put("requests",org.json.JSONArray().put(request))
        val batch=w.copy(intent=w.intent!!.copy(toolName="GOOGLEDOCS_BATCH_UPDATE_DOCUMENT",argumentsJson=args.toString()))
        assertTrue(check(batch,read("An inserted passage.")).matched)
        request.getJSONObject("insertText").getJSONObject("location").put("segmentId","footer")
        assertFalse(check(batch.copy(intent=batch.intent!!.copy(argumentsJson=args.toString())),read("An inserted passage.")).matched)
        args.getJSONArray("requests").put(JSONObject().put("deleteContentRange",JSONObject()))
        assertFalse(check(batch.copy(intent=batch.intent!!.copy(argumentsJson=args.toString())),read("An inserted passage.")).matched)
    }
    @Test fun receiptTargetConflictAndNestedFailureDoNotVerify() {
        val w=write()
        assertFalse(check(w.copy(receipts=listOf(w.receipts.single().copy(providerId="other-document")))).matched)
        val data=JSONObject().put("document_id","doc-real").put("plain_text",plain).put("successful",false)
        assertFalse(check(r=read().copy(resultContext=context(data))).matched)
        data.remove("successful");data.put("status","failed")
        assertFalse(check(r=read().copy(resultContext=context(data))).matched)
    }
    @Test fun contentMismatchProducesPartialFeedbackWithoutRequestingAnotherWrite() {
        val w=write();val r=read("Changed content.")
        val guard=DocumentContentGuard(w.instructions,"chat",100,0,"owner","generation")
        val result=requireNotNull(guard.checkReply(listOf(w,r),"Done."))
        assertNull(result.correction);assertTrue(result.fallback.contains("different body text"))
    }
    @Test fun structuredDocumentUsesBodyTextRunsOnly() {
        val doc=JSONObject("""{"documentId":"doc-real","title":"unrelated","body":{"content":[{"sectionBreak":{}},{"paragraph":{"elements":[{"textRun":{"content":"Literal "}},{"textRun":{"content":"body.\n"}}]}}]}}""")
        val r=read().let { it.copy(intent=it.intent!!.copy(toolName="GOOGLEDOCS_GET_DOCUMENT"),resultContext=context(doc)) }
        assertTrue(check(write("Literal body.","GOOGLEDOCS_CREATE_DOCUMENT","text"),r).matched)
        assertFalse(check(write("unrelated","GOOGLEDOCS_CREATE_DOCUMENT","text"),r).matched)
        assertFalse(check(write("Literal body.","GOOGLEDOCS_CREATE_DOCUMENT","text"),r.copy(resultContext=context(doc.put("tabs",org.json.JSONArray())))).matched)
    }
    @Test fun progressAndGuardShareEvidenceAndNeverChangeWriteAcceptanceOrApproval() {
        val w=write();val r=read();val tasks=listOf(w,r)
        val run=ExecutionRun("run","chat","message",RunOrigin.CHAT,GoalContract(w.instructions,"document"),privacyRevision=1,createdAt=100)
        assertTrue(GoalProgress.render(tasks,run,emptyList()).contains("TEXT_MATCHED"))
        val guard=DocumentContentGuard(w.instructions,"chat",100,0,"owner","generation")
        assertNull(guard.checkReply(tasks,"Created the document."))
        assertTrue(guard.verification(tasks).single().second.matched)
        assertEquals(TaskStatus.ACCEPTED,w.status);assertEquals("",w.approvedFingerprint)
    }
    @Test fun verificationRebuildsFromPersistedTaskRecordsAndDisappearsWhenReadbackIsForgotten() {
        val state=KaiState(tasks=listOf(write(),read()))
        val restored=KaiJson.decode(KaiJson.encode(state))
        assertTrue(DocumentVerification.evaluate(restored.tasks.first(),restored.tasks).matched)
        val forgotten=restored.tasks.map { it.copy(resultContext="") }
        assertFalse(DocumentVerification.evaluate(forgotten.first(),forgotten).matched)
    }
}
