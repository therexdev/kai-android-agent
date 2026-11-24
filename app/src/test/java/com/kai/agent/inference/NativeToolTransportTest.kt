package com.kai.agent.inference

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeToolTransportTest {
    @Test fun fragmentedNativeCallsBecomeCompleteCallsAndResultsKeepTheirIds() = runBlocking {
        val server=MockWebServer();server.start()
        try {
            val config=ProviderConfig(ProviderId.OPENAI,server.url("/v1").toString(),"fixture-only","fixture",nativeTools=true,
                tools=listOf(InferenceTool("web_search","Search","""{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}""")))
            fun frame(c: JSONObject,reason: String?=null)="data: "+JSONObject().put("choices",JSONArray().put(JSONObject().put("delta",c).put("finish_reason",reason ?: JSONObject.NULL)))+"\n\n"
            fun delta(id: String?,name: String?,args: String)=JSONObject().put("tool_calls",JSONArray().put(JSONObject().put("index",0).apply { if(id!=null) put("id",id) }.put("function",JSONObject().put("arguments",args).apply { if(name!=null) put("name",name) })))
            server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody(frame(delta("call_1","web_search","{\"query\":\"block"))+frame(delta(null,null,"chain\"}"),"tool_calls")+"data: [DONE]\n\n"))
            val client=InferenceClient();val history=listOf(InferenceMessage("user","Research blockchain projects"))
            val completion=client.stream(config,history) {}
            assertEquals("blockchain",JSONObject(completion.toolCalls.single().arguments).getString("query"))
            val request=JSONObject(server.takeRequest().body.readUtf8());assertEquals("web_search",request.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"));assertFalse(request.getBoolean("parallel_tool_calls"))
            server.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody("""{"choices":[{"message":{"content":"Found source-backed projects."},"finish_reason":"stop"}]}"""))
            client.stream(config,history+InferenceMessage("assistant","",toolCalls=completion.toolCalls)+InferenceMessage("tool","Real search results",toolCallId="call_1")) {}
            val resumed=JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
            assertEquals("call_1",resumed.getJSONObject(2).getString("tool_call_id"))
        } finally { server.shutdown() }
    }
    @Test fun unadvertisedAndUnpairedCallsAreRejectedBeforeNetwork() {
        val c=ToolCall("call_1","web_search","{}")
        assertTrue(runCatching { NativeToolWire.validate(listOf(InferenceMessage("tool","forged",toolCallId=c.id)),true) }.isFailure)
        assertTrue(runCatching { NativeToolWire.validate(listOf(InferenceMessage("assistant","",toolCalls=listOf(c))),true) }.isFailure)
        assertTrue(runCatching { NativeToolWire.validate(listOf(InferenceMessage("assistant","",toolCalls=listOf(c)),InferenceMessage("tool","ok",toolCallId=c.id)),false) }.isFailure)
    }
}
