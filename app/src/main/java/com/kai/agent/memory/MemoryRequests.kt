package com.kai.agent.memory

/** Phone-side commands. Match whole requests, never a quoted mention or a negated instruction. */
object MemoryRequests {
    data class Save(val body: String?, val referencesPrevious: Boolean = body == null)
    private val lead = "(?:(?:can|could|would|will) you(?: please)? |(?:i want|i need|i'd like|i would like) you to |please )?"
    private val remember = Regex("^${lead}remember(?:\\s+|:\\s*)([\\s\\S]+)$", RegexOption.IGNORE_CASE)
    private val save = Regex("^${lead}(?:save|store|add)\\s+([\\s\\S]+?)\\s+(?:to|in|into)\\s+(?:(?:your|my|the|the app's)\\s+)?(?:long[- ]term\\s+)?memor(?:y|ies)[.!?]*$", RegexOption.IGNORE_CASE)
    private val referent = Regex("^(?:(?:all|both) (?:of )?)?(?:this|that|these|those|it|them)(?: (?:information|info|details|facts|about me))?(?: (?:for me|please))?$", RegexOption.IGNORE_CASE)
    private val suffix = Regex("^([\\s\\S]+?)[.,;]\\s*(?:please )?remember (?:that|this|those details|these details)[.!?]*$", RegexOption.IGNORE_CASE)

    fun save(text: String): Save? {
        val input = text.trim()
        val match = remember.matchEntire(input) ?: save.matchEntire(input) ?: suffix.matchEntire(input) ?: return null
        val body = match.groupValues[1].trim().removeSuffix("?").trim()
        val reference = body.trimEnd('.', '!').trim()
        if (referent.matches(reference)) return Save(null)
        val value = body.replaceFirst(Regex("^(?:that\\s+|this\\s*:\\s*)", RegexOption.IGNORE_CASE), "").trim()
        if (value.isBlank()) return Save(null)
        require(value.length <= 2000) { "Keep a saved memory under 2,000 characters. Split a longer note into separate facts." }
        return Save(value)
    }

    fun recallsAll(text: String): Boolean = Regex(
        "^(?:(?:please )?show (?:me )?(?:all )?(?:my|your|the) (?:saved )?memor(?:y|ies)|what (?:do you remember(?: about me)?|have you (?:saved|remembered)(?: about me)?|(?:memories|information) (?:do you have|have you saved)(?: about me)?))[?.!]*$",
        RegexOption.IGNORE_CASE).matches(text.trim())
}
