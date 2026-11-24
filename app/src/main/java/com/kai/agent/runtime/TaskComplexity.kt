package com.kai.agent.runtime

/** Bounded planning allowance for explicit collection requests, never a permission grant. */
object TaskComplexity {
    fun collectionSize(request: String): Int {
        val match=Regex("(?i)\\b(?:with|find|list|research|compare|include|collect|gather|containing)\\s+(?:at\\s+least\\s+|the\\s+)?(\\d{1,3})\\b\\s+(?:top\\s+|local\\s+)?([a-z]+)").find(request) ?: return 1
        if(match.groupValues[2].lowercase() in setOf("words","word","tokens","token","minutes","hours","days","years","column","columns")) return 1
        return match.groupValues[1].toInt().coerceIn(1,20)
    }
    fun modelSteps(request: String)=maxOf(36,24+collectionSize(request)*4).coerceAtMost(104)
    fun guidance(request: String): String {
        val count=collectionSize(request)
        return (if(count>1) "This request needs coverage for $count entries. Track the requested fields for each entry. After a directory search, read the relevant source pages and use targeted entity/contact/review queries for missing fields instead of repeating the broad search. " else "")+
            if(SpreadsheetEvidence.requiresContent(request)) "The spreadsheet goal includes its cell contents, not just file creation. Prefer researching a table draft before creating an empty file. If creation and writing use separate tools, retain the created spreadsheet ID, prepare the exact values write for review, and check the result. If some details cannot be verified, prepare a clearly labeled partial table using supported findings, never invented contacts or reviews. A web limit does not remove connected-app tools or approve a write." else ""
    }
}
