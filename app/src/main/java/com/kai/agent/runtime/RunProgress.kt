package com.kai.agent.runtime

import com.kai.agent.agent.AgentDecision
import com.kai.agent.data.MemoryRules
import com.kai.agent.web.PublicWebPolicy
import org.json.JSONArray
import org.json.JSONObject

/** Durable progress control shared by chat, tasks and routines. These hashes are not
 * evidence or authority to repeat an effect. Writes remain exclusively in the intent journal.
 */
data class RunProgress(val outcomes: List<String> = emptyList(), val stalled: Int = 0,
    val collectionClosed: Boolean = false, val reason: String = "") {
    /** Only the explicit user continuation paths call this; workers retain the stop. */
    fun continued()=copy(stalled=0)
    fun observe(decision: AgentDecision, result: String): RunProgress {
        val key=key(decision) ?: return this
        val data=runCatching { JSONObject(result.substringAfter('\n',result)) }.getOrNull()
        val status=data?.optString("status").orEmpty()
        val web=decision is AgentDecision.WebSearch || decision is AgentDecision.ReadWebPage
        val evidence=if(web) JSONObject().put("status",status).put("requestKey",data?.optString("requestKey"))
            .put("code",data?.optString("code")).toString()
        else if(decision is AgentDecision.RecordFindings && status=="RECORDED") data?.optJSONObject("evidence")?.optInt("findingCount").toString()
        else canonical(data ?: result)
        val signature=MemoryRules.digest(key+"\n"+evidence)
        val repeat=signature in outcomes
        val failed=status in setOf("blocked","unavailable","off_topic","REJECTED","NOT_FOUND","NO_TOOLS","FAILED","DENIED","NO_MATCHING_TOOLS","DISCOVERY_FAILED","SCHEMA_UNAVAILABLE","SCHEMA_TOO_LARGE")
        val streak=if(repeat || failed) stalled+1 else 0
        val closed=collectionClosed || web && (outcomes.count { it==signature }>=2 || streak>=3)
        return copy(outcomes=(outcomes+signature).takeLast(256),stalled=streak.coerceAtMost(10000),collectionClosed=closed,
            reason=if(!collectionClosed && closed) "Repeated research produced no new evidence. Use retained findings for the requested output, or report the missing evidence." else reason)
    }
    fun reserve(budget: RunBudget, hasResearch: Boolean): RunProgress {
        if(collectionClosed || !hasResearch) return this
        val remaining=budget.maxModelSteps-budget.modelSteps
        return if(remaining<=8 || budget.inference.maxAttempts-budget.inference.attempts<=4 ||
            budget.maxOutputTokens-maxOf(budget.outputTokens,budget.inference.output)<=4096)
            copy(collectionClosed=true,reason="Collection stopped to reserve the remaining allowance for drafting, review and finishing. Coverage may be partial; state what remains unknown.") else this
    }
    fun validate() { require(outcomes.size<=256 && outcomes.all { it.matches(Regex("[a-f0-9]{64}")) } && stalled in 0..10000 && reason.length<=600) }
    fun context()=JSONObject().put("collectionClosed",collectionClosed).put("consecutiveUnchangedOrFailedResults",stalled).put("reason",reason)
        .put("next",if(collectionClosed) "Retrieve retained findings as needed and prepare the requested output. Preserve exact approvals and all accepted/UNKNOWN receipts. Do not restart collection. If the evidence is insufficient, return a partial result." else "Collect only missing evidence, then prepare the requested output. Cached reads and repeated schemas do not advance the task.")
    companion object {
        private fun key(d: AgentDecision): String? = when(d) {
            is AgentDecision.WebSearch -> "search:"+d.query.lowercase().trim().replace(Regex("\\s+")," ")
            is AgentDecision.ReadWebPage -> "page:"+runCatching { PublicWebPolicy.url(d.url).toString() }.getOrDefault(d.url)
            is AgentDecision.RecordFindings -> "findings"
            is AgentDecision.PrepareAction,is AgentDecision.Reply,is AgentDecision.ProposeMemory -> null
            else -> d.toString()
        }
        private fun canonical(value: Any?): String=when(value) {
            is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(prefix="{",postfix="}") { JSONObject.quote(it)+":"+canonical(value.opt(it)) }
            is JSONArray -> (0 until value.length()).joinToString(prefix="[",postfix="]") { canonical(value.opt(it)) }
            else -> value.toString()
        }
    }
}

object RunProgressJson {
    fun write(p: RunProgress)=p.context().put("version",1).put("outcomes",JSONArray(p.outcomes))
    fun read(o: JSONObject): RunProgress {
        require(o.getInt("version")==1)
        val a=o.getJSONArray("outcomes");require(a.length()<=256)
        return RunProgress((0 until a.length()).map(a::getString),o.getInt("consecutiveUnchangedOrFailedResults"),
            o.getBoolean("collectionClosed"),o.getString("reason")).also { it.validate() }
    }
}
