package com.kai.agent.memory

import com.kai.agent.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MemoryEngineTest {
    private var sequence = 0L
    private fun utterance(text: String, conversation: String = "chat-a", scope: String = "personal", explicit: Boolean = false) =
        MemoryUtterance("message-${++sequence}", conversation, text, sequence * 1000, scope, explicit = explicit)
    private fun learn(graph: MemoryGraph, text: String, conversation: String = "chat-a") =
        MemoryEngine.ingest(graph, utterance(text, conversation), MemoryMode.AUTOMATIC).graph
    private fun current(graph: MemoryGraph, predicate: String) = graph.facts.filter { it.predicate == predicate && it.status == FactStatus.CURRENT }

    @Test fun daughterAndMonthBirthdayLinkAcrossChatsInBothOrders() {
        listOf(false, true).forEach { reverse ->
            val first = if (reverse) "Aurora's birthday is in October." else "My daughter's name is Aurora."
            val second = if (reverse) "My daughter's name is Aurora." else "Aurora's birthday is in October."
            val graph = learn(learn(MemoryGraph(), first), second, "chat-b")
            val daughter = current(graph, "has_daughter").single().objectEntityId
            val birthday = current(graph, "birthday").single()
            assertEquals(daughter, birthday.subjectId)
            assertEquals(10, birthday.value?.month)
            assertNull(birthday.value?.day); assertNull(birthday.value?.year)
            val retrieved = MemoryRetriever.retrieve(graph, "When is my daughter's birthday?", setOf("personal"))
            assertNull(retrieved.clarification)
            assertTrue(retrieved.factIds.containsAll(listOf(birthday.id, current(graph, "has_daughter").single().id)))
            assertTrue(retrieved.contextJson.contains("October"))
        }
    }

    @Test fun unnamedDaughterBirthdayCanAcquireNameWithoutAnotherPerson() {
        var graph = learn(MemoryGraph(), "My daughter's birthday is in October.")
        val id = current(graph, "has_daughter").single().objectEntityId
        graph = learn(graph, "My daughter's name is Aurora.", "chat-b")
        assertEquals(id, current(graph, "birthday").single().subjectId)
        assertEquals("Aurora", graph.entities.first { it.id == id }.displayName)
    }

    @Test fun sameNamesAreSeparateAndAmbiguousBirthdayWaitsForChoice() {
        var graph = learn(MemoryGraph(), "My daughter's name is Aurora.")
        graph = learn(graph, "I have another daughter named Aurora.")
        assertEquals(2, current(graph, "has_daughter").map { it.objectEntityId }.distinct().size)
        graph = learn(graph, "Aurora's birthday is in October.")
        assertTrue(current(graph, "birthday").isEmpty())
        val pending = graph.candidates.single { it.status == CandidateStatus.PENDING }
        assertEquals(CandidateKind.IDENTITY, pending.kind)
        assertTrue(pending.entityChoices.contains(MemoryEngine.NEW_PERSON_CHOICE))
        val selected = pending.entityChoices.first { it != MemoryEngine.NEW_PERSON_CHOICE }
        graph = MemoryEngine.confirm(graph, pending.id, true, selected).graph
        assertEquals(selected, current(graph, "birthday").single().subjectId)
        assertNotNull(MemoryRetriever.retrieve(graph, "When is my daughter's birthday?", setOf("personal")).clarification)
    }

    @Test fun relationshipAmbiguityBundlesDependentBirthdayWithoutWritingToFriend() {
        var graph = learn(MemoryGraph(), "My friend's name is Emma.")
        val friend = current(graph, "has_friend").single().objectEntityId
        graph = learn(graph, "My daughter Emma's birthday is in May.")
        assertTrue(current(graph, "birthday").isEmpty())
        assertTrue(current(graph, "has_daughter").isEmpty())
        val pending = graph.candidates.single { it.status == CandidateStatus.PENDING }
        assertTrue(pending.proposedFacts.any { it.predicate == "birthday" })
        graph = MemoryEngine.confirm(graph, pending.id, true, MemoryEngine.NEW_PERSON_CHOICE).graph
        val daughter = current(graph, "has_daughter").single().objectEntityId
        assertNotEquals(friend, daughter)
        assertEquals(daughter, current(graph, "birthday").single().subjectId)
    }

    @Test fun laterSameNameSentenceDoesNotChooseNewDaughterWithoutReview() {
        var graph = learn(MemoryGraph(), "My daughter's name is Emma.")
        graph = learn(graph, "I have another daughter named Emma. Emma's birthday is in May.")
        assertEquals(2, current(graph, "has_daughter").size)
        assertTrue(current(graph, "birthday").isEmpty())
        assertEquals(CandidateKind.IDENTITY, graph.candidates.single().kind)
    }

    @Test fun askFirstCombinesSameTurnEntityAndBirthday() {
        val u = utterance("My daughter's name is Aurora. Aurora's birthday is in October.")
        var graph = MemoryEngine.ingest(MemoryGraph(), u, MemoryMode.ASK_FIRST).graph
        assertTrue(graph.facts.isEmpty())
        val pending = graph.candidates.single()
        assertEquals(3, pending.proposedFacts.size)
        graph = MemoryEngine.confirm(graph, pending.id, true).graph
        assertEquals(current(graph, "has_daughter").single().objectEntityId, current(graph, "birthday").single().subjectId)
        assertTrue(graph.evidence.all { it.confirmedByUser })
    }

    @Test fun correctionsSupersedeButBareConflictNeedsReview() {
        var graph = learn(MemoryGraph(), "Aurora's birthday is in October.")
        graph = learn(graph, "Aurora's birthday is in November.")
        assertEquals(10, current(graph, "birthday").single().value?.month)
        assertEquals(CandidateKind.CONFLICT, graph.candidates.single().kind)
        graph = MemoryEngine.confirm(graph, graph.candidates.single().id, false).graph
        graph = learn(graph, "Actually, Aurora's birthday is in December.")
        assertEquals(12, current(graph, "birthday").single().value?.month)
        assertEquals(2, current(graph, "birthday").single().revision)
        assertEquals(1, graph.facts.count { it.status == FactStatus.SUPERSEDED })
        assertFalse(MemoryRetriever.retrieve(graph, "Aurora birthday", setOf("personal")).contextJson.contains("October"))
    }

    @Test fun rejectedAndRepeatedSourcesCannotSilentlyReappear() {
        val u = utterance("My daughter's name is Aurora.")
        var graph = MemoryEngine.ingest(MemoryGraph(), u, MemoryMode.ASK_FIRST).graph
        val candidate = graph.candidates.single()
        graph = MemoryEngine.confirm(graph, candidate.id, false).graph
        val retried = MemoryEngine.ingest(graph, u, MemoryMode.AUTOMATIC)
        assertTrue(retried.graph.facts.isEmpty())
        assertTrue(retried.handled)
        assertEquals(1, retried.graph.candidates.size)
    }

    @Test fun questionsHypotheticalsQuotedDocumentsNegationAndAdjectivesDoNotSave() {
        listOf("Pretend my name is Bob.", "If my daughter's name is Emma.", "My daughter's name is not Emma.",
            "\"My daughter's name is Emma.\"", "Translate to French:\nMy daughter's name is Emma.",
            "Summarize this email:\nMy daughter's name is Emma.", "My friend said my daughter's name is Emma.",
            "My daughter's name is Emma?", "My daughter is seven.", "My daughter's name is Aurora I think.", "My name is A.J.", "My friend is kind.", "My mother is at home.").forEach { text ->
            val result = MemoryEngine.ingest(MemoryGraph(), utterance(text), MemoryMode.AUTOMATIC)
            assertTrue("Saved from $text", result.graph.facts.isEmpty())
        }
    }

    @Test fun pronounsAlwaysNeedConfirmedIdentityEvenAfterRecentFact() {
        var graph = learn(MemoryGraph(), "My daughter's name is Emma.")
        graph = learn(graph, "Her birthday is in May.")
        assertTrue(current(graph, "birthday").isEmpty())
        assertEquals(CandidateKind.IDENTITY, graph.candidates.single().kind)
    }

    @Test fun temporaryWrongRoleOffAndSecretTurnsCannotSave() {
        val u = utterance("My daughter's name is Aurora.")
        assertTrue(MemoryEngine.ingest(MemoryGraph(), u.copy(temporary = true), MemoryMode.AUTOMATIC).graph.facts.isEmpty())
        assertTrue(MemoryEngine.ingest(MemoryGraph(), u.copy(role = "assistant"), MemoryMode.AUTOMATIC).graph.facts.isEmpty())
        assertTrue(MemoryEngine.ingest(MemoryGraph(), u, MemoryMode.OFF).graph.facts.isEmpty())
        assertTrue(MemoryEngine.ingest(MemoryGraph(), u.copy(explicit = true), MemoryMode.OFF).graph.facts.isNotEmpty())
        assertFalse(MemoryExtractor.shouldExtract(utterance("My API key is sk_abcdefghijklmnop.")))
    }

    @Test fun scopeIsEnforcedEvenWithHighSemanticAndFtsScores() {
        val private = MemoryEngine.ingest(MemoryGraph(), utterance("Aurora's birthday is in October.", scope = "work"), MemoryMode.AUTOMATIC).graph
        val id = current(private, "birthday").single().id
        val result = MemoryRetriever.retrieve(private, "Aurora birthday", setOf("personal"), mapOf(id to 1.0), keywordFactIds = setOf(id))
        assertTrue(result.factIds.isEmpty())
        assertFalse(result.contextJson.contains("Aurora"))
        assertFalse(MemoryExtractor.prompt(utterance("I have a family."), private).contains("Aurora"))
    }

    @Test fun forgettingBlocksLateSameSourceAndJoinedAnswer() {
        val nameSource = utterance("My daughter's name is Aurora.")
        var graph = MemoryEngine.ingest(MemoryGraph(), nameSource, MemoryMode.AUTOMATIC).graph
        graph = learn(graph, "Aurora's birthday is in October.")
        val relation = current(graph, "has_daughter").single()
        graph = MemoryGraphRules.forget(graph, relation.id)
        val late = MemoryEngine.ingest(graph, nameSource, MemoryMode.AUTOMATIC)
        assertTrue(current(late.graph, "has_daughter").isEmpty())
        val answer = MemoryRetriever.retrieve(late.graph, "When is my daughter's birthday?", setOf("personal"))
        assertNotNull(answer.clarification)
        assertFalse(answer.contextJson.contains("October"))
    }

    @Test fun sourceSpansAreExactWithRememberPrefixAndPunctuation() {
        val u = utterance("Please remember that my daughter's name is Aurora.", explicit = true)
        val graph = MemoryEngine.ingest(MemoryGraph(), u, MemoryMode.ASK_FIRST).graph
        assertTrue(graph.facts.isNotEmpty())
        graph.evidence.forEach { assertEquals(it.quote, u.text.substring(it.start, it.end)) }
    }

    @Test fun explicitAliasResolvesSameEntityWithoutSimilarityMerge() {
        var graph = learn(MemoryGraph(), "My daughter's name is Aurora.")
        graph = learn(graph, "Aurora goes by Rory.")
        graph = learn(graph, "Rory's birthday is in October.")
        assertEquals(current(graph, "has_daughter").single().objectEntityId, current(graph, "birthday").single().subjectId)
        graph = learn(graph, "Aurorah's birthday is in November.")
        assertEquals(2, current(graph, "birthday").map { it.subjectId }.distinct().size)
    }

    @Test fun sensitiveInterpretedClaimsArePendingAndModelQuoteMustExist() {
        val u = utterance("I have a medical diagnosis of asthma.")
        val response = """{"claims":[{"subjectId":"self","predicate":"note","value":{"type":"TEXT","text":"Has asthma"},"quote":"I have a medical diagnosis of asthma."}]}"""
        val proposals = MemoryExtractor.parse(response, u, MemoryGraph())
        assertEquals(1, proposals.size)
        val update = MemoryEngine.propose(MemoryGraph(), u, proposals, MemoryMode.AUTOMATIC)
        assertTrue(update.graph.facts.isEmpty())
        assertEquals(CandidateKind.SENSITIVE, update.graph.candidates.single().kind)
        assertTrue(MemoryExtractor.parse(response.replace("I have a medical diagnosis of asthma.", "The assistant thinks I have asthma."), u, MemoryGraph()).isEmpty())
    }

    @Test fun mixedTurnQueuesOnlyUnhandledPersonalClaimWithoutRepeatingSavedName() {
        val u = utterance("My name is Mike. I work as a carpenter.")
        val local = MemoryEngine.ingest(MemoryGraph(), u, MemoryMode.AUTOMATIC)
        assertEquals(1, current(local.graph, "name").size)
        assertTrue(MemoryExtractor.shouldExtract(u))
        val prompt = MemoryExtractor.prompt(u, local.graph)
        assertTrue(prompt.contains("I work as a carpenter."))
        assertFalse(prompt.contains("My name is Mike."))
        val response = """{"claims":[
            {"subjectId":"self","predicate":"name","value":{"type":"TEXT","text":"Mike"},"quote":"My name is Mike."},
            {"subjectId":"self","predicate":"occupation","value":{"type":"TEXT","text":"carpenter"},"quote":"I work as a carpenter."}
        ]}"""
        val proposals = MemoryExtractor.parse(response, u, local.graph)
        val result = MemoryEngine.propose(local.graph, u, proposals, MemoryMode.AUTOMATIC)
        assertEquals(1, current(result.graph, "name").size)
        assertTrue(current(result.graph, "occupation").isEmpty())
        val pending = result.graph.candidates.single()
        assertEquals("occupation", pending.proposedFacts.single().predicate)
        val proof = pending.evidence.single()
        assertEquals("I work as a carpenter.", proof.quote)
        assertEquals(proof.quote, u.text.substring(proof.start, proof.end))
        assertEquals(1, MemoryEngine.propose(result.graph, u, proposals, MemoryMode.AUTOMATIC).graph.candidates.size)
    }

    @Test fun extractionPrivacyFlagOmitsSavedEntityHintsButKeepsCurrentUserText() {
        val graph = learn(MemoryGraph(), "My daughter's name is Aurora.")
        val u = utterance("I work as a baker.")
        val person = graph.entities.single { it.displayName == "Aurora" }
        val normal = MemoryExtractor.prompt(u, graph)
        assertTrue(normal.contains(person.id))
        assertTrue(normal.contains(person.displayName))
        val private = MemoryExtractor.prompt(u, graph, includeKnownEntities = false)
        assertFalse(private.contains(person.id))
        assertFalse(private.contains(person.displayName))
        assertTrue(private.contains("<entities>[]</entities>"))
        assertTrue(private.contains(u.text))
    }

    @Test fun fullyCoveredTwoFactTurnNeedsNoAdditionalInference() {
        val u = utterance("My daughter's name is Aurora. Aurora's birthday is in October.")
        val result = MemoryEngine.ingest(MemoryGraph(), u, MemoryMode.AUTOMATIC)
        assertEquals(1, current(result.graph, "has_daughter").size)
        assertEquals(1, current(result.graph, "birthday").size)
        assertFalse(MemoryExtractor.shouldExtract(u))
        assertFalse(MemoryExtractor.shouldExtract(utterance("My name is Mike. What time is it?")))
    }

    @Test fun semanticAndFtsRetrieveCurrentFactsWithoutChangingIdentity() {
        var graph = learn(MemoryGraph(), "I prefer hiking.")
        graph = learn(graph, "I dislike traffic.")
        val fact = current(graph, "preference").single()
        val vector = MemoryRetriever.retrieve(graph, "outdoor pastime", setOf("personal"), mapOf(fact.id to .8))
        assertEquals(listOf(fact.id), vector.factIds)
        val keyword = MemoryRetriever.retrieve(graph, "outdoor pastime", setOf("personal"), keywordFactIds = setOf(fact.id))
        assertEquals(listOf(fact.id), keyword.factIds)
    }
}
