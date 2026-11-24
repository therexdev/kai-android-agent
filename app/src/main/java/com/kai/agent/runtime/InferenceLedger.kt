package com.kai.agent.runtime

import com.kai.agent.inference.*
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

enum class InferencePurpose { PLAN, AGENT, SUMMARY, FINALIZATION }
enum class AttemptState { RESERVED, REPORTED, UNREPORTED, INTERRUPTED }
class RunBudgetExceeded(message: String) : Exception(message)
class InferenceAttemptContext(val id: String, val purpose: InferencePurpose) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<InferenceAttemptContext>
}

/** Catalog estimates, never a billing receipt or permission to use purchased credits. */
data class CreditPricing(val model: String, val input: String, val output: String, val observedAt: Long) {
    fun estimate(inputTokens: Long, outputTokens: Long): Long =
        (BigDecimal(input).multiply(BigDecimal(inputTokens)) + BigDecimal(output).multiply(BigDecimal(outputTokens)))
            .setScale(0, RoundingMode.CEILING).longValueExact() // credits/million -> microcredits/token
    fun valid(modelId: String, now: Long) = model == modelId && observedAt > 0 && now >= observedAt && now-observedAt <= 86_400_000 &&
        listOf(input,output).all { it.matches(Regex("[0-9]{1,7}(?:\\.[0-9]{1,8})?")) && BigDecimal(it) <= BigDecimal(1_000_000) }
    fun json() = JSONObject().put("model",model).put("input",input).put("output",output).put("at",observedAt)
    companion object {
        fun read(o: JSONObject?) = o?.let { CreditPricing(it.getString("model"),it.getString("input"),it.getString("output"),it.getLong("at")) }
        fun from(m: com.kai.agent.account.CreditModel, at: Long) = CreditPricing(m.id,m.inputPrice,m.outputPrice,at)
        fun display(micro: Long) = BigDecimal(micro).movePointLeft(6).stripTrailingZeros().toPlainString()
    }
}

/** No prompts, credentials, provider replay or account identifiers are stored in this ledger. */
data class InferenceAttempt(val id: String, val purpose: InferencePurpose, val route: String, val at: Long,
    val input: Long, val output: Long, val millis: Long, val estimatedMicroCredits: Long?,
    val pricing: CreditPricing?, val state: AttemptState = AttemptState.RESERVED)

