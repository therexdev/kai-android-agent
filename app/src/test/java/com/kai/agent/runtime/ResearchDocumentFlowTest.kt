package com.kai.agent.runtime

import com.kai.agent.agent.*
import com.kai.agent.connections.*
import com.kai.agent.data.*
import com.kai.agent.inference.*
import com.kai.agent.web.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Scripted-model component integration, real parsing/context/approval/client/journal code.
 * No live account/model calls; Android adapter/lifecycle execution is a separate device gate.
 */
class ResearchDocumentFlowTest {
    private val url="https://directory.example/projects"
    private val sourceText=(1..16).joinToString(" ") { "Project $it provides community service $it with public documentation." }
    private val docId="fixture-document-123456789"
    private val docUrl="https://docs.google.com/document/d/$docId/edit"
    private val create="GOOGLEDOCS_CREATE_DOCUMENT_MARKDOWN"
    private val read="GOOGLEDOCS_GET_DOCUMENT_PLAINTEXT"
    private val send="GMAIL_SEND_EMAIL"
    private class Repo: RunRepository {
        lateinit var saved: ExecutionRun
        override suspend fun get(id: String)=saved
        override suspend fun claim(run: ExecutionRun,owner: String)=run.copy(owner=owner,state=ExecutionState.RUNNING).also { saved=it }
        override suspend fun update(id: String,owner: String,transform: (ExecutionRun)->ExecutionRun): ExecutionRun {
            if(saved.owner!=owner || saved.state!=ExecutionState.RUNNING) throw StaleRunException()
            return transform(saved).also { saved=it }
        }
    }
    private inner class Services: DirectComposioTransport {
        var writes=0;var reads=0;var storedBody="";var sentBody=""
        fun metadata(id: String): JSONObject {
            val fields=when(id) { create->listOf("title","markdown_text");read->listOf("document_id");else->listOf("recipient_email","subject","body") }
            return JSONObject().put("slug",id).put("name",id).put("toolkit",JSONObject().put("slug",if(id==send) "gmail" else "googledocs"))
                .put("version","fixture-v1").put("tags",JSONArray(if(id==read) listOf("readOnlyHint") else emptyList<String>()))
                .put("input_parameters",JSONObject().put("type","object").put("properties",JSONObject().apply { fields.forEach { put(it,JSONObject().put("type","string")) } }).put("required",JSONArray(fields)).put("additionalProperties",false))
        }
        override suspend fun request(path: String,apiKey: String,method: String,query: Map<String,String>,body: JSONObject?): JSONObject = when {
            path=="/connected_accounts" -> JSONObject().put("items",JSONArray(listOf("docs" to "googledocs","mail" to "gmail").filter { query["connected_account_ids"]==null || it.first==query["connected_account_ids"] }.map { (id,kit)->
                JSONObject().put("id",id).put("user_id","owner").put("toolkit",JSONObject().put("slug",kit)).put("status","ACTIVE").put("is_disabled",false) }))
            path=="/tools" -> JSONObject().put("items",JSONArray(listOf(create,read,send).map(::metadata)))
            path.startsWith("/tools/execute/") -> {
                val args=body!!.getJSONObject("arguments")
                when(path.substringAfterLast('/')) {
                    create -> { writes++;assertEquals(1,writes);storedBody=args.getString("markdown_text");JSONObject().put("successful",true).put("data",JSONObject().put("document_id",docId).put("url",docUrl)) }
                    read -> { reads++;assertEquals(docId,args.getString("document_id"));JSONObject().put("successful",true).put("data",JSONObject().put("document_id",docId).put("plain_text",storedBody)) }
                    send -> { writes++;sentBody=args.getString("body");assertEquals("recipient@example.test",args.getString("recipient_email"));assertTrue(sentBody.contains(docUrl));assertTrue(args.getString("subject").isNotBlank());JSONObject("""{"successful":true,"data":{"id":"fixture-message"}}""") }
                    else -> error("Unexpected execution")
                }
            }
            path.startsWith("/tools/") -> metadata(path.substringAfterLast('/'))
            else -> error("Unexpected service request $path")
        }
    }
    @Test fun documentOnlyReachesReviewWriteAndReadbackWithoutRecordedFindings()=runBlocking {
        for(origin in RunOrigin.entries) for(native in listOf(false,true)) for(resume in listOf(false,true)) scenario(false,origin,native,resume)
    }
    @Test fun addingEmailPreservesTheDocumentFlowAndUsesTheReturnedLinkOnce()=runBlocking {
        for(origin in RunOrigin.entries) for(native in listOf(false,true)) for(resume in listOf(false,true)) scenario(true,origin,native,resume)
    }
    private suspend fun scenario(email: Boolean,origin: RunOrigin,native: Boolean,resume: Boolean) {
        val request="Research projects at $url and create a Google doc called Projects"+(if(email) "; email its link to recipient@example.test" else "")
        val repo=Repo();var session=RunController(repo).open(ExecutionRun("run","chat","msg",origin,GoalContract.infer(request),privacyRevision=1,createdAt=1,
            progress=if(resume) RunProgress(stalled=6,collectionClosed=true,reason="Old stop") else RunProgress()))
        val services=Services();val client=DirectComposioClient(services);client.useCredentials(ComposioCredentials("fixture-key","owner"));val account=client.refresh()
        val settings=AppSettings(composioApiKey="fixture-key",composioUserId="owner",connectionGeneration=account.generation)
        val tools=listOf(create,read,send).associateWith { client.tool(it,"latest") }
        var fetches=0
        val webClient=WebResearchClient(WebTransport { address,_->
            fetches++
            val html=if(address.contains("duckduckgo")) """<div class="result"><a class="result__a" href="$url">Projects</a><div class="result__snippet">A directory of projects.</div></div>"""
                else "<main>$sourceText</main>"
            WebDocument(address,html,"text/html")
        })
        fun web()=WebResearchSession(request,webClient,trustedResults=session.record.research.receipts.map { it.result },onResult={ session.recordResearch(it) })
        val initial=listOf(InferenceMessage("system",if(native) AgentHarness.prompt("Be concise.","[]","[]","2026-11-24") else AgentProtocol.systemPrompt("Be concise.","[]","[]","2026-11-24")),InferenceMessage("user",request))
        var context=AgentTurnContext(initial,managed=true);var harness=AgentHarness(native)
        val config=ProviderConfig(ProviderId.KOINOS,"https://fixture.example","fixture-key","fixture-model",accountSession=true,nativeTools=native,contextTokens=131072)
        fun registered()=harness.config(config,tools.values.map { (if(it.toolkit=="gmail") "mail" else "docs") to it })
        registered()
        fun proposal(type: String,args: JSONObject,tool: String=""): Completion {
            val action=tool.isNotBlank()
            return if(native) {
                val name=if(action) registered().tools.single { it.description.startsWith("$tool:") }.name else type
                Completion("","tool_calls",toolCalls=listOf(ToolCall("call_${context.entryCount}",name,args.toString())))
            } else Completion(if(action) JSONObject().put("type","action").put("accountId",if(tool==send) "mail" else "docs").put("tool",tool).put("arguments",args).toString() else JSONObject(args.toString()).put("type",type).toString(),"stop")
        }
        fun append(c: Completion,result: String) { context.append(c.text,result,wire=if(native) harness.exchange(c.text,result) else null) }
        var phase=0
        session.drive {
            val c=if(phase++==0) proposal("web_search",JSONObject().put("query","community projects")) else proposal("read_web_page",JSONObject().put("url",url))
            val d=harness.parse(c,AgentResponseRecovery());val w=web();assertNull(w.correction(d));append(c,w.execute(d))
            if(phase==2) finish(ExecutionState.PAUSED)
        }
        // Reproduce optional legacy-note rejection: sources must remain available with zero findings.
        session=RunController(repo).open(RunJson.read(RunJson.write(repo.saved)))
        assertEquals("REJECTED",JSONObject(session.recordFindings(listOf(FindingProposal("A model claim","s2_0","An invented exact quotation.")))).getString("status"))
        assertTrue(session.record.research.findings.isEmpty())
        repeat(12) { context.append("catalog","Large catalog entry $it "+"schema ".repeat(1800)) }
        if(resume) {
            session.checkpoint(context.archive());session.finish(ExecutionState.PAUSED)
            session=RunController(repo).open(RunJson.read(RunJson.write(repo.saved)))
            context=AgentTurnContext(initial,checkpointSeed=session.record.archive,managed=true);harness=AgentHarness(native);registered()
        }
        val cached=JSONObject(web().execute(AgentDecision.ReadWebPage(url)).removePrefix(WebResearchSession.PREFIX))
        assertEquals(2,fetches);assertTrue(cached.getBoolean("cached"));assertTrue(cached.getString("text").contains(sourceText))
        val sources=JSONObject(session.record.research.context()).getJSONArray("sources")
        for(i in 0 until sources.length()) {
            val id=sources.getJSONObject(i).getString("entryId");var offset=0;val text=StringBuilder()
            do { val page=JSONObject(session.record.research.read(id,offset)!!);text.append(page.getString("excerpt"));offset=if(page.isNull("nextOffset")) -1 else page.getInt("nextOffset") } while(offset>=0)
            assertEquals(session.record.research.sources.first { "source:${it.id}"==id }.text,text.toString())
        }
        context.progressContext=GoalProgress.render(emptyList(),session.record,emptyList(),true,true)
        val working=AgentContextManager().prepare(context,registered(),summarize={_,_->error("Canonical source data is already available")})
        val input=working.joinToString("\n") { it.content }
        assertTrue(input.contains(sourceText));assertTrue(input.contains(request));assertFalse(registered().tools.any { it.name=="record_findings" })
        assertFalse(input.contains("Record quoted findings"));NativeToolWire.validate(working,native)
        val tasks=mutableListOf<AgentTask>();var tick=100L
        val guard=DocumentContentGuard(request,"chat",1,0,"owner",account.generation)
        suspend fun execute(id: String,args: JSONObject): AgentTask {
            val tool=tools.getValue(id);val completion=proposal("",args,id);val d=harness.parse(completion,AgentResponseRecovery()) as AgentDecision.PrepareAction
            assertNull(web().correction(d));ConnectionArguments.validate(d.arguments.toString(),tool.schemaJson)
            EffectGuard.validate(tasks,"chat",1,d.accountId,tool,d.arguments);guard.validateAction(tool,tasks,d.arguments)
            val intent=ActionIntent(id="intent-$id",toolName=id,accountId=d.accountId,ownerId="owner",connectionGeneration=account.generation,toolVersion=tool.version,toolSchemaJson=tool.schemaJson,argumentsJson=d.arguments.toString(),executionId="run",readOnly=tool.readOnly,effectKind=EffectKinds.classify(tool))
            var task=AgentTask(id=id,title=id,instructions=request,conversationId="chat",createdAt=++tick,status=TaskStatus.AWAITING_APPROVAL,intent=intent)
            val before=services.writes
            assertTrue(runCatching { TaskRules.transition(task,TaskStatus.RUNNING,settings) }.isFailure);assertEquals(before,services.writes)
            task=TaskRules.approve(task,settings,++tick)
            // Exact review is persisted before crossing the real client's one-use approval boundary.
            val approval=client.approveAction(d.accountId,tool,d.arguments.toString())
            val raw=EffectExecutor.submit(admit={ task=TaskRules.transition(task,TaskStatus.RUNNING,settings,at=++tick) },dispatch={ client.execute(d.accountId,tool,d.arguments.toString(),approval).resultJson },commit={ result ->
                val summary=ToolResultSummary.parseExecution(result,tool.readOnly)
                task=TaskRules.transition(task.copy(receipts=listOf(ActionReceipt(intent.id,if(id==send) "fixture-message" else docId,summary.status.name,url=if(id==create) docUrl else "",observedAt=++tick))),summary.status,settings,at=++tick).copy(resultContext=summary.modelData)
            },failed={ error("Unexpected fixture failure") })
            tasks+=task;session.serviceReceipt(task.id,tool.readOnly,task.status);append(completion,raw)
            assertTrue(runCatching { TaskRules.transition(task,TaskStatus.RUNNING,settings) }.isFailure)
            return task
        }
        // The fixture drafts only from material asserted present in the real projected model input.
        val body="Projects\n$sourceText\nSource: $url"
        val write=execute(create,JSONObject().put("title","Projects").put("markdown_text",body))
        assertEquals(body,services.storedBody);assertEquals(1,services.writes)
        execute(read,JSONObject().put("document_id",docId))
        assertTrue(DocumentVerification.evaluate(write,tasks).matched)
        assertNull(guard.checkReply(tasks,"Created the document."))
        val progress=GoalProgress.render(tasks,session.record,emptyList())
        assertTrue(progress.contains(docId));assertTrue(progress.contains("TEXT_MATCHED"))
        assertTrue(runCatching { EffectGuard.validate(tasks,"chat",1,"docs",tools.getValue(create),JSONObject(write.intent!!.argumentsJson)) }.isFailure)
        if(email) execute(send,JSONObject().put("recipient_email","recipient@example.test").put("subject","Projects document").put("body","Here is the requested document: $docUrl"))
        assertEquals(if(email) 2 else 1,services.writes);assertEquals(1,services.reads)
        assertTrue(GoalVerifier.acceptedEffect(tasks,"chat",1,session.record))
        session.finish(ExecutionState.COMPLETED)
    }
}
