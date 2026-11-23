package com.kai.agent.data

import android.content.Context
import android.content.ContextWrapper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GraphMemoryDeviceTest {
    private val stores=mutableListOf<KaiStore>()
    private fun store(context:Context)=KaiStore(context).also(stores::add)

    @Test fun migratesAuthenticatedLegacySnapshotOnceAndRetainsEncryptedRecoveryUntilForget() = runBlocking {
        isolated { context ->
            val original=KaiState(settings=AppSettings(accountId="legacy-owner",openai=ProviderProfile(apiKey="provider-secret")),
                memories=listOf(MemoryFact(id="legacy-memory",text="Prefer meetings after ten")))
            val bytes=writeLegacy(context,original)
            val active=store(context); active.load()
            assertEquals("kai:legacy-owner",active.state.value.settings.composioUserId)
            assertEquals("provider-secret",active.state.value.settings.openai.apiKey)
            val note=active.state.value.memoryGraph.facts.single()
            assertEquals("legacy-memory",note.id); assertEquals("legacy_note",note.predicate)
            assertArrayEquals(bytes,File(context.noBackupFilesDir,"kai-state/state.enc").readBytes())
            active.close()
            val reopened=store(context); reopened.load()
            assertEquals(note,reopened.state.value.memoryGraph.facts.single())
            reopened.forgetMemory("legacy-memory")
            assertFalse(File(context.noBackupFilesDir,"kai-state/state.enc").exists())
            assertTrue(reopened.state.value.memoryGraph.facts.isEmpty())
        }
    }

    @Test fun missingWrappingKeyFailsClosedWithoutFallbackToOldJson() = runBlocking {
        isolated { context ->
            writeLegacy(context,KaiState(memories=listOf(MemoryFact(text="Old recovery"))))
            val active=store(context); active.load(); active.remember("Newest database fact"); active.close()
            val file=File(context.noBackupFilesDir,"kai-state/memory.db"); val before=file.readBytes()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(context.packageName+".kai.db.wrap.v1") }
            assertTrue(runCatching { store(context).load() }.exceptionOrNull() is KaiStoreException)
            assertArrayEquals(before,file.readBytes())
            assertTrue(File(context.noBackupFilesDir,"kai-state/state.enc").exists())
        }
    }

    @Test fun recoveryRetirementFailureDoesNotCommitForgetOrAdvanceInMemoryState() = runBlocking {
        isolated { context ->
            writeLegacy(context,KaiState(memories=listOf(MemoryFact(id="kept",text="Keep until the privacy change can finish"))))
            val active=store(context); active.load()
            val before=active.state.value
            val recovery=File(context.noBackupFilesDir,"kai-state/state.enc")
            assertTrue(recovery.delete()); assertTrue(recovery.mkdir())
            File(recovery,"unexpected-child").writeText("Fixture preventing nonrecursive deletion")
            assertTrue(runCatching { active.forgetMemory("kept") }.exceptionOrNull() is KaiStoreException)
            assertEquals(before,active.state.value)
            active.close()
            val reopened=store(context); reopened.load()
            assertEquals(before,reopened.state.value)
            assertTrue(recovery.exists())
        }
    }

    @Test fun missingActivatedDatabaseDoesNotAutomaticallyRestoreLegacySnapshot() = runBlocking {
        isolated { context ->
            val recovery=writeLegacy(context,KaiState(memories=listOf(MemoryFact(text="Old snapshot"))))
            val active=store(context); active.load(); active.remember("Newer database state"); active.close()
            assertTrue(File(context.noBackupFilesDir,"kai-state/database-authority").exists())
            assertTrue(File(context.noBackupFilesDir,"kai-state/memory.db").delete())
            assertTrue(runCatching { store(context).load() }.exceptionOrNull() is KaiStoreException)
            assertArrayEquals(recovery,File(context.noBackupFilesDir,"kai-state/state.enc").readBytes())
            assertFalse(File(context.noBackupFilesDir,"kai-state/memory.db").exists())
        }
    }

    @Test fun factEvidenceFtsAndJobSuppressionCommitTogetherAndOldImportCannotResurrect() = runBlocking {
        isolated { context ->
            val active=store(context); active.load()
            val conversation=active.createConversation()
            val message=active.appendMessage(conversation.id,"user","Aurora's birthday is October")
            val entity=MemoryEntity(id="aurora",displayName="Aurora")
            val fact=StructuredFact(id="birthday",subjectId=entity.id,predicate="birthday",value=MemoryValue(type="DATE",month=10,precision="month"),displayText="Aurora birthday October")
            val evidence=FactEvidence(id="proof",factId=fact.id,sourceMessageId=message.id,conversationId=conversation.id,quote=message.text)
            active.mutateMemoryGraph { it.copy(entities=listOf(entity),facts=listOf(fact),evidence=listOf(evidence)) }
            assertEquals(fact.id,active.searchStructuredFacts("October",setOf("personal")).single().id)
            assertTrue(active.searchStructuredFacts("October",setOf("work")).isEmpty())
            val job=requireNotNull(active.enqueueMemoryJob(conversation.id,message.id))
            assertNotNull(active.startMemoryJob(job.id))
            active.mutateMemoryGraph { g -> g.copy(vectors=listOf(MemoryVector(fact.id,fact.revision,"fixture-model","fixture-hash",listOf(1f)))) }
            assertFalse(active.state.value.memoryGraph.jobs.isEmpty())
            assertFalse(active.state.value.memoryGraph.vectors.isEmpty())
            val backup=active.exportJson()
            val exportedGraph=JSONObject(backup).getJSONObject("state").getJSONObject("memoryGraph")
            assertEquals(0,exportedGraph.getJSONArray("jobs").length())
            assertEquals(0,exportedGraph.getJSONArray("vectors").length())
            active.forgetStructuredFact(fact.id)
            assertFalse(active.commitMemoryJob(job.id) { error("Deleted-source transform must never run") })
            active.importJson(backup)
            assertTrue(active.state.value.memoryGraph.facts.isEmpty())
            assertTrue(active.state.value.memoryGraph.entities.isEmpty())
            assertTrue(active.searchStructuredFacts("October",setOf("personal")).isEmpty())
            active.close()
            val reopened=store(context); reopened.load()
            assertTrue(reopened.state.value.memoryGraph.facts.isEmpty())
            assertNull(reopened.enqueueMemoryJob(conversation.id,message.id,"new-version"))
        }
    }

    @Test fun temporaryAndDisabledSourcesNeverQueueAndRejectedMutationDoesNotChangeDatabase() = runBlocking {
        isolated { context ->
            val active=store(context); active.load()
            val temporary=active.createConversation(temporary=true)
            val tempMessage=active.appendMessage(temporary.id,"user","Do not retain this")
            assertNull(active.enqueueMemoryJob(temporary.id,tempMessage.id))
            val normal=active.createConversation(); val normalMessage=active.appendMessage(normal.id,"user","A normal turn")
            active.updateSettings { it.copy(memoryMode=MemoryMode.OFF) }
            assertNull(active.enqueueMemoryJob(normal.id,normalMessage.id))
            active.updateSettings { it.copy(memoryMode=MemoryMode.AUTOMATIC) }
            val before=active.state.value
            assertTrue(runCatching { active.mutateMemoryGraph { it.copy(facts=listOf(StructuredFact(subjectId="missing",predicate="birthday",value=MemoryValue(type="DATE",month=10,precision="month")))) } }.isFailure)
            assertEquals(before,active.state.value)
            active.close()
            val reopened=store(context); reopened.load()
            assertTrue(reopened.state.value.conversations.none { it.id==temporary.id })
            assertEquals(before.revision,reopened.state.value.revision)
        }
    }

    @Test fun explicitLiveRememberCanSaveWhenAutomaticMemoryIsOff() = runBlocking {
        isolated { context ->
            val active=store(context); active.load(); active.updateSettings { it.copy(memoryMode=MemoryMode.OFF) }
            val conversation=active.createConversation()
            val message=active.appendMessage(conversation.id,"user","Remember my name is Rowan")
            val entity=MemoryEntity(id="self:personal",displayName="You")
            val fact=StructuredFact(id="name",subjectId=entity.id,predicate="name",value=MemoryValue(text="Rowan"))
            val evidence=FactEvidence(id="proof",factId=fact.id,sourceMessageId=message.id,conversationId=conversation.id,quote=message.text)
            val edit: (MemoryGraph)->MemoryGraph = { it.copy(entities=listOf(entity),facts=listOf(fact),evidence=listOf(evidence)) }
            assertTrue(runCatching { active.mutateMemoryGraph(transform=edit) }.isFailure)
            active.mutateMemoryGraph(MemorySource(ref=message.id,conversationId=conversation.id),edit)
            assertEquals("Rowan",active.state.value.memoryGraph.facts.single().value!!.text)
        }
    }

    @Test fun undoMixedSaveBatchRestoresPriorBirthdayAndRemovesNewPreferenceAtomically() = runBlocking {
        isolated { context ->
            val active=store(context); active.load()
            val conversation=active.createConversation()
            val named=active.appendMessage(conversation.id,"user","My daughter's name is Aurora.")
            val october=active.appendMessage(conversation.id,"user","Aurora's birthday is October.")
            val self=MemoryEntity(id="self:personal",displayName="You")
            val aurora=MemoryEntity(id="aurora",displayName="Aurora")
            val name=StructuredFact(id="name",subjectId=aurora.id,predicate="name",value=MemoryValue(text="Aurora"))
            val daughter=StructuredFact(id="daughter",subjectId=self.id,predicate="has_daughter",objectEntityId=aurora.id)
            val oldBirthday=StructuredFact(id="october",subjectId=aurora.id,predicate="birthday",value=MemoryValue(type="DATE",month=10,precision="month"))
            fun proof(id:String,factId:String,message:ChatMessage,quote:String=message.text,start:Int=0)=FactEvidence(id=id,factId=factId,
                sourceMessageId=message.id,conversationId=conversation.id,quote=quote,start=start)
            active.mutateMemoryGraph { it.copy(entities=listOf(self,aurora),facts=listOf(name,daughter,oldBirthday),
                aliases=listOf(MemoryAlias(id="aurora-alias",entityId=aurora.id,name="Aurora",evidenceFactIds=listOf(name.id))),
                evidence=listOf(proof("name-proof",name.id,named),proof("daughter-proof",daughter.id,named),proof("old-birthday-proof",oldBirthday.id,october))) }
            val batch=active.appendMessage(conversation.id,"user","I prefer tea. Actually Aurora's birthday is November.")
            val tea=StructuredFact(id="tea",subjectId=self.id,predicate="preference",value=MemoryValue(text="tea"))
            val corrected=oldBirthday.copy(id="november",value=MemoryValue(type="DATE",month=11,precision="month"),revision=2,
                supersedesId=oldBirthday.id,lineageIds=listOf(oldBirthday.id))
            val correctionStart=batch.text.indexOf("Actually")
            active.mutateMemoryGraph { g -> g.copy(facts=g.facts.map { if(it.id==oldBirthday.id) it.copy(status=FactStatus.SUPERSEDED,validUntil=batch.createdAt) else it }+tea+corrected,
                evidence=g.evidence+proof("tea-proof",tea.id,batch,"I prefer tea.")+proof("new-birthday-proof",corrected.id,batch,batch.text.substring(correctionStart),correctionStart)) }
            active.mutateMemoryGraph { g -> g.copy(vectors=listOf(MemoryVector(tea.id,1,"fixture-model","tea-hash",listOf(1f)),
                MemoryVector(corrected.id,2,"fixture-model","november-hash",listOf(1f)))) }
            val job=requireNotNull(active.enqueueMemoryJob(conversation.id,batch.id)); active.startMemoryJob(job.id)
            val beforeUndo=active.state.value.revision
            // Preference first reproduced the previous bug: its source suppression hid the correction.
            active.undoStructuredFacts(listOf(tea.id,corrected.id))
            assertEquals(beforeUndo+1,active.state.value.revision)
            val graph=active.state.value.memoryGraph
            val birthday=graph.facts.single { it.predicate=="birthday" && it.status==FactStatus.CURRENT }
            assertEquals(oldBirthday.id,birthday.id); assertEquals(10,birthday.value!!.month)
            assertNull(birthday.value!!.day); assertNull(birthday.value!!.year)
            assertTrue(graph.facts.none { it.id==tea.id || it.id==corrected.id })
            assertTrue(graph.evidence.none { it.sourceMessageId==batch.id })
            assertTrue(graph.vectors.isEmpty())
            assertEquals(MemoryJobStatus.CANCELLED,graph.jobs.single().status)
            assertTrue(active.messagesForContext(conversation.id).none { it.id==batch.id })
            assertTrue(active.searchStructuredFacts("tea November",setOf("personal")).isEmpty())
            assertEquals(oldBirthday.id,active.searchStructuredFacts("October",setOf("personal")).single().id)
            active.close()
            val reopened=store(context); reopened.load()
            assertEquals(10,reopened.state.value.memoryGraph.facts.single { it.predicate=="birthday" && it.status==FactStatus.CURRENT }.value!!.month)
            assertTrue(reopened.searchStructuredFacts("tea November",setOf("personal")).isEmpty())
        }
    }

    @Test fun editedManualNoteKeepsCurrentGraphFtsAndRejectsOldBackupAcrossRestart() = runBlocking {
        isolated { context ->
            val active=store(context); active.load()
            val conversation=active.createConversation()
            val message=active.appendMessage(conversation.id,"user","I prefer coffee.")
            val note=active.remember("I prefer coffee.",MemorySource("conversation",message.id,conversation.id,message.text))
            val oldBackup=active.exportJson()
            active.correctMemory(note.id,"I prefer tea.")
            val updated=active.state.value.memories.single()
            assertEquals(2L,updated.revision)
            assertEquals("manual_correction",updated.source.kind)
            assertEquals("",updated.source.snippet); assertEquals("",updated.source.conversationId)
            assertNotEquals(message.id,updated.source.ref)
            assertEquals("I prefer tea.",active.state.value.memoryGraph.facts.single().value!!.text)
            assertTrue(active.searchStructuredFacts("coffee",setOf("personal")).isEmpty())
            assertEquals(note.id,active.searchStructuredFacts("tea",setOf("personal")).single().id)
            assertTrue(active.messagesForContext(conversation.id).none { it.id==message.id })
            active.importJson(oldBackup)
            active.close()
            val reopened=store(context); reopened.load()
            assertEquals("I prefer tea.",reopened.state.value.memories.single().text)
            assertEquals(note.id,reopened.searchStructuredFacts("tea",setOf("personal")).single().id)
            assertTrue(reopened.searchStructuredFacts("coffee",setOf("personal")).isEmpty())
            val correctedBackup=reopened.exportJson()
            reopened.forgetMemory(note.id)
            reopened.importJson(correctedBackup)
            assertTrue(reopened.state.value.memories.isEmpty())
            assertTrue(reopened.state.value.memoryGraph.facts.isEmpty())
        }
    }

    @Test fun legacyAlreadyCorrectedNoteMigratesWithoutOldQuoteAttributionOrSuppression() = runBlocking {
        isolated { context ->
            val sourceMessage=ChatMessage(id="legacy-message",role="user",text="I prefer coffee.",createdAt=10)
            val conversation=Conversation(id="legacy-chat",messages=listOf(sourceMessage))
            val original=MemoryFact(id="legacy-note",text="I prefer coffee.",source=MemorySource("conversation",sourceMessage.id,conversation.id,sourceMessage.text),createdAt=10,updatedAt=10)
            val corrected=original.copy(text="I prefer tea.",revision=2,updatedAt=20)
            writeLegacy(context,KaiState(conversations=listOf(conversation),memories=listOf(corrected),
                memoryTombstones=listOf(MemoryRules.tombstone(original,"corrected",20))))
            val active=store(context); active.load()
            assertEquals("manual_correction",active.state.value.memories.single().source.kind)
            assertEquals("",active.state.value.memories.single().source.snippet)
            assertEquals("I prefer tea.",active.state.value.memoryGraph.facts.single().value!!.text)
            assertTrue(active.searchStructuredFacts("coffee",setOf("personal")).isEmpty())
            assertEquals(corrected.id,active.searchStructuredFacts("tea",setOf("personal")).single().id)
            assertTrue(active.messagesForContext(conversation.id).isEmpty())
            active.close()
            val reopened=store(context); reopened.load()
            assertEquals(corrected.id,reopened.searchStructuredFacts("tea",setOf("personal")).single().id)
            assertTrue(reopened.searchStructuredFacts("coffee",setOf("personal")).isEmpty())
        }
    }

    private fun writeLegacy(context:Context,state:KaiState):ByteArray {
        val alias=context.packageName+".kai.state.aes.v1"
        val key=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
        val root=JSONObject(KaiJson.encode(state)); root.remove("memoryGraph")
        root.getJSONObject("settings").apply { remove("composioUserId"); remove("composioApiKey"); remove("memoryMode"); remove("useMemory") }
        val header=byteArrayOf(75,65,73,1)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key); updateAAD(header) }
        val bytes=header+cipher.iv+cipher.doFinal(root.toString().toByteArray())
        File(context.noBackupFilesDir,"kai-state/state.enc").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        return bytes
    }

    private suspend fun isolated(block:suspend (Context)->Unit) {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val suffix=UUID.randomUUID().toString(); val directory=File(app.noBackupFilesDir,"graph-test-$suffix").apply { mkdirs() }
        val identity=app.packageName+".test."+suffix
        val context=object:ContextWrapper(app) {
            override fun getApplicationContext():Context=this
            override fun getNoBackupFilesDir():File=directory
            override fun getPackageName():String=identity
        }
        try { block(context) } finally {
            stores.forEach { it.close() }; stores.clear(); directory.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("$identity.kai.state.aes.v1"); deleteEntry("$identity.kai.db.wrap.v1") }
        }
    }
}
