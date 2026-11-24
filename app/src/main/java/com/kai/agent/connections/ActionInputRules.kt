package com.kai.agent.connections

import org.json.JSONArray
import org.json.JSONObject

/** Known cross-field service requirements that are absent from the published JSON schema.
 * Reject only: never invent, fill, or alter the arguments the user will approve.
 */
internal object ActionInputRules {
    fun validate(tool: ConnectionTool, args: JSONObject) {
        if (tool.toolkit != "gmail") return
        fun text(key: String) = (args.opt(key) as? String)?.trim().orEmpty()
        if (tool.id == "GMAIL_SEND_EMAIL") {
            if (text("subject").isEmpty() && text("body").isEmpty())
                throw ConnectionServiceException("Gmail requires a non-empty subject or body. Prepare the actual message, including any requested document link, in a new proposal before review.")
            fun recipients(key: String): Boolean = when(val value=args.opt(key)) {
                is String -> value.isNotBlank()
                is JSONArray -> (0 until value.length()).any { (value.opt(it) as? String)?.isNotBlank() == true }
                else -> false
            }
            // Older catalogs use recipient; current catalogs use recipient_email or to.
            if (listOf("recipient_email","recipient","to","cc","bcc").none(::recipients))
                throw ConnectionServiceException("Gmail requires a recipient in the discovered To, Cc or Bcc fields. Use the user's requested recipient; do not guess one.")
        }
        if (tool.id == "GMAIL_GET_DRAFT" &&
            text("draft_id").lowercase() in setOf("", "unused", "placeholder", "draft_id", "null", "undefined"))
            throw ConnectionServiceException("Use a real draft_id returned by a draft creation or list action. A placeholder is not a draft. To send a new message, discover the send-email action instead.")
    }
}
