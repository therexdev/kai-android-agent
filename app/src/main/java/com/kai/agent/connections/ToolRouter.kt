package com.kai.agent.connections

import com.kai.agent.connections.mcp.*
import com.kai.agent.data.*
import org.json.JSONObject

/** One tool-facing adapter surface. Discovery exposes schemas; it never grants execution authority. */
class ToolRouter(private val settings: ()->AppSettings,private val composio: DirectComposioClient,private val clientFactory: (McpServer)->McpHttpClient = { McpHttpClient(it) }) {
    private data class Binding(val server: McpServer,val remote: String,val tool: ConnectionTool)
    private val clients=java.util.concurrent.ConcurrentHashMap<String,McpHttpClient>()
    private val bindings=java.util.concurrent.ConcurrentHashMap<String,Binding>()
    private fun server(account: String): McpServer? {
        val server=settings().mcpServers.firstOrNull { it.enabled && it.accountId==account }
        if(account.startsWith("mcp:") && server==null) throw ConnectionServiceException("MCP connection is disabled or removed.")
        return server
    }
    fun transport(account: String)=server(account)?.let { "mcp:${it.id}" } ?: "composio"
    fun generation(account: String)=server(account)?.generation ?: settings().connectionGeneration
    fun invalidate() { clients.values.forEach { it.close() };clients.clear();bindings.clear() }
    private fun client(s: McpServer)=clients.getOrPut(s.generation) { clientFactory(s) }
    suspend fun refresh(): ConnectionsSnapshot {
        val s=settings()
        val base=if(s.composioApiKey.isNotBlank()) composio.refresh() else ConnectionsSnapshot(s.composioUserId,s.connectionGeneration,emptyList())
        return base.copy(accounts=base.accounts+s.mcpServers.filter { it.enabled }.map {
            ConnectionAccount(it.accountId,"mcp",s.composioUserId,it.name,"ACTIVE",false,"",providerDisplayName=it.name+" · MCP",identitySource="phone_configured_endpoint")
        })
    }
    suspend fun discoverTools(account: String, query: String): ToolDiscovery {
        val s=server(account) ?: return composio.discoverTools(account,query)
        val loaded=load(s)
        return ToolDiscovery(ToolDiscovery.rank(loaded,query).take(20),false,loaded.size>20,loaded.size)
    }
    private suspend fun load(s: McpServer): List<ConnectionTool> {
        bindings.entries.removeIf { it.value.server.id==s.id }
        val result=mutableListOf<ConnectionTool>();var cursor="";val cursors=mutableSetOf<String>()
        repeat(5) {
            require(server(s.accountId)?.generation==s.generation) { "MCP connection changed." }
            val page=client(s).request("tools/list",JSONObject().apply { if(cursor.isNotBlank()) put("cursor",cursor) })
            val tools=page.getJSONArray("tools");require(tools.length()<=500)
            for(i in 0 until tools.length()) {
                val definition=tools.getJSONObject(i);val name=definition.getString("name");if(name.length !in 1..128 || name.any { it.code<33 || it.code>126 }) continue
                val schema=definition.getJSONObject("inputSchema")
                val redactor=com.kai.agent.diagnostics.DiagnosticRedactor(settings())
                if(redactor.text(schema.toString())!=schema.toString()) continue
                if(runCatching { McpSchema.requireSupported(schema) }.isFailure) continue
                require(schema.optString("type")=="object")
                val id="MCP_"+MemoryRules.digest(s.id).take(16)+"_"+MemoryRules.digest(name).take(24)
                val version=MemoryRules.digest(name+schema.toString()+s.protocol)
                val tool=ConnectionTool(id,redactor.text(definition.optString("title",name)).take(200),redactor.text(definition.optString("description")).take(1000),"mcp",version,false,schema.toString())
                bindings["$id:$version"]=Binding(s,name,tool);result+=tool
            }
            cursor=page.optString("nextCursor").takeIf { it!="null" }.orEmpty()
            if(cursor.isBlank()) return result
            require(cursor.length<=1000 && cursors.add(cursor)) { "MCP pagination did not progress." }
        }
        return result
    }
    suspend fun tool(id: String, version: String): ConnectionTool {
        if(!id.startsWith("MCP_")) return composio.tool(id,version)
        var binding=bindings["$id:$version"]
        if(binding==null) settings().mcpServers.filter { it.enabled && id.startsWith("MCP_"+MemoryRules.digest(it.id).take(16)+"_") }.forEach { load(it) }
        binding=bindings["$id:$version"] ?: throw ConnectionServiceException("MCP action schema changed or is unsupported. Discover and review it again.")
        require(server(binding.server.accountId)?.generation==binding.server.generation) { "MCP connection changed. Prepare a new action." }
        return binding.tool
    }
    suspend fun approveAction(account: String, tool: ConnectionTool, arguments: String): ActionApproval {
        val s=server(account) ?: return composio.approveAction(account,tool,arguments)
        load(s) // Pin the current server schema before approval admission.
        val binding=bindings["${tool.id}:${tool.version}"] ?: throw ConnectionServiceException("MCP action changed; review it again.")
        require(binding.server.generation==s.generation && binding.tool.schemaJson==tool.schemaJson)
        ConnectionArguments.validate(arguments,tool.schemaJson)
        return ActionApproval(settings().composioUserId,s.generation,0,account,tool.id,tool.version,ConnectionArguments.digest(JSONObject(arguments)),System.currentTimeMillis()+120000)
    }
    suspend fun execute(account: String, tool: ConnectionTool, arguments: String, approval: ActionApproval): ExecutionReceipt {
        val s=server(account) ?: return composio.execute(account,tool,arguments,approval)
        val binding=bindings["${tool.id}:${tool.version}"] ?: throw ConnectionServiceException("Discover this MCP action again.")
        require(approval.ownerId==settings().composioUserId && approval.generation==s.generation && approval.accountId==account && approval.toolId==tool.id &&
            approval.version==tool.version && approval.argumentDigest==ConnectionArguments.digest(JSONObject(arguments)) && approval.expiresAtMillis>System.currentTimeMillis() && approval.consumed.compareAndSet(false,true)) { "The exact MCP approval is no longer valid." }
        require(binding.server.generation==s.generation)
        val result=client(s).request("tools/call",JSONObject().put("name",binding.remote).put("arguments",JSONObject(arguments)))
        if(result.optBoolean("isError")) throw ConnectionServiceException("The MCP tool reported an error. Check its outcome before retrying.",outcomeMayBeUnknown=true)
        return ExecutionReceipt(account,tool.id,JSONObject().put("status","ACCEPTED").put("successful",true).put("data",result).toString())
    }
}
