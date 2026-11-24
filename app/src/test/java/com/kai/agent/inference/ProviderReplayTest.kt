package com.kai.agent.inference

import com.kai.agent.account.CreditAccount
import com.kai.agent.agent.AgentTurnContext
import com.kai.agent.data.AppSettings
import com.kai.agent.diagnostics.DiagnosticRedactor
import com.kai.agent.runtime.RunReplay
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProviderReplayTest {
    private val token="kr1."+"a".repeat(150)
    private val replay=ProviderReplay.read(JSONObject().put("kai_replay",token))!!
    private val call=ToolCall("call_1","web_search","{\"query\":\"test\"}")
    private fun message()=InferenceMessage("assistant","",toolCalls=listOf(call),providerReplay=replay)
    @Test fun opaqueStateRoundTripsInEncryptedArchiveButNeverEntersSummaryRetrievalOrPublicCopy() {
        val c=AgentTurnContext(listOf(InferenceMessage("user","goal")),managed=true)
        c.append("proposal","source evidence",wire=listOf(message(),InferenceMessage("tool","source evidence",toolCallId=call.id)))
        assertTrue(c.archive().contains(token))
        val restored=AgentTurnContext(emptyList(),checkpointSeed=c.archive(),managed=true)
        assertEquals(replay,restored.fullMessages().first().providerReplay)
        assertFalse(c.summaryInput(30000).contains(token));assertFalse(c.read("t_1",0).contains(token));assertFalse(c.checkpoint().contains(token))
        val exported=ProviderReplay.publicCopy(JSONObject().put("archive",c.archive()).put("messages",JSONArray().put(NativeToolWire.messageJson(message())))).toString()
        assertFalse(exported.contains(token));assertFalse(exported.contains("kai_replay"));assertTrue(exported.contains("source evidence"))
        assertFalse(replay.toString().contains(token));assertFalse(DiagnosticRedactor(AppSettings()).clean(JSONObject().put("wire",NativeToolWire.messageJson(message())).put("nested",c.archive())).toString().contains(token))
    }
    @Test fun pendingApprovalRetainsExactCallUntilRealOutcomeAndDoesNotCreateAnUnpairedPrompt() {
        val c=AgentTurnContext(emptyList(),managed=true)
        c.awaiting(Completion("","tool_calls",toolCalls=listOf(call),providerReplay=replay))
        assertTrue(c.fullMessages().isEmpty())
        val restored=AgentTurnContext(emptyList(),checkpointSeed=c.archive(),managed=true)
        restored.appendOutcome("ACCEPTED: real receipt")
        val messages=restored.fullMessages();NativeToolWire.validate(messages,true)
        assertEquals(call,messages[0].toolCalls.single());assertEquals(replay,messages[0].providerReplay)
        assertEquals(call.id,messages[1].toolCallId);assertTrue(messages[1].content.contains("real receipt"))
        assertFalse(restored.archive().contains("pendingWire"))
    }
    @Test fun replayIsRejectedOnNonToolMessagesAndInvalidTokens() {
        assertTrue(runCatching { NativeToolWire.validate(listOf(InferenceMessage("user","x",providerReplay=replay)),true) }.isFailure)
        assertTrue(runCatching { ProviderReplay.read(JSONObject().put("kai_replay","plain private text")) }.isFailure)
        assertTrue(runCatching { ProviderReplay.read(JSONObject().put("kai_replay","kr1."+"x".repeat(34000))) }.isFailure)
    }
    @Test fun qualificationRequiresFreshVersionedEvidenceAndLegacyStoredFlagsStayOff() {
        val now=System.currentTimeMillis()
        val model=JSONObject().put("nativeTools",1)
        assertNull(NativeQualification.read(model,now))
        val q=JSONObject().put("version",2).put("replay","sealed-v1").put("qualificationId","a".repeat(64)).put("checkedAt",now-1000).put("expiresAt",now+60000)
        model.put("nativeQualification",q);assertNotNull(NativeQualification.read(model,now))
        assertNull(NativeQualification.read(model,now+60000))
        q.put("version",3);assertNull(NativeQualification.read(model,now))
        assertFalse(CreditAccount(nativeTools=true).inference().nativeTools)
        val a=CreditAccount(nativeTools=true,nativeQualificationId="a".repeat(64),nativeQualifiedUntil=now+60000)
        assertTrue(CreditAccount.read(a.json(true)).inference().nativeTools)
        assertFalse(a.copy(nativeQualifiedUntil=now-1).inference().nativeTools)
        assertNotEquals(RunReplay.route(a.inference()),RunReplay.route(a.copy(wallet="changed").inference()))
    }
    @Test fun changedSavedTaskRouteRetainsOutcomeButDropsOpaqueStateAndNativeWire() {
        val c=CreditAccount().inference()
        val messages=listOf(com.kai.agent.data.RunMessage("assistant","proposal",listOf(call),providerReplay=replay),com.kai.agent.data.RunMessage("tool","accepted receipt",toolCallId=call.id))
        val clean=RunReplay.stepMessages(messages,c)
        assertTrue(clean.all { it.providerReplay==null && it.toolCalls.isEmpty() && it.toolCallId.isBlank() })
        assertEquals("accepted receipt",clean.last().content)
    }
    @Test fun managedStreamingAndApprovalFollowupKeepOpaqueMetadataOutOfVisibleOutput()=runBlocking {
        val bodies=mutableListOf<JSONObject>()
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            val buffer=okio.Buffer();chain.request().body!!.writeTo(buffer);bodies+=JSONObject(buffer.readUtf8())
            val delta=if(bodies.size==1) NativeToolWire.messageJson(message()).apply { getJSONArray("tool_calls").getJSONObject(0).put("index",0) } else JSONObject().put("content","Finished")
            val frame=JSONObject().put("choices",JSONArray().put(JSONObject().put("delta",delta).put("finish_reason",if(bodies.size==1) "tool_calls" else "stop")))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").header("Content-Type","text/event-stream")
                .body(("data: $frame\n\ndata: [DONE]\n\n").toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val config=CreditAccount(token="fixture-secret",nativeTools=true,nativeQualificationId="a".repeat(64),nativeQualifiedUntil=System.currentTimeMillis()+60000,contextTokens=131072).inference()
            .copy(tools=listOf(InferenceTool("web_search","Search","{\"type\":\"object\"}")))
        val client=InferenceClient(http);val spoken=StringBuilder()
        val first=client.stream(config,listOf(InferenceMessage("user","test"))) { spoken.append(it) }
        assertEquals(token,first.providerReplay?.token);assertEquals("",spoken.toString());assertEquals(2,bodies[0].getInt("native_protocol"))
        client.stream(config,listOf(InferenceMessage("user","test"),message().copy(providerReplay=first.providerReplay),InferenceMessage("tool","result",toolCallId=call.id))) { spoken.append(it) }
        assertEquals(token,bodies[1].getJSONArray("messages").getJSONObject(1).getString("kai_replay"));assertEquals("Finished",spoken.toString())
    }
}
