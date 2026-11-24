package com.kai.agent.agent

import com.kai.agent.inference.Completion
import com.kai.agent.inference.ProviderConfig

/** A rejected proposal is never executable and is not evidence of a submitted operation. */
class AgentResponseException(detail: String, val outputLimit: Boolean = false, val actionOutstanding: Boolean = false) : IllegalArgumentException(
    "$detail This response did not execute an action. Check task history for earlier operations."
)

/** Two corrections per rejected step, six per turn; never retries service IO or an action. */
class AgentResponseRecovery {
    private var attempts = 0
    private var consecutiveAttempts = 0
    private var requestedOutput = 2048
    private var outputFloor = 0

    fun requestConfig(config: ProviderConfig): ProviderConfig {
        val ceiling=minOf(config.modelOutputLimit ?: 8192,if(config.allowOutputGrowth) 8192 else config.maxOutputTokens)
        requestedOutput = maxOf(config.maxOutputTokens, outputFloor).coerceAtMost(ceiling)
        return config.copy(maxOutputTokens = requestedOutput)
    }

    fun parse(completion: Completion, validate: (AgentDecision)->Unit = {}): AgentDecision {
        require(completion.finishReason != "content_filter") { "The provider filtered this response. This response did not execute an action." }
        if (completion.finishReason == "length") throw AgentResponseException("The model reached its response limit before finishing.", outputLimit = true)
        return AgentProtocol.parse(completion.text).also { validate(it);consecutiveAttempts = 0 }
    }

    fun parseNative(completion: Completion, parse: () -> AgentDecision): AgentDecision {
        if(completion.finishReason == "length") throw AgentResponseException("The model reached its response limit before finishing.",true)
        if(completion.finishReason == "content_filter") throw AgentResponseException("The provider filtered this response.")
        return parse().also { consecutiveAttempts = 0 }
    }

    fun correction(error: AgentResponseException? = null, native: Boolean = false): String? {
        if (consecutiveAttempts >= 2 || attempts >= 6) return null
        attempts++
        consecutiveAttempts++
        if (error?.outputLimit == true) outputFloor = (requestedOutput * 2).coerceAtMost(8192)
        // Do not feed rejected fragments back as instructions, fake results, or action permission.
        // The caller retains the original request, exact schemas and operation receipts.
        return "Phone format check: the last model response was rejected before any action from that response ran. " +
            (if(native) "Use one registered native function with complete arguments for the next step, or a final reply for completed work. For an actual missing input or blocker, use the explicit outcome form. "
             else "Return exactly ONE complete JSON object for the next remaining step, with type first. No commentary, fences, alternatives or second object. End with its matching brace. ") +
            (if(error?.actionOutstanding==true) "The requested action is still unprepared; a readiness or confirmation message cannot finish it. ${AgentProtocol.ACTION_REVIEW} ${AgentProtocol.INCOMPLETE_OUTCOME} " else "") +
            "Preserve the remaining task step and existing receipts instead of restarting broad research after a format error. " +
            (if (error?.outputLimit == true) "The response ran out of output tokens. Finish one smaller content chunk, within 16,000 UTF-8 bytes of arguments; use later append actions for the remainder. Reuse any existing document ID from receipts. Do not recreate a submitted document. " else "") +
            "Use only the supplied account IDs, discovered tools and exact schema fields. " +
            "Use needs_input for a specific missing required detail; never guess or request preliminary approval. " +
            "Earlier action receipts remain binding: never repeat RUNNING, UNKNOWN, ACCEPTED or SUCCEEDED operations. " +
            "This is a format correction, not new action authorization."
    }

    companion object {
        const val REJECTED = "{\"type\":\"reply\",\"text\":\"\"}"
    }
}
