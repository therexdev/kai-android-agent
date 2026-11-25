package com.kai.agent.agent

import org.json.JSONObject

/** Typed creation adapters; a file container is a document only for the Docs MIME type. */
object DocumentResources {
    val bodyCreates=setOf("GOOGLEDOCS_CREATE_DOCUMENT","GOOGLEDOCS_CREATE_DOCUMENT_MARKDOWN")
    val fileCreates=setOf("GOOGLEDRIVE_CREATE_FILE","GOOGLEDOCS_CREATE_DRIVE_FILE")
    val creates=bodyCreates+fileCreates
    fun createsDocument(tool: String,args: JSONObject)=tool in bodyCreates ||
        tool in fileCreates && args.optString("mimeType")=="application/vnd.google-apps.document"
}
