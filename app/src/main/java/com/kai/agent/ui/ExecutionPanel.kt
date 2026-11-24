package com.kai.agent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kai.agent.runtime.*

@Composable
internal fun ExecutionPanel(state: KaiUiState, actions: KaiUiActions, selected: ExecutionRun? = null) {
    val run=selected ?: state.executions.lastOrNull { it.conversationId==state.currentConversationId && it.origin==RunOrigin.CHAT } ?: return
    if(run.state==ExecutionState.CANCELLED || run.state==ExecutionState.COMPLETED && run.research.receipts.isEmpty()) {
        if(state.speechAudible && run.conversationId==state.currentConversationId)
            TextButton(onClick=actions::stopSpeakingOnly) { Text("Stop speaking") }
        return
    }
    var expanded by remember(run.id) { mutableStateOf(false) }
    Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainer,modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(when(run.state) {
                ExecutionState.RUNNING -> "Working on your request"
                ExecutionState.WAITING_APPROVAL -> "Ready for review"
                ExecutionState.WAITING_INPUT -> "Your answer is needed"
                ExecutionState.RECONCILING -> "Action outcome needs checking"
                ExecutionState.PARTIAL -> "Progress saved · work remains"
                ExecutionState.FAILED -> "Stopped · progress saved"
                ExecutionState.COMPLETED -> "Research saved · review sources"
                else -> "Paused · progress saved"
            },style=MaterialTheme.typography.titleSmall)
            if(run.detail.isNotBlank()) Text(run.detail,style=MaterialTheme.typography.bodySmall)
            if(run.resumable && (run.budget.exhausted || run.failure==FailureCode.BUDGET)) {
                val allowance=InferenceLedger.allowance(run.origin!=RunOrigin.CHAT,state.runCreditLimit)
                Text("Continue allows up to ${allowance.maxAttempts} more model requests and ${allowance.maxMillis/60000} minutes of model time"+
                    if(state.runCreditLimit>0) ", with a ${state.runCreditLimit}-credit estimate allowance." else ". Your credit policy still applies.",style=MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                if(run.resumable && !state.isBusy) {
                    TextButton(onClick={actions.continueExecution(run.id,false)}) { Text("Continue") }
                    if(run.archive!="[]") TextButton(onClick={actions.continueExecution(run.id,true)}) { Text("Answer from findings") }
                }
                if(run.state==ExecutionState.RUNNING) TextButton(onClick={actions.pauseExecution(run.id)}) { Text("Pause task") }
                if(run.state==ExecutionState.WAITING_INPUT) TextButton(onClick={actions.pauseExecution(run.id)}) { Text("Set aside · new request") }
                if(state.speechAudible) TextButton(onClick=actions::stopSpeakingOnly) { Text("Stop speaking") }
                TextButton(onClick={expanded=!expanded}) { Text(if(expanded) "Hide progress" else "View progress") }
            }
            if(expanded) {
                Text("${run.budget.modelSteps} steps · ${run.budget.inputTokens+run.budget.outputTokens} reported tokens",style=MaterialTheme.typography.bodySmall)
                val allowance=run.budget.inference
                Text("${allowance.input}/${allowance.maxInput} input allowance · ${allowance.output}/${run.budget.maxOutputTokens} output allowance",style=MaterialTheme.typography.bodySmall)
                Text("${allowance.attempts}/${allowance.maxAttempts} model requests · ${allowance.summaries} summaries · ${allowance.millis/1000}s model time",style=MaterialTheme.typography.bodySmall)
                Text("Catalog cost estimate: ${CreditPricing.display(allowance.estimatedMicroCredits)} credits"+
                    if(allowance.unpriced>0) " · ${allowance.unpriced} requests without a price" else " · not settled billing",style=MaterialTheme.typography.bodySmall)
                if(allowance.unreported>0) Text("Usage was not returned for ${allowance.unreported} requests; their token and cost allowances are retained.",style=MaterialTheme.typography.bodySmall)
                if(allowance.legacy) Text("Calls made before 0.5.4 were not fully measured.",style=MaterialTheme.typography.bodySmall)
                if(run.research.receipts.isNotEmpty() || run.research.legacy) {
                    Text(run.research.reviewSummary(),style=MaterialTheme.typography.bodySmall)
                    run.research.findings.takeLast(4).forEach { finding ->
                        val source=run.research.sources.first { it.id==finding.sourceId }
                        Text(finding.claim,style=MaterialTheme.typography.bodySmall)
                        Text("Source quotation: ${finding.quote}",style=MaterialTheme.typography.bodySmall)
                        Text("${if(source.kind==com.kai.agent.web.SourceKind.SEARCH_SNIPPET) "Search excerpt" else "Page excerpt"}: ${source.url}",style=MaterialTheme.typography.bodySmall)
                    }
                }
                run.artifacts.lastOrNull()?.let { artifact ->
                    Text("Saved draft · version ${artifact.version}",style=MaterialTheme.typography.titleSmall)
                    MarkdownReply(artifact.text)
                }
                run.events.takeLast(8).forEach { event ->
                    Text(when(event.kind) { "document_text_verified" -> "Document text matched a later read"; "checkpoint" -> "Progress saved"; "step_admitted" -> "Next step started"; "claimed" -> "Run started"; "outcome" -> "Run paused or finished"; else -> event.kind.replace('_',' ') },style=MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
