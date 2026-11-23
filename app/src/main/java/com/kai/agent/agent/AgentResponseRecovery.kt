package com.kai.agent.agent

import com.kai.agent.inference.Completion

/** A rejected proposal is never executable and is not evidence of a submitted operation. */
class AgentResponseException(detail: String) : IllegalArgumentException(
    "$detail This response did not execute an action. Check task history for earlier operations."
)

/** At most two extra inference attempts per run/turn; never retries service IO or an action. */
class AgentResponseRecovery {
    private var attempts = 0

    fun parse(completion: Completion): AgentDecision {
        require(completion.finishReason != "content_filter") { "The provider filtered this response. This response did not execute an action." }
        if (completion.finishReason == "length") throw AgentResponseException("The model reached its response limit before finishing.")
        return AgentProtocol.parse(completion.text)
    }

    fun correction(): String? {
        if (attempts >= 2) return null
        attempts++
        // Do not feed rejected fragments back as instructions, fake results, or action permission.
        // The caller retains the original request, exact schemas and operation receipts.
        return "Phone format check: the last model response was rejected before any action from that response ran. " +
            "Return exactly ONE complete JSON object for the next remaining step, with type first. " +
            "No commentary, fences, alternatives or second object. Keep it concise. " +
            "Use only the supplied account IDs, discovered tools and exact schema fields. " +
            "Ask a clarification with reply if required details are missing; never guess. " +
            "Earlier action receipts remain binding: never repeat RUNNING, UNKNOWN, ACCEPTED or SUCCEEDED operations. " +
            "This is a format correction, not new action authorization."
    }

    companion object {
        const val REJECTED = "{\"type\":\"reply\",\"text\":\"\"}"
    }
}
