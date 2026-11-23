package com.kai.agent.data

import com.kai.agent.projects.ProjectContext
import com.kai.agent.tasks.TaskAgentEngine
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class WorkspaceRulesTest {
    private fun at(value: String)=Instant.parse(value).toEpochMilli()
    private fun routine(frequency: RoutineFrequency=RoutineFrequency.DAILY)=AgentRoutine(title="Briefing",goal="Summarize",frequency=frequency,hour=9,minute=30,zoneId="America/Chicago",nextRunAt=0)
    @Test fun attachmentContextVersionChangesOnlyForTheAllowedConversation() {
        val state=KaiState();val initial=WorkspaceRules.contextRevision(state,"","chat-a")
        val withFile=state.copy(documents=listOf(ProjectDocument(id="doc",name="file.txt",mimeType="text/plain",conversationId="chat-a",text="new")))
        assertNotEquals(initial,WorkspaceRules.contextRevision(withFile,"","chat-a"))
        assertEquals(initial,WorkspaceRules.contextRevision(withFile,"","chat-b"))
        assertNotEquals(initial,WorkspaceRules.contextRevision(state.copy(settings=state.settings.copy(useMemory=false)),"","chat-a"))
    }
    @Test fun calendarScheduleUsesSavedTimeZoneAndNextDay() {
        assertEquals(at("2026-11-17T15:30:00Z"),RoutineRules.next(routine(),at("2026-11-16T15:30:00Z")))
        assertEquals(at("2026-11-16T15:30:00Z"),RoutineRules.next(routine(),at("2026-11-16T15:29:00Z")))
    }
    @Test fun springGapMovesForwardAndFallHourIsNotRepeated() {
        assertEquals(at("2026-03-08T08:30:00Z"),RoutineRules.next(routine().copy(hour=2),at("2026-03-08T06:00:00Z")))
        assertEquals(at("2026-11-02T07:30:00Z"),RoutineRules.next(routine().copy(hour=1),at("2026-11-01T06:31:00Z")))
    }
    @Test fun weeklyAndHourlySchedulesAreStrictlyFuture() {
        assertEquals(at("2026-11-23T15:30:00Z"),RoutineRules.next(routine(RoutineFrequency.WEEKLY),at("2026-11-16T15:30:00Z")))
        assertEquals(at("2026-11-16T16:30:00Z"),RoutineRules.next(routine(RoutineFrequency.HOURLY),at("2026-11-16T15:30:00Z")))
    }
    @Test fun occurrenceIdentityIsStableAndRevisionSpecific() {
        assertEquals(RoutineRules.occurrenceId("id",1,10),RoutineRules.occurrenceId("id",1,10))
        assertNotEquals(RoutineRules.occurrenceId("id",1,10),RoutineRules.occurrenceId("id",2,10))
    }
    @Test fun documentsAndMemoryCannotCrossProjectOrConversationScopes() {
        val a=KaiProject(name="A",memory="alpha private");val b=KaiProject(name="B",memory="beta secret")
        val state=KaiState(projects=listOf(a,b),documents=listOf(
            ProjectDocument(id="a",name="a.txt",mimeType="text/plain",projectId=a.id,text="alpha shared"),
            ProjectDocument(id="b",name="b.txt",mimeType="text/plain",projectId=b.id,text="beta secret"),
            ProjectDocument(id="c",name="c.txt",mimeType="text/plain",conversationId="c1",text="chat private")))
        val prompt=ProjectContext.build(state,a.id,"different","secret",setOf("b","c"))
        assertTrue(prompt.contains("alpha private"));assertFalse(prompt.contains("beta secret"));assertFalse(prompt.contains("chat private"))
        assertEquals(listOf("c"),ProjectContext.documents(state,"","c1").map { it.id })
    }
    @Test fun exportRoundTripRetainsWorkspaceButDisarmsImportedWork() {
        val project=KaiProject(name="Fixture",memory="Keep blue")
        val chat=Conversation(projectId=project.id,messages=listOf(ChatMessage(role="user",text="Read file",documentIds=listOf("d"),includeImages=true)))
        val run=AgentRun(title="Work",goal="Help",projectId=project.id,conversationId=chat.id,status=RunStatus.RUNNING,lease="old",steps=listOf(RunStep(title="First",instructions="Read")))
        val state=KaiState(projects=listOf(project),conversations=listOf(chat),runs=listOf(run),routines=listOf(routine()),documents=listOf(ProjectDocument(id="d",name="d.txt",mimeType="text/plain",projectId=project.id,text="blue")))
        val decoded=KaiJson.decode(KaiJson.encode(state));assertEquals(state.runs,decoded.runs);assertEquals(state.documents,decoded.documents);assertEquals(state.conversations,decoded.conversations)
        val imported=WorkspaceRules.imported(KaiState(conversations=listOf(chat)),decoded)
        assertEquals(RunStatus.PAUSED,imported.runs.single().status);assertFalse(imported.routines.single().enabled)
    }
    @Test fun localDeletesBlockBackupResurrectionAndIncomingDeletesDoNotInvalidateLiveProjects() {
        val project=KaiProject(id="p",name="Project");val chat=Conversation(projectId="p")
        val incoming=KaiState(projects=listOf(project),conversations=listOf(chat),documents=listOf(ProjectDocument(name="a",mimeType="text/plain",projectId="p",text="x")))
        val merged=WorkspaceRules.imported(KaiState(deletedProjectIds=listOf("p"),conversations=listOf(chat)),incoming)
        assertTrue(merged.projects.isEmpty());assertTrue(merged.documents.isEmpty());assertTrue(merged.conversations.isEmpty())
        val live=WorkspaceRules.imported(KaiState(projects=listOf(project)),KaiState(deletedProjectIds=listOf("p")))
        WorkspaceRules.validate(live);assertEquals("p",live.projects.single().id)
    }
    @Test fun recoverNeverRearmsPausedOrApprovalWorkAndRejectsStaleLease() {
        val run=AgentRun(title="Task",goal="goal",conversationId="c",status=RunStatus.RUNNING,lease="old")
        assertEquals(RunStatus.QUEUED,RunRules.recover(run).status)
        assertEquals(RunStatus.PAUSED,RunRules.recover(run.copy(status=RunStatus.PAUSED)).status)
        assertThrows(IllegalStateException::class.java) { RunRules.requireOwner(run,"stale") }
    }
    @Test fun plannerRejectsOversizedAndEmptyPlans() {
        assertEquals(2,TaskAgentEngine.parsePlan("""{"steps":[{"title":"Read","instructions":"Read the file"},{"title":"Summarize","instructions":"Summarize findings"}]}""").size)
        assertThrows(IllegalArgumentException::class.java) { TaskAgentEngine.parsePlan("""{"steps":[]}""") }
        assertThrows(IllegalArgumentException::class.java) { TaskAgentEngine.parsePlan("""{"steps":[{"title":"","instructions":""}]}""") }
    }
}
