package com.kai.agent.runtime
import com.kai.agent.connections.ConnectionServiceException
import com.kai.agent.connections.ConnectionTool
import com.kai.agent.data.*
import org.json.JSONArray
import org.json.JSONObject
/** General effect protection, independent of service name or model protocol. */
object EffectGuard {
    fun validate(tasks: List<AgentTask>, conversationId: String, requestedAt: Long, accountId: String, tool: ConnectionTool, arguments: JSONObject) {
        if(tool.readOnly) return
        val accountHistory=tasks.filter { it.conversationId==conversationId && it.intent?.accountId==accountId && it.intent.readOnly!=true }
        if(accountHistory.any { it.status in setOf(TaskStatus.UNKNOWN,TaskStatus.RUNNING) })
            throw ConnectionServiceException("An earlier write on this account has an uncertain outcome. Reconcile that action in Tasks before submitting another write.")
        val previous=accountHistory.filter { it.createdAt>=requestedAt }
        if(previous.any { it.submittedAt!=null && it.status in setOf(TaskStatus.ACCEPTED,TaskStatus.SUCCEEDED) && it.intent?.toolName==tool.id &&
            runCatching { canonical(JSONObject(it.intent.argumentsJson))==canonical(arguments) }.getOrDefault(false) })
            throw ConnectionServiceException("This exact action was already accepted in this run. Use its retained receipt; do not submit it again.")
    }
    private fun canonical(value: Any?): String = when(value) {
        null,JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",","{","}") { JSONObject.quote(it)+":"+canonical(value.opt(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",","[","]") { canonical(value.opt(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
}
