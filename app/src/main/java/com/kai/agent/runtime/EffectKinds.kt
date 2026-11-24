package com.kai.agent.runtime
import com.kai.agent.connections.ConnectionTool
/** Completion semantics are separate from read/write permission. Unknown adapters remain conservative. */
object EffectKinds {
    fun classify(tool: ConnectionTool): String = classify(tool.id,tool.toolkit)
    fun classify(id: String, toolkit: String = ""): String {
        val text=(toolkit+" "+id).uppercase()
        return when {
            "GOOGLEDOCS" in text -> "DOCUMENT"
            ("GMAIL" in text || "OUTLOOK" in text || "EMAIL" in text) && Regex("SEND|FORWARD|REPLY").containsMatchIn(text) && "DRAFT" !in text -> "EMAIL"
            "CALENDAR" in text -> "CALENDAR"
            Regex("SLACK|DISCORD|TELEGRAM").containsMatchIn(text) -> "MESSAGE"
            Regex("TWITTER|FACEBOOK|LINKEDIN|INSTAGRAM").containsMatchIn(text) -> "PUBLISH"
            Regex("DRIVE|SHEETS|DROPBOX").containsMatchIn(text) -> "FILE"
            else -> "EXTERNAL"
        }
    }
}
