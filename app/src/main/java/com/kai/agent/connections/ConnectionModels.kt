package com.kai.agent.connections

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Credentials belong in the app's encrypted session vault, never in logs or model prompts. */
class ManagedSession(val ownerId: String, val token: String, val displayName: String = "") {
    init {
        require(ownerId.isNotBlank() && ownerId.length <= 200)
        require(token.isNotBlank() && token.length <= 4096 && token.none { it == '\r' || it == '\n' })
    }
    override fun toString() = "ManagedSession(ownerId=$ownerId, token=[redacted])"
}

class DeviceLink(
    val userCode: String,
    internal val deviceSecret: String,
    val verifyUrl: String,
    val expiresAtMillis: Long,
    val pollIntervalMillis: Long,
    internal val requestEpoch: Long
) {
    override fun toString() = "DeviceLink(userCode=$userCode, deviceSecret=[redacted])"
}

sealed class SignInPoll {
    object Pending : SignInPoll()
    data class Approved(val session: ManagedSession) : SignInPoll()
}

data class ConnectionAccount(
    val id: String,
    val toolkit: String,
    val userId: String,
    val name: String,
    val status: String,
    val disabled: Boolean,
    val createdAt: String,
    /** Provider-supplied identity only; the original generated alias remains in [name]. */
    val providerDisplayName: String = "",
    val emailAddress: String = "",
    val identitySource: String = ""
) {
    val active: Boolean get() = status == "ACTIVE" && !disabled
    val displayLabel: String get() = emailAddress.ifBlank { providerDisplayName.ifBlank { "Connected account" } }
}

data class ConnectionToolkit(
    val slug: String,
    val name: String,
    val description: String,
    val logoUrl: String,
    val categories: List<String>,
    val noAuth: Boolean
)

data class ConnectionCategory(val id: String, val name: String)

data class ConnectionPage<T>(
    val items: List<T>,
    val nextCursor: String?,
    val total: Int,
    val categories: List<ConnectionCategory> = emptyList()
)

data class ConnectionTool(
    val id: String,
    val name: String,
    val description: String,
    val toolkit: String,
    val version: String,
    val readOnly: Boolean,
    /** JSON text avoids sharing a mutable JSONObject between UI and execution. */
    val schemaJson: String
)

data class ConnectionsSnapshot(
    val ownerId: String,
    val generation: String,
    val accounts: List<ConnectionAccount>
)

data class HostedConnectionLink(val url: String, val accountId: String?, val toolkit: String, val expiresAt: String?)

/** The provider reported success; this does not independently prove email delivery. */
data class ExecutionReceipt(val accountId: String, val toolId: String, val resultJson: String)

/** Bind a review to exact arguments, account, model-visible tool and project. */
class ActionApproval internal constructor(
    internal val ownerId: String,
    internal val generation: String,
    internal val epoch: Long,
    internal val accountId: String,
    internal val toolId: String,
    internal val version: String,
    internal val argumentDigest: String,
    internal val expiresAtMillis: Long
) {
    internal val consumed = AtomicBoolean(false)
    override fun toString() = "ActionApproval(accountId=$accountId, toolId=$toolId)"
}

class ConnectionServiceException(
    message: String,
    val statusCode: Int? = null,
    /** Never automatically retry a write when the remote outcome is unknown. */
    val outcomeMayBeUnknown: Boolean = false
) : IOException(message)

/** Replaceable for deterministic tests. Implementations must not log authorization or payloads. */
interface ConnectionsTransport {
    suspend fun request(path: String, token: String? = null, body: JSONObject? = null): JSONObject
}
