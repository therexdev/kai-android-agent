package com.kai.agent.extensions

import com.kai.agent.data.RunStep
import org.json.JSONObject
import org.json.JSONArray

/** Portable procedures contain data and required capabilities, never credentials, grants or executable code. */
data class WorkflowNode(val id: String,val title: String,val instructions: String,val dependsOn: List<String> = emptyList(),
    val model: String = "",val kind: String = "AGENT",val reviewTarget: String = "",val maxRevisions: Int = 2)
data class WorkflowTemplate(val id: String,val version: Int,val name: String,val goal: String,val nodes: List<WorkflowNode>,val requiredCapabilities: List<String>) {
    fun ordered(): List<WorkflowNode> {
        val result=mutableListOf<WorkflowNode>();val remaining=nodes.toMutableList()
        while(remaining.isNotEmpty()) {
            val ready=remaining.filter { node -> node.dependsOn.all { dep -> result.any { it.id==dep } } }
            require(ready.isNotEmpty()) { "Workflow dependencies contain a cycle or unknown node." }
            result+=ready;remaining.removeAll(ready.toSet())
        }
        return result
    }
    fun steps(input: String): List<RunStep> = ordered().map { node ->
        RunStep(title=node.title,instructions=node.instructions.replace("{{input}}",input).also { require(it.length<=12000) },
            nodeId=node.id,modelId=node.model,kind=node.kind,reviewTarget=node.reviewTarget,maxRevisions=node.maxRevisions)
    }
    fun json()=JSONObject().put("schema","kai.workflow.v1").put("id",id).put("version",version).put("name",name).put("goal",goal)
        .put("requiredCapabilities",JSONArray(requiredCapabilities)).put("nodes",JSONArray(nodes.map { n ->
            JSONObject().put("id",n.id).put("title",n.title).put("instructions",n.instructions).put("dependsOn",JSONArray(n.dependsOn))
                .put("model",n.model).put("kind",n.kind).put("reviewTarget",n.reviewTarget).put("maxRevisions",n.maxRevisions) }))
    companion object {
        private fun exact(o: JSONObject, keys: Set<String>) { require(o.keys().asSequence().all { it in keys }) { "Unsupported workflow fields. Credentials, approvals and executable code are not accepted." } }
        fun parse(raw: String): WorkflowTemplate {
            require(raw.length<=64000) { "Workflow exceeds the supported size." }
            val o=JSONObject(raw)
            exact(o,setOf("schema","id","version","name","goal","requiredCapabilities","nodes"))
            require(o.getString("schema")=="kai.workflow.v1") { "Unsupported workflow version." }
            fun strings(a: JSONArray)=(0 until a.length()).map(a::getString)
            val a=o.getJSONArray("nodes");require(a.length() in 1..8)
            val nodes=(0 until a.length()).map { i -> val n=a.getJSONObject(i)
                exact(n,setOf("id","title","instructions","dependsOn","model","kind","reviewTarget","maxRevisions"))
                WorkflowNode(n.getString("id"),n.getString("title"),n.getString("instructions"),strings(n.optJSONArray("dependsOn") ?: JSONArray()),n.optString("model"),n.optString("kind","AGENT"),n.optString("reviewTarget"),n.optInt("maxRevisions",2))
            }
            val result=WorkflowTemplate(o.getString("id"),o.getInt("version"),o.getString("name"),o.getString("goal"),nodes,strings(o.getJSONArray("requiredCapabilities")))
            require(Regex("[a-zA-Z0-9._-]{1,100}").matches(result.id) && result.version>0 && result.name.length in 1..100 && result.goal.length in 1..12000)
            require(result.requiredCapabilities.all { it in setOf("web","files","connections.read","connections.write","memory.read") }) { "This workflow requests unsupported capabilities." }
            require(nodes.map { it.id }.distinct().size==nodes.size)
            nodes.forEach { n ->
                require(Regex("[a-zA-Z0-9_-]{1,64}").matches(n.id) && n.title.length in 1..200 && n.instructions.length in 1..12000 && n.model.length<=300)
                require(n.kind in setOf("AGENT","REVIEW") && n.maxRevisions in 0..2)
                if(n.kind=="REVIEW") require(n.reviewTarget in n.dependsOn && nodes.any { it.id==n.reviewTarget && it.kind=="AGENT" }) { "A review must depend on its revision target." }
            }
            result.ordered();return result
        }
        fun example()=WorkflowTemplate("researched-draft",1,"Researched draft","Research {{input}} and prepare a source-linked draft",listOf(
            WorkflowNode("research","Research","Research {{input}}. Find relevant sources, read supporting pages, preserve links and evidence limits."),
            WorkflowNode("draft","Draft","Write a useful draft from the retrieved evidence; cite source URLs. Do not publish it.",listOf("research")),
            WorkflowNode("review","Review","Review the draft against the user's goal and retained sources. Reply text must be JSON with verdict PASS or REVISE and changes. Never execute external writes.",listOf("draft"),kind="REVIEW",reviewTarget="draft")),listOf("web"))
    }
}
