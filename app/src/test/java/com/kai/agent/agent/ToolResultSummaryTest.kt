package com.kai.agent.agent

import com.kai.agent.data.TaskStatus
import org.junit.Assert.*
import org.junit.Test

class ToolResultSummaryTest {
    @Test fun writeReceiptIsAcceptedNotDelivered() {
        val result = ToolResultSummary.parseExecution("""{"success":true,"id":"message-123","url":"https://mail.google.com/mail/u/0/#sent/123"}""", false)
        assertEquals(TaskStatus.ACCEPTED, result.status)
        assertEquals(listOf("message-123"), result.evidenceIds)
        assertEquals(1, result.evidenceUrls.size)
        assertFalse(result.summary.contains("delivered", ignoreCase = true))
    }

    @Test fun explicitFailureOverridesSuccessfulTransport() {
        listOf("""{"success":false}""", """{"error":{"code":"INVALID_RECIPIENT"}}""",
            """{"status":"failed"}""", """{"statusCode":403}""").forEach {
            assertEquals(TaskStatus.FAILED, ToolResultSummary.parseExecution(it, false).status)
        }
    }

    @Test fun unknownAndMalformedRemainUnknown() {
        listOf("broken response", "null", "{} trailing", """{"status":"unknown"}""").forEach {
            assertEquals(TaskStatus.UNKNOWN, ToolResultSummary.parseExecution(it, false).status)
        }
    }

    @Test fun readSuccessAndPendingAreDistinct() {
        assertEquals(TaskStatus.SUCCEEDED, ToolResultSummary.parseExecution("""{"items":[]}""", true).status)
        assertEquals(TaskStatus.ACCEPTED, ToolResultSummary.parseExecution("""{"status":202}""", true).status)
    }

    @Test fun linksNeverAcceptLocalOrInsecureTargets() {
        listOf("http://example.com/post", "https://127.0.0.1/a", "https://10.0.0.1/a",
            "https://[::1]/a", "https://nas.local/a", "https://user:pass@example.com/a",
            "javascript:alert(1)", "https://example.com/a?access_token=secret").forEach {
            assertNull(ToolResultSummary.displayUrl(it))
        }
        assertEquals("https://example.com/post/123", ToolResultSummary.displayUrl("https://example.com/post/123"))
    }

    @Test fun modelDataIsBoundedAndRedactsCredentialFields() {
        val result = ToolResultSummary.parseExecution("""{"access_token":"do-not-leak","body":"${"x".repeat(20_000)}"}""", true)
        assertFalse(result.modelData.contains("do-not-leak"))
        assertTrue(result.modelData.contains("untrusted_connected_app_output"))
        assertTrue(result.modelData.length < 13_000)
    }
    @Test fun capturedDocsFailureShowsActualErrorWithoutTreatingHttp200AsSuccess() {
        val result=ToolResultSummary.parseExecution("""{"successful":false,"data":{"status_code":404,"message":"Failed to insert table. Status: 404. Details: Requested entity was not found."},"error":"Failed to insert table. Status: 404. Details: Requested entity was not found."}""",false)
        assertEquals(TaskStatus.FAILED,result.status)
        assertTrue(result.summary.contains("404"))
        assertTrue(result.summary.contains("Requested entity was not found"))
    }
    @Test fun errorDisplayRedactsConfiguredSecretsAndBoundsLongMessages() {
        val redactor=com.kai.agent.diagnostics.DiagnosticRedactor(com.kai.agent.data.AppSettings(composioApiKey="private-fixture-secret"))
        val raw=org.json.JSONObject().put("successful",false).put("error","private-fixture-secret Bearer other-secret access_token=unknown-secret " + "x".repeat(5000)).toString()
        val result=ToolResultSummary.parseExecution(raw,false,redactor::text)
        assertFalse(result.summary.contains("private-fixture-secret"))
        assertFalse(result.summary.contains("other-secret"))
        assertFalse(result.summary.contains("unknown-secret"))
        assertTrue(result.summary.length<600)
    }
    @Test fun documentReceiptKeepsReturnedIdentifierAndLinkForNextStep() {
        val result=ToolResultSummary.parseExecution("""{"successful":true,"data":{"documentId":"created-doc-123","webViewLink":"https://docs.google.com/document/d/created-doc-123/edit"}}""",false)
        assertEquals(TaskStatus.ACCEPTED,result.status)
        assertEquals(listOf("created-doc-123"),result.evidenceIds)
        assertEquals(listOf("https://docs.google.com/document/d/created-doc-123/edit"),result.evidenceUrls)
    }

}