data class InferenceLedger(
    val attempts: Int = 0, val summaries: Int = 0, val unreported: Int = 0, val unpriced: Int = 0,
    val input: Long = 0, val output: Long = 0, val millis: Long = 0, val estimatedMicroCredits: Long = 0,
    val maxAttempts: Int = 80, val maxInput: Long = 2_000_000, val maxMillis: Long = 900_000,
    val maxEstimatedMicroCredits: Long = 0, val history: List<InferenceAttempt> = emptyList(), val legacy: Boolean = false,
) {
    val exhausted get() = attempts >= maxAttempts || input >= maxInput || millis >= maxMillis ||
        (maxEstimatedMicroCredits > 0 && estimatedMicroCredits >= maxEstimatedMicroCredits)

    fun reserve(a: InferenceAttempt, outputLimit: Long): InferenceLedger {
        fun stop(text: String): Nothing = throw RunBudgetExceeded(text + " Progress is saved.")
        if (history.any { it.state == AttemptState.RESERVED }) stop("A previous model request is still being accounted for.")
        if (exhausted || attempts >= 10000) stop("The run's model allowance is used up. Continue to authorize another bounded allowance.")
        if (a.input > maxInput-input || a.output > outputLimit-output) stop("The next model request exceeds the remaining token allowance.")
        if (a.millis <= 0 || a.millis > maxMillis-millis) stop("The run's model time allowance is used up.")
        if (maxEstimatedMicroCredits > 0) {
            val cost = a.estimatedMicroCredits ?: stop("A current price estimate is unavailable. Refresh Account before continuing with a credit limit.")
            if (cost > maxEstimatedMicroCredits-estimatedMicroCredits) stop("The next model request exceeds the remaining estimated credit allowance.")
        }
        return copy(attempts=attempts+1,summaries=summaries+if(a.purpose==InferencePurpose.SUMMARY) 1 else 0,
            unreported=unreported+1,unpriced=unpriced+if(a.estimatedMicroCredits==null) 1 else 0,
            input=input+a.input,output=output+a.output,millis=millis+a.millis,
            estimatedMicroCredits=estimatedMicroCredits+(a.estimatedMicroCredits ?: 0),history=(history+a).takeLast(32))
    }
    fun settle(id: String, usage: TokenUsage?, elapsed: Long, interrupted: Boolean): InferenceLedger {
        val a = history.lastOrNull { it.id==id } ?: error("Unknown model admission")
        if (a.state != AttemptState.RESERVED) return this // Duplicate callbacks cannot refund twice.
        val valid = reported(usage)
        val usedInput = valid?.inputTokens?.toLong() ?: a.input
        val usedOutput = valid?.outputTokens?.toLong() ?: a.output
        val usedMillis = elapsed.coerceIn(0,a.millis)
        val cost = if(valid!=null) a.pricing?.estimate(usedInput,usedOutput) else a.estimatedMicroCredits
        val settled=a.copy(input=usedInput,output=usedOutput,millis=usedMillis,estimatedMicroCredits=cost,
            state=if(valid!=null) AttemptState.REPORTED else if(interrupted) AttemptState.INTERRUPTED else AttemptState.UNREPORTED)
        return copy(input=input-a.input+usedInput,output=output-a.output+usedOutput,millis=millis-a.millis+usedMillis,
            estimatedMicroCredits=estimatedMicroCredits-(a.estimatedMicroCredits ?: 0)+(cost ?: 0),
            unreported=unreported-if(valid!=null) 1 else 0,history=history.map { if(it.id==id) settled else it })
    }
    /** A process death cannot refund an admitted call or cause it to be replayed. */
    fun recover() = copy(history=history.map { if(it.state==AttemptState.RESERVED) it.copy(state=AttemptState.INTERRUPTED) else it })
    fun extend(saved: Boolean, creditLimit: Int, finalOnly: Boolean = false): InferenceLedger {
        val fresh = allowance(saved,creditLimit,finalOnly)
        return recover().copy(maxAttempts=(attempts+fresh.maxAttempts).coerceAtMost(10000),maxInput=input+fresh.maxInput,
            maxMillis=millis+fresh.maxMillis,maxEstimatedMicroCredits=if(creditLimit==0) 0 else estimatedMicroCredits+fresh.maxEstimatedMicroCredits)
    }
    fun validate() {
        require(attempts in 0..10000 && summaries in 0..attempts && unreported in 0..attempts && unpriced in 0..attempts)
        require(listOf(input,output,millis).all { it in 0..1_000_000_000_000 })
        require(estimatedMicroCredits in 0..2_000_000_000_000_000_000 && maxEstimatedMicroCredits in 0..2_000_000_000_000_000_000)
        require(maxAttempts in 1..10000 && maxInput in 1..100_000_000_000 && maxMillis in 1..100_000_000_000)
        require(history.size<=32 && history.map { it.id }.distinct().size==history.size && history.count { it.state==AttemptState.RESERVED }<=1)
        history.forEach { a ->
            require(a.id.length in 1..100 && a.route.matches(Regex("[a-f0-9]{64}")) && a.at>=0 && listOf(a.input,a.output,a.millis).all { it in 0..1_000_000_000_000 } && (a.estimatedMicroCredits==null || a.estimatedMicroCredits in 0..1_000_000_000_000_000))
            require(a.pricing?.let { it.model.length in 1..300 && it.valid(it.model,it.observedAt) } != false)
            require((a.pricing==null)==(a.estimatedMicroCredits==null))
            require(a.pricing==null || a.estimatedMicroCredits==a.pricing.estimate(a.input,a.output))
        }
        require(attempts>=history.size && input>=history.sumOf { it.input } && output>=history.sumOf { it.output } && millis>=history.sumOf { it.millis })
        require(estimatedMicroCredits>=history.sumOf { it.estimatedMicroCredits ?: 0 })
        require(summaries>=history.count { it.purpose==InferencePurpose.SUMMARY } && unreported>=history.count { it.state!=AttemptState.REPORTED } && unpriced>=history.count { it.pricing==null })
    }
    companion object {
        fun reported(usage: TokenUsage?) = usage?.takeIf { it.inputTokens in 0..50_000_000 && it.outputTokens in 0..50_000_000 && (it.inputTokens>0 || it.outputTokens>0) }
        fun allowance(saved: Boolean, creditLimit: Int, finalOnly: Boolean = false) = InferenceLedger(
            maxAttempts=if(finalOnly) 1 else if(saved) 400 else 80,
            maxInput=if(finalOnly) 128_000 else if(saved) 8_000_000 else 2_000_000,
            maxMillis=if(finalOnly) 360_000 else if(saved) 3_600_000 else 900_000,
            maxEstimatedMicroCredits=creditLimit.toLong()*1_000_000)
        fun request(config: ProviderConfig, messages: List<InferenceMessage>, purpose: InferencePurpose, now: Long, millis: Long): InferenceAttempt {
            // UTF-8 wire bytes plus message/schema overhead deliberately overestimate ordinary
            // text input. This is an admission estimate, not a tokenizer or a billing amount.
            val bytes=JSONArray(messages.map(NativeToolWire::messageJson)).toString().toByteArray(Charsets.UTF_8).size.toLong() +
                JSONArray(config.tools.map(NativeToolWire::toolJson)).toString().toByteArray(Charsets.UTF_8).size
            val input=bytes+messages.sumOf { it.images.sumOf { image -> image.length.toLong() } }+messages.size*256L+1024
            val output=config.maxOutputTokens.coerceIn(128,8192).toLong()
            val price=config.creditPricing?.takeIf { config.accountSession && it.valid(config.modelId,now) }
            return InferenceAttempt(UUID.randomUUID().toString(),purpose,com.kai.agent.data.MemoryRules.digest(RunReplay.route(config)),now,input,output,millis,
                price?.estimate(input,output),price)
        }
    }
}

