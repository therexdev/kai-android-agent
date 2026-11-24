package com.kai.agent.data

import org.json.JSONArray
import org.json.JSONObject

internal object WorkspaceJson {
    private fun array(values: List<Any>) = JSONArray().also { a -> values.forEach(a::put) }
    private fun objects(o: JSONObject, key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
    fun strings(o: JSONObject, key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
    fun write(root: JSONObject, state: KaiState): JSONObject {
        val persistentConversations = state.conversations.filterNot { it.temporary }.map { it.id }.toSet()
        return root.put("runs", array(state.runs.map(::run)))
            .put("routines", array(state.routines.map(::routine)))
            .put("projects", array(state.projects.map(::project)))
            .put("documents", array(state.documents.filter { it.projectId.isNotBlank() || it.conversationId in persistentConversations }.map(::document)))
            .put("deletedProjectIds", array(state.deletedProjectIds)).put("deletedDocumentIds", array(state.deletedDocumentIds))
    }
    fun read(base: KaiState, root: JSONObject): KaiState = base.copy(
        runs = objects(root,"runs").map(::readRun), routines = objects(root,"routines").map(::readRoutine),
        projects = objects(root,"projects").map(::readProject), documents = objects(root,"documents").map(::readDocument),
        deletedProjectIds = strings(root,"deletedProjectIds"), deletedDocumentIds = strings(root,"deletedDocumentIds"))
    private fun step(s: RunStep) = JSONObject().put("id",s.id).put("title",s.title).put("instructions",s.instructions)
        .put("status",s.status.name).put("output",s.output).put("pendingActionId",s.pendingActionId)
        .put("nodeId",s.nodeId).put("modelId",s.modelId).put("kind",s.kind).put("reviewTarget",s.reviewTarget).put("revisions",s.revisions).put("maxRevisions",s.maxRevisions).put("revisionFeedback",s.revisionFeedback).put("turns",s.turns).put("contextRevision",s.contextRevision).put("completedAt",s.completedAt).put("contextSummary",s.contextSummary)
        .put("messages",array(s.messages.map { com.kai.agent.inference.NativeToolWire.persistedMessageJson(com.kai.agent.inference.InferenceMessage(it.role,it.content,toolCalls=it.toolCalls,toolCallId=it.toolCallId,providerReplay=it.providerReplay)) }))
        .put("tools",array(s.tools.map { JSONObject().put("accountId",it.accountId).put("toolId",it.toolId).put("version",it.version) }))
    private fun readStep(o: JSONObject) = RunStep(o.getString("id"),o.getString("title"),o.getString("instructions"),
        enumValueOf(o.getString("status")),o.optString("output"),objects(o,"messages").map { RunMessage(it.getString("role"),it.getString("content"), com.kai.agent.inference.NativeToolWire.Accumulator().apply { add(it.optJSONArray("tool_calls"),false) }.finish(),it.optString("tool_call_id"),com.kai.agent.inference.ProviderReplay.read(it)) },
        objects(o,"tools").map { RunTool(it.getString("accountId"),it.getString("toolId"),it.getString("version")) },
        o.optString("pendingActionId"),o.optInt("turns"),o.optLong("contextRevision",-1),o.optLong("completedAt"),o.optString("contextSummary"),o.optString("nodeId"),o.optString("modelId"),o.optString("kind","AGENT"),o.optString("reviewTarget"),o.optInt("revisions"),o.optInt("maxRevisions",2),o.optString("revisionFeedback"))
    fun run(r: AgentRun) = JSONObject().put("id",r.id).put("title",r.title).put("goal",r.goal)
        .put("projectId",r.projectId).put("characterId",r.characterId).put("conversationId",r.conversationId)
        .put("status",r.status.name).put("steps",array(r.steps.map(::step))).put("lease",r.lease)
        .put("detail",r.detail).put("routineId",r.routineId).put("createdAt",r.createdAt).put("updatedAt",r.updatedAt)
    private fun readRun(o: JSONObject) = AgentRun(o.getString("id"),o.getString("title"),o.getString("goal"),
        o.optString("projectId"),o.optString("characterId","kai"),o.getString("conversationId"),enumValueOf(o.getString("status")),
        objects(o,"steps").map(::readStep),o.optString("lease"),o.optString("detail"),o.optString("routineId"),o.getLong("createdAt"),o.getLong("updatedAt"))
        .also(RunRules::validate)
    private fun routine(r: AgentRoutine) = JSONObject().put("id",r.id).put("title",r.title).put("goal",r.goal)
        .put("projectId",r.projectId).put("characterId",r.characterId).put("frequency",r.frequency.name)
        .put("hour",r.hour).put("minute",r.minute).put("weekday",r.weekday).put("zoneId",r.zoneId)
        .put("enabled",r.enabled).put("revision",r.revision).put("nextRunAt",r.nextRunAt).put("lastRunId",r.lastRunId).put("createdAt",r.createdAt)
    private fun readRoutine(o: JSONObject) = AgentRoutine(o.getString("id"),o.getString("title"),o.getString("goal"),
        o.optString("projectId"),o.optString("characterId","kai"),enumValueOf(o.getString("frequency")),o.getInt("hour"),o.getInt("minute"),
        o.getInt("weekday"),o.getString("zoneId"),o.getBoolean("enabled"),o.getLong("revision"),o.getLong("nextRunAt"),o.optString("lastRunId"),o.getLong("createdAt"))
    private fun project(p: KaiProject) = JSONObject().put("id",p.id).put("name",p.name).put("memory",p.memory)
        .put("revision",p.revision).put("forgottenBefore",p.forgottenBefore).put("createdAt",p.createdAt).put("updatedAt",p.updatedAt)
    private fun readProject(o: JSONObject) = KaiProject(o.getString("id"),o.getString("name"),o.optString("memory"),
        o.optLong("revision",1),o.optLong("forgottenBefore"),o.getLong("createdAt"),o.getLong("updatedAt"))
    fun document(d: ProjectDocument) = JSONObject().put("id",d.id).put("name",d.name).put("mimeType",d.mimeType)
        .put("projectId",d.projectId).put("conversationId",d.conversationId).put("text",d.text).put("imageBase64",d.imageBase64)
        .put("sourceKind",d.sourceKind).put("sha256",d.sha256).put("createdAt",d.createdAt)
    private fun readDocument(o: JSONObject) = ProjectDocument(o.getString("id"),o.getString("name"),o.getString("mimeType"),
        o.optString("projectId"),o.optString("conversationId"),o.getString("text"),o.optString("imageBase64"),o.optString("sourceKind","file"),o.optString("sha256"),o.getLong("createdAt"))
}
