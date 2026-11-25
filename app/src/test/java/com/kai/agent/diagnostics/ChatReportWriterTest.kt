package com.kai.agent.diagnostics

import com.kai.agent.data.*
import com.kai.agent.runtime.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter
import java.io.Writer

class ChatReportWriterTest {
    private fun canonical(value: Any?): Any? = when(value) {
        is JSONObject -> value.keys().asSequence().sorted().associateWith { canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { canonical(value.get(it)) }
        JSONObject.NULL -> null
        is Number -> value.toString().toBigDecimal().stripTrailingZeros()
        else -> value
    }
    private val app = JSONObject().put("version", "exporter-version").put("versionCode", 51)
    private fun trace(id: String, dropped: Long = 0) = JSONObject().put("droppedEvents", dropped)
        .put("lastPrivacyResetAt", 7).put("events", JSONArray().put(JSONObject().put("event", "model_start")
            .put("sessionId", "session-$id").put("recordedAppVersion", "recording-version")
            .put("data", JSONObject().put("provider", "KOINOS").put("model", "model-$id"))))
    private fun export(state: KaiState, traces: (String) -> JSONObject = { trace(it) }): JSONObject {
        val writer = StringWriter()
        ChatReportWriter.writeAll(state, writer, traces, app, 0, 1234)
        return JSONObject(writer.toString())
    }
    @Test fun twentyMixedAttemptsRemainSeparateAndMatchIndividualReports() {
        val chats = (0 until 20).map { Conversation(id="chat-$it",title="Attempt $it",createdAt=it.toLong(),
            messages=listOf(ChatMessage(id="message-$it",role="user",text="Research and email attempt $it"))) }
        val runs = chats.mapIndexed { i,c -> ExecutionRun(id="run-$i",conversationId=c.id,sourceId="message-$i",
            origin=RunOrigin.CHAT,goal=GoalContract("Research and email"),privacyRevision=0,createdAt=i.toLong(),
            state=if(i%2==0) ExecutionState.COMPLETED else ExecutionState.FAILED,
            failure=if(i%2==0) null else FailureCode.UNSUPPORTED) }
        val state=KaiState(conversations=chats.reversed(),executions=runs)
        val batch=export(state)
        assertEquals("kai-chat-batch-report",batch.getString("format"))
        assertEquals(20,batch.getInt("chatCount"))
        assertEquals(20,batch.getJSONArray("reports").length())
        assertEquals(10,batch.getJSONObject("summary").getJSONObject("runStates").getInt("FAILED"))
        assertEquals(10,batch.getJSONObject("summary").getJSONObject("runStates").getInt("COMPLETED"))
        chats.forEachIndexed { i,c ->
            val report=batch.getJSONArray("reports").getJSONObject(i)
            assertEquals(canonical(ChatReportWriter.report(state,c,trace(c.id),app,0,1234)),canonical(report))
            assertEquals(c.id,report.getJSONObject("conversation").getString("id"))
            assertEquals("run-$i",report.getJSONArray("runs").getJSONObject(0).getString("id"))
            val entry=batch.getJSONArray("index").getJSONObject(i)
            assertEquals("model-${c.id}",entry.getJSONArray("modelsObservedInTrace").getJSONObject(0).getString("model"))
            assertEquals("recording-version",entry.getJSONArray("recordedAppVersions").getString(0))
        }
    }
    @Test fun temporaryChatsAndTheirRunsCannotLeakIntoTheBatch() {
        val regular=Conversation(id="regular",title="Safe")
        val temporary=Conversation(id="private",title="temporary-marker",temporary=true,
            messages=listOf(ChatMessage(role="user",text="temporary-marker")))
        val state=KaiState(conversations=listOf(temporary,regular),executions=listOf(
            ExecutionRun(id="private-run",conversationId=temporary.id,sourceId="private-source",origin=RunOrigin.CHAT,
                goal=GoalContract("temporary-marker"),temporary=true,privacyRevision=0,createdAt=0)))
        val reads=mutableListOf<String>()
        val batch=export(state) { reads+=it;trace(it) }
        assertEquals(listOf("regular"),reads)
        assertFalse(batch.toString().contains("temporary-marker"))
        assertFalse(batch.toString().contains("private-run"))
        assertEquals(0,batch.getJSONArray("reports").getJSONObject(0).getJSONArray("runs").length())
    }
    @Test fun credentialsAreFilteredInEveryChatAndInTheIndexWhilePersonalContentRemains() {
        val secret="all-chats-fixture-key"
        val state=KaiState(settings=AppSettings(composioApiKey=secret),conversations=(0..2).map {
            Conversation(id="c$it",title="Title $secret",messages=listOf(ChatMessage(role="user",text="personal-$it Bearer $secret"))) })
        val batch=export(state) { trace(it).apply { getJSONArray("events").put(JSONObject().put("api_key","echoed-secret")) } }
        assertFalse(batch.toString().contains(secret)); assertFalse(batch.toString().contains("echoed-secret"))
        assertTrue(batch.toString().contains("personal-2")); assertTrue(batch.toString().contains("credential-removed"))
    }
    @Test fun absentAndPrunedTracesAreExplicitAndDoNotInventSuccessOrHistoricalVersion() {
        val batch=export(KaiState(conversations=listOf(Conversation(id="old")))) {
            JSONObject().put("events",JSONArray()).put("droppedEvents",29).put("lastPrivacyResetAt",77)
        }
        val summary=batch.getJSONObject("summary")
        assertEquals(1,summary.getInt("chatsWithoutRetainedTrace")); assertEquals(29,summary.getInt("droppedEvents"))
        assertEquals(0,summary.getJSONObject("runStates").length())
        val row=batch.getJSONArray("index").getJSONObject(0)
        assertEquals(77,row.getInt("lastPrivacyResetAt")); assertEquals(0,row.getJSONArray("recordedAppVersions").length())
        assertEquals(1,batch.getJSONArray("reports").length())
    }
    @Test fun emptySnapshotIsValidAndTraceReaderIsNeverCalled() {
        val batch=export(KaiState(conversations=emptyList())) { error("Unexpected trace read") }
        assertEquals(0,batch.getInt("chatCount")); assertEquals(0,batch.getJSONArray("reports").length())
    }
    @Test fun compressedArchiveContainsExactlyTheCompleteUtf8BatchAndNoOtherFiles() {
        val state=KaiState(conversations=(0 until 20).map { Conversation(id="c$it",title="試行 $it — café",
            messages=listOf(ChatMessage(role="user",text="Research Ω ".repeat(10000)))) })
        val bytes=java.io.ByteArrayOutputStream()
        ChatReportWriter.writeArchive(state,bytes,{ trace(it) },app,0,1234)
        java.util.zip.ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
            assertEquals("kai-all-chats-report.json",zip.nextEntry.name)
            val restored=JSONObject(zip.readBytes().toString(Charsets.UTF_8))
            assertEquals(canonical(export(state)),canonical(restored))
            assertEquals(20,restored.getJSONArray("reports").length())
            assertNull(zip.nextEntry)
        }
        assertTrue(bytes.size()<100_000)
    }
    @Test fun writerFailureStopsBeforeReadingTheRestOfTheBatch() {
        val reads=mutableListOf<String>()
        val writer=object: Writer() {
            var count=0
            override fun write(buffer: CharArray, offset: Int, length: Int) {
                count+=length
                if(count>1600) throw java.io.IOException("Destination full")
            }
            override fun flush() {}
            override fun close() {}
        }
        val failure=runCatching { ChatReportWriter.writeAll(KaiState(conversations=(0..19).map { Conversation(id="c$it") }),
            writer,{ reads+=it;trace(it) },app,0,1234) }.exceptionOrNull()
        assertTrue(failure is java.io.IOException); assertTrue(reads.size<20)
    }
}
