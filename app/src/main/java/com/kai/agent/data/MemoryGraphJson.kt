package com.kai.agent.data

import org.json.JSONArray
import org.json.JSONObject

/** Codec used by encrypted SQL rows and portable exports; never writes files itself. */
object MemoryGraphJson {
    private fun array(items: List<JSONObject>) = JSONArray().also { a -> items.forEach(a::put) }
    private fun strings(items: List<String>) = JSONArray().also { a -> items.forEach(a::put) }
    private fun objects(o: JSONObject, key: String): List<JSONObject> = o.optJSONArray(key)?.let { a -> (0 until a.length()).map(a::getJSONObject) } ?: emptyList()
    private fun texts(o: JSONObject, key: String): List<String> = o.optJSONArray(key)?.let { a -> (0 until a.length()).map(a::getString) } ?: emptyList()
    private fun JSONObject.nullableString(key: String) = if (!has(key) || isNull(key)) null else getString(key)
    private fun JSONObject.nullableInt(key: String) = if (!has(key) || isNull(key)) null else getInt(key)
    private fun JSONObject.nullableLong(key: String) = if (!has(key) || isNull(key)) null else getLong(key)

    fun entity(e: MemoryEntity) = JSONObject().put("id",e.id).put("type",e.type).put("displayName",e.displayName).put("scope",e.scope).put("createdAt",e.createdAt)
    fun readEntity(o: JSONObject) = MemoryEntity(o.getString("id"),o.getString("type"),o.getString("displayName"),o.getString("scope"),o.getLong("createdAt"))
    fun alias(a: MemoryAlias) = JSONObject().put("id",a.id).put("entityId",a.entityId).put("name",a.name).put("scope",a.scope).put("evidenceFactIds",strings(a.evidenceFactIds))
    fun readAlias(o: JSONObject) = MemoryAlias(o.getString("id"),o.getString("entityId"),o.getString("name"),o.getString("scope"),texts(o,"evidenceFactIds"))
    fun value(v: MemoryValue) = JSONObject().put("type",v.type).put("text",v.text).put("month",v.month ?: JSONObject.NULL).put("day",v.day ?: JSONObject.NULL).put("year",v.year ?: JSONObject.NULL).put("precision",v.precision)
    fun readValue(o: JSONObject) = MemoryValue(o.getString("type"),o.optString("text"),o.nullableInt("month"),o.nullableInt("day"),o.nullableInt("year"),o.optString("precision","text"))
    fun fact(f: StructuredFact) = JSONObject().put("id",f.id).put("subjectId",f.subjectId).put("predicate",f.predicate)
        .put("objectEntityId",f.objectEntityId ?: JSONObject.NULL).put("value",f.value?.let(::value) ?: JSONObject.NULL)
        .put("scope",f.scope).put("status",f.status.name).put("revision",f.revision).put("createdAt",f.createdAt).put("updatedAt",f.updatedAt)
        .put("validFrom",f.validFrom ?: JSONObject.NULL).put("validUntil",f.validUntil ?: JSONObject.NULL).put("supersedesId",f.supersedesId ?: JSONObject.NULL)
        .put("displayText",f.displayText).put("lineageIds",strings(f.lineageIds))
    fun readFact(o: JSONObject) = StructuredFact(o.getString("id"),o.getString("subjectId"),o.getString("predicate"),o.nullableString("objectEntityId"),o.optJSONObject("value")?.let(::readValue),
        o.getString("scope"),enumValueOf(o.getString("status")),o.getLong("revision"),o.getLong("createdAt"),o.getLong("updatedAt"),
        o.nullableLong("validFrom"),o.nullableLong("validUntil"),o.nullableString("supersedesId"),o.optString("displayText"),texts(o,"lineageIds"))
    fun evidence(e: FactEvidence) = JSONObject().put("id",e.id).put("factId",e.factId).put("sourceMessageId",e.sourceMessageId).put("conversationId",e.conversationId)
        .put("quote",e.quote).put("start",e.start).put("end",e.end).put("sourceKind",e.sourceKind).put("observedAt",e.observedAt).put("confirmedByUser",e.confirmedByUser)
    fun readEvidence(o: JSONObject) = FactEvidence(o.getString("id"),o.getString("factId"),o.getString("sourceMessageId"),o.getString("conversationId"),o.getString("quote"),
        o.getInt("start"),o.getInt("end"),o.getString("sourceKind"),o.getLong("observedAt"),o.optBoolean("confirmedByUser"))
    fun candidate(c: MemoryCandidate) = JSONObject().put("id",c.id).put("kind",c.kind.name).put("status",c.status.name).put("question",c.question).put("summary",c.summary)
        .put("proposedFacts",array(c.proposedFacts.map(::fact))).put("proposedEntities",array(c.proposedEntities.map(::entity))).put("proposedAliases",array(c.proposedAliases.map(::alias)))
        .put("evidence",array(c.evidence.map(::evidence))).put("entityChoices",strings(c.entityChoices)).put("sourceMessageId",c.sourceMessageId).put("conversationId",c.conversationId)
        .put("createdAt",c.createdAt).put("resolvedAt",c.resolvedAt ?: JSONObject.NULL)
    fun readCandidate(o: JSONObject) = MemoryCandidate(o.getString("id"),enumValueOf(o.getString("kind")),enumValueOf(o.getString("status")),o.getString("question"),o.optString("summary"),
        objects(o,"proposedFacts").map(::readFact),objects(o,"proposedEntities").map(::readEntity),objects(o,"proposedAliases").map(::readAlias),objects(o,"evidence").map(::readEvidence),
        texts(o,"entityChoices"),o.optString("sourceMessageId"),o.optString("conversationId"),o.getLong("createdAt"),o.nullableLong("resolvedAt"))
    fun job(j: MemoryJob) = JSONObject().put("id",j.id).put("sourceMessageId",j.sourceMessageId).put("conversationId",j.conversationId).put("extractionVersion",j.extractionVersion)
        .put("status",j.status.name).put("deletionEpoch",j.deletionEpoch).put("attempts",j.attempts).put("createdAt",j.createdAt).put("updatedAt",j.updatedAt).put("error",j.error)
    fun readJob(o: JSONObject) = MemoryJob(o.getString("id"),o.getString("sourceMessageId"),o.getString("conversationId"),o.getString("extractionVersion"),enumValueOf(o.getString("status")),
        o.getLong("deletionEpoch"),o.getInt("attempts"),o.getLong("createdAt"),o.getLong("updatedAt"),o.optString("error"))
    fun vector(v: MemoryVector) = JSONObject().put("factId",v.factId).put("factRevision",v.factRevision).put("modelId",v.modelId).put("textHash",v.textHash)
        .put("values",JSONArray().also { a -> v.values.forEach { a.put(it.toDouble()) } }).put("createdAt",v.createdAt)
        .put("dependencyRevisions",JSONObject().also { o -> v.dependencyRevisions.forEach { (id,revision) -> o.put(id,revision) } })
    fun readVector(o: JSONObject): MemoryVector {
        val values=o.getJSONArray("values"); val deps=o.optJSONObject("dependencyRevisions") ?: JSONObject()
        return MemoryVector(o.getString("factId"),o.getLong("factRevision"),o.getString("modelId"),o.getString("textHash"),
            (0 until values.length()).map { values.getDouble(it).toFloat() },o.getLong("createdAt"),deps.keys().asSequence().associateWith { deps.getLong(it) })
    }
    fun suppression(s: GraphSuppression) = JSONObject().put("factId",s.factId).put("claimHash",s.claimHash).put("sourceHash",s.sourceHash).put("deletedAt",s.deletedAt).put("revision",s.revision).put("lineageIds",strings(s.lineageIds)).put("reason",s.reason)
    fun readSuppression(o: JSONObject) = GraphSuppression(o.optString("factId"),o.optString("claimHash"),o.optString("sourceHash"),o.getLong("deletedAt"),o.getLong("revision"),texts(o,"lineageIds"),o.optString("reason","forgotten"))
    fun encode(g: MemoryGraph) = JSONObject().put("entities",array(g.entities.map(::entity))).put("aliases",array(g.aliases.map(::alias)))
        .put("facts",array(g.facts.map(::fact))).put("evidence",array(g.evidence.map(::evidence))).put("candidates",array(g.candidates.map(::candidate)))
        .put("jobs",array(g.jobs.map(::job))).put("vectors",array(g.vectors.map(::vector))).put("suppressions",array(g.suppressions.map(::suppression))).put("deletionEpoch",g.deletionEpoch)
    fun decode(o: JSONObject) = MemoryGraph(objects(o,"entities").map(::readEntity),objects(o,"aliases").map(::readAlias),objects(o,"facts").map(::readFact),
        objects(o,"evidence").map(::readEvidence),objects(o,"candidates").map(::readCandidate),objects(o,"jobs").map(::readJob),objects(o,"vectors").map(::readVector),
        objects(o,"suppressions").map(::readSuppression),o.optLong("deletionEpoch"))
}
