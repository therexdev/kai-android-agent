package com.kai.agent.account

import com.kai.agent.inference.*
import org.json.JSONObject
import java.math.BigDecimal

const val CREDIT_ORIGIN = "https://credits.koinosai.com"
const val DEFAULT_CREDIT_MODEL = "akash-gpt-oss-120b"
/** Stored only in the encrypted device database. Never exported or placed in browser URLs. */
data class CreditAccount(
    val token: String = "", val id: String = "", val email: String = "",
    val wallet: String = "", val model: String = DEFAULT_CREDIT_MODEL, val contextTokens: Int = 4096,
    val maxOutput: Int = 2048, val freeOnly: Boolean = true,
    val linkCode: String = "", val linkSecret: String = "", val linkExpiresAt: Long = 0,
    val nativeTools: Boolean = false,
    val preferredOutput: Int = 4096, val fallbackModels: List<String> = emptyList(),
) {
    override fun toString() = "CreditAccount(credentials=<redacted>)"
    fun json(secrets: Boolean) = if (!secrets) JSONObject() else JSONObject()
        .put("token",token).put("id",id).put("email",email).put("wallet",wallet).put("model",model)
        .put("preferredOutput",preferredOutput).put("fallbackModels",org.json.JSONArray(fallbackModels)).put("nativeTools",nativeTools).put("contextTokens",contextTokens).put("maxOutput",maxOutput).put("freeOnly",freeOnly)
        .put("linkCode",linkCode).put("linkSecret",linkSecret).put("linkExpiresAt",linkExpiresAt)
    fun inference(output: Int = preferredOutput) = ProviderConfig(ProviderId.KOINOS, "$CREDIT_ORIGIN/platform/api", token, model,
        RoutingMode.NETWORK_ONLY, minOf(output,maxOutput), accountSession=true, creditWallet=wallet,
        freeOnly=freeOnly, contextTokens=contextTokens, modelOutputLimit=maxOutput, nativeTools=nativeTools, allowOutputGrowth=false)
    companion object {
        fun read(o: JSONObject?) = if(o==null) CreditAccount() else CreditAccount(
            o.optString("token"),o.optString("id"),o.optString("email"),o.optString("wallet"),o.optString("model").ifBlank { DEFAULT_CREDIT_MODEL },
            o.optInt("contextTokens",4096).coerceIn(1024,2_000_000),o.optInt("maxOutput",2048).coerceIn(1,131072),
            o.optBoolean("freeOnly",true),o.optString("linkCode"),o.optString("linkSecret"),o.optLong("linkExpiresAt"), o.optBoolean("nativeTools",false),o.optInt("preferredOutput",4096).coerceIn(1024,8192),
            o.optJSONArray("fallbackModels")?.let { a -> (0 until minOf(a.length(),8)).map { a.getString(it).take(300) }.distinct() }.orEmpty())
    }
}
data class CreditModel(val id: String,val label: String,val available: Boolean,val freeEligible: Boolean,
    val maxOutput: Int,val contextTokens: Int,val inputPrice: String,val outputPrice: String,val nativeTools: Boolean=false)
data class AccountOverview(val id: String,val email: String,val wallets: List<String>,val selectedWallet: String,
    val freeCredits: String,val paidCredits: String,val freeState: String,val models: List<CreditModel>,val vaultAvailable: Boolean=false,
    val primaryCreditWallet: String="",val walletLabels: Map<String,String> = emptyMap()) {
    fun walletLabel(address: String): String = walletLabels[address]?.takeIf { it.isNotBlank() } ?: address
    fun allowsPurchasedCredits(account: CreditAccount): Boolean = id==account.id && wallets.isNotEmpty() &&
        (account.wallet.isBlank() || account.wallet in wallets)
}
data class AccountUi(val signedIn: Boolean=false,val email: String="",val busy: Boolean=false,val code: String="",
    val message: String="Sign in to use your website credits and available models.",val overview: AccountOverview?=null,
    val model: String="",val wallet: String="",val freeOnly: Boolean=true)
fun creditAmount(atoms: String): String = runCatching { BigDecimal(atoms).divide(BigDecimal(100000)).stripTrailingZeros().toPlainString() }.getOrDefault("Unavailable")