object InferenceLedgerJson {
    fun write(l: InferenceLedger) = JSONObject().put("version",1).put("attempts",l.attempts).put("summaries",l.summaries)
        .put("unreported",l.unreported).put("unpriced",l.unpriced).put("input",l.input).put("output",l.output).put("millis",l.millis)
        .put("estimatedMicroCredits",l.estimatedMicroCredits).put("maxAttempts",l.maxAttempts).put("maxInput",l.maxInput)
        .put("maxMillis",l.maxMillis).put("maxEstimatedMicroCredits",l.maxEstimatedMicroCredits).put("legacy",l.legacy)
        .put("history",JSONArray(l.history.map { a -> JSONObject().put("id",a.id).put("purpose",a.purpose.name).put("route",a.route).put("at",a.at)
            .put("input",a.input).put("output",a.output).put("millis",a.millis).put("estimatedMicroCredits",a.estimatedMicroCredits ?: JSONObject.NULL)
            .put("pricing",a.pricing?.json() ?: JSONObject.NULL).put("state",a.state.name) }))
    fun read(o: JSONObject): InferenceLedger {
        require(o.getInt("version")==1) { "This run's allowance needs a newer KAI version. Its data was preserved." }
        val a=o.getJSONArray("history");require(a.length()<=32)
        return InferenceLedger(o.getInt("attempts"),o.getInt("summaries"),o.getInt("unreported"),o.getInt("unpriced"),
            o.getLong("input"),o.getLong("output"),o.getLong("millis"),o.getLong("estimatedMicroCredits"),o.getInt("maxAttempts"),
            o.getLong("maxInput"),o.getLong("maxMillis"),o.getLong("maxEstimatedMicroCredits"),
            (0 until a.length()).map { i -> val v=a.getJSONObject(i);InferenceAttempt(v.getString("id"),enumValueOf(v.getString("purpose")),v.getString("route"),v.getLong("at"),
                v.getLong("input"),v.getLong("output"),v.getLong("millis"),if(v.isNull("estimatedMicroCredits")) null else v.getLong("estimatedMicroCredits"),
                CreditPricing.read(v.optJSONObject("pricing")),enumValueOf(v.getString("state"))) },o.getBoolean("legacy")).also(InferenceLedger::validate)
    }
}
