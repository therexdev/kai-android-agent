package com.kai.agent.runtime

import org.json.JSONArray
import org.json.JSONObject

/** Read/write compatibility for 0.5.6 records. These retired heuristics never admit or stop work. */
data class RunProgress(val outcomes: List<String> = emptyList(), val stalled: Int = 0,
    val collectionClosed: Boolean = false, val reason: String = "") {
    fun validate() { require(outcomes.size<=256 && outcomes.all { it.matches(Regex("[a-f0-9]{64}")) } && stalled in 0..10000 && reason.length<=600) }
}
object RunProgressJson {
    fun write(p: RunProgress)=JSONObject().put("version",1).put("outcomes",JSONArray(p.outcomes))
        .put("consecutiveUnchangedOrFailedResults",p.stalled).put("collectionClosed",p.collectionClosed).put("reason",p.reason)
    fun read(o: JSONObject): RunProgress {
        require(o.getInt("version")==1)
        val a=o.getJSONArray("outcomes");require(a.length()<=256)
        return RunProgress((0 until a.length()).map(a::getString),o.getInt("consecutiveUnchangedOrFailedResults"),
            o.getBoolean("collectionClosed"),o.getString("reason")).also { it.validate() }
    }
}
