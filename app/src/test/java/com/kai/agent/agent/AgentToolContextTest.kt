package com.kai.agent.agent

import com.kai.agent.connections.ConnectionServiceException
import com.kai.agent.connections.ConnectionTool
import com.kai.agent.connections.ToolDiscovery
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentToolContextTest {
    @Test fun smallCatalogIsBoundedAndKeepsExactToolIdsWithoutUnrequestedSchemas() {
        val tools = (1..20).map { tool("GOOGLEDOCS_TOOL_$it").copy(description = "long catalog description ".repeat(40)) }
        val result = AgentToolContext.discovery("account-A", "create document", ToolDiscovery(tools, false, false, 20), smallContext = true)
        val data = JSONObject(result.full)
        assertTrue(result.full.length <= 2600)
        assertTrue(data.getBoolean("hasMore"))
        assertEquals("NOT_TESTED", data.getString("permissionStatus"))
        val entries = data.getJSONArray("tools")
        assertTrue(entries.length() in 1..6)
        assertEquals("GOOGLEDOCS_TOOL_1", entries.getJSONObject(0).getString("id"))
        assertFalse(entries.getJSONObject(0).getBoolean("schemaIncluded"))
        assertTrue(result.compact.contains("No service action was executed"))
    }

    @Test fun smallSchemaKeepsAllValidationConstraintsAndPropertiesNamedLikeAnnotations() {
        val raw = JSONObject("""{"type":"object","description":"Long annotation","properties":{"description":{"type":"string","description":"Annotation","minLength":2},"updates":{"type":"array","items":{"oneOf":[{"type":"string","enum":["a","b"]},{"type":"integer","minimum":1}]}}},"required":["description"],"additionalProperties":false}""")
        val result = JSONObject(AgentToolContext.inspection("a", tool("WRITE", raw), smallContext = true).full)
        val schema = result.getJSONObject("tool").getJSONObject("schema")
        assertFalse(schema.has("description"))
        val property = schema.getJSONObject("properties").getJSONObject("description")
        assertEquals(2, property.getInt("minLength")); assertEquals("Annotation", property.getString("description"))
        assertEquals("description", schema.getJSONArray("required").getString(0))
        assertFalse(schema.getBoolean("additionalProperties"))
        assertEquals(2, schema.getJSONObject("properties").getJSONObject("updates").getJSONObject("items").getJSONArray("oneOf").length())
    }

    private fun tool(id: String, schema: JSONObject = JSONObject().put("type", "object").put("properties", JSONObject())) =
        ConnectionTool(id, id, "Read information", "gmail", "v1", true, schema.toString())

    @Test fun laterToolStaysDiscoverableAndInspectionSuppliesItsSchema() {
        val tools = (1..13).map { tool("GMAIL_READ_$it") }
        val payload = AgentToolContext.discovery("account-A", "inbox", ToolDiscovery(tools, true, false, 13))
        val data = JSONObject(payload.full)
        val entries = data.getJSONArray("tools")
        assertEquals(13, entries.length())
        assertEquals("GMAIL_READ_13", entries.getJSONObject(12).getString("id"))
        assertFalse(entries.getJSONObject(12).getBoolean("schemaIncluded"))
        val inspected = JSONObject(AgentToolContext.inspection("account-A", tools.last()).full)
        assertEquals("account-A", inspected.getString("accountId"))
        assertEquals("SCHEMA_READY", inspected.getString("status"))
        assertTrue(inspected.getJSONObject("tool").getBoolean("schemaIncluded"))
        assertTrue(JSONObject(payload.compact).getJSONArray("tools").let { array -> (0 until array.length()).none { array.getJSONObject(it).has("schema") } })
    }

    @Test fun schemaAnnotationsShrinkWithoutChangingPropertiesOrConstraints() {
        val schema = JSONObject("""{
          "type":"object", "title":"Title", "description":"Summary", "examples":[{"title":"sample"}],
          "properties": {
            "title":{"type":"string","minLength":2,"enum":["A","B"],"description":"Human title"},
            "description":{"type":"string","maxLength":20},
            "example":{"type":"integer","minimum":1},
            "ref":{"${'$'}ref":"#/${'$'}defs/Thing"}
          },
          "required":["title","description","example"], "additionalProperties":false,
          "${'$'}defs":{"Thing":{"type":"string","pattern":"^[A-Z]+$","title":"annotation"}}
        }""")
        val compact = JSONObject(AgentToolContext.inspection("account-A", tool("GMAIL_READ", schema)).full)
            .getJSONObject("tool").getJSONObject("schema")
        assertFalse(compact.has("title")); assertFalse(compact.has("examples"))
        assertEquals(3, compact.getJSONArray("required").length())
        assertFalse(compact.getBoolean("additionalProperties"))
        val properties = compact.getJSONObject("properties")
        assertTrue(properties.has("title")); assertTrue(properties.has("description")); assertTrue(properties.has("example"))
        assertEquals(2, properties.getJSONObject("title").getInt("minLength"))
        assertEquals("B", properties.getJSONObject("title").getJSONArray("enum").getString(1))
        assertEquals("#/\$defs/Thing", properties.getJSONObject("ref").getString("\$ref"))
        assertEquals("^[A-Z]+$", compact.getJSONObject("\$defs").getJSONObject("Thing").getString("pattern"))
    }

    @Test fun oversizedSchemaIsVisibleAndFailsInspectionExplicitly() {
        val properties = JSONObject()
        for (index in 0..400) properties.put("field$index", JSONObject().put("type", "string").put("enum", JSONArray(listOf("x".repeat(90)))))
        val large = tool("GMAIL_LARGE", JSONObject().put("type", "object").put("properties", properties))
        val discovery = JSONObject(AgentToolContext.discovery("a", "read", ToolDiscovery(listOf(large), false, false, 1)).full)
        val entry = discovery.getJSONArray("tools").getJSONObject(0)
        assertEquals("GMAIL_LARGE", entry.getString("id")); assertFalse(entry.getBoolean("schemaIncluded"))
        val inspection = JSONObject(AgentToolContext.inspection("a", large).full)
        assertEquals("SCHEMA_TOO_LARGE", inspection.getString("status"))
        assertTrue(inspection.getString("instructions").contains("not an OAuth failure"))
    }

    @Test fun emptyCatalogAndForbiddenResponseNeverEstablishMissingGmailPermissions() {
        val empty = JSONObject(AgentToolContext.discovery("a", "inbox", ToolDiscovery(emptyList(), true, false, 0)).full)
        assertEquals("NO_MATCHING_TOOLS", empty.getString("status"))
        assertEquals("NOT_TESTED", empty.getString("permissionStatus"))
        val denied = JSONObject(AgentToolContext.discoveryFailure("a", "inbox", ConnectionServiceException("private detail", 403)))
        assertEquals("CATALOG_ACCESS_DENIED", denied.getString("reason"))
        assertFalse(denied.getBoolean("mailboxRead"))
        assertTrue(denied.getString("instructions").contains("Tools Read"))
        assertFalse(denied.toString().contains("private detail"))
    }

    @Test fun executionFailuresStayBoundToExactAccountAndUnknownOutcome() {
        val failed = JSONObject(AgentToolContext.readFailure("account-A", "GMAIL_FETCH", ConnectionServiceException("denied", 403)))
        val unknown = JSONObject(AgentToolContext.readFailure("account-B", "GMAIL_FETCH", ConnectionServiceException("timeout", outcomeMayBeUnknown = true)))
        assertEquals("account-A", failed.getString("accountId")); assertEquals("FAILED", failed.getString("reportedStatus"))
        assertEquals(403, failed.getInt("httpStatus"))
        assertEquals("account-B", unknown.getString("accountId")); assertEquals("UNKNOWN", unknown.getString("reportedStatus"))
    }
}
