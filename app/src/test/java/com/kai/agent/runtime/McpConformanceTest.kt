package com.kai.agent.runtime
import com.kai.agent.connections.*
import com.kai.agent.connections.mcp.*
import com.kai.agent.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class McpConformanceTest {
    private fun server(protocol: String="2026-07-28")=McpServer("service","Service","https://example.org/mcp","private-mcp-token",protocol)
    private fun client(server: McpServer, block: (Request,JSONObject)->JSONObject): McpHttpClient {
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request();val body=Buffer().also { request.body!!.writeTo(it) }.readUtf8();val rpc=JSONObject(body)
            val result=block(request,rpc)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if(rpc.has("id")) 200 else 202).message("OK").header("Mcp-Session-Id","fixture-session")
                .body(JSONObject().put("jsonrpc","2.0").put("id",rpc.optString("id")).put("result",result).toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        return McpHttpClient(server,http)
    }
    @Test fun `modern calls carry their own identity and version and never acquire a session`()=runBlocking {
        var calls=0;val s=server()
        val c=client(s) { request,rpc ->
            calls++;assertEquals("tools/list",rpc.getString("method"));assertEquals("2026-07-28",request.header("MCP-Protocol-Version"))
            assertEquals("tools/list",request.header("Mcp-Method"));assertNull(request.header("Mcp-Session-Id"))
            assertEquals("Bearer private-mcp-token",request.header("Authorization"))
            assertEquals("2026-07-28",rpc.getJSONObject("params").getJSONObject("_meta").getString("io.modelcontextprotocol/protocolVersion"))
            JSONObject().put("resultType","complete").put("tools",JSONArray())
        }
        c.request("tools/list");c.request("tools/list");assertEquals(2,calls)
    }
    @Test fun `legacy profile initializes once and pins the negotiated session`()=runBlocking {
        val methods=mutableListOf<String>();val s=server("2025-11-25")
        val c=client(s) { request,rpc ->
            val method=rpc.getString("method");methods+=method
            if(method=="initialize") JSONObject().put("protocolVersion","2025-11-25")
            else { assertEquals("fixture-session",request.header("Mcp-Session-Id"));JSONObject().put("tools",JSONArray()) }
        }
        c.request("tools/list");c.request("tools/list")
        assertEquals(listOf("initialize","notifications/initialized","tools/list","tools/list"),methods)
    }
    @Test fun `input required is never automatically replayed as a tool invocation`()=runBlocking {
        var calls=0;val c=client(server()) { _,_ -> calls++;JSONObject().put("resultType","input_required").put("requestState","opaque") }
        val e=runCatching { c.request("tools/call",JSONObject().put("name","write").put("arguments",JSONObject())) }.exceptionOrNull()
        assertTrue(e is ConnectionServiceException && e.outcomeMayBeUnknown);assertEquals(1,calls)
    }
    @Test fun `MCP annotations cannot bypass exact approval and credentials revoke old bindings`()=runBlocking {
        val s=server();var settings=AppSettings(composioUserId="owner",mcpServers=listOf(s));var writes=0
        val router=ToolRouter({settings},DirectComposioClient(), { target -> client(target) { _,rpc ->
            if(rpc.getString("method")=="tools/list") JSONObject().put("tools",JSONArray().put(JSONObject().put("name","write")
                .put("inputSchema",JSONObject("""{"type":"object","properties":{"text":{"type":"string"}},"required":["text"],"additionalProperties":false}"""))
                .put("annotations",JSONObject().put("readOnlyHint",true))))
            else { writes++;JSONObject().put("resultType","complete").put("content",JSONArray().put(JSONObject().put("type","text").put("text","accepted"))) }
        } })
        val tool=router.discoverTools(s.accountId,"").tools.single();assertFalse(tool.readOnly)
        val args="""{"text":"approved"}"""
        val intent=ActionIntent(toolName=tool.id,accountId=s.accountId,ownerId="owner",connectionGeneration=s.generation,transportId="mcp:${s.id}")
        TaskRules.validateBinding(intent,settings)
        val approval=router.approveAction(s.accountId,tool,args)
        router.execute(s.accountId,tool,args,approval);assertEquals(1,writes)
        assertTrue(runCatching { router.execute(s.accountId,tool,args,approval) }.isFailure);assertEquals(1,writes)
        settings=settings.copy(mcpServers=listOf(s.copy(token="changed")))
        assertTrue(runCatching { TaskRules.validateBinding(intent,settings) }.isFailure)
        val exported=KaiJson.encode(KaiState(settings=settings),includeSecrets=false)
        assertFalse(exported.contains("changed"));assertFalse(exported.contains("private-mcp-token"))
    }
    @Test fun `unsupported references headers and private endpoints fail closed`() {
        for(schema in listOf("""{"type":"object","${'$'}ref":"https://private.example/schema"}""", """{"type":"object","properties":{"key":{"type":"string","x-mcp-header":"Authorization"}}}""")) {
            assertTrue(runCatching { McpSchema.requireSupported(JSONObject(schema)) }.isFailure)
        }
        assertTrue(runCatching { server().copy(endpoint="https://127.0.0.1/mcp").validate() }.isFailure)
        assertTrue(runCatching { server().copy(endpoint="https://example.org/mcp?token=secret").validate() }.isFailure)
    }
}
