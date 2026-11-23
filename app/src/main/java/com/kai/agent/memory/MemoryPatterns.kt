package com.kai.agent.memory

import com.kai.agent.data.MemoryValue
import java.time.MonthDay
import java.util.Locale

internal data class ParsedMemory(val subject: String, val predicate: String, val value: MemoryValue? = null,
    val name: String? = null, val relationship: String? = null, val another: Boolean = false,
    val correction: Boolean = false, val quote: String, val start: Int, val review: Boolean = false)

/** A deliberately small grammar: unsupported wording is reviewed through the model-proposal path. */
internal object MemoryPatterns {
    private val correction = Regex("^(?:actually[, ]+|correction[: ,]+|to correct that[, ]+)", RegexOption.IGNORE_CASE)
    private val update = Regex("^(?:(?:can|could|would) you |please )?(?:update|correct|change) (?:that|this|the|my) (?:card|memory|saved memory)(?: because|:|,)\\s*", RegexOption.IGNORE_CASE)
    private val conversationalLead = Regex("^(?:more things[, :]*|also[, :]*|another thing[, :]*|one more thing[, :]*)\\s+", RegexOption.IGNORE_CASE)
    private val remember = Regex("^(?:please\\s+)?remember(?:\\s+that|\\s+this\\s*:)?\\s*[:,-]?\\s+", RegexOption.IGNORE_CASE)
    private val unsafe = Regex("\\b(?:pretend|imagine|hypothetical|hypothetically|fiction|fictional|story|quoted|quote|example|translate|translation|summari[sz]e|summary|rewrite|proofread|document|email|forwarded|transcript|passage|wrote|writes|copied|pasted|article|novel|screenplay|roleplay|scenario|hypothesis|assume|extract|analy[sz]e|suppose|supposed|maybe|perhaps|think|believe|guess|probably|likely|possibly|uncertain|unsure|might|would|could|if|unless|joking|joke|said|says|told|according)\\b", RegexOption.IGNORE_CASE)
    private val negative = Regex("\\b(?:not|never|isn't|wasn't|don't|doesn't|aren't|can't|cannot|no)\\b", RegexOption.IGNORE_CASE)
    private val secret = Regex("\\b(?:password|passphrase|api[ _-]?key|secret[ _-]?key|access[ _-]?token|refresh[ _-]?token|private[ _-]?key|seed phrase|recovery phrase)\\b|\\b(?:sk|ak|ghp)[_-][A-Za-z0-9_-]{12,}", RegexOption.IGNORE_CASE)
    private val sensitive = Regex("\\b(?:diagnos(?:ed|is)|medication|medical|allerg(?:y|ic)|disease|depression|anxiety|cancer|hiv|social security|ssn|credit card|bank account|salary|income|street address|home address|address is|religion|sexual)\\b|\\b\\d{3}[- .]\\d{2}[- .]\\d{4}\\b", RegexOption.IGNORE_CASE)
    private val roles = "daughter|son|child|wife|husband|partner|mother|father|sister|brother|friend|colleague"
    private val roleName = Regex("^my (?:(other|another) )?($roles)(?:'s name is| is (?:named|called)) (.+)$", RegexOption.IGNORE_CASE)
    private val haveName = Regex("^i have (a|an|another) ($roles) (?:named|called) (.+)$", RegexOption.IGNORE_CASE)
    private val apposition = Regex("^my (?:(other|another) )?($roles) ([\\p{L}][\\p{L}\\p{M}'’ -]{0,70})$", RegexOption.IGNORE_CASE)
    private val birthday = Regex("^(.+?)(?:'s) birthday (?:is|falls)(?: (?:in|on))? (.+)$", RegexOption.IGNORE_CASE)
    private val possessiveBirthday = Regex("^(my|her|his|their) birthday (?:is|falls)(?: (?:in|on))? (.+)$", RegexOption.IGNORE_CASE)
    private val birthdayOther = Regex("^(.+?) has (?:a |their |her |his )?birthday (?:in|on) (.+)$", RegexOption.IGNORE_CASE)
    private val roleBirthday = Regex("^my ($roles)(?:'s|s)? birthday (?:is )?(?:in |on )?(.+)$", RegexOption.IGNORE_CASE)
    private val alias = Regex("^(.+?)(?:'s nickname is| goes by| is now called) (.+)$", RegexOption.IGNORE_CASE)
    private val name = Regex("^[\\p{L}][\\p{L}\\p{M}'’-]*(?: [\\p{L}][\\p{L}\\p{M}'’-]*){0,4}$")
    private val monthNames = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
    fun hasSecret(text: String): Boolean = secret.containsMatchIn(text)
    fun isSensitive(text: String): Boolean = sensitive.containsMatchIn(text)
    fun eligible(u: MemoryUtterance): Boolean = u.role == "user" && !u.temporary && u.messageId.isNotBlank() &&
        u.conversationId.isNotBlank() && u.scope.isNotBlank() && u.text.length in 1..20_000 && !hasSecret(u.text) &&
        !u.text.any { it in "\"“”`‘" } && !u.text.trimStart().startsWith("'") &&
        !Regex("^(?:pretend|imagine|suppose|assume|roleplay|in (?:a|this) (?:story|example)|here is (?:a|an) (?:story|example))\\b", RegexOption.IGNORE_CASE).containsMatchIn(u.text.trim()) &&
        !Regex("^(?:translate|summari[sz]e|rewrite|proofread|extract|analy[sz]e)\\b|:\\s*\\n", RegexOption.IGNORE_CASE).containsMatchIn(u.text.trim())
    /** Reject unsafe clauses, not unrelated facts elsewhere in a turn. Pretend-play preferences are real. */
    fun safePassage(text: String): Boolean {
        val play = text.replace(Regex("\\b(?:likes?|loves?|enjoys?) (?:to )?pretend(?:ing)?\\b", RegexOption.IGNORE_CASE), "enjoys play")
        return !unsafe.containsMatchIn(statement(play)) && !text.any { it in "\"“”`‘" } &&
            !text.trimStart().startsWith("'") && !hasSecret(text)
    }
    fun passages(u: MemoryUtterance): List<MatchResult> = if (!eligible(u)) emptyList() else
        Regex("(?:[^.!?;\\n]|[.](?=\\S))+[.!?]?").findAll(u.text).take(12).filter { safePassage(it.value) }.toList()
    fun isName(value: String): Boolean = value.length in 2..80 && normalized(value) !in setOf("dr", "mr", "mrs", "ms", "prof") && name.matches(value.trim()) &&
        !Regex("\\b(?:and|or|not|named|called|birthday|is|was|has|are|the|my|your|her|his|their|this|that)\\b", RegexOption.IGNORE_CASE).containsMatchIn(value)
    fun relation(subject: String): String? = Regex("^my ($roles)$", RegexOption.IGNORE_CASE)
        .matchEntire(subject.trim())?.groupValues?.get(1)?.lowercase(Locale.ROOT)
    fun relationPredicate(role: String) = "has_" + role.lowercase(Locale.ROOT)
    fun body(u: MemoryUtterance): String = remember.replaceFirst(u.text.trim(), "")
    private fun statement(text: String): String = conversationalLead.replaceFirst(
        update.replaceFirst(MemoryRequests.save(text)?.body ?: text.trim(), ""), "").trim()

