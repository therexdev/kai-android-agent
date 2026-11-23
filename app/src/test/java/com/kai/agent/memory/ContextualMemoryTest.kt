package com.kai.agent.memory

import com.kai.agent.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ContextualMemoryTest {
    private fun u(text: String, id: String = "current", context: List<MemoryContextTurn> = emptyList()) =
        MemoryUtterance(id, "chat", text, 10000, context = context)
    private fun claim(source: MemoryUtterance, value: String, predicate: String = "preference", subject: String = "self",
                      name: String = "", review: Boolean = false, support: List<MemoryContextTurn> = emptyList()): String =
        JSONObject().put("claims", JSONArray().put(JSONObject().put("subjectId", subject).put("subjectName", name)
            .put("predicate", predicate).put("value", JSONObject().put("type", "TEXT").put("text", value))
            .put("quote", source.text).put("needsReview", review).put("contextQuotes", JSONArray(support.map {
                JSONObject().put("messageId", it.messageId).put("quote", it.text)
            })))).toString()
    private fun extract(source: MemoryUtterance, response: String, graph: MemoryGraph = MemoryGraph(), mode: MemoryMode = MemoryMode.AUTOMATIC) =
        MemoryEngine.propose(graph, source, MemoryExtractor.parse(response, source, graph), mode)
    private fun current(g: MemoryGraph, predicate: String) = g.facts.filter { it.predicate == predicate && it.status == FactStatus.CURRENT }
    private val intro = "It's going good do you want to say hi to my daughter Aurora"

    @Test fun naturalIntroductionCreatesLinkedDaughterInAutomaticMode() {
        val result = MemoryEngine.ingest(MemoryGraph(), u(intro), MemoryMode.AUTOMATIC)
        val daughter = current(result.graph, "has_daughter").single()
        assertEquals("Aurora", result.graph.entities.single { it.id == daughter.objectEntityId }.displayName)
        assertTrue(result.graph.candidates.isEmpty())
        assertTrue(result.graph.evidence.all { it.quote == intro })
    }
    @Test fun introductionRespectsAskFirstAndOff() {
        assertTrue(MemoryEngine.ingest(MemoryGraph(), u(intro), MemoryMode.OFF).graph.facts.isEmpty())
        val review = MemoryEngine.ingest(MemoryGraph(), u(intro), MemoryMode.ASK_FIRST).graph
        assertTrue(review.facts.isEmpty()); assertEquals(1, review.candidates.size)
    }
    @Test fun fictionalIntroductionCannotSave() {
        listOf("Pretend $intro", "If you want to meet my daughter Aurora", "My friend said $intro").forEach {
            assertTrue(MemoryEngine.ingest(MemoryGraph(), u(it), MemoryMode.AUTOMATIC).graph.facts.isEmpty())
        }
    }
    @Test fun pretendPlayDoesNotRejectRealPreferenceOrOtherClause() {
        val source = u("My name is Mike. My daughter likes to pretend she drives to a gas station.")
        assertEquals("Mike", current(MemoryEngine.ingest(MemoryGraph(), source, MemoryMode.AUTOMATIC).graph, "name").single().value?.text)
        assertTrue(MemoryExtractor.shouldExtract(source))
        assertTrue(MemoryExtractor.prompt(source, MemoryGraph()).contains("likes to pretend"))
        val separate = u("My name is Mike. Pretend my name is Bob.")
        assertEquals(listOf("Mike"), current(MemoryEngine.ingest(MemoryGraph(), separate, MemoryMode.AUTOMATIC).graph, "name").map { it.value?.text })
    }
    @Test fun backgroundFactsHonorAutomaticAndAskFirst() {
        val source = u("I work as a carpenter.")
        val response = claim(source, "carpenter", "occupation")
        val saved = extract(source, response)
        assertEquals("carpenter", current(saved.graph, "occupation").single().value?.text)
        assertTrue(saved.graph.candidates.isEmpty())
        assertTrue(extract(source, response, mode = MemoryMode.ASK_FIRST).graph.facts.isEmpty())
        assertEquals(1, extract(source, response, mode = MemoryMode.ASK_FIRST).graph.candidates.size)
        assertTrue(extract(source, response, mode = MemoryMode.OFF).graph.facts.isEmpty())
    }
    @Test fun sensitiveAndUncertainInterpretationsStillNeedReview() {
        val medical = u("For anxiety, I prefer medication.")
        val result = extract(medical, claim(medical, "medication for anxiety"))
        assertTrue(result.graph.facts.isEmpty()); assertEquals(CandidateKind.SENSITIVE, result.graph.candidates.single().kind)
        val uncertain = u("I enjoy cycling.")
        assertEquals(1, extract(uncertain, claim(uncertain, "cycling", review = true)).graph.candidates.size)
    }
    @Test fun assistantGuessIsNeverAnAutomaticallySavedValue() {
        val source = u("I like the Lego game", context = listOf(MemoryContextTurn("assistant", "assistant", "Is that LEGO City Undercover?", 100)))
        val result = extract(source, claim(source, "LEGO City Undercover"))
        assertTrue(result.graph.facts.isEmpty())
        val invalid = claim(source, "LEGO City Undercover", support = source.context)
        assertTrue(MemoryExtractor.parse(invalid, source, MemoryGraph()).isEmpty())
    }
    @Test fun childAnswerUsesHandoffAndCannotOverwriteParent() {
        val graph = MemoryEngine.ingest(MemoryGraph(), u(intro, "intro"), MemoryMode.AUTOMATIC).graph
        val girl = graph.entities.single { it.displayName == "Aurora" }
        val context = listOf(MemoryContextTurn("intro", "user", intro, 100), MemoryContextTurn("question", "assistant", "Do you like driving fast or exploring the city?", 200))
        val source = u("Exploring the city", context = context)
        assertTrue(MemoryExtractor.shouldExtract(source))
        assertTrue(MemoryExtractor.prompt(source, graph).contains("<speaker_hint>Aurora</speaker_hint>"))
        assertTrue(MemoryExtractor.parse(claim(source, "Exploring the city"), source, graph).isEmpty())
        val result = extract(source, claim(source, "Exploring the city", subject = girl.id), graph)
        assertEquals(girl.id, current(result.graph, "preference").single().subjectId)
        assertEquals(setOf("intro", "current"), result.graph.evidence.filter { it.factId == current(result.graph, "preference").single().id }.map { it.sourceMessageId }.toSet())
    }
    @Test fun localFirstPersonPreferencesRespectGuestSpeakerToo() {
        val graph = MemoryEngine.ingest(MemoryGraph(), u(intro, "intro"), MemoryMode.AUTOMATIC).graph
        val source = u("I like driving", context = listOf(MemoryContextTurn("intro", "user", intro, 100)))
        val result = MemoryEngine.ingest(graph, source, MemoryMode.AUTOMATIC)
        assertEquals("Aurora", result.graph.entities.single { it.id == current(result.graph, "preference").single().subjectId }.displayName)
    }
    @Test fun parentReturnChangesSpeakerWithoutSilentlyMergingIdentity() {
        val source = u("This is her dad Mike she just likes to pretend that she needs to fill up the car with gas", context = listOf(MemoryContextTurn("intro", "user", intro, 100)))
        assertEquals("Mike", MemoryConversationContext.speaker(source))
        assertTrue(MemoryExtractor.shouldExtract(source))
    }
    @Test fun duplicateSameNameRemainsIdentityReviewEvenWhenModelChoosesId() {
        var graph = MemoryEngine.ingest(MemoryGraph(), u("My daughter's name is Aurora.", "first"), MemoryMode.AUTOMATIC).graph
        graph = MemoryEngine.ingest(graph, u("I have another daughter named Aurora.", "second"), MemoryMode.AUTOMATIC).graph
        val source = u("Aurora likes driving.")
        val id = graph.entities.first { it.displayName == "Aurora" }.id
        val result = extract(source, claim(source, "driving", subject = id), graph)
        assertTrue(current(result.graph, "preference").isEmpty())
        assertEquals(CandidateKind.IDENTITY, result.graph.candidates.single().kind)
    }
    @Test fun repeatedAutomaticClaimDoesNotDuplicateFact() {
        val source = u("I work as a carpenter.")
        val first = extract(source, claim(source, "carpenter", "occupation")).graph
        val next = source.copy(messageId = "next", createdAt = 11000)
        val second = extract(next, claim(next, "carpenter", "occupation"), first).graph
        assertEquals(1, current(second, "occupation").size)
    }
    @Test fun conflictingAutomaticClaimIsReviewedRatherThanAppended() {
        val source = u("I work as a carpenter.")
        val first = extract(source, claim(source, "carpenter", "occupation")).graph
        val next = u("I work as a baker.", "new").copy(createdAt = 11000)
        val second = extract(next, claim(next, "baker", "occupation"), first).graph
        assertEquals("carpenter", current(second, "occupation").single().value?.text)
        assertEquals(CandidateKind.CONFLICT, second.candidates.single().kind)
        val corrected = MemoryEngine.confirm(second, second.candidates.single().id, true).graph
        assertEquals("baker", current(corrected, "occupation").single().value?.text)
        assertEquals(1, corrected.facts.count { it.status == FactStatus.SUPERSEDED })
    }
    @Test fun malformedEmptyAndRejectedResponsesAreDistinguishable() {
        val source = u("I enjoy cycling.")
        assertEquals("invalid_response", MemoryExtractor.inspect("not json", source, MemoryGraph()).outcome)
        assertEquals("no_facts", MemoryExtractor.inspect("{\"claims\":[]}", source, MemoryGraph()).outcome)
        val wrong = claim(source, "cycling").replace("I enjoy cycling.", "No such user quote")
        assertEquals("rejected_evidence_or_identity", MemoryExtractor.inspect(wrong, source, MemoryGraph()).outcome)
    }
    @Test fun contextIsBoundedAndCannotCrossTemporaryOrForgetBoundary() {
        val messages = (1..20).map { ChatMessage("m$it", "user", "I like cycling", it.toLong()) }
        val chat = Conversation(id = "chat", messages = messages)
        assertEquals(12, MemoryConversationContext.utterance(chat, messages.last(), MemoryGraph()).context.size)
        assertTrue(MemoryConversationContext.utterance(chat.copy(temporary = true), messages.last(), MemoryGraph()).context.isEmpty())
        val forgotten = MemoryGraph(suppressions = listOf(GraphSuppression(sourceHash = MemoryGraphRules.sourceHash("chat", "m18"))))
        assertEquals(listOf("m19"), MemoryConversationContext.utterance(chat, messages.last(), forgotten).context.map { it.messageId })
        assertEquals(listOf("m19"), MemoryConversationContext.utterance(chat.copy(contextAfter = 19), messages.last(), MemoryGraph()).context.map { it.messageId })
    }
    @Test fun forgottenContextCannotResurrectDependentFacts() {
        val source = u("I enjoy cycling.", context = listOf(MemoryContextTurn("old", "user", "I enjoy cycling", 100)))
        val graph = MemoryGraph(suppressions = listOf(GraphSuppression(sourceHash = MemoryGraphRules.sourceHash("chat", "old"))))
        assertTrue(MemoryExtractor.parse(claim(source, "cycling", support = source.context), source, graph).isEmpty())
    }
    @Test fun questionChatterAndSecretsDoNotQueue() {
        listOf("Hello", "Okay bye Kai", "What time is it?", "My API key is sk_abcdefghijklmnop").forEach {
            assertFalse(it, MemoryExtractor.shouldExtract(u(it)))
        }
    }
    @Test fun olderSpeakerAnchorSurvivesBeyondRecentWindow() {
        val first = ChatMessage("intro", "user", intro, 1)
        val messages = listOf(first) + (2..30).map { ChatMessage("m$it", if (it % 2 == 0) "assistant" else "user", "Hello", it.toLong()) } + ChatMessage("last", "user", "I like cycling", 31)
        val chat = Conversation(id = "chat", messages = messages)
        val source = MemoryConversationContext.utterance(chat, messages.last(), MemoryGraph())
        assertTrue(source.context.size <= 12)
        assertEquals("Aurora", MemoryConversationContext.speaker(source))
        val forgotten = MemoryGraph(suppressions = listOf(GraphSuppression(sourceHash = MemoryGraphRules.sourceHash("chat", "intro"))))
        val after = MemoryConversationContext.utterance(chat, messages.last(), forgotten)
        assertEquals("unknown", MemoryConversationContext.speaker(after))
        assertTrue(MemoryEngine.ingest(forgotten, after, MemoryMode.AUTOMATIC).graph.facts.isEmpty())
    }
    @Test fun thirdPersonFactCannotBeAutomaticallyAttributedToOwner() {
        val source = u("My daughter likes cycling.")
        assertTrue(extract(source, claim(source, "cycling")).graph.facts.isEmpty())
    }
    @Test fun quotedHypotheticalContextCannotGroundAModelClaim() {
        val old = MemoryContextTurn("old", "user", "Pretend I enjoy skydiving", 100)
        val source = u("I enjoy cycling.", context = listOf(old))
        val response = claim(source, "skydiving", support = listOf(old.copy(text = "I enjoy skydiving")))
        assertTrue(MemoryExtractor.parse(response, source, MemoryGraph()).isEmpty())
    }

    @Test fun vaguePreferenceDoesNotBecomeAPermanentVerbatimFact() {
        for (text in listOf("I like this", "I love it", "I prefer that game"))
            assertTrue(MemoryEngine.ingest(MemoryGraph(), u(text), MemoryMode.AUTOMATIC).graph.facts.isEmpty())
    }

    @Test fun forgettingPreferenceKeepsFamilyButForgettingItsIdentityRemovesDependentPreference() {
        val graph = MemoryEngine.ingest(MemoryGraph(), u(intro, "intro"), MemoryMode.AUTOMATIC).graph
        val source = u("I like driving", context = listOf(MemoryContextTurn("intro", "user", intro, 100)))
        val learned = MemoryEngine.ingest(graph, source, MemoryMode.AUTOMATIC).graph
        val preference = current(learned, "preference").single()
        val daughter = current(learned, "has_daughter").single()
        val forgetPreference = MemoryGraphRules.forget(learned, preference.id)
        assertEquals(1, current(forgetPreference, "has_daughter").size)
        assertTrue(current(forgetPreference, "preference").isEmpty())
        val forgetIdentity = MemoryGraphRules.forget(learned, daughter.id)
        assertTrue(current(forgetIdentity, "preference").isEmpty())
    }

    @Test fun fictionalSetupOrQuotationCannotLeakFactsFromLaterSentences() {
        for (text in listOf("Pretend you are me. My name is Bob.", "Imagine a person. My name is Bob.",
            "In this story a person speaks. My name is Bob.", "\"Some text. My name is Bob. More text.\"")) {
            val source = u(text)
            assertTrue(text, MemoryEngine.ingest(MemoryGraph(), source, MemoryMode.AUTOMATIC).graph.facts.isEmpty())
            assertFalse(text, MemoryExtractor.shouldExtract(source))
        }
    }

    @Test fun fictionalHandoffCannotReplaceRealSpeakerAnchor() {
        val messages = listOf(ChatMessage("intro", "user", intro, 1),
            ChatMessage("fiction", "user", "Pretend you can say hi to my daughter Bella", 2)) +
            (3..30).map { ChatMessage("m$it", "assistant", "Hello", it.toLong()) } + ChatMessage("last", "user", "I like cycling", 31)
        val source = MemoryConversationContext.utterance(Conversation(id = "chat", messages = messages), messages.last(), MemoryGraph())
        assertEquals("Aurora", MemoryConversationContext.speaker(source))
    }

}
