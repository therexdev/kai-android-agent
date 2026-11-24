package com.kai.agent.ui
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kai.agent.connections.mcp.McpServer

@Composable
internal fun McpConnectionsPanel(state: KaiUiState, actions: KaiUiActions) {
    var editing by remember { mutableStateOf<McpServer?>(null) }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<McpServer?>(null) }
    Column {
        Row { Text("MCP services",style=MaterialTheme.typography.titleMedium);TextButton(onClick={adding=true}) { Text("Add service") } }
        Text("Connect a public HTTPS MCP endpoint you trust. Tokens stay encrypted on this phone. Tool annotations do not bypass approval; every MCP action is reviewed.",style=MaterialTheme.typography.bodySmall)
        state.mcpServers.forEach { server ->
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                Column(Modifier.weight(1f)) { Text(server.name);Text(server.protocol,style=MaterialTheme.typography.bodySmall) }
                Switch(checked=server.enabled,onCheckedChange={actions.toggleMcpServer(server.id,it)})
            }
            FlowRow { TextButton(onClick={actions.checkMcpServer(server.id)},enabled=server.enabled) { Text("Check tools") };TextButton(onClick={editing=server}) { Text("Edit") };TextButton(onClick={removing=server}) { Text("Remove") } }
        }
    }
    if(adding || editing!=null) {
        var name by remember(editing) { mutableStateOf(editing?.name.orEmpty()) }
        var endpoint by remember(editing) { mutableStateOf(editing?.endpoint.orEmpty()) }
        var token by remember { mutableStateOf("") }
        var protocol by remember(editing) { mutableStateOf(editing?.protocol ?: "2026-07-28") }
        val valid=runCatching { McpServer(editing?.id ?: "new",name,endpoint,token,protocol).validate() }.isSuccess
        AlertDialog(onDismissRequest={adding=false;editing=null},title={Text("MCP service")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name,{name=it.take(100)},label={Text("Name")})
            OutlinedTextField(endpoint,{endpoint=it.take(2048)},label={Text("HTTPS endpoint")})
            OutlinedTextField(token,{token=it.take(4096)},label={Text(if(editing==null) "Bearer token · optional" else "Replacement token · blank keeps current")},visualTransformation=PasswordVisualTransformation())
            Text("No browser session, local network access, code execution or OAuth token sharing is implied.",style=MaterialTheme.typography.bodySmall)
            listOf("2026-07-28","2025-11-25").forEach { version -> FilterChip(selected=protocol==version,onClick={protocol=version},label={Text(version)}) }
        }},confirmButton={TextButton(onClick={actions.saveMcpServer(editing?.id.orEmpty(),name,endpoint,token,protocol);adding=false;editing=null},enabled=valid) { Text("Save") }},dismissButton={TextButton(onClick={adding=false;editing=null}) { Text("Cancel") }})
    }
    if(removing!=null) AlertDialog(onDismissRequest={removing=null},title={Text("Remove ${removing!!.name}?")},text={Text("Removes this endpoint and its saved token. Pending approvals are invalidated. Submitted action receipts remain available for reconciliation.")},confirmButton={TextButton(onClick={actions.removeMcpServer(removing!!.id);removing=null}) { Text("Remove") }},dismissButton={TextButton(onClick={removing=null}) { Text("Cancel") }})
}
