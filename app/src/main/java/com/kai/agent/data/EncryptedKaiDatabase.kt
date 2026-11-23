package com.kai.agent.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/** Single transactional authority; SQLCipher encrypts rows, journals, FTS and vector payloads. */
internal class EncryptedKaiDatabase(context: Context) {
    private val directory=File(context.applicationContext.noBackupFilesDir,"kai-state")
    private val file=File(directory,"memory.db")
    private val wrapped=AtomicFile(File(directory,"database-key.enc"))
    private val authority=AtomicFile(File(directory,"database-authority"))
    private val alias=context.applicationContext.packageName+".kai.db.wrap.v1"
    private var database: SQLiteDatabase?=null
    private var unlockedPassword: ByteArray?=null
    private var previous: KaiState?=null
    private val preserveCorruption=DatabaseErrorHandler { _, error -> throw IllegalStateException("Encrypted database is damaged; existing files were preserved.",error) }

    fun openOrMigrate(legacy: () -> KaiState?): KaiState {
        check(directory.exists() || directory.mkdirs()) { "Cannot create private storage." }
        System.loadLibrary("sqlcipher")
        val staged=File(directory,"memory.db.migrating")
        val activated=authority.baseFile.exists() || File(authority.baseFile.path+".bak").exists()
        val existed=file.exists()
        check(!activated || existed || staged.exists()) { "The active encrypted database is missing. Legacy data was preserved, not automatically restored." }
        // Decode legacy before creating anything. An unreadable old file is never treated as empty.
        val initial=if (!existed && !activated) legacy() else null
        val password=password(create=!existed && !activated)
        try {
            if (activated && !existed) {
                // Crash between the durable activation marker and atomic rename: finish the verified
                // staging transaction, never silently resurrect the old JSON recovery snapshot.
                database=open(staged,password,false)
                check(requireNotNull(database).version in 1..2) { "Incomplete database activation." }
                read()
                database?.close(); database=null
                check(staged.renameTo(file)) { "Could not finish database activation." }
            }
            if (file.exists()) {
                database=open(file,password,false)
                migrateSchema(requireNotNull(database))
                return read().also { previous=it; markActive() }
            }
            // A staging file is never authoritative; the legacy snapshot is retained until rename.
            listOf(staged,File(staged.path+"-journal"),File(staged.path+"-wal"),File(staged.path+"-shm")).forEach { if (it.exists()) check(it.delete()) }
            database=open(staged,password,true)
            migrateSchema(requireNotNull(database))
            val fresh=initial ?: KaiState(settings=ComposioIdentity.migrate(AppSettings()),revision=1)
            val normalized=fresh.copy(memoryGraph=MemoryGraphRules.projectLegacy(fresh))
            save(normalized)
            requireNotNull(database).rawQuery("PRAGMA quick_check",emptyArray<String>()).use { c -> check(c.moveToFirst() && c.getString(0)=="ok") { "Database verification failed." } }
            val verified=read()
            check(KaiJson.encode(verified)==KaiJson.encode(normalized)) { "Migration verification failed." }
            database?.close(); database=null
            markActive()
            check(staged.renameTo(file)) { "Could not activate migrated database." }
            database=open(file,password,false)
            previous=verified
            return verified
        } catch (e: Exception) {
            database?.close(); database=null
            throw e
        } finally {
            // SQLCipher retains the supplied array for pooled connection reopens. Wipe only after
            // closing the database, otherwise a later connection could be keyed with zero bytes.
            if (database == null) password.fill(0) else unlockedPassword=password
        }
    }

    private fun markActive() {
        if (authority.baseFile.exists()) return
        val stream=authority.startWrite()
        try { stream.write(byteArrayOf(1)); authority.finishWrite(stream) }
        catch (e: Exception) { authority.failWrite(stream); throw e }
    }

