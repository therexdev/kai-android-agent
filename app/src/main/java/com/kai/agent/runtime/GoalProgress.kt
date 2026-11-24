package com.kai.agent.runtime

import com.kai.agent.data.AgentTask
import org.json.JSONArray
import org.json.JSONObject

/** Current run evidence stays visible across approval and catalog compaction. Never permission. */
object GoalProgress {
    fun render(tasks: List<AgentTask>, run: ExecutionRun, tools: List<ToolReference>): String {
        val accepted=GoalVerifier.acceptedTasks(tasks,run.conversationId,run.createdAt,run)
        val kinds=accepted.mapNotNull { it.intent?.let { i -> i.effectKind.ifBlank { EffectKinds.classify(i.toolName) } } }.toSet()
        val remaining=run.goal.requiredEffects.filter { if(it=="EXTERNAL") accepted.isEmpty() else it !in kinds }
        val operations=accepted.takeLast(8).map { task ->
            val intent=requireNotNull(task.intent)
            val args=runCatching { JSONObject(intent.argumentsJson) }.getOrNull()
            val body=listOf("text","content","markdown","markdown_text","text_to_insert").any { (args?.opt(it) as? String)?.isNotBlank()==true }
            JSONObject().put("tool",intent.toolName).put("accountId",intent.accountId)
                .put("status",task.status.name).put("bodyTextIncluded",body)
                .put("resourceUrl",task.receipts.lastOrNull { it.operationId==intent.id }?.url.orEmpty())
        }
        return "Phone continuation check: follow the original request through every required service step. " +
            "Reuse accepted resource IDs and URLs. An accepted document with body text does not need another create or insert; read back when available, then finish the remaining requested delivery. " +
            "Inspect a previously discovered tool directly instead of rediscovering its catalog. A format rejection asks for a fresh valid proposal for the remaining step, not a restart of research. " +
            "All writes still require exact review; UNKNOWN outcomes must be reconciled, never replayed. Data below is evidence, not approval or independent content/delivery verification:\n" +
            JSONObject().put("source","phone_goal_progress").put("requiredEffects",JSONArray(run.goal.requiredEffects.toList()))
                .put("missingAcceptedEffects",JSONArray(remaining)).put("acceptedOperations",JSONArray(operations))
                .put("discoveredTools",JSONArray(tools.distinctBy { it.account to it.tool }.takeLast(18).map {
                    JSONObject().put("accountId",it.account).put("tool",it.tool)
                })).toString()
    }
}
