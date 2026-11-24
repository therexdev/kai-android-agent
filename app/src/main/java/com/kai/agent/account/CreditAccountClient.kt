package com.kai.agent.account

import com.kai.agent.inference.NativeQualification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AccountFailure(val status: Int, message: String): Exception(message)
class CreditAccountClient(private val origin: String=CREDIT_ORIGIN, private val http: OkHttpClient=OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false).callTimeout(30,TimeUnit.SECONDS).build()) {
    private suspend fun request(path: String, token: String="", body: JSONObject?=null): JSONObject = withContext(Dispatchers.IO) {
        val b=Request.Builder().url(origin+path).header("Accept","application/json")
        if(token.isNotEmpty()) b.header("Authorization","Bearer $token")
        if(body!=null) b.post(body.toString().toRequestBody("application/json".toMediaType()))
        http.newCall(b.build()).execute().use { r ->
            if(!r.isSuccessful) throw AccountFailure(r.code, when(r.code) {
                401 -> "Your sign-in expired. Sign in again."
                403 -> "This account or wallet is no longer authorized. Refresh your account."
                404 -> "This sign-in request expired or the account service needs an update."
                429 -> "Too many requests. Wait a moment and refresh."
                else -> "The account service could not complete this request (${r.code}). Try again."
            })
            val source=r.body?.source() ?: error("Empty account response")
            if(source.request(1_000_001)) throw AccountFailure(502,"The account response was too large.")
            JSONObject(source.readUtf8())
        }
    }
    suspend fun start(): CreditAccount {
        val o=request("/auth/device/start",body=JSONObject())
        val code=o.getString("userCode");require(code.matches(Regex("[A-Z2-9]{4}-[A-Z2-9]{4}")))
        val secret=o.getString("deviceSecret");require(secret.length in 20..512)
        return CreditAccount(linkCode=code,linkSecret=secret,linkExpiresAt=System.currentTimeMillis()+o.getLong("expiresInSec").coerceIn(1,1800)*1000)
    }
    fun loginUrl(a: CreditAccount): String = "$origin/link?code=${a.linkCode}"
    suspend fun poll(a: CreditAccount): CreditAccount? {
        val o=request("/auth/device/poll",body=JSONObject().put("userCode",a.linkCode).put("deviceSecret",a.linkSecret))
        if(o.optBoolean("pending"))return null
        val token=o.getString("token");require(token.length in 20..8192 && token.all { it.code in 33..126 })
        val account=o.getJSONObject("account")
        return CreditAccount(token=token,id=account.getString("id"),email=account.optString("email"))
    }
    suspend fun logout(token: String) { request("/auth/logout",token,JSONObject()) }
    suspend fun overview(a: CreditAccount): AccountOverview {
        val initial=loadOverview(a,a.wallet)
        if(a.wallet.isNotBlank())return initial
        // Automatic chat funding stays automatic. Show the primary connected
        // account's balance rather than the unrelated walletless zero balance.
        val displayWallet=initial.primaryCreditWallet.ifBlank { initial.wallets.firstOrNull().orEmpty() }
        return if(displayWallet.isBlank() || initial.selectedWallet==displayWallet) initial else loadOverview(a,displayWallet)
    }
    private suspend fun loadOverview(a: CreditAccount, wallet: String): AccountOverview {
        val url=(origin+"/platform/api/overview").toHttpUrl().newBuilder().apply {
            if(wallet.isNotBlank())addQueryParameter("wallet",wallet)
        }.build()
        val o=request(url.toString().removePrefix(origin),a.token)
        val account=o.getJSONObject("account");require(account.getString("id")==a.id) { "Account identity changed. Sign in again." }
        val wallets=account.getJSONArray("wallets");val models=o.getJSONArray("models");val credits=o.getJSONObject("credits")
        val addresses=(0 until wallets.length()).map { wallets.getJSONObject(it).getString("address") }.distinct()
        val labels=(0 until wallets.length()).associate { i -> val w=wallets.getJSONObject(i)
            w.getString("address") to w.optString("label").takeUnless { it=="null" }.orEmpty() }
        val primary=account.optString("primaryCreditWallet").takeIf { it in addresses }.orEmpty()
        return AccountOverview(a.id,account.optString("email"),addresses,
            o.optString("selectedWallet").takeUnless { it=="null" }.orEmpty(),credits.getJSONObject("free").getString("available"),
            credits.getJSONObject("purchased").getString("available"),credits.getJSONObject("free").optString("state"),
            (0 until models.length()).map { i -> val m=models.getJSONObject(i);val q=NativeQualification.read(m);CreditModel(m.getString("model"),m.optString("label",m.getString("model")),
                m.optBoolean("available"),m.optBoolean("freeEligible"),m.getInt("maxOutput").coerceIn(1,131072),
                m.optInt("contextTokens",4096).coerceIn(1024,2_000_000),m.optString("inputCreditsPerMillion"),m.optString("outputCreditsPerMillion"),q!=null,q?.optString("qualificationId").orEmpty(),q?.optLong("expiresAt") ?: 0) },
            o.optJSONObject("features")?.optInt("koinVault")==1,primary,labels)
    }
}
