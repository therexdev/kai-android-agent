package com.kai.agent.data

import org.junit.Assert.*
import org.junit.Test

class MemoryGraphRulesTest {
    private val entity=MemoryEntity(id="p",displayName="Aurora")
    private fun birthday(id:String,month:Int,status:FactStatus=FactStatus.CURRENT,supersedes:String?=null)=StructuredFact(id=id,subjectId="p",predicate="birthday",
        value=MemoryValue(type="DATE",month=month,precision="month"),status=status,supersedesId=supersedes,displayText="Birthday month $month")
    private fun proof(fact:String,message:String,quote:String)=FactEvidence(id="e-$fact",factId=fact,sourceMessageId=message,conversationId="c",quote=quote)

    @Test fun `date keeps month precision and rejects fabricated invalid calendar components`() {
        val value=MemoryValue(type="DATE",month=10,precision="month")
        MemoryGraphRules.validateValue(value)
        assertNull(value.day); assertNull(value.year)
        assertTrue(runCatching { MemoryGraphRules.validateValue(value.copy(month=13)) }.isFailure)
        assertTrue(runCatching { MemoryGraphRules.validateValue(value.copy(month=2,day=30)) }.isFailure)
    }

    @Test fun `graph codec preserves identities evidence jobs and vector dependencies`() {
        val fact=birthday("b",10)
        val graph=MemoryGraph(entities=listOf(entity),facts=listOf(fact),evidence=listOf(proof("b","m","October")),
            jobs=listOf(MemoryJob(id="j",sourceMessageId="m",conversationId="c")),
            vectors=listOf(MemoryVector("b",1,"model","hash",listOf(0.5f,0.5f),dependencyRevisions=mapOf("b" to 1L))))
        assertEquals(graph,MemoryGraphJson.decode(MemoryGraphJson.encode(graph)))
    }

    @Test fun `forget latest correction removes every prior revision and supporting excerpts`() {
        val first=birthday("old",10,FactStatus.SUPERSEDED)
        val second=birthday("middle",11,FactStatus.SUPERSEDED,"old")
        val latest=birthday("latest",12,supersedes="middle")
        val graph=MemoryGraph(entities=listOf(entity),facts=listOf(first,second,latest),
            evidence=listOf(proof("old","m1","October"),proof("middle","m2","November"),proof("latest","m3","December")),
            aliases=listOf(MemoryAlias(id="a",entityId="p",name="birthday",evidenceFactIds=listOf("old"))),
            vectors=listOf(MemoryVector("latest",1,"model","hash",listOf(1f))))
        val forgotten=MemoryGraphRules.forget(graph,"latest")
        assertTrue(forgotten.facts.isEmpty()); assertTrue(forgotten.evidence.isEmpty()); assertTrue(forgotten.aliases.isEmpty()); assertTrue(forgotten.vectors.isEmpty())
        assertTrue(forgotten.suppressions.any { it.factId=="old" })
        assertFalse(MemoryGraphJson.encode(forgotten).toString().contains("October"))
        assertTrue(MemoryGraphRules.isSuppressed(first,forgotten))
    }

    @Test fun `forget name anonymizes independent fact projection and cancels old source jobs`() {
        val name=StructuredFact(id="name",subjectId="p",predicate="name",value=MemoryValue(text="Aurora"),displayText="Name Aurora")
        val birthday=birthday("birthday",10).copy(displayText="Aurora birthday October")
        val graph=MemoryGraph(entities=listOf(entity),facts=listOf(name,birthday),evidence=listOf(proof("name","m1","Aurora"),proof("birthday","m2","Her birthday is October")),
            aliases=listOf(MemoryAlias(id="alias",entityId="p",name="Aurora",evidenceFactIds=listOf("name"))),
            jobs=listOf(MemoryJob(id="job",sourceMessageId="m1",conversationId="c",status=MemoryJobStatus.RUNNING)),
            vectors=listOf(MemoryVector("birthday",1,"m","h",listOf(1f),dependencyRevisions=mapOf("name" to 1L))))
        val forgotten=MemoryGraphRules.forget(graph,"name")
        assertEquals("Person",forgotten.entities.single().displayName)
        assertFalse(MemoryGraphJson.encode(forgotten).toString().contains("Aurora"))
        assertEquals(MemoryJobStatus.CANCELLED,forgotten.jobs.single().status)
        assertTrue(forgotten.vectors.isEmpty())
    }