    /** A spelling run followed by a different word is an ASR ambiguity, not a new surname. */
    fun ambiguousName(text: String): Boolean {
        val value = Regex("\\b(?:last name|surname|first name) is (.+)", RegexOption.IGNORE_CASE)
            .find(statement(text))?.groupValues?.get(1)?.trim()?.trimEnd('.', '?', '!') ?: return false
        val words = value.split(Regex("\\s+"))
        return words.count { it.length == 1 && it[0].isLetter() } >= 2 && words.any { it.length > 1 }
    }

    private fun spelledName(value: String): String {
        val words = value.split(Regex("\\s+"))
        return if (words.size in 2..40 && words.all { it.length == 1 && it[0].isLetter() })
            words.joinToString("").lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) } else value
    }

    fun parse(u: MemoryUtterance): List<ParsedMemory> {
        if (!eligible(u) || MemoryConversationContext.speaker(u) == "unknown") return emptyList()
        val results = mutableListOf<ParsedMemory>()
        // Preserve offsets into original UTF-16 text; never use a model-supplied substring offset.
        passages(u).forEach { part ->
            val original = part.value.trim()
            MemoryConversationContext.introduced(original)?.let { (role, person) ->
                results += ParsedMemory("self", relationPredicate(role), name = person, relationship = role,
                    quote = original, start = u.text.indexOf(original, part.range.first))
                return@forEach
            }
            if (original.endsWith('?') && MemoryRequests.save(original) == null && !update.containsMatchIn(original)) return@forEach
            val quote = original.trimEnd('.', '!', '?').trim()
            if (quote.isBlank()) return@forEach
            val start = u.text.indexOf(quote, part.range.first)
            var clean = statement(quote).replace('’', '\'').trim()
            val correcting = correction.containsMatchIn(clean) || update.containsMatchIn(quote)
            clean = correction.replaceFirst(clean, "").trim()
            if (negative.containsMatchIn(clean)) return@forEach
            fun add(subject: String, predicate: String, value: MemoryValue? = null, nameValue: String? = null,
                    role: String? = null, another: Boolean = false, review: Boolean = false) {
                results += ParsedMemory(subject.trim(), predicate, value, nameValue?.trim(), role, another, correcting, quote, start, review)
            }
            Regex("^my (last name|surname|first name) is (.+)$", RegexOption.IGNORE_CASE).matchEntire(clean)?.let { m ->
                val value = spelledName(m.groupValues[2])
                if (!ambiguousName(clean) && isName(value)) add("self",
                    if (m.groupValues[1].equals("first name", true)) "first_name" else "last_name", MemoryValue(text = value), review = true)
                return@forEach
            }
            // One explicit relationship and its dependent birth date; do not resolve a free pronoun
            // across messages or choose between existing people by name similarity.
            Regex("^my ($roles) is (.+?)(?: and (?:she|he|they) (?:was|were) born(?: on)? (.+))?$", RegexOption.IGNORE_CASE)
                .matchEntire(clean)?.let { m ->
                    val person = m.groupValues[2]
                    val born = m.groupValues[3].takeIf { it.isNotBlank() }
                    if (isName(person) && person.first().isUpperCase() && (born == null || date(born) != null)) {
                        add("self", relationPredicate(m.groupValues[1]), nameValue = person,
                            role = m.groupValues[1].lowercase(Locale.ROOT), review = true)
                        if (born != null) add(person, "birthday", date(born), review = true)
                    }
                    return@forEach
                }
            roleBirthday.matchEntire(clean)?.let { m ->
                date(m.groupValues[2])?.let { add("my ${m.groupValues[1]}", "birthday", it) }
                return@forEach
            }
            (birthday.matchEntire(clean) ?: possessiveBirthday.matchEntire(clean) ?: birthdayOther.matchEntire(clean))?.let { m ->
                val target = m.groupValues[1].trim()
                date(m.groupValues[2])?.let { date ->
                    val embeddedRole = apposition.matchEntire(target)
                    if (embeddedRole != null && isName(embeddedRole.groupValues[3])) {
                        add("self", relationPredicate(embeddedRole.groupValues[2]), nameValue = embeddedRole.groupValues[3],
                            role = embeddedRole.groupValues[2].lowercase(Locale.ROOT), another = embeddedRole.groupValues[1].isNotEmpty())
                        add(embeddedRole.groupValues[3], "birthday", date)
                    } else if (isName(target) || relation(target) != null || target.lowercase(Locale.ROOT) in setOf("her", "his", "their", "my")) {
                        add(if (target.equals("my", true)) "self" else target, "birthday", date)
                    }
                }
                return@forEach
            }
            roleName.matchEntire(clean)?.let { m ->
                if (isName(m.groupValues[3])) add("self", relationPredicate(m.groupValues[2]), nameValue = m.groupValues[3],
                    role = m.groupValues[2].lowercase(Locale.ROOT), another = m.groupValues[1].isNotEmpty())
                return@forEach
            }
            haveName.matchEntire(clean)?.let { m ->
                if (isName(m.groupValues[3])) add("self", relationPredicate(m.groupValues[2]), nameValue = m.groupValues[3],
                    role = m.groupValues[2].lowercase(Locale.ROOT), another = m.groupValues[1].equals("another", true))
                return@forEach
            }
            Regex("^my name is (.+)$", RegexOption.IGNORE_CASE).matchEntire(clean)?.let { m ->
                if (isName(m.groupValues[1])) add("self", "name", MemoryValue(text = m.groupValues[1]))
                return@forEach
            }
            alias.matchEntire(clean)?.let { m ->
                if (isName(m.groupValues[1]) && isName(m.groupValues[2])) add(m.groupValues[1], "alias", MemoryValue(text = m.groupValues[2]))
                return@forEach
            }
            Regex("^i (like|love|prefer|dislike) (.{1,160})$", RegexOption.IGNORE_CASE).matchEntire(clean)?.let { m ->
                // A short deictic answer needs conversational extraction, not a permanent "likes this" fact.
                if (Regex("^(?:this|that|it|these|those|here|there)\\b", RegexOption.IGNORE_CASE).containsMatchIn(m.groupValues[2])) return@let
                val predicate = if (m.groupValues[1].equals("dislike", true)) "dislikes" else "preference"
                add("self", predicate, MemoryValue(text = m.groupValues[2]))
            }
        }
        val guest = MemoryConversationContext.speaker(u) != "self"
        return results.filterNot { guest && it.relationship != null }.take(8)
    }

    private fun date(raw: String): MemoryValue? {
        val match = Regex("^(${monthNames.joinToString("|")})(?: (\\d{1,2})(?:st|nd|rd|th)?(?:,? (\\d{4}))?)?$", RegexOption.IGNORE_CASE)
            .matchEntire(raw.trim()) ?: return null
        val month = monthNames.indexOf(match.groupValues[1].lowercase(Locale.ROOT)) + 1
        val day = match.groupValues[2].takeIf(String::isNotEmpty)?.toIntOrNull()
        val year = match.groupValues[3].takeIf(String::isNotEmpty)?.toIntOrNull()
        if (day != null && runCatching { MonthDay.of(month, day) }.isFailure) return null
        if (year != null && (year !in 1..9999 || runCatching { java.time.LocalDate.of(year, month, day!!) }.isFailure)) return null
        return MemoryValue(type = "DATE", month = month, day = day, year = year,
            precision = if (year != null) "date" else if (day != null) "day" else "month")
    }
}
