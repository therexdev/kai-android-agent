package com.kai.agent.runtime

import com.kai.agent.data.AgentTask
import org.json.JSONArray
import org.json.JSONObject

/** A file-creation receipt does not establish that requested table contents were written. */
object SpreadsheetEvidence {
    fun requiresContent(request: String): Boolean = Regex("(?i)\\b(?:sheets?|spreadsheet|workbook)\\b").containsMatchIn(request) &&
        Regex("(?i)\\b(?:columns?|rows?|data|populate|fill|table|list|details|information)\\b|\\bwith\\s+\\d+\\b").containsMatchIn(request)

    fun complete(tasks: List<AgentTask>,request: String): Boolean {
        if(!requiresContent(request))return true
        // Keep distinct target workbooks separate; unrelated partial tables cannot add up to success.
        val written=linkedMapOf<String,MutableSet<String>>()
        val headerOnly=TaskComplexity.collectionSize(request)==1 &&
            Regex("(?i)\\b(?:headers?|columns?)\\b").containsMatchIn(request) &&
            !Regex("(?i)\\b(?:find|research|list|populate|fill|entries|records|data|details|information)\\b").containsMatchIn(request)
        val createdTargets=tasks.filter { it.intent?.toolName?.contains("CREATE",true)==true }
            .flatMap { it.receipts }.mapNotNull { Regex("https://docs\\.google\\.com/spreadsheets/d/([^/?#]+)").find(it.url)?.groupValues?.get(1) }.toSet()
        for(task in tasks) {
            val intent=task.intent ?: continue
            if(!intent.toolName.contains("SHEETS",true))continue
            val args=runCatching { JSONObject(intent.argumentsJson) }.getOrNull() ?: continue
            val target=args.optString("spreadsheet_id",args.optString("spreadsheetId"))
            if(target.isBlank() || createdTargets.isNotEmpty() && target !in createdTargets)continue
            val rows=written.getOrPut(target) { linkedSetOf() }
            collect(args,rows,headerOnly)
        }
        return written.values.any { it.size>=TaskComplexity.collectionSize(request) }
    }
    private fun collect(value: JSONObject,rows: MutableSet<String>,headerOnly: Boolean) {
        val range=value.optString("range")
        value.optJSONArray("values")?.let { matrix ->
            if(value.optString("major_dimension",value.optString("majorDimension","ROWS"))=="COLUMNS") {
                val columns=(0 until matrix.length()).mapNotNull { matrix.optJSONArray(it) }
                for(i in 0 until (columns.maxOfOrNull { it.length() } ?: 0)) addRow(JSONArray(columns.map { it.opt(i) ?: JSONObject.NULL }),rows,headerOnly,range,i)
            } else for(i in 0 until matrix.length()) matrix.optJSONArray(i)?.let { addRow(it,rows,headerOnly,range,i) }
        }
        value.optJSONArray("data")?.let { data ->
            for(i in 0 until data.length()) data.optJSONObject(i)?.let { collect(it,rows,headerOnly) }
        }
    }
    private fun addRow(row: JSONArray,rows: MutableSet<String>,headerOnly: Boolean,range: String,offset: Int) {
        if(row.length()==0)return
        val first=row.optString(0).trim()
        if(first.isBlank() || !headerOnly && Regex("(?i)^(?:name|business(?: name)?|practice(?: name)?|company(?: name)?|dentist(?: name)?|provider(?: name)?|phone|website|address|reviews?)$").matches(first))return
        val headerCells=(0 until row.length()).count { Regex("(?i)^(?:name|phone(?: number)?|website|address|reviews?(?: summary)?|google maps)$").matches(row.optString(it).trim()) }
        if(!headerOnly && headerCells>=2)return
        val start=Regex("(?i)(?:^|!)[A-Z]+([0-9]+)(?::|$)").find(range)?.groupValues?.get(1)?.toIntOrNull()
        val sheet=range.substringBefore('!',"")
        // Separate column writes to the same five rows must not look like ten entries.
        // Unlocated append results conservatively establish at most their largest batch size.
        rows+=if(start!=null) "$sheet:${start+offset}" else "unlocated:$offset"
    }
    fun correction(tasks: List<AgentTask>,request: String): String? {
        if(!requiresContent(request) || complete(tasks,request))return null
        val urls=tasks.flatMap { it.receipts }.map { it.url }.filter { it.startsWith("https://docs.google.com/spreadsheets/d/") }.distinct()
        if(urls.isEmpty())return null
        return "Phone goal check: creating the spreadsheet is only the setup step. The requested table contents are not confirmed written. " +
            "Use the existing spreadsheet from the action receipts, never create a replacement: "+urls.joinToString(" ").take(1200)+". " +
            "Retrieve retained source findings with read_context when necessary. Discover the matching Sheets values/batch/append action, inspect its exact schema and prepare a reviewed write with the known entries and requested columns. " +
            "Public web limits do not disable connected-app actions. Do not ask whether to add information the user already requested. Never invent missing facts or review summaries; label gaps and return partial if the full request remains unfinished."
    }
}
