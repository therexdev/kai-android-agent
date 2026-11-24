package com.kai.agent.connections

import com.kai.agent.agent.*
import com.kai.agent.inference.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ActionInputRulesTest {
    private val tool=ConnectionTool("GMAIL_SEND_EMAIL","Send email","Send a message","gmail","fixture",false,
        """{"type":"object","properties":{"recipient_email":{"type":"string"},"subject":{"type":"string"},"body":{"type":"string"},"cc":{"type":"array","items":{"type":"string"}},"bcc":{"type":"array","items":{"type":"string"}},"extra_recipients":{"type":"array"},"include_signature":{"type":"boolean"}}}""")
    private val account=ConnectionAccount("mail","gmail","owner","Mail","ACTIVE",false,"")
    private val blank="""{"recipient_email":"recipient@example.test","extra_recipients":[],"include_signature":false}"""
    private fun invalid(args: String) {
        try { ActionInputRules.validate(tool,JSONObject(args));fail("Expected a rejected proposal") }
        catch(_: ConnectionServiceException) {}
    }
    @Test fun serviceCrossFieldRequirementRejectsSchemaValidEmptyMail() {
        ConnectionArguments.validate(blank,tool.schemaJson)
        invalid(blank)
        invalid("""{"recipient_email":"recipient@example.test","subject":"  ","body":"\n"}""")
        invalid("""{"recipient_email":"recipient@example.test","subject":null,"body":null}""")
    }
    @Test fun recipientAndEitherContentFieldAreRequiredWithoutRewriting() {
        for(raw in listOf("""{"recipient_email":"recipient@example.test","subject":"Hello"}""",
            """{"cc":["recipient@example.test"],"body":"https://example.test/doc"}""")) {
            val args=JSONObject(raw);val before=args.toString()
            ActionInputRules.validate(tool,args);assertEquals(before,args.toString())
        }
        invalid("""{"body":"Hello","cc":[],"bcc":[" "]}""")
        invalid("""{"body":"Hello","extra_recipients":["recipient@example.test"]}""")
    }
    @Test fun knownPlaceholderDraftsDoNotReachAService() {
        val draft=tool.copy(id="GMAIL_GET_DRAFT",readOnly=true)
        for(id in listOf("unused","placeholder","","draft_id","undefined")) {
            try { ActionInputRules.validate(draft,JSONObject().put("draft_id",id));fail() }
            catch(e: ConnectionServiceException) { assertTrue(e.message!!.contains("real draft_id")) }
        }
        ActionInputRules.validate(draft,JSONObject().put("draft_id","r-fixture-returned-id"))
        ActionInputRules.validate(tool.copy(toolkit="mcp"),JSONObject(blank))
        ActionInputRules.validate(tool.copy(id="GMAIL_LIST_DRAFTS"),JSONObject())
    }
    @Test fun nativeAndJsonProposalsGetBoundedCorrectionBeforePreparation()=runBlocking {
        for(native in listOf(false,true)) {
            val harness=AgentHarness(native)
            val config=harness.config(ProviderConfig(ProviderId.KOINOS,"https://example.test","fixture","model"),listOf(account.id to tool))
            fun proposal(args: String): AgentDecision {
                val response=if(native) Completion("","tool_calls",toolCalls=listOf(ToolCall("call",config.tools.last().name,args)))
                    else Completion(JSONObject().put("type","action").put("accountId",account.id).put("tool",tool.id).put("arguments",JSONObject(args)).put("explanation","Requested email").toString(),"stop")
                return harness.parse(response,AgentResponseRecovery())
            }
            val recovery=AgentToolRecovery()
            val correction=requireNotNull(recovery.recover(proposal(blank),listOf(account),false,{_,_->tool},{_,_->error("No catalog IO")}))
            assertTrue(correction.payload.full.contains("non-empty subject or body"))
            assertTrue(correction.payload.full.contains("before preparation or execution"))
            val corrected=JSONObject(blank).put("subject","Project document").put("body","Here is the document: https://example.test/doc").toString()
            assertNull(recovery.recover(proposal(corrected),listOf(account),false,{_,_->tool},{_,_->error("No IO")}))
            try { recovery.recover(proposal(blank),listOf(account),false,{_,_->tool},{_,_->error("No IO")});fail() }
            catch(_: IllegalStateException) {}
        }
    }
}
