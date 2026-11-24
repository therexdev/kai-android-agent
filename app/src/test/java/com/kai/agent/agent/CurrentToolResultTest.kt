package com.kai.agent.agent

import com.kai.agent.connections.*
import com.kai.agent.inference.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CurrentToolResultTest {
    private val config=ProviderConfig(ProviderId.KOINOS,"https://fixture.example","fixture","model",accountSession=true,contextTokens=131072,maxOutputTokens=4096)
    private val initial=listOf(InferenceMessage("system","Exact approval required; UNKNOWN never replays."),InferenceMessage("user","Research projects and create a Google document"))
    private val schema="""{"type":"object","properties":{"title":{"type":"string"},"text":{"type":"string","description":"Optional initial document body"}},"required":["title"]}"""
    private val tool=ConnectionTool("GOOGLEDOCS_CREATE_DOCUMENT","Create document","Create a document with optional initial text","googledocs","v1",false,schema)
    private val account=ConnectionAccount("docs","googledocs","owner","Docs","ACTIVE",false,"")

    @Test fun completeCurrentFeedbackSurvivesStrongestProjectionAndRestoreInBothProtocols()=runBlocking {
        for(native in listOf(false,true)) for(resume in listOf(false,true)) {
            val harness=AgentHarness(native);val recovery=AgentToolRecovery { native }
            val c=harness.config(config.copy(nativeTools=native),listOf(account.id to tool))
            val guard=DocumentContentGuard(initial.last().content,"chat",1,0,"owner","generation")
            fun completion(args: JSONObject)=if(native) Completion("","tool_calls",toolCalls=listOf(ToolCall("call",c.tools.single { it.name.startsWith("action_") }.name,args.toString())))
                else Completion(JSONObject().put("type","action").put("accountId",account.id).put("tool",tool.id).put("arguments",args).toString(),"stop")
            val invalid=completion(JSONObject().put("title","Projects"))
            val parsed=harness.parse(invalid,AgentResponseRecovery(),true) as AgentDecision.PrepareAction
            val check=requireNotNull(recovery.recover(parsed,listOf(account),false,{_,_->tool},{_,_->error("No rediscovery")},
                validateAction={ action,t->guard.validateAction(t,emptyList(),action.arguments) }))
            assertTrue(check.payload.full.contains("in text, not in title"))
            assertEquals(native,check.payload.full.contains("registered native function"))
            assertEquals(!native,check.payload.full.contains("Return one new JSON response"))
            var context=AgentTurnContext(initial,managed=true)
            repeat(4) { context.append("Earlier result","x".repeat(18000)) }
            context.append(invalid.text,check.payload.full,check.payload.compact,harness.exchange(invalid.text,check.payload.full))
            if(resume) context=AgentTurnContext(initial,checkpointSeed=context.archive(),managed=true)
            context.progressContext="Retained goal and sources. "+"evidence ".repeat(700)
            val manager=AgentContextManager()
            assertTrue(manager.recover(InferenceException("Context rejected",400,true)))
            val projected=manager.prepare(context,c,summarize={_,_->error("No additional inference")})
            assertEquals(check.payload.full,projected.last().content)
            assertTrue(projected.last().content.contains("Optional initial document body"))
            NativeToolWire.validate(projected,native)
            assertTrue(JSONArray(projected.map(NativeToolWire::messageJson)).toString().toByteArray().size+JSONArray(c.tools.map(NativeToolWire::toolJson)).toString().toByteArray().size<60000)
            // A new model proposal supplies the field. Host code never moves title text into it.
            val args=JSONObject().put("title","Projects").put("text","A researched body with enough useful details to populate the document.")
            val valid=harness.parse(completion(args),AgentResponseRecovery(),true) as AgentDecision.PrepareAction
            assertNull(recovery.recover(valid,listOf(account),false,{_,_->tool},{_,_->error("No rediscovery")},
                validateAction={ action,t->guard.validateAction(t,emptyList(),action.arguments) }))
            assertEquals(args.toString(),valid.arguments.toString())
        }
    }

    @Test fun changingOnlyTheTitleStillFailsWithoutAnotherCorrectionOrAutomaticRewrite()=runBlocking {
        val r=AgentToolRecovery();val guard=DocumentContentGuard(initial.last().content,"chat",1,0,"owner","generation")
        suspend fun check(title: String)=r.recover(AgentDecision.PrepareAction(account.id,tool.id,JSONObject().put("title",title),""),listOf(account),false,
            {_,_->tool},{_,_->error("No rediscovery")},validateAction={ a,t->guard.validateAction(t,emptyList(),a.arguments) })
        assertNotNull(check("Projects"))
        val error=runCatching { check("Projects\n"+"Research mistakenly placed in the title. ".repeat(30)) }.exceptionOrNull()
        assertTrue(error is IllegalStateException);assertTrue(error!!.message!!.contains("same missing or invalid input"))
    }

    @Test fun newestPagesAndAllResultsOfANativeGroupRemainExactAtEveryLevel() {
        val results=listOf("A source excerpt "+"研究".repeat(1800),"A different receipt "+"b".repeat(5000))
        val calls=listOf(ToolCall("a","read_a","{}"),ToolCall("b","read_b","{}"))
        val wire=listOf(InferenceMessage("assistant","",toolCalls=calls))+results.mapIndexed { i,s->InferenceMessage("tool",s,toolCallId=calls[i].id) }
        val context=AgentTurnContext.fromMessages(initial+wire)
        for(level in 0..3) {
            val projected=context.project(level);NativeToolWire.validate(projected,true)
            assertEquals(results,projected.filter { it.role=="tool" }.map { it.content })
        }
    }

    @Test fun anOversizedCurrentResultStopsInsteadOfSilentlyRemovingRequiredDetails()=runBlocking {
        val context=AgentTurnContext(initial,managed=true)
        val result="Current tool details "+"x".repeat(65000)
        context.append("read",result);context.progressContext="Retained goal"
        var summaries=0
        val failure=runCatching { AgentContextManager().prepare(context,config,summarize={_,_->summaries++;error("No summary")}) }.exceptionOrNull()
        assertTrue(failure is InferenceException);assertEquals(0,summaries)
        assertEquals(result,context.project(3).last().content)
    }

    @Test fun consumedResultsCanStillBeCompactedWithoutChangingTheirArchive() {
        val c=AgentTurnContext(initial,managed=true);val old="Old source "+"data ".repeat(2000)
        c.append("read",old);c.append("next","Current exact result")
        assertEquals("Current exact result",c.project(3).last().content)
        assertFalse(c.project(3).any { it.content==old })
        assertTrue(c.archive().contains(old));assertEquals(old.take(3000),JSONObject(c.read("t_1",0)).getString("excerpt"))
    }
}
