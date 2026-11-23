package com.kai.agent.memory

import com.kai.agent.data.*

data class MemoryContextTurn(val messageId: String, val role: String, val text: String, val createdAt: Long)
data class MemoryContextQuote(val messageId: String, val quote: String, val start: Int, val createdAt: Long)

/** A bounded, same-conversation view. Assistant text can explain a question, never prove a fact. */
object MemoryConversationContext {
    fun utterance(conversation: Conversation, source: ChatMessage, graph: MemoryGraph, explicit: Boolean = false): MemoryUtterance {
        val prior = conversation.messages.takeWhile { it.id != source.id }
            .filter { it.createdAt >= conversation.contextAfter && it.role in setOf("user", "assistant") }

        // A forgotten turn breaks continuity; do not join the two sides into new evidence.
        val boundary = prior.indexOfLast { MemoryGraphRules.sourceBlocked(graph, conversation.id, it.id) }
        val available = prior.drop(boundary + 1)
        val anchor = available.lastOrNull { it.role == "user" && validSpeakerChange(it.text) }
        var remaining = 6000 - (anchor?.text?.length ?: 0)
        val recent = available.takeLast(if (anchor == null) 12 else 11).filter { it.id != anchor?.id }.asReversed().takeWhile { turn ->
            remaining -= turn.text.length
            remaining >= 0 && !MemoryPatterns.hasSecret(turn.text)
        }.asReversed()
        val context = (listOfNotNull(anchor?.takeIf { it.text.length <= 2000 && !MemoryPatterns.hasSecret(it.text) }) + recent)
            .distinctBy { it.id }.sortedBy { it.createdAt }.map { MemoryContextTurn(it.id, it.role, it.text, it.createdAt) }
        return MemoryUtterance(source.id, conversation.id, source.text, source.createdAt,
            scope = WorkspaceRules.scope(conversation.projectId), temporary = conversation.temporary,
            explicit = explicit, role = source.role, context = if (conversation.temporary) emptyList() else context,
            speakerUnknown = anchor == null && boundary >= 0 && prior.take(boundary + 1).any { it.role == "user" && validSpeakerChange(it.text) })
    }

    private val introduction = Regex("\\b(?:say (?:hi|hello) to|meet|this is) my (daughter|son|child|wife|husband|partner|friend) (?-i:([\\p{Lu}][\\p{L}'’-]*(?: [\\p{Lu}][\\p{L}'’-]*){0,3}))(?=[.!?,]|$)", RegexOption.IGNORE_CASE)
    fun introduced(text: String): Pair<String, String>? = introduction.find(text.trim())?.let {
        it.groupValues[1].lowercase() to it.groupValues[2].trim()
    }?.takeIf { MemoryPatterns.isName(it.second) }

    private fun validSpeakerChange(text: String) = MemoryPatterns.safePassage(text) &&
        MemoryPatterns.eligible(MemoryUtterance("speaker", "context", text)) &&
        (introduced(text) != null || Regex("^this is (?:her|his|their) (?:dad|mom|father|mother)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text.trim()))

    /** A hand-off is evidence for a speaker change, not permission to merge their identity with You. */
    fun speaker(u: MemoryUtterance): String {
        var speaker = if (u.speakerUnknown) "unknown" else "self"
        for (turn in u.context.filter { it.role == "user" } + MemoryContextTurn(u.messageId, "user", u.text, u.createdAt)) {
            if (!MemoryPatterns.eligible(u.copy(text = turn.text, context = emptyList())) || !MemoryPatterns.safePassage(turn.text)) continue
            val returnName = Regex("^this is (?:her|his|their) (?:dad|mom|father|mother) ([\\p{L}][\\p{L}'’-]*)\\b", RegexOption.IGNORE_CASE).find(turn.text.trim())
            if (returnName != null) speaker = returnName.groupValues[1]
            // The introduced person speaks on following turns, not on their parent's introduction.
            if (turn.messageId != u.messageId) introduced(turn.text)?.let { speaker = it.second }
        }
        return speaker
    }

    fun hasPersonalQuestion(u: MemoryUtterance): Boolean = u.context.takeLast(4).any {
        it.role == "assistant" && '?' in it.text && Regex("\\b(?:like|love|enjoy|prefer|favorite|favourite|for fun)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it.text)
    }

    fun speakerEvidence(u: MemoryUtterance): List<MemoryContextQuote> = u.context.filter { it.role == "user" }
        .filter { validSpeakerChange(it.text) }
        .map { MemoryContextQuote(it.messageId, it.text, 0, it.createdAt) }
}