    private fun open(target: File,password: ByteArray,create: Boolean): SQLiteDatabase {
        val db=if (create) SQLiteDatabase.openOrCreateDatabase(target,password,null,preserveCorruption)
            else SQLiteDatabase.openDatabase(target.absolutePath,password,null,SQLiteDatabase.OPEN_READWRITE,preserveCorruption,null)
        try {
            db.execSQL("PRAGMA foreign_keys=ON")
            db.execSQL("PRAGMA temp_store=MEMORY")
            // secure_delete, like journal_mode, returns its resulting value even on assignment.
            db.rawQuery("PRAGMA secure_delete=ON",emptyArray<String>()).use { it.moveToFirst() }
            db.rawQuery("PRAGMA journal_mode=DELETE",emptyArray<String>()).use { it.moveToFirst() }
            db.execSQL("PRAGMA synchronous=FULL")
            return db
        } catch (e: Exception) { db.close(); throw e }
    }

    private fun wrappingKey(create: Boolean): SecretKey {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias,null) as? SecretKey)?.let { return it }
        check(create) { "The database encryption key is unavailable." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setKeySize(256).build())
        }.generateKey()
    }

    private fun password(create: Boolean): ByteArray {
        if (wrapped.baseFile.exists() || File(wrapped.baseFile.path+".bak").exists()) {
            val bytes=wrapped.readFully()
            check(bytes.size==64 && bytes.copyOfRange(0,4).contentEquals(KEY_HEADER)) { "Invalid encrypted database key." }
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,wrappingKey(false),GCMParameterSpec(128,bytes.copyOfRange(4,16)))
            cipher.updateAAD(KEY_HEADER)
            return cipher.doFinal(bytes.copyOfRange(16,bytes.size)).also { check(it.size==32) }
        }
        check(create) { "The wrapped database key is missing. Existing data was preserved." }
        val bytes=ByteArray(32).also(SecureRandom()::nextBytes)
        try {
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE,wrappingKey(true)); cipher.updateAAD(KEY_HEADER)
            val stream=wrapped.startWrite()
            try { stream.write(KEY_HEADER); stream.write(cipher.iv); stream.write(cipher.doFinal(bytes)); wrapped.finishWrite(stream) }
            catch (e: Exception) { wrapped.failWrite(stream); throw e }
            return bytes
        } catch (e: Exception) { bytes.fill(0); throw e }
    }

    /** Ordered, non-destructive migrations. Unsupported future schemas fail closed. */
    private fun migrateSchema(db: SQLiteDatabase) {
        val version=db.version
        require(version in 0..2) { "This database needs a newer KAI version." }
        if (version==2) return
        if (version==1) { migrateDiagnostics(db); return }
        db.beginTransaction()
        try {
            db.execSQL("CREATE TABLE app_state (id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE conversations (id TEXT PRIMARY KEY, payload TEXT NOT NULL, ordinal INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE entities (id TEXT PRIMARY KEY, scope TEXT NOT NULL, type TEXT NOT NULL, name TEXT NOT NULL, payload TEXT NOT NULL, ordinal INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE facts (id TEXT PRIMARY KEY, subject_id TEXT NOT NULL, predicate TEXT NOT NULL, object_id TEXT, scope TEXT NOT NULL, status TEXT NOT NULL, revision INTEGER NOT NULL, payload TEXT NOT NULL, ordinal INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX facts_subject ON facts(scope,subject_id,predicate,status)")
            db.execSQL("CREATE INDEX facts_object ON facts(scope,predicate,object_id,status)")
            db.execSQL("CREATE TABLE aliases (id TEXT PRIMARY KEY, entity_id TEXT NOT NULL, scope TEXT NOT NULL, name TEXT NOT NULL, payload TEXT NOT NULL, ordinal INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX aliases_name ON aliases(scope,name)")
            db.execSQL("CREATE TABLE evidence (id TEXT PRIMARY KEY, fact_id TEXT NOT NULL, message_id TEXT NOT NULL, conversation_id TEXT NOT NULL, payload TEXT NOT NULL, ordinal INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX evidence_source ON evidence(conversation_id,message_id)")
            db.execSQL("CREATE INDEX evidence_fact ON evidence(fact_id)")
            db.execSQL("CREATE TABLE candidates (id TEXT PRIMARY KEY, status TEXT NOT NULL, message_id TEXT NOT NULL, conversation_id TEXT NOT NULL, payload TEXT NOT NULL, ordinal INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE jobs (id TEXT PRIMARY KEY, status TEXT NOT NULL, message_id TEXT NOT NULL, conversation_id TEXT NOT NULL, payload TEXT NOT NULL, ordinal INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX jobs_status ON jobs(status)")
            db.execSQL("CREATE TABLE vectors (id TEXT PRIMARY KEY, fact_id TEXT NOT NULL, model_id TEXT NOT NULL, revision INTEGER NOT NULL, payload TEXT NOT NULL, ordinal INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE suppressions (id TEXT PRIMARY KEY, fact_id TEXT NOT NULL, source_hash TEXT NOT NULL, claim_hash TEXT NOT NULL, payload TEXT NOT NULL, ordinal INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX suppressions_source ON suppressions(source_hash)")
            db.execSQL("CREATE TABLE memory_meta (id INTEGER PRIMARY KEY CHECK(id=1), deletion_epoch INTEGER NOT NULL)")
            db.execSQL("CREATE VIRTUAL TABLE fact_search USING fts4(fact_id, text, notindexed=fact_id)")
            db.version=1
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        migrateDiagnostics(db)
    }

    private fun migrateDiagnostics(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            db.execSQL("CREATE TABLE diagnostics (seq INTEGER PRIMARY KEY AUTOINCREMENT, conversation_id TEXT NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("CREATE INDEX diagnostics_conversation ON diagnostics(conversation_id,seq)")
            db.execSQL("CREATE TABLE diagnostic_meta (id INTEGER PRIMARY KEY CHECK(id=1), epoch INTEGER NOT NULL, reset_at INTEGER NOT NULL)")
            db.execSQL("INSERT INTO diagnostic_meta VALUES(1,0,0)")
            db.execSQL("CREATE TABLE diagnostic_stats (conversation_id TEXT PRIMARY KEY, dropped INTEGER NOT NULL DEFAULT 0)")
            db.version=2
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun diagnosticEpoch(): Long = requireNotNull(database).rawQuery("SELECT epoch FROM diagnostic_meta", emptyArray<String>()).use { it.moveToFirst(); it.getLong(0) }
    fun appendDiagnostic(cid: String, payload: String) {
        val db=requireNotNull(database)
        db.beginTransaction()
        try {
            db.execSQL("INSERT INTO diagnostics(conversation_id,payload) VALUES(?,?)", arrayOf(cid,payload))
            fun prune(where: String, args: Array<String>, maxChars: Long, maxCount: Int) {
                var size=0L; var count=0
                val remove=mutableListOf<Pair<Long,String>>()
                db.rawQuery("SELECT seq,conversation_id,length(payload) FROM diagnostics $where ORDER BY seq DESC", args).use { c ->
                    while(c.moveToNext()) { size+=c.getLong(2); count++; if(size>maxChars || count>maxCount) remove+=c.getLong(0) to c.getString(1) }
                }
                remove.forEach { (seq,conversation) ->
                    db.execSQL("DELETE FROM diagnostics WHERE seq=?", arrayOf(seq))
                    db.execSQL("INSERT OR IGNORE INTO diagnostic_stats(conversation_id,dropped) VALUES(?,0)", arrayOf(conversation))
                    db.execSQL("UPDATE diagnostic_stats SET dropped=dropped+1 WHERE conversation_id=?", arrayOf(conversation))
                }
            }
            prune("WHERE conversation_id=?", arrayOf(cid), 1_000_000, 500)
            prune("", emptyArray(), 4_000_000, 4000)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun diagnosticReport(cid: String): JSONObject {
        val db=requireNotNull(database)
        val events=JSONArray()
        db.rawQuery("SELECT seq,payload FROM diagnostics WHERE conversation_id=? ORDER BY seq", arrayOf(cid)).use { c ->
            while(c.moveToNext()) events.put(JSONObject(c.getString(1)).put("sequence",c.getLong(0)))
        }
        val dropped=db.rawQuery("SELECT dropped FROM diagnostic_stats WHERE conversation_id=?",arrayOf(cid)).use { if(it.moveToFirst()) it.getLong(0) else 0 }
        val reset=db.rawQuery("SELECT reset_at FROM diagnostic_meta",emptyArray<String>()).use { it.moveToFirst(); it.getLong(0) }
        return JSONObject().put("events",events).put("droppedEvents",dropped).put("lastPrivacyResetAt",reset)
    }

    fun save(state: KaiState, clearDiagnostics: Boolean = false) {
        val db=requireNotNull(database)
        val old=previous
        val core=JSONObject(KaiJson.encode(state.copy(memoryGraph=MemoryGraph()))).put("conversations",JSONArray())
        val payload=core.toString()
        require(payload.toByteArray().size <= 32*1024*1024) { "KAI storage limit reached." }
        db.beginTransaction()
        try {
            if(clearDiagnostics) {
                db.execSQL("DELETE FROM diagnostics")
                db.execSQL("DELETE FROM diagnostic_stats")
                db.execSQL("UPDATE diagnostic_meta SET epoch=epoch+1,reset_at=?",arrayOf(now()))
            }
            db.execSQL("INSERT OR REPLACE INTO app_state(id,payload) VALUES(1,?)",arrayOf(payload))
            val conversations=JSONObject(KaiJson.encode(state.copy(memoryGraph=MemoryGraph()))).getJSONArray("conversations")
            val oldConversations=old?.conversations?.filterNot { it.temporary }?.associateBy { it.id }.orEmpty()
            val newConversations=state.conversations.filterNot { it.temporary }
            oldConversations.keys.filter { id -> newConversations.none { it.id==id } }.forEach { db.execSQL("DELETE FROM conversations WHERE id=?",arrayOf(it)) }
            newConversations.forEachIndexed { i,c -> if (oldConversations[c.id]!=c || old?.conversations?.filterNot { it.temporary }?.indexOfFirst { it.id==c.id }!=i)
                db.execSQL("INSERT OR REPLACE INTO conversations(id,payload,ordinal) VALUES(?,?,?)",arrayOf(c.id,conversations.getJSONObject(i).toString(),i)) }
            val g=state.memoryGraph; val p=old?.memoryGraph ?: MemoryGraph()
            sync("entities",p.entities,g.entities,{it.id},MemoryGraphJson::entity) { e -> mapOf("scope" to e.scope,"type" to e.type,"name" to e.displayName) }
            sync("facts",p.facts,g.facts,{it.id},MemoryGraphJson::fact) { f -> mapOf("subject_id" to f.subjectId,"predicate" to f.predicate,"object_id" to f.objectEntityId,"scope" to f.scope,"status" to f.status.name,"revision" to f.revision) }
            sync("aliases",p.aliases,g.aliases,{it.id},MemoryGraphJson::alias) { a -> mapOf("entity_id" to a.entityId,"scope" to a.scope,"name" to MemoryRules.normalize(a.name)) }
            sync("evidence",p.evidence,g.evidence,{it.id},MemoryGraphJson::evidence) { e -> mapOf("fact_id" to e.factId,"message_id" to e.sourceMessageId,"conversation_id" to e.conversationId) }
            sync("candidates",p.candidates,g.candidates,{it.id},MemoryGraphJson::candidate) { c -> mapOf("status" to c.status.name,"message_id" to c.sourceMessageId,"conversation_id" to c.conversationId) }
            sync("jobs",p.jobs,g.jobs,{it.id},MemoryGraphJson::job) { j -> mapOf("status" to j.status.name,"message_id" to j.sourceMessageId,"conversation_id" to j.conversationId) }
            sync("vectors",p.vectors,g.vectors,{it.factId+"\u0000"+it.modelId},MemoryGraphJson::vector) { v -> mapOf("fact_id" to v.factId,"model_id" to v.modelId,"revision" to v.factRevision) }
            // Keep the original stable row ID when adding/changing the retention reason. Existing
            // pre-reason rows must be updated in place, not left behind as permanent-forget copies.
            sync("suppressions",p.suppressions,g.suppressions,{ s -> MemoryRules.digest(MemoryGraphJson.suppression(s).also { it.remove("reason") }.toString()) },MemoryGraphJson::suppression) { s -> mapOf("fact_id" to s.factId,"source_hash" to s.sourceHash,"claim_hash" to s.claimHash) }
            val priorFacts=p.facts.associateBy { it.id }; val nextFacts=g.facts.associateBy { it.id }
            (priorFacts.keys+nextFacts.keys).forEach { id ->
                val before=priorFacts[id]; val after=nextFacts[id]
                if (before?.displayText!=after?.displayText || before?.status!=after?.status || before==null || after==null) {
                    db.execSQL("DELETE FROM fact_search WHERE fact_id=?",arrayOf(id))
                    if (after?.status==FactStatus.CURRENT) db.execSQL("INSERT INTO fact_search(fact_id,text) VALUES(?,?)",arrayOf(id,after.displayText))
                }
            }
            db.execSQL("INSERT OR REPLACE INTO memory_meta(id,deletion_epoch) VALUES(1,?)",arrayOf(g.deletionEpoch))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        previous=state.copy(conversations=state.conversations.filterNot { it.temporary })
    }

    private fun <T> sync(table:String,before:List<T>,after:List<T>,id:(T)->String,json:(T)->JSONObject,columns:(T)->Map<String,Any?>) {
        val db=requireNotNull(database); val old=before.withIndex().associateBy { id(it.value) }; val next=after.map(id).toSet()
        old.keys.filterNot { it in next }.forEach { db.execSQL("DELETE FROM $table WHERE id=?",arrayOf(it)) }
        after.forEachIndexed { position,value ->
            val prior=old[id(value)]
            if (prior?.value!=value || prior?.index!=position) {
                val fields=linkedMapOf<String,Any?>("id" to id(value),"payload" to json(value).toString(),"ordinal" to position).apply { putAll(columns(value)) }
                db.execSQL("INSERT OR REPLACE INTO $table(${fields.keys.joinToString(",")}) VALUES(${fields.keys.joinToString(",") { "?" }})",fields.values.toTypedArray())
            }
        }
    }

    private fun rows(table: String): JSONArray = JSONArray().also { a -> requireNotNull(database).rawQuery("SELECT payload FROM $table ORDER BY ordinal",emptyArray<String>()).use { c -> while(c.moveToNext()) a.put(JSONObject(c.getString(0))) } }
    private fun read(): KaiState {
        val db=requireNotNull(database)
        val root=db.rawQuery("SELECT payload FROM app_state WHERE id=1",emptyArray<String>()).use { c -> check(c.moveToFirst()) { "Database state is missing." }; JSONObject(c.getString(0)) }
        root.put("conversations",rows("conversations"))
        val graph=JSONObject()
        listOf("entities","aliases","facts","evidence","candidates","jobs","vectors","suppressions").forEach { graph.put(it,rows(it)) }
        val epoch=db.rawQuery("SELECT deletion_epoch FROM memory_meta WHERE id=1",emptyArray<String>()).use { c -> check(c.moveToFirst()); c.getLong(0) }
        graph.put("deletionEpoch",epoch); root.put("memoryGraph",graph)
        return KaiJson.decode(root.toString())
    }

    fun search(query: String,scopes:Set<String>,limit:Int): List<StructuredFact> {
        if(scopes.isEmpty()) return emptyList()
        val tokens=Regex("[\\p{L}\\p{N}]+").findAll(query).map { it.value }.take(12).toList()
        if(tokens.isEmpty()) return emptyList()
        val match=tokens.joinToString(" OR ") { "\"${it.replace("\"", "\"\"")}\"" }
        val args=(listOf(match)+scopes.sorted()+limit.coerceIn(1,50).toString()).toTypedArray()
        return requireNotNull(database).rawQuery("SELECT f.payload FROM fact_search s JOIN facts f ON f.id=s.fact_id WHERE fact_search MATCH ? AND f.status='CURRENT' AND f.scope IN (${scopes.joinToString(",") { "?" }}) LIMIT ?",args).use { c ->
            buildList { while(c.moveToNext()) add(MemoryGraphJson.readFact(JSONObject(c.getString(0)))) }
        }
    }

    fun close() { database?.close(); database=null; previous=null; unlockedPassword?.fill(0); unlockedPassword=null }
    private companion object { val KEY_HEADER=byteArrayOf(75,68,66,1) }
}
