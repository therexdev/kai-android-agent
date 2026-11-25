package com.kai.agent.agent

import com.kai.agent.inference.*
import com.kai.agent.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PreparationAndRetrievalTest {
    private fun context()=AgentTurnContext(listOf(InferenceMessage("system","rules"),InferenceMessage("user","Research, create the document, then email it")),managed=true)
    @Test fun feedbackAndDraftSurviveCompactionAndRestartForBothProtocols() {
        for(native in listOf(false,true)) {
            val c=context()
            fun append(text: String,slot: PreparationSlot)=c.append("proposal",text,"lossy",if(native) listOf(
                InferenceMessage("assistant","",toolCalls=listOf(ToolCall(text,"web_search","{\"query\":\"test\"}"))),
                InferenceMessage("tool",text,toolCallId=text)) else null,slot)
            append("OLD_FEEDBACK",PreparationSlot.FEEDBACK)
            append("EXACT_SUBJECT_AND_BODY",PreparationSlot.DRAFT)
            append("BODY_REQUIRED",PreparationSlot.FEEDBACK)
            repeat(8) { c.append("find", "catalog $it") }
            val restored=AgentTurnContext(listOf(InferenceMessage("system","rules"),InferenceMessage("user","goal")),checkpointSeed=c.archive(),managed=true)
            val projected=restored.project(3)
            assertTrue(projected.any { it.content=="EXACT_SUBJECT_AND_BODY" })
            assertTrue(projected.any { it.content=="BODY_REQUIRED" })
            assertFalse(projected.any { it.content=="OLD_FEEDBACK" })
            NativeToolWire.validate(projected,native)
            restored.resolvePreparation()
            assertFalse(restored.project(3).any { it.content=="EXACT_SUBJECT_AND_BODY" })
        }
    }
    @Test fun savedStepPreparationSurvivesStorageAndProviderReplayConversion() {
        val messages=listOf(RunMessage("system","rules"),RunMessage("user","goal"),RunMessage("assistant","proposal"),RunMessage("user","subject and body",preparationSlot=PreparationSlot.DRAFT))
        val run=AgentRun(id="run",title="task",goal="email",conversationId="chat",steps=listOf(RunStep(id="step",title="email",instructions="send",messages=messages)))
        val restored=KaiJson.decode(KaiJson.encode(KaiState(runs=listOf(run)))).runs.single().steps.single().messages
        assertEquals(PreparationSlot.DRAFT,restored.last().preparationSlot)
        assertFalse(NativeToolWire.messageJson(InferenceMessage("user","draft",preparationSlot=PreparationSlot.DRAFT)).has("preparationSlot"))
    }
    @Test fun repeatedReadsKeepOriginalPagesAndDoNotBuildRecursiveIndexEntries() {
        val c=context();c.append("read_web_page","Original source with zero saved findings")
        repeat(3) {
            c.append("""{"type":"read_context","entryId":"t_1","offset":0}""",c.read("t_1",0))
        }
        c.append("inspect","schema")
        val restored=AgentTurnContext(emptyList(),checkpointSeed=c.archive(),managed=true)
        val projected=restored.project(3)
        assertTrue(projected.any { it.content.contains("Original source with zero saved findings") })
        val progress=projected.mapNotNull { runCatching { JSONObject(it.content) }.getOrNull() }.first { it.optString("source")=="phone_context_read_progress" }
        assertEquals(3,progress.getJSONArray("pagesAlreadyRead").getJSONObject(0).getInt("readCount"))
        val index=JSONObject(restored.read("index",0)).getString("excerpt")
        assertFalse(index.contains("t_2"));assertFalse(index.contains("t_3"));assertFalse(index.contains("t_4"))
        assertTrue(index.contains("t_1"));assertTrue(index.contains("t_5"))
    }
    @Test fun exactSheetsFollowupIsAnOrdinaryNewActionWithExistingReceiptsAvailable() {
        val request="can you put all the project's you found with the details in a Google sheet? columns for name, description, and then a third for a reference link?"
        val tool=com.kai.agent.connections.ConnectionTool("GOOGLESHEETS_CREATE_GOOGLE_SHEET1","Create sheet","Create sheet","googlesheets","1",false,"{}")
        // Resource reuse validation must not classify user intent from the words link or with.
        ResourceReuseGuard.validate(request,"chat",0,"owner","generation",AgentDecision.PrepareAction("sheets",tool.id,JSONObject().put("title","Projects"),""),tool,emptyList())
        assertEquals(request,AgentTurnContext(listOf(InferenceMessage("user",request)),managed=true).project(3).first().content)
    }
}