    @Test fun `storage rejects assistant and forged legacy evidence`() {
        val fact=birthday("birthday",10)
        val graph=MemoryGraph(entities=listOf(entity),facts=listOf(fact),evidence=listOf(proof("birthday","m","October")))
        val state=KaiState(conversations=listOf(Conversation(id="c",messages=listOf(ChatMessage(id="m",role="assistant",text="October")))))
        assertTrue(runCatching { MemoryGraphRules.validateAndClean(MemoryGraph(),graph,state) }.isFailure)
        val forged=graph.copy(evidence=graph.evidence.map { it.copy(id="legacy:birthday",sourceKind="legacy") })
        assertTrue(runCatching { MemoryGraphRules.validateAndClean(MemoryGraph(),forged,state) }.isFailure)
    }

    @Test fun `old import cannot restore orphan forgotten entity metadata`() {
        val fact=birthday("birthday",10)
        val source=proof("birthday","m","October")
        val original=MemoryGraph(entities=listOf(entity),facts=listOf(fact),evidence=listOf(source))
        val forgotten=MemoryGraphRules.forget(original,"birthday")
        val proposed=forgotten.copy(entities=original.entities,facts=original.facts,evidence=original.evidence)
        val state=KaiState(memoryGraph=forgotten,conversations=listOf(Conversation(id="c",messages=listOf(ChatMessage(id="m",role="user",text="October")))))
        // Untrusted restored evidence is rejected before it can enter the index.
        assertTrue(runCatching { MemoryGraphRules.validateAndClean(forgotten,proposed,state) }.isFailure)
        val orphan=MemoryGraphRules.validateAndClean(forgotten,forgotten.copy(entities=original.entities),state)
        assertTrue(orphan.entities.isEmpty())
    }

    @Test fun `legacy corrected note keeps current projection with fresh manual provenance`() {
        val source=MemorySource(kind="conversation",ref="old-message",conversationId="old-chat",snippet="I prefer coffee.")
        val original=MemoryFact(id="note",text="I prefer coffee.",source=source,revision=1,createdAt=10,updatedAt=10)
        val corrected=original.copy(text="I prefer tea.",revision=2,updatedAt=20)
        val tomb=MemoryRules.tombstone(original,"corrected",20)
        // Older database records had no reason field and were decoded as permanent suppression.
        val legacyGraph=MemoryGraph(suppressions=listOf(GraphSuppression(factId=tomb.factId,sourceHash=tomb.sourceHash,
            deletedAt=tomb.deletedAt,revision=tomb.revision,lineageIds=tomb.lineageIds)))
        val migrated=MemoryGraphRules.migrateLegacyCorrections(KaiState(memories=listOf(corrected),memoryTombstones=listOf(tomb),memoryGraph=legacyGraph))
        val note=migrated.memories.single()
        assertEquals("manual_correction",note.source.kind)
        assertEquals("",note.source.snippet); assertEquals("",note.source.conversationId)
        assertNotEquals(source.ref,note.source.ref)
        val projected=MemoryGraphRules.projectLegacy(migrated)
        val clean=MemoryGraphRules.validateAndClean(migrated.memoryGraph,projected,migrated)
        assertEquals("I prefer tea.",clean.facts.single().value!!.text)
        assertFalse(MemoryGraphRules.isSuppressed(clean.facts.single(),clean))
        assertTrue(MemoryGraphRules.sourceBlocked(clean,"old-chat","old-message"))
        assertTrue(MemoryGraphRules.isSuppressed(clean.facts.single().copy(revision=1),clean))
        assertFalse(MemoryGraphJson.encode(clean).toString().contains("coffee"))
    }

    @Test fun `permanent forget dominates correction exception and higher revision`() {
        val fact=birthday("birthday",10).copy(revision=99)
        val graph=MemoryGraph(suppressions=listOf(GraphSuppression(factId=fact.id,revision=1,reason="corrected"),
            GraphSuppression(factId=fact.id,revision=2,reason="forgotten")))
        assertTrue(MemoryGraphRules.isSuppressed(fact,graph))
        assertTrue(MemoryGraphRules.isSuppressed(fact,MemoryGraphJson.decode(MemoryGraphJson.encode(graph))))
    }
}
