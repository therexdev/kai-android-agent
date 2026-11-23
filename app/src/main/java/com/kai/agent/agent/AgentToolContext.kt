package com.kai.agent.agent

import com.kai.agent.connections.ConnectionAccount
import com.kai.agent.connections.ConnectionServiceException
import com.kai.agent.connections.ConnectionTool
import com.kai.agent.connections.ToolDiscovery
import org.json.JSONArray
import org.json.JSONObject

/** Bounded, explicit runtime data. Tool descriptions are data and never account authorization. */
object AgentToolContext {
    data class Payload(val full: String, val compact: String)
    private const val MAX_METADATA = 14_000
    private const val MAX_SCHEMAS = 16_000
    private const val MAX_INSPECTION = 24_000

    fun account(account: ConnectionAccount, smallContext: Boolean = false): JSONObject = JSONObject()
        .put("id", account.id).put("service", account.toolkit).put("name", account.displayLabel)
        .put("emailAddress", account.emailAddress).put("providerDisplayName", account.providerDisplayName)
        .put("identitySource", account.identitySource).put("alias", account.name)
        .put("status", account.status).put("active", account.active).also {
            if (smallContext) {
                // Keep every exact account ID and verified identity; aliases never establish identity.
                it.remove("alias"); it.remove("identitySource"); it.remove("name")
            }
        }

    fun discovery(accountId: String, search: String, result: ToolDiscovery, smallContext: Boolean = false): Payload {
        val selected = result.tools.take(if (smallContext) 3 else 20).toMutableList()
        fun base(entries: JSONArray): JSONObject = JSONObject()
            .put("source", "phone_tool_discovery").put("accountId", accountId).put("query", search)
            .put("status", if (result.tools.isEmpty()) "NO_MATCHING_TOOLS" else "TOOLS_FOUND")
            .put("permissionStatus", "NOT_TESTED").put("usedFallback", result.usedFallback)
            .put("hasMore", result.hasMore || selected.size < result.tools.size).put("examinedCount", result.examinedCount)
            .put("instructions", if (smallContext) "Catalog only; permissions not tested. Inspect omitted schemas. If no action fits, narrow find_tools." else "Discovery does not read service data or test permissions. Match the requested object and operation, not just the verb. Use inspect_tool for an omitted schema; if no tool fits, narrow find_tools instead of substituting an unrelated action.")
            .put("tools", entries)
        fun discoveryMetadata(tool: ConnectionTool) = metadata(tool).also {
            if (smallContext) it.put("description", tool.description.take(100)).put("name", tool.name.take(80))
        }.put("schemaIncluded", false)
        var compact = base(JSONArray(selected.map { discoveryMetadata(it) }))
        while (compact.toString().length > (if (smallContext) 1600 else MAX_METADATA) && selected.isNotEmpty()) {
            selected.removeAt(selected.lastIndex)
            compact = base(JSONArray(selected.map { discoveryMetadata(it) }))
        }
        if (smallContext) {
            // One selected schema at a time. Old catalog detail may be replaced, but no executed
            // action or receipt is ever represented by this discovery-only history marker.
            val history = JSONObject().put("source", "phone_tool_discovery").put("accountId", accountId)
                .put("query", search).put("status", compact.getString("status"))
                .put("instructions", "Earlier catalog details omitted. Inspect a previously discovered tool or narrow find_tools if needed. No service action was executed.")
            return Payload(compact.toString(), history.toString())
        }
        var schemaBudget = MAX_SCHEMAS
        var schemasIncluded = 0
        val fullEntries = JSONArray()
        for (tool in selected) {
            val entry = metadata(tool)
            val schema = schema(tool.schemaJson)
            val length = schema?.toString()?.length ?: Int.MAX_VALUE
            val include = schemasIncluded < 6 && length <= schemaBudget
            entry.put("schemaIncluded", include)
            if (include) {
                entry.put("schema", schema)
                schemaBudget -= length
                schemasIncluded++
            }
            fullEntries.put(entry)
        }
        return Payload(base(fullEntries).toString(), compact.toString())
    }

