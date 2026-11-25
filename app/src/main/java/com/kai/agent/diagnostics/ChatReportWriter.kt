package com.kai.agent.diagnostics

import com.kai.agent.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.Writer

/** Same per-chat contract for individual and batch reports; never serialize credentials/settings. */
object ChatReportWriter {
    fun writeArchive(current: KaiState, output: java.io.OutputStream, readTrace: (String) -> JSONObject,
        app: JSONObject, diagnosticWriteFailures: Int, exportedAt: Long) {
        java.util.zip.ZipOutputStream(output).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("kai-all-chats-report.json"))
            val writer = zip.bufferedWriter(Charsets.UTF_8)
            writeAll(current, writer, readTrace, app, diagnosticWriteFailures, exportedAt)
            zip.closeEntry()
        }
    }

    /** Writes directly to the chosen destination. Never builds the entire batch as a String/JSONArray. */
    fun writeAll(current: KaiState, writer: Writer, readTrace: (String) -> JSONObject, app: JSONObject,
        diagnosticWriteFailures: Int, exportedAt: Long) {
        val chats = current.conversations.filterNot { it.temporary }.sortedWith(
            compareBy<Conversation> { it.createdAt }.thenBy { it.id })
        val header = JSONObject().put("format", "kai-chat-batch-report").put("version", 1)
            .put("scope", "all_saved_regular_chats").put("exportedAt", exportedAt).put("app", app)
            .put("notice", "Contains personal content from all saved regular chats. Credentials are filtered. Review before sharing. No automatic upload. Not an importable backup. App metadata describes the exporter; recordedAppVersion on trace events identifies their recording version when available.")
            .put("chatCount", chats.size).put("limits", DiagnosticRetention.description)
        writer.write(header.toString().dropLast(1))
        writer.write(",\"reports\":[\n")
        val index = JSONArray()
        val states = JSONObject()
        var events = 0L
        var dropped = 0L
        var withoutTrace = 0
        chats.forEachIndexed { position, chat ->
            val report = report(current, chat, readTrace(chat.id), app, diagnosticWriteFailures, exportedAt)
            if (position > 0) writer.write(",\n")
            writer.write(report.toString())
            val summary = summary(report)
            index.put(summary)
            events += summary.getLong("eventCount")
            dropped += summary.getLong("droppedEvents")
            if (summary.getLong("eventCount") == 0L) withoutTrace++
            val runStates = summary.getJSONObject("runStates")
            runStates.keys().forEach { state -> states.put(state, states.optInt(state) + runStates.getInt(state)) }
        }
        // Index entries contain only redacted metadata from the already-redacted per-chat report.
        writer.write("\n],\"index\":")
        writer.write(index.toString())
        writer.write(",\"summary\":")
        writer.write(JSONObject().put("chatCount", chats.size).put("eventCount", events)
            .put("droppedEvents", dropped).put("chatsWithoutRetainedTrace", withoutTrace)
            .put("writeFailuresThisProcess", diagnosticWriteFailures).put("runStates", states)
            .put("interpretation", "Run states are recorded runtime outcomes, not independent verification of task success. Missing traces or missing end events do not prove that no action occurred.").toString())
        writer.write("}\n")
        writer.flush()
    }

    private fun summary(report: JSONObject): JSONObject {
        val chat = report.getJSONObject("conversation")
        val coverage = report.getJSONObject("coverage")
        val runs = report.getJSONArray("runs")
        val states = JSONObject()
        val failures = linkedSetOf<String>()
        for (i in 0 until runs.length()) {
            val run = runs.getJSONObject(i)
            if (!run.has("state")) continue // Explicit redactor omission marker, if present.
            val state = run.getString("state")
            states.put(state, states.optInt(state) + 1)
            if (!run.isNull("failureCode")) failures += run.getString("failureCode")
        }
        val models = linkedSetOf<Pair<String, String>>()
        val versions = linkedSetOf<String>()
        val trace = report.getJSONArray("trace")
        for (i in 0 until trace.length()) {
            val event = trace.getJSONObject(i)
            event.optString("recordedAppVersion").takeIf { it.isNotBlank() }?.let(versions::add)
            if (event.optString("event") == "model_start") event.optJSONObject("data")?.let { data ->
                models += data.optString("provider") to data.optString("model")
            }
        }
        return JSONObject().put("conversationId", chat.getString("id")).put("title", chat.getString("title"))
            .put("createdAt", chat.getLong("createdAt")).put("updatedAt", chat.getLong("updatedAt"))
            .put("runStates", states).put("failureCodes", JSONArray(failures.toList()))
            .put("modelsObservedInTrace", JSONArray(models.map { (provider, model) -> JSONObject().put("provider", provider).put("model", model) }))
            .put("recordedAppVersions", JSONArray(versions.toList()))
            .put("eventCount", coverage.getLong("eventCount")).put("droppedEvents", coverage.getLong("droppedEvents"))
            .put("lastPrivacyResetAt", coverage.getLong("lastPrivacyResetAt"))
    }

    fun report(current: KaiState, conversation: Conversation, trace: JSONObject, app: JSONObject,
        diagnosticWriteFailures: Int, exportedAt: Long): JSONObject {
        require(!conversation.temporary) { "Temporary chats are not recorded." }
        val cid = conversation.id
        val report=JSONObject().put("format","kai-chat-report").put("version",1).put("exportedAt",exportedAt)
            .put("app",app)
            .put("notice","Contains personal chat, relevant model context, action arguments and service results. Credentials are filtered; review before sharing. No automatic upload. This is a diagnostic snapshot, not an importable backup.")
            .put("coverage",JSONObject().put("recordingSinceVersion","0.4.20").put("writeFailuresThisProcess",diagnosticWriteFailures)
                .put("eventCount",trace.getJSONArray("events").length()).put("droppedEvents",trace.getLong("droppedEvents"))
                .put("lastPrivacyResetAt",trace.getLong("lastPrivacyResetAt"))
                .put("limits",DiagnosticRetention.description)
                .put("memoryRecordingSinceVersion","0.4.26")
                .put("notCaptured","Audio, attachment bytes, provider-internal reasoning and server-side logs. Background memory extraction before 0.4.26. Missing end events may indicate interruption; absence is not proof of non-execution."))
            .put("memory",JSONObject().put("savingMode",current.settings.memoryMode.name).put("enabled",current.settings.memoryEnabled)
                .put("jobs",org.json.JSONArray(current.memoryGraph.jobs.filter { it.conversationId == cid }.map { job ->
                    JSONObject().put("id",job.id).put("sourceMessageId",job.sourceMessageId).put("status",job.status.name)
                        .put("extractionVersion",job.extractionVersion).put("attempts",job.attempts).put("error",job.error)
                })))
            .put("conversation",KaiJson.conversation(conversation))
            .put("tasks",org.json.JSONArray(current.tasks.filter { it.conversationId==cid }.map(KaiJson::task)))
            .put("runs",org.json.JSONArray(current.executions.filter { it.conversationId==cid }.map { r ->
                JSONObject().put("id",r.id).put("state",r.state.name).put("origin",r.origin.name).put("sourceId",r.sourceId)
                    .put("failureCode",r.failure?.name ?: JSONObject.NULL).put("modelSteps",r.budget.modelSteps)
                    .put("inputTokens",r.budget.inputTokens).put("outputTokens",r.budget.outputTokens).put("artifactCount",r.artifacts.size)
                    .put("inferenceAllowance",com.kai.agent.runtime.InferenceLedgerJson.write(r.budget.inference))
                    .put("researchEvidence",com.kai.agent.web.ResearchEvidenceJson.write(r.research))
                    .put("progress",com.kai.agent.runtime.RunProgressJson.write(r.progress))
                    .put("events",org.json.JSONArray(r.events.map { e -> JSONObject().put("sequence",e.sequence).put("at",e.at).put("kind",e.kind).put("code",e.code) }))
            }))
            .put("trace",trace.getJSONArray("events"))
        return DiagnosticRedactor(current.settings).clean(report) as JSONObject
    }
}
