package com.kai.agent.agent

import com.kai.agent.connections.ConnectionServiceException
import com.kai.agent.connections.ConnectionTool
import com.kai.agent.data.*
import org.json.JSONObject

/** Resource receipts are scoped to the conversation, connection owner, generation and account. */
object ResourceReuseGuard {
    private val creates = setOf("GOOGLEDOCS_CREATE_DOCUMENT","GOOGLEDOCS_CREATE_DOCUMENT_MARKDOWN","GOOGLEDRIVE_CREATE_FILE")
    fun linkRequest(request: String): Boolean {
        val text=request.replace("\\n"," ").lowercase().replace(Regex("\\s+"),"")
        return text.length <= 200 && (text.contains("link") || text.contains("url")) && (text.contains("doc") || text.contains("it") || text.contains("that")) &&
            !Regex("create|make|research|look.up|insert|write|add|another|copy").containsMatchIn(text)
    }
    private fun relevant(tasks: List<AgentTask>, cid: String, boundary: Long, owner: String, generation: String) = tasks.filter {
        it.conversationId==cid && it.createdAt>boundary && it.intent?.ownerId==owner && it.intent.connectionGeneration==generation
    }
    fun documentUrl(task: AgentTask): String? {
        val receipt=task.receipts.lastOrNull { it.operationId==task.intent?.id && it.status in setOf("ACCEPTED","SUCCEEDED") } ?: return null
        ToolResultSummary.displayUrl(receipt.url)?.let { return it }
        // Construct only a canonical Docs URL from a typed creation receipt, never model-supplied IDs.
        if(task.intent?.toolName !in creates || !receipt.providerId.matches(Regex("[A-Za-z0-9_-]{10,200}"))) return null
        if(task.intent?.toolName=="GOOGLEDRIVE_CREATE_FILE" && runCatching { JSONObject(task.intent.argumentsJson).optString("mimeType") }.getOrNull()!="application/vnd.google-apps.document") return null
        return "https://docs.google.com/document/d/${receipt.providerId}/edit"
    }
    fun linkReply(request: String,tasks: List<AgentTask>,cid: String,boundary: Long,owner: String,generation: String,activeAccounts: Set<String>): String? {
        if(!linkRequest(request)) return null
        val urls=relevant(tasks,cid,boundary,owner,generation).filter { it.intent?.accountId in activeAccounts && it.intent?.toolName in creates && it.submittedAt!=null && it.status in setOf(TaskStatus.ACCEPTED,TaskStatus.SUCCEEDED) }.mapNotNull(::documentUrl).distinct()
        if(urls.isEmpty()) return null
        return (if(urls.size==1) "Here’s the document link. The creation receipt alone does not verify its contents:" else "These are the documents already created in this conversation. Their creation receipts do not verify their contents:")+"\n\n"+urls.joinToString("\n")
    }
    fun validate(request: String,cid: String,boundary: Long,owner: String,generation: String,action: AgentDecision.PrepareAction,tool: ConnectionTool,tasks: List<AgentTask>) {
        if(tool.readOnly || tool.id !in creates) return
        if(linkRequest(request)) throw ConnectionServiceException("This request asks for an existing link. Read existing resources or use receipts; do not create another document.")
        if(Regex("(?i)\\b(another|separate|duplicate|copy)\\b").containsMatchIn(request)) return
        val title=title(action.arguments) ?: return
        val prior=relevant(tasks,cid,boundary,owner,generation).firstOrNull { task ->
            task.intent?.accountId==action.accountId && task.intent.toolName in creates &&
                task.status !in setOf(TaskStatus.FAILED,TaskStatus.CANCELLED,TaskStatus.DRAFT) &&
                runCatching { title(JSONObject(task.intent.argumentsJson)) }.getOrNull()==title
        }
        if(prior!=null) throw ConnectionServiceException("A document with this title was already prepared or submitted in this conversation (${prior.status}). Reuse its receipt and ID, check an uncertain outcome, or ask whether a separate copy is wanted.")
    }
    private fun title(args: JSONObject) = sequenceOf("title","name").mapNotNull { (args.opt(it) as? String)?.trim()?.lowercase()?.takeIf(String::isNotBlank) }.firstOrNull()
}
