package com.kai.agent.memory

import com.kai.agent.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MemoryReconciliationTest {
    private var next = 0L
    private fun u(text: String, scope: String = "personal") = MemoryUtterance("m${++next}", "chat", text, next * 1000, scope)
    private fun review(g: MemoryGraph, text: String) = MemoryEngine.ingest(g, u(text), MemoryMode.ASK_FIRST).graph
    private fun pending(g: MemoryGraph) = g.candidates.filter { it.status == CandidateStatus.PENDING }
    private fun accept(g: MemoryGraph) = MemoryEngine.confirm(g, pending(g).single().id, true).graph
    private fun current(g: MemoryGraph, p: String) = g.facts.filter { it.status == FactStatus.CURRENT && it.predicate == p }

    @Test fun familySentenceExtractsRelatedPersonAndBirthdayWithoutTranscriptNote() {
        val source = u("More things my daughter is Clara and she was born October 3rd 2018")
        val g = MemoryNotes.propose(MemoryGraph(), source, source.text).graph
        val card = pending(g).single()
        assertEquals(setOf("has_daughter", "name", "birthday"), card.proposedFacts.map { it.predicate }.toSet())
        assertTrue(card.summary.contains("Clara is your daughter"))
        assertTrue(card.summary.contains("October 3, 2018"))
        assertFalse(card.summary.contains("More things"))
        card.evidence.forEach { assertEquals(it.quote, source.text.substring(it.start, it.end)) }
        val saved = accept(g)
        assertEquals(current(saved,"has_daughter").single().objectEntityId, current(saved,"birthday").single().subjectId)
    }
    @Test fun revisitingAnOldFamilyTranscriptCardConvertsItInPlace() {
        val source=u("More things my daughter is Clara and she was born October 3rd 2018")
        val note=StructuredFact(subjectId="self:personal",predicate="note",value=MemoryValue(text=source.text))
        val old=MemoryCandidate(question="Save exact words?",summary=source.text,proposedFacts=listOf(note),
            evidence=listOf(FactEvidence(factId=note.id,sourceMessageId=source.messageId,conversationId=source.conversationId,quote=source.text)),
            sourceMessageId=source.messageId,conversationId=source.conversationId)
        val result=MemoryNotes.propose(MemoryGraph(candidates=listOf(old)),source,source.text)
        assertEquals(old.id,result.candidateIds.single())
        assertEquals(setOf("has_daughter","name","birthday"),pending(result.graph).single().proposedFacts.map { it.predicate }.toSet())
        assertFalse(pending(result.graph).single().summary.contains("More things"))
    }
    @Test fun pendingCorrectionReusesCardAndConfirmedCorrectionReplacesCurrentProperty() {
        val first = review(MemoryGraph(), "My last name is Jones")
        val id = pending(first).single().id
        val corrected = review(first, "Can you update that card because my last name is J o h n s")
        assertEquals(id, pending(corrected).single().id)
        assertEquals("Johns", pending(corrected).single().proposedFacts.single().value?.text)
        assertTrue(pending(corrected).single().question.contains("Jones"))
        var saved = accept(corrected)
        saved = accept(review(saved, "Actually, my last name is Johnson"))
        assertEquals("Johnson", current(saved,"last_name").single().value?.text)
        assertEquals(1, saved.facts.count { it.status == FactStatus.SUPERSEDED })
        assertFalse(MemoryRetriever.retrieve(saved,"my last name", setOf("personal")).contextJson.contains("Johns."))
        val repeated = review(saved,"My last name is Johnson")
        assertTrue(pending(repeated).isEmpty())
        assertEquals(1,current(repeated,"last_name").size)
    }
    @Test fun oldVerbatimSurnameCardIsUpdatedInsteadOfDuplicated() {
        val old = StructuredFact(subjectId="self:personal",predicate="note",value=MemoryValue(text="My last name is Jones"))
        val card = MemoryCandidate(question="Save exact words?", summary=old.value!!.text, proposedFacts=listOf(old),
            evidence=listOf(FactEvidence(factId=old.id,sourceMessageId="old",conversationId="chat",quote=old.value.text,observedAt=0)), sourceMessageId="old",conversationId="chat")
        val fixed = review(MemoryGraph(candidates=listOf(card)), "Can you update that card because my last name is Johns")
        assertEquals(card.id,pending(fixed).single().id)
        assertEquals("last_name",pending(fixed).single().proposedFacts.single().predicate)
    }
    @Test fun savedLegacySurnameNoteIsSupersededButMixedNotesArePreserved() {
        val person=MemoryEntity(id="self:personal",displayName="You")
        val old=StructuredFact(subjectId=person.id,predicate="legacy_note",value=MemoryValue(text="My last name is Jones"))
        val mixed=StructuredFact(subjectId=person.id,predicate="note",value=MemoryValue(text="My last name is Jones. I prefer tea."))
        val g=MemoryGraph(entities=listOf(person),facts=listOf(old,mixed))
        val fixed=accept(review(g,"My last name is Johns"))
        assertEquals(FactStatus.SUPERSEDED,fixed.facts.first {it.id==old.id}.status)
        assertEquals(FactStatus.CURRENT,fixed.facts.first {it.id==mixed.id}.status)
        assertEquals(listOf(old.id),current(fixed,"last_name").single().lineageIds)
    }
    @Test fun repeatedFamilyReviewDoesNotCreateAnotherPersonOrBirthday() {
        val text="My daughter is Clara and she was born October 3rd 2018"
        val first=review(MemoryGraph(),text)
        val second=review(first,text)
        assertEquals(pending(first).single().id,pending(second).single().id)
        assertEquals(3,pending(second).single().proposedFacts.size)
        val saved=accept(second)
        assertTrue(pending(review(saved,text)).isEmpty())
    }
    @Test fun ambiguousSpokenSpellingCannotBecomeAProposalOrBeConfirmed() {
        val source=u("Can you update that card because my last name is j o n e s johns")
        assertTrue(MemoryPatterns.ambiguousName(source.text))
        assertTrue(MemoryPatterns.parse(source).isEmpty())
        assertTrue(runCatching { MemoryNotes.propose(MemoryGraph(),source,source.text) }.isFailure)
        val f=StructuredFact(subjectId="self:personal",predicate="note",value=MemoryValue(text=source.text))
        val c=MemoryCandidate(question="Save?",proposedFacts=listOf(f),evidence=listOf(FactEvidence(factId=f.id,sourceMessageId=source.messageId,conversationId=source.conversationId,quote=source.text)))
        assertTrue(runCatching { MemoryEngine.confirm(MemoryGraph(candidates=listOf(c)),c.id,true) }.isFailure)
    }
    @Test fun modelFactsReconcileCurrentAndPendingOccupationAndNeverCopyTranscript() {
        fun propose(g:MemoryGraph,text:String,value:String):MemoryGraph {
            val source=u(text)
            val json=JSONObject().put("claims",org.json.JSONArray().put(JSONObject().put("subjectId","self").put("predicate","occupation")
                .put("value",JSONObject().put("type","TEXT").put("text",value)).put("quote",text))).toString()
            val claims=MemoryExtractor.parse(json,source,g)
            return MemoryNotes.propose(g,source,text,claims).graph
        }
        val first=propose(MemoryGraph(),"I work as a carpenter.","carpenter")
        val second=propose(first,"I now work as a plumber.","plumber")
        assertEquals(pending(first).single().id,pending(second).single().id)
        val third=propose(accept(second),"I now work as a baker.","baker")
        assertTrue(pending(third).single().question.contains("plumber"))
        assertEquals("baker",current(accept(third),"occupation").single().value?.text)
        val text="For future chats, keep in mind that I like quiet cafes."
        assertTrue(runCatching { MemoryNotes.propose(MemoryGraph(),u(text),text) }.isFailure)
    }
    @Test fun delayedExtractionCannotReplaceANewerCorrection() {
        val old=u("I work as a carpenter.")
        val newer=u("I now work as a baker.")
        fun proposal(source:MemoryUtterance,value:String)=ModelMemoryProposal("self","You","occupation",MemoryValue(text=value),source.text,0)
        val currentGraph=MemoryEngine.propose(MemoryGraph(),newer,listOf(proposal(newer,"baker")),MemoryMode.AUTOMATIC).graph
        val late=MemoryEngine.propose(currentGraph,old,listOf(proposal(old,"carpenter")),MemoryMode.AUTOMATIC).graph
        assertEquals("baker",pending(late).single().proposedFacts.single().value?.text)
        val saved=accept(currentGraph)
        val lateAfterSave=MemoryEngine.propose(saved,old,listOf(proposal(old,"carpenter")),MemoryMode.AUTOMATIC).graph
        assertTrue(pending(lateAfterSave).isEmpty())
        assertEquals("baker",current(lateAfterSave,"occupation").single().value?.text)
    }
    @Test fun correctionsDoNotCrossScopesAndForgetBlocksOldSources() {
        val original=u("My last name is Jones")
        val initial=MemoryEngine.ingest(MemoryGraph(),original,MemoryMode.ASK_FIRST).graph
        val personal=accept(initial)
        val work=MemoryEngine.ingest(personal,u("My last name is Johns","project:work"),MemoryMode.ASK_FIRST).graph
        assertEquals("Jones",current(work,"last_name").single().value?.text)
        assertFalse(pending(work).single().question.contains("Jones"))
        val forgotten=MemoryGraphRules.forget(personal,current(personal,"last_name").single().id)
        assertTrue(runCatching { MemoryNotes.propose(forgotten,original,original.text) }.isFailure)
    }
}
