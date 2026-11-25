package com.kai.agent.agent

import com.kai.agent.connections.ConnectionServiceException
import com.kai.agent.connections.ConnectionTool
import com.kai.agent.data.*
import org.json.JSONObject

/** Resource receipts are scoped to the conversation, connection owner, generation and account. */
object ResourceReuseGuard {
    private val creates = DocumentResources.creates
    private fun relevant(tasks: List<AgentTask>, cid: String, boundary: Long, owner: String, generation: String) = tasks.filter {
        it.conversationId==cid && it.createdAt>boundary && it.intent?.ownerId==owner && it.intent.connectionGeneration==generation
    }
    fun documentUrl(task: AgentTask): String? {
        val receipt=task.receipts.lastOrNull { it.operationId==task.intent?.id && it.status in setOf("ACCEPTED","SUCCEEDED") } ?: return null
        val intent=task.intent ?: return null
        if(intent.toolName in DocumentResources.fileCreates && !runCatching { DocumentResources.createsDocument(intent.toolName,JSONObject(intent.argumentsJson)) }.getOrDefault(false)) return null
        ToolResultSummary.displayUrl(receipt.url)?.takeIf { it.startsWith("https://docs.google.com/document/") }?.let { return it }
        // Construct only a canonical Docs URL from a typed creation receipt, never model-supplied IDs.
        if(intent.toolName !in creates || !receipt.providerId.matches(Regex("[A-Za-z0-9_-]{10,200}"))) return null
        return "https://docs.google.com/document/d/${receipt.providerId}/edit"
    }
    fun validate(request: String,cid: String,boundary: Long,owner: String,generation: String,action: AgentDecision.PrepareAction,tool: ConnectionTool,tasks: List<AgentTask>) {
        if(tool.readOnly || tool.id !in creates) return
        if(Regex("(?i)\\b(another|separate|duplicate|copy)\\b").containsMatchIn(request)) return
        val title=title(action.arguments) ?: return
        val prior=relevant(tasks,cid,boundary,owner,generation).firstOrNull { task ->
            task.intent?.accountId==action.accountId && task.intent.toolName in creates &&
                task.status !in setOf(TaskStatus.FAILED,TaskStatus.CANCELLED,TaskStatus.DRAFT) &&
                runCatching { title(JSONObject(requireNotNull(task.intent).argumentsJson)) }.getOrNull()==title
        }
        if(prior!=null) throw ConnectionServiceException("A document with this title was already prepared or submitted in this conversation (${prior.status}). Reuse its receipt and ID, check an uncertain outcome, or ask whether a separate copy is wanted.")
    }
    private fun title(args: JSONObject) = sequenceOf("title","name").mapNotNull { (args.opt(it) as? String)?.trim()?.lowercase()?.takeIf(String::isNotBlank) }.firstOrNull()
}
