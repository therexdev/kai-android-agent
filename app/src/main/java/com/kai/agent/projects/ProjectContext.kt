package com.kai.agent.projects

import com.kai.agent.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Scope is enforced before ranking; similarity can never grant another project's file access. */
object ProjectContext {
    fun documents(state: KaiState, projectId: String, conversationId: String): List<ProjectDocument> = state.documents.filter {
        it.id !in state.deletedDocumentIds && (it.projectId.isNotBlank() && it.projectId == projectId ||
            it.projectId.isBlank() && conversationId.isNotBlank() && it.conversationId == conversationId)
    }
    fun build(state: KaiState, projectId: String, conversationId: String, query: String, selected: Set<String> = emptySet()): String {
        val project=state.projects.firstOrNull { it.id==projectId && it.id !in state.deletedProjectIds }
        val allowed=documents(state,projectId,conversationId)
        val tokens=Regex("[\\p{L}\\p{N}]{3,}").findAll(query.lowercase(Locale.ROOT)).map { it.value }.toSet()
        data class Excerpt(val doc: ProjectDocument,val index: Int,val text: String,val score: Int)
        val excerpts=allowed.flatMap { doc -> doc.text.chunked(1600).mapIndexed { index,text ->
            val lower=text.lowercase(Locale.ROOT)
            Excerpt(doc,index,text,(if(doc.id in selected) 100 else 0)+tokens.count { it in lower }*5+(if(index==0) 1 else 0))
        } }.filter { it.score>0 }.sortedWith(compareByDescending<Excerpt> { it.score }.thenBy { it.index }).take(6)
        return JSONObject().put("project",project?.name.orEmpty()).put("projectMemory",if(state.settings.useMemory)project?.memory?.take(6000).orEmpty() else "")
            .put("files",JSONArray().apply { allowed.take(30).forEach { put(JSONObject().put("id",it.id).put("name",it.name).put("characters",it.text.length)) } })
            .put("excerpts",JSONArray().apply { excerpts.forEach { put(JSONObject().put("source","${it.doc.name} · excerpt ${it.index+1}")
                .put("fileId",it.doc.id).put("text",it.text)) } }).toString()
    }
    fun images(state: KaiState, projectId: String, conversationId: String, selected: Set<String>): List<String> =
        documents(state,projectId,conversationId).filter { it.id in selected && it.imageBase64.isNotBlank() }.take(2)
            .map { "data:image/jpeg;base64,${it.imageBase64}" }
}
