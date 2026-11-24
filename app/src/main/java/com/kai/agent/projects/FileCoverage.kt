package com.kai.agent.projects
import com.kai.agent.data.*
import com.kai.agent.inference.InferenceMessage
import org.json.JSONObject

object FileCoverage {
    fun exhaustive(request: String)=Regex("(?i)(?:\\b(?:entire|whole|all)\\b.{0,35}\\b(?:files?|documents?|attachments?|codebase)\\b|\\b(?:files?|documents?|codebase)\\b.{0,35}\\b(?:entirely|exhaustive|line.by.line)\\b)").containsMatchIn(request)
    fun missing(files: List<ProjectDocument>, observations: List<InferenceMessage>): List<String> {
        val reads=observations.mapNotNull { message ->
            if(!message.content.startsWith(FileRetrieval.PREFIX)) null else runCatching { JSONObject(message.content.removePrefix(FileRetrieval.PREFIX)) }.getOrNull()?.takeIf { it.optString("kind")=="file_read" }
        }
        return files.filter { file ->
            val revision=MemoryRules.digest(file.text)
            val ranges=reads.filter { it.optString("fileId")==file.id && it.optString("revision")==revision }
                .map { it.optInt("offset") to it.optInt("endOffset") }.sortedBy { it.first }
            var through=0
            for((start,end) in ranges) if(start<=through && end>=start) through=maxOf(through,end)
            through<file.text.length
        }.map { it.name }
    }
}
