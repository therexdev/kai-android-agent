package com.kai.agent.runtime

import com.kai.agent.data.AgentTask
import org.json.JSONArray
import org.json.JSONObject

/** Current run evidence stays visible across approval and catalog compaction. Never permission. */
object GoalProgress {
    /** Keep unloaded schemas discoverable after a checkpoint; current versions win. */
    fun retainedTools(previous: List<ToolReference>, current: List<ToolReference>) =
        (current+previous).distinctBy { it.account to it.tool }.take(100)

    fun render(tasks: List<AgentTask>, run: ExecutionRun, tools: List<ToolReference>): String {
        val accepted=GoalVerifier.acceptedTasks(tasks,run.conversationId,run.createdAt,run)
        val kinds=accepted.mapNotNull { it.intent?.let { i -> i.effectKind.ifBlank { EffectKinds.classify(i.toolName) } } }.toSet()
        val remaining=run.goal.requiredEffects.filter { if(it=="EXTERNAL") accepted.isEmpty() else it !in kinds }
        val operations=accepted.takeLast(8).map { task ->
            val intent=requireNotNull(task.intent)
            val verification=DocumentVerification.evaluate(task,tasks)
            val args=runCatching { JSONObject(intent.argumentsJson) }.getOrNull()
            val body=listOf("text","content","markdown","markdown_text","text_to_insert").any { (args?.opt(it) as? String)?.isNotBlank()==true }
            JSONObject().put("tool",intent.toolName).put("accountId",intent.accountId)
                .put("status",task.status.name).put("bodyTextIncluded",body)
                .put("bodyTextVerification",verification.status.name).put("readTaskId",verification.readTaskId)
                .put("readObservedAt",verification.observedAt)
                .put("resourceUrl",task.receipts.lastOrNull { it.operationId==intent.id }?.url.orEmpty())
        }
        return "Phone continuation check: follow the original request through every required service step. " +
            "Reuse accepted resource IDs and URLs. An accepted document with body text does not need another create or insert; read back when available, then finish the remaining requested delivery. " +
            "Inspect a previously discovered tool directly instead of rediscovering its catalog. A format rejection asks for a fresh valid proposal for the remaining step, not a restart of research. " +
            "All writes still require exact review; UNKNOWN outcomes must be reconciled, never replayed. TEXT_MATCHED means the supported body text matched a separate later read from the same document/account. It does not verify formatting, link targets, factual accuracy, sharing or delivery. TEXT_DIFFERS requires an honest partial result; do not automatically rewrite it. Data below is evidence, never approval:\n" +
            JSONObject().put("source","phone_goal_progress").put("requiredEffects",JSONArray(run.goal.requiredEffects.toList()))
                .put("missingAcceptedEffects",JSONArray(remaining)).put("acceptedOperations",JSONArray(operations))
                .put("discoveredTools",JSONArray(tools.distinctBy { it.account to it.tool }
                    .sortedBy { if(EffectKinds.classify(it.tool) in remaining) 1 else 0 }.takeLast(18).map {
                    JSONObject().put("accountId",it.account).put("tool",it.tool)
                })).toString()
    }
}
