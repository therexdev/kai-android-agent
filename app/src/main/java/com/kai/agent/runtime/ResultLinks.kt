package com.kai.agent.runtime
import com.kai.agent.agent.ResourceReuseGuard
import com.kai.agent.data.AgentTask
/** Final result links are phone receipts. Model-invented Google document IDs are never displayed as results. */
object ResultLinks {
    private val doc=Regex("https://docs\\.google\\.com/document/(?:u/\\d+/)?d/([^/?#\\s<>]+)[^\\s<>]*")
    fun documents(tasks: List<AgentTask>, cid: String, since: Long, run: ExecutionRun): List<String> = tasks.filter { task ->
        task.conversationId==cid && task.createdAt>=since && task.submittedAt!=null && task.intent?.readOnly!=true &&
            (task.intent?.executionId==run.id || run.events.any { it.kind=="effect_recorded" && it.code==task.id })
    }
        .mapNotNull(ResourceReuseGuard::documentUrl).distinct()
    fun render(text: String, trusted: List<String>): String {
        val ids=trusted.mapNotNull { doc.find(it)?.groupValues?.get(1) }.toSet()
        val cleaned=doc.replace(text) { match -> if(match.groupValues[1].trimEnd('.',',',')') in ids) match.value else "[unverified document link omitted]" }
        val missing=trusted.filterNot { cleaned.contains(it) }
        return cleaned+(if(missing.isEmpty()) "" else "\n\n"+missing.joinToString("\n"))
    }
}
