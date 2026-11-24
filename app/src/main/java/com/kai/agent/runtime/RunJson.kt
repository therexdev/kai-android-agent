package com.kai.agent.runtime

import org.json.JSONArray
import org.json.JSONObject

object RunJson {
    fun write(r: ExecutionRun) = JSONObject().put("schemaVersion",r.schemaVersion).put("id",r.id).put("conversationId",r.conversationId)
        .put("sourceId",r.sourceId).put("origin",r.origin.name).put("request",r.goal.request).put("kind",r.goal.kind)
        .put("projectId",r.projectId).put("revision",r.revision).put("state",r.state.name).put("owner",r.owner)
        .put("privacyRevision",r.privacyRevision).put("createdAt",r.createdAt).put("updatedAt",r.updatedAt)
        .put("archive",r.archive).put("modelRoute",r.modelRoute).put("protocol",r.protocol).put("detail",r.detail)
        .put("failure",r.failure?.name ?: "").put("budget",JSONObject().put("steps",r.budget.modelSteps)
            .put("input",r.budget.inputTokens).put("output",r.budget.outputTokens).put("corrections",r.budget.corrections)
            .put("maxSteps",r.budget.maxModelSteps).put("maxOutput",r.budget.maxOutputTokens).put("inference",InferenceLedgerJson.write(r.budget.inference)))
        .put("research",com.kai.agent.web.ResearchEvidenceJson.write(r.research))
        .put("tools",JSONArray(r.tools.map { JSONObject().put("account",it.account).put("tool",it.tool).put("version",it.version) }))
        .put("observations",JSONArray(r.observations.map { JSONObject().put("id",it.id).put("stepId",it.stepId).put("content",it.content).put("createdAt",it.createdAt) }))
        .put("artifacts",JSONArray(r.artifacts.map { JSONObject().put("id",it.id).put("version",it.version).put("text",it.text).put("sources",JSONArray(it.sourceUrls)).put("createdAt",it.createdAt).put("kind",it.kind) }))
        .put("events",JSONArray(r.events.map { JSONObject().put("sequence",it.sequence).put("at",it.at).put("kind",it.kind).put("code",it.code) }))
    fun read(o: JSONObject): ExecutionRun {
        require(o.getInt("schemaVersion")==1) { "This run needs a newer KAI version. Its data was preserved." }
        val b=o.getJSONObject("budget")
        fun <T> list(key: String, read: (JSONObject)->T): List<T> = o.getJSONArray(key).let { a -> (0 until a.length()).map { read(a.getJSONObject(it)) } }
        return ExecutionRun(o.getString("id"),o.getString("conversationId"),o.getString("sourceId"),enumValueOf(o.getString("origin")),
            GoalContract(o.getString("request"),o.getString("kind")),projectId=o.optString("projectId"),revision=o.getLong("revision"),
            state=enumValueOf(o.getString("state")),owner=o.getString("owner"),privacyRevision=o.getLong("privacyRevision"),
            createdAt=o.getLong("createdAt"),updatedAt=o.getLong("updatedAt"),archive=o.getString("archive"),modelRoute=o.optString("modelRoute"),protocol=o.optString("protocol"),
            tools=list("tools") { ToolReference(it.getString("account"),it.getString("tool"),it.getString("version")) },
            budget=RunBudget(b.getInt("steps"),b.getLong("input"),b.getLong("output"),b.getInt("corrections"),b.getInt("maxSteps"),b.getLong("maxOutput"),
                if(b.has("inference")) InferenceLedgerJson.read(b.getJSONObject("inference")) else InferenceLedger(
                    attempts=b.getInt("steps"),unpriced=b.getInt("steps"),input=b.getLong("input"),output=b.getLong("output"),
                    maxAttempts=maxOf(80,b.getInt("maxSteps")*2+4).coerceAtMost(10000),legacy=b.getInt("steps")>0)),
            observations=o.optJSONArray("observations")?.let { a -> (0 until a.length()).map { i -> val x=a.getJSONObject(i);RunObservation(x.getString("id"),x.getString("stepId"),x.getString("content"),x.getLong("createdAt")) } }.orEmpty(),
            artifacts=o.optJSONArray("artifacts")?.let { a -> (0 until a.length()).map { i -> val x=a.getJSONObject(i);val urls=x.getJSONArray("sources");
                RunArtifact(x.getString("id"),x.getInt("version"),x.getString("text"),(0 until urls.length()).map(urls::getString),x.getLong("createdAt"),x.getString("kind")) } }.orEmpty(),
            detail=o.optString("detail"),failure=o.optString("failure").takeIf { it.isNotBlank() }?.let { enumValueOf<FailureCode>(it) },
            events=list("events") { RunEvent(it.getLong("sequence"),it.getLong("at"),it.getString("kind"),it.optString("code")) },
            research=if(o.has("research")) com.kai.agent.web.ResearchEvidenceJson.read(o.getJSONObject("research")) else com.kai.agent.web.ResearchEvidence(legacy=o.getString("archive")!="[]"))
    }
}
