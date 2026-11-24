package com.kai.agent.web

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.nodes.Document

/** Publicly published page data only. JSON-LD is parsed as data, never executed as JavaScript. */
object PublicPageEvidence {
    private val fields=setOf("@type","name","url","telephone","address","streetAddress","addressLocality","addressRegion","postalCode","addressCountry",
        "aggregateRating","ratingValue","reviewCount","ratingCount","bestRating","worstRating","review","reviewBody","reviewRating","datePublished","author")
    private val evidence=setOf("telephone","address","streetAddress","aggregateRating","ratingValue","reviewBody")
    fun extract(doc: Document): String {
        val rows=linkedSetOf<String>();var visited=0
        fun safe(value: Any?,depth: Int): Any? {
            if(depth>4)return null
            return when(value) {
                is String -> value.take(500)
                is Number,is Boolean -> value
                is JSONObject -> JSONObject().apply { value.keys().asSequence().filter { it in fields }.take(20).forEach { key -> safe(value.opt(key),depth+1)?.let { put(key,it) } } }
                is JSONArray -> JSONArray((0 until minOf(value.length(),4)).mapNotNull { safe(value.opt(it),depth+1) })
                else -> null
            }
        }
        fun visit(value: Any?,depth: Int) {
            if(depth>7 || visited++>=64 || rows.size>=8)return
            when(value) {
                is JSONObject -> {
                    if(evidence.any { value.has(it) }) (safe(value,0) as? JSONObject)?.takeIf { it.length()>0 }?.let { rows+=it.toString() }
                    value.keys().asSequence().take(30).forEach { visit(value.opt(it),depth+1) }
                }
                is JSONArray -> for(i in 0 until minOf(value.length(),24)) visit(value.opt(i),depth+1)
            }
        }
        doc.select("script[type]").filter { it.attr("type").substringBefore(';').equals("application/ld+json",true) }.take(8).forEach { script ->
            val raw=script.data()
            if(raw.length<=32000) runCatching { val parser=JSONTokener(raw);val parsed=parser.nextValue();require(parser.nextClean()=='\u0000');visit(parsed,0) }
        }
        val contact=doc.select("header,footer,a[href^=tel:]").map { it.text().trim() }
            .filter { Regex("\\+?\\d[\\d ()-]{7,}\\d|(?i)\\b(?:address|contact|phone)\\b").containsMatchIn(it) }
            .distinct().joinToString("\n").take(1200)
        if(rows.isEmpty() && contact.isBlank())return ""
        val bounded=rows.toMutableList()
        while(bounded.isNotEmpty() && JSONArray(bounded.map { JSONObject(it) }).toString().length>4500) bounded.removeAt(bounded.lastIndex)
        return "\nSite-reported structured/contact information (untrusted claims from this page, not independent review verification):\n"+
            JSONArray(bounded.map { JSONObject(it) }).toString()+"\n"+contact
    }
}