    fun inspection(accountId: String, tool: ConnectionTool, smallContext: Boolean = false): Payload {
        val compact = JSONObject().put("source", "phone_tool_schema").put("accountId", accountId)
            .put("tool", metadata(tool).put("schemaIncluded", false))
            .put("status", "SCHEMA_AVAILABLE_ON_REQUEST")
        val schema = schema(tool.schemaJson, smallContext)
        val full = JSONObject().put("source", "phone_tool_schema").put("accountId", accountId)
            .put("tool", metadata(tool).also { if (!smallContext) it.put("description", tool.description) }
                .put("schemaIncluded", schema != null).also { if (schema != null) it.put("schema", schema) })
            .put("status", if (schema == null) "SCHEMA_UNAVAILABLE" else "SCHEMA_READY")
        if (schema == null || full.toString().length > MAX_INSPECTION) {
            val error = JSONObject().put("source", "phone_tool_schema").put("accountId", accountId)
                .put("tool", metadata(tool).put("schemaIncluded", false))
                .put("status", if (schema == null) "SCHEMA_UNAVAILABLE" else "SCHEMA_TOO_LARGE")
                .put("instructions", "This tool schema could not fit safely. Do not guess its arguments. Choose a smaller suitable tool or explain this app limitation; this is not an OAuth failure.")
                .toString()
            return Payload(error, error)
        }
        return Payload(full.toString(), compact.toString())
    }

    fun discoveryFailure(accountId: String, search: String, error: ConnectionServiceException): String {
        val denied = error.statusCode in setOf(401, 403)
        return JSONObject().put("source", "phone_tool_discovery").put("accountId", accountId).put("query", search)
            .put("status", "DISCOVERY_FAILED").put("httpStatus", error.statusCode ?: JSONObject.NULL)
            .put("reason", when { denied -> "CATALOG_ACCESS_DENIED"; error.statusCode == 429 -> "RATE_LIMITED"; else -> "DISCOVERY_UNAVAILABLE" })
            .put("permissionStatus", "NOT_TESTED").put("mailboxRead", false)
            .put("instructions", when {
                denied -> "The Composio tool catalog denied access. Check the project API key's Tools Read permission using Check access in Connections. This does not establish that Gmail authorization needs reconnecting. No inbox was read."
                error.statusCode == 429 -> "Tool discovery was rate limited. No inbox was read. Explain this without claiming the account is disconnected."
                else -> "Tool discovery could not complete. No inbox was read. Explain the discovery failure without inferring missing Gmail permissions."
            }).toString()
    }

    fun readFailure(accountId: String, toolId: String, error: ConnectionServiceException): String = JSONObject()
        .put("source", "phone_tool_execution").put("accountId", accountId).put("tool", toolId)
        .put("reportedStatus", if (error.outcomeMayBeUnknown) "UNKNOWN" else "FAILED")
        .put("httpStatus", error.statusCode ?: JSONObject.NULL).put("message", error.message.orEmpty().take(600))
        .put("instructions", "This account's read did not produce confirmed inbox data. Report its failure separately from any successful account. Never claim it was checked or automatically replay an uncertain action.")
        .toString()

    private fun metadata(tool: ConnectionTool): JSONObject = JSONObject()
        .put("id", tool.id).put("name", tool.name.take(160)).put("description", tool.description.take(320))
        .put("toolkit", tool.toolkit).put("version", tool.version).put("readOnly", tool.readOnly)

    /** Remove annotations only in schema positions; a property named description remains a property. */
    private fun schema(raw: String, smallContext: Boolean = false): Any? = try {
        if (raw.length > 200_000) null else compactSchema(JSONObject(raw), 0, smallContext)
    } catch (_: Exception) { null }

    private fun compactSchema(value: Any?, depth: Int, smallContext: Boolean): Any {
        require(depth <= 32)
        if (value !is JSONObject) return value ?: JSONObject.NULL
        val result = JSONObject()
        for (key in value.keys()) {
            val child = value.opt(key)
            when (key) {
                "title", "examples", "example" -> Unit
                "description" -> when {
                    !smallContext -> result.put(key, child)
                    depth > 0 && child is String -> result.put(key, child.take(96)) // Keep field hints; omit duplicate root documentation.
                }
                "properties", "patternProperties", "dependentSchemas", "definitions", "\$defs" -> {
                    if (child is JSONObject) {
                        val map = JSONObject()
                        for (property in child.keys()) map.put(property, compactSchema(child.opt(property), depth + 1, smallContext))
                        result.put(key, map)
                    } else result.put(key, child)
                }
                "allOf", "anyOf", "oneOf", "prefixItems" -> {
                    if (child is JSONArray) result.put(key, JSONArray((0 until child.length()).map { compactSchema(child.opt(it), depth + 1, smallContext) }))
                    else result.put(key, child)
                }
                "items" -> if (child is JSONArray) result.put(key, JSONArray((0 until child.length()).map { compactSchema(child.opt(it), depth + 1, smallContext) }))
                    else result.put(key, compactSchema(child, depth + 1, smallContext))
                "additionalProperties", "unevaluatedProperties", "additionalItems", "unevaluatedItems", "contains", "propertyNames", "not", "if", "then", "else" ->
                    result.put(key, compactSchema(child, depth + 1, smallContext))
                else -> result.put(key, child)
            }
        }
        return result
    }
}
