package com.kai.agent.agent

import com.kai.agent.connections.*
import com.kai.agent.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DocumentTargetEvidenceTest {
    private val tool=ConnectionTool("GOOGLEDOCS_CREATE_AND_POPULATE_TABLE","Create table","Edit an existing document","googledocs","v1",false,
        """{"type":"object","required":["documentId"],"properties":{"documentId":{"type":"string"}}}""")
    private val account=ConnectionAccount("docs","googledocs","owner","Docs","ACTIVE",false,"")
    private fun args(id: String)=JSONObject().put("documentId",id)
    private fun rejected(e: DocumentTargetEvidence,id: String,account: String="docs") =
        assertTrue(runCatching { e.validate(account,tool,args(id)) }.exceptionOrNull() is ConnectionServiceException)

    @Test fun driveDocumentReceiptCanBeUsedByDocsForTheSameVerifiedGoogleIdentity() {
        val docs=account.copy(emailAddress="same@example.test",identitySource="google_profile")
        val drive=docs.copy(id="drive",toolkit="googledrive")
        val intent=ActionIntent(toolName="GOOGLEDRIVE_CREATE_FILE",accountId="drive",ownerId="owner",connectionGeneration="g")
        val task=AgentTask(title="create",conversationId="c",status=TaskStatus.ACCEPTED,updatedAt=200,intent=intent,
            receipts=listOf(ActionReceipt(intent.id,providerId="real-doc",status="ACCEPTED",url="https://docs.google.com/document/d/real-doc/edit",observedAt=200)))
        val evidence=DocumentTargetEvidence(emptyList(),listOf(docs,drive))
        evidence.observeTasks(listOf(task),"c",0,"owner","g")
        evidence.validate("docs",tool,args("real-doc"))
        val forgotten=DocumentTargetEvidence(emptyList(),listOf(docs,drive))
        forgotten.observeTasks(listOf(task),"c",201,"owner","g")
        rejected(forgotten,"real-doc")
    }

    @Test fun displayAliasDifferentGoogleIdentityOrGenericDriveFileNeverSharesDocumentTarget() {
        val docs=account.copy(emailAddress="same@example.test",identitySource="google_profile")
        val drive=docs.copy(id="drive",toolkit="googledrive")
        for(source in listOf(drive.copy(emailAddress="other@example.test"),drive.copy(identitySource="composio_account_display_name"),
            drive.copy(disabled=true),drive.copy(userId="other-owner"))) {
            val evidence=DocumentTargetEvidence(emptyList(),listOf(docs,source))
            evidence.observeResult("drive","googledrive","""{"successful":true,"data":{"id":"real-doc","display_url":"https://docs.google.com/document/d/real-doc/edit"}}""")
            rejected(evidence,"real-doc")
        }
        val evidence=DocumentTargetEvidence(emptyList(),listOf(docs,drive))
        evidence.observeResult("drive","googledrive","""{"successful":true,"data":{"id":"some-file"}}""")
        rejected(evidence,"some-file")
    }

    @Test fun driveFileIdAlsoRequiresObservedOrUserSuppliedEvidence() {
        val driveTool=tool.copy(id="GOOGLEDRIVE_UPDATE_FILE",toolkit="googledrive",schemaJson="""{"type":"object","required":["fileId"]}""")
        val evidence=DocumentTargetEvidence(emptyList())
        assertTrue(runCatching { evidence.validate("drive",driveTool,JSONObject().put("fileId","guessed")) }.exceptionOrNull() is ConnectionServiceException)
        evidence.observeResult("drive","googledrive","""{"successful":true,"data":{"id":"actual-file"}}""")
        evidence.validate("drive",driveTool,JSONObject().put("fileId","actual-file"))
    }

    @Test fun capturedPlaceholderEmptyAndGuessedTargetsNeverPass() {
        val evidence=DocumentTargetEvidence(listOf("make a Google doc called test and put test doc in the page content and send me the link"))
        for(id in listOf("","Please provide the document ID.","DOCUMENT_ID","invented-but-valid-looking-id","test")) rejected(evidence,id)
        assertTrue(runCatching { evidence.validate("docs",tool,JSONObject()) }.exceptionOrNull() is ConnectionServiceException)
    }
    @Test fun userDocumentUrlAndExplicitIdentifierCanBeUsedWithoutRewriting() {
        DocumentTargetEvidence(listOf("Edit https://docs.google.com/document/d/real-doc-123/edit"))
            .validate("docs",tool,args("real-doc-123"))
        DocumentTargetEvidence(listOf("document ID: explicit-doc-123" )).validate("docs",tool,args("explicit-doc-123"))
    }
    @Test fun creationDoesNotNeedAnExistingIdAndContentMayContainLiteralPlaceholderText() {
        val create=tool.copy(id="GOOGLEDOCS_CREATE_DOCUMENT",schemaJson="""{"type":"object","required":["title"],"properties":{"title":{"type":"string"}}}""")
        DocumentTargetEvidence(emptyList()).validate("docs",create,JSONObject().put("title","Please provide the document ID."))
    }
    @Test fun successfulCreationUnlocksExactTargetOnlyForThatAccount() {
        val evidence=DocumentTargetEvidence(emptyList())
        evidence.observeResult("docs","googledocs","""{"successful":true,"data":{"documentId":"returned-doc-123"}}""")
        evidence.validate("docs",tool,args("returned-doc-123"))
        rejected(evidence,"returned-doc-123","other")
        rejected(evidence,"guessed-doc-456")
    }
    @Test fun failedUnknownMalformedAndUnrelatedResultsCannotSupplyIds() {
        val evidence=DocumentTargetEvidence(emptyList())
        for(raw in listOf("""{"successful":false,"data":{"documentId":"returned-doc-123"}}""",
            """{"status":"unknown","documentId":"returned-doc-123"}""",
            """{"documentId":"returned-doc-123"} trailing""")) evidence.observeResult("docs","googledocs",raw)
        evidence.observeResult("docs","gmail","""{"documentId":"returned-doc-123"}""")
        rejected(evidence,"returned-doc-123")
    }
    @Test fun persistedReceiptsRetainMultipleTargetsAndRespectConversationOwnerAndPrivacyBoundary() {
        val task=AgentTask(id="task",status=TaskStatus.SUCCEEDED,title="Find docs",conversationId="chat",updatedAt=200,
            intent=ActionIntent(toolName="GOOGLEDOCS_LIST",accountId="docs",argumentsJson="{}",connectionGeneration="generation",ownerId="owner"),
            receipts=listOf("first-doc","second-doc").map { ActionReceipt(operationId="op",providerId=it,status="SUCCEEDED",observedAt=200) })
        val evidence=DocumentTargetEvidence(emptyList())
        evidence.observeTasks(listOf(task),"chat",100,"owner","generation")
        evidence.validate("docs",tool,args("second-doc"))
        for(t in listOf(task.copy(conversationId="other"),task.copy(updatedAt=99),task.copy(status=TaskStatus.UNKNOWN),
            task.copy(intent=task.intent!!.copy(ownerId="other")),task.copy(intent=task.intent!!.copy(connectionGeneration="other")))) {
            val isolated=DocumentTargetEvidence(emptyList())
            isolated.observeTasks(listOf(t),"chat",100,"owner","generation")
            rejected(isolated,"second-doc")
        }
    }
    @Test fun repeatedMissingPrerequisiteStopsBeforeAnyPreparation() = runBlocking {
        val evidence=DocumentTargetEvidence(emptyList())
        val recovery=AgentToolRecovery()
        suspend fun recover(id: String)=recovery.recover(AgentDecision.PrepareAction("docs",tool.id,args(id),""),listOf(account),false,
            knownTool={_,_->tool},discover={_,_->error("No discovery or execution")},validateAction={action,t->evidence.validate(action.accountId,t,action.arguments)})
        val first=requireNotNull(recover(""))
        assertTrue(first.payload.full.contains("document-creation action first"))
        assertFalse(first.payload.full.contains("\"documentId\":\"\""))
        val second=runCatching { recover("Please provide the document ID.") }.exceptionOrNull()
        assertTrue(second!!.message!!.contains("same missing or invalid input"))
    }
}
