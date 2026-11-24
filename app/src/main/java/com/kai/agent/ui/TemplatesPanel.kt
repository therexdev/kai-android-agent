package com.kai.agent.ui
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kai.agent.extensions.WorkflowTemplate

@Composable
internal fun TemplatesPanel(state: KaiUiState, actions: KaiUiActions) {
    var editor by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf<WorkflowTemplate?>(null) }
    Column {
        Row { Text("Saved workflows",style=MaterialTheme.typography.titleMedium);TextButton(onClick={editor=WorkflowTemplate.example().json().toString(2)}) { Text("Create / import") } }
        state.templates.forEach { raw ->
            val template=WorkflowTemplate.parse(raw)
            Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainer,modifier=Modifier.fillMaxWidth().padding(vertical=4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(template.name,style=MaterialTheme.typography.titleSmall)
                    template.ordered().forEachIndexed { i,node -> Text("${i+1}. ${node.title}"+(if(node.kind=="REVIEW") " · up to ${node.maxRevisions} reviews" else "")+(if(node.model.isNotBlank()) " · ${node.model}" else ""),style=MaterialTheme.typography.bodySmall) }
                    FlowRow { TextButton(onClick={running=template}) { Text("Run") };TextButton(onClick={editor=raw}) { Text("Edit") };TextButton(onClick={actions.exportTemplate(template.id)}) { Text("Export") };TextButton(onClick={actions.removeTemplate(template.id)}) { Text("Remove") } }
                }
            }
        }
    }
    if(editor!=null) {
        var raw by remember(editor) { mutableStateOf(editor.orEmpty()) }
        val parsed=runCatching { WorkflowTemplate.parse(raw) }
        AlertDialog(onDismissRequest={editor=null},title={Text("Workflow definition")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Paste a workflow JSON or edit this example. Import saves a procedure; it grants no account permissions and starts no work.",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(raw,{raw=it.take(64000)},label={Text("Workflow JSON")},minLines=8)
            if(parsed.isFailure) Text(parsed.exceptionOrNull()?.message ?: "Invalid workflow",color=MaterialTheme.colorScheme.error)
        }},confirmButton={TextButton(onClick={actions.saveTemplate(raw);editor=null},enabled=parsed.isSuccess) { Text("Save") }},dismissButton={TextButton(onClick={editor=null}) { Text("Cancel") }})
    }
    if(running!=null) {
        var input by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest={running=null},title={Text("Run ${running!!.name}")},text={Column { Text("Runs in the current project using your credit policy. External writes require approval.");OutlinedTextField(input,{input=it.take(4000)},label={Text("Topic or input")}) }},confirmButton={TextButton(onClick={actions.runTemplate(running!!.id,input);running=null},enabled=input.isNotBlank()) { Text("Start") }},dismissButton={TextButton(onClick={running=null}) { Text("Cancel") }})
    }
}
