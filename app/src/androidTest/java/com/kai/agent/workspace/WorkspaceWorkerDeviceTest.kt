package com.kai.agent.workspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.*
import com.kai.agent.data.*
import com.kai.agent.tasks.AgentRunWorker
import com.kai.agent.upgrade.UpgradeFixture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceWorkerDeviceTest {
    @Test fun realWorkManagerExecutesAndPersistsPlanAndStepsWithMockInference()=runBlocking<Unit> {
        UpgradeFixture.requireEmulator();UpgradeFixture.backupProfile()
        val context=UpgradeFixture.context;val handle=KaiStores.acquire(context);val store=handle.store
        val server=MockWebServer();var runId="";var answerTurns=0
        server.dispatcher=object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val messages=JSONObject(request.body.readUtf8()).getJSONArray("messages")
                assertFalse(messages.toString().contains("rejected-fragment"))
                val planning=messages.getJSONObject(0).getString("content").startsWith("Plan the user's task")
                val text=if (!planning && ++answerTurns % 2 == 1) "{\"type\":\"reply\",\"text\":\"rejected-fragment" else if(planning) """{"steps":[{"title":"Read","instructions":"Read the fixture"},{"title":"Summarize","instructions":"Summarize the fixture"}]}""" else """{"type":"reply","text":"Verified mock result"}"""
                return MockResponse().setHeader("Content-Type","application/json").setBody(JSONObject().put("choices",org.json.JSONArray().put(JSONObject().put("message",JSONObject().put("content",text)).put("finish_reason","stop"))).toString())
            }
        }
        server.start()
        try {
            store.load();store.updateSettings { it.copy(selectedProvider=Provider.OPENAI,openai=ProviderProfile(server.url("/v1").toString(),"fixture-key","fixture-model"),memoryMode=MemoryMode.OFF,composioApiKey="") }
            val run=store.createRun("Worker fixture","Read and summarize this fixture");runId=run.id
            // This fixture's endpoint is on the device itself. The emulator has no validated
            // Internet, so test the real worker with an unconstrained local-only request.
            // Production TaskScheduler retains its CONNECTED constraint (separately observed
            // correctly holding the original request while isValidated=false).
            val work=OneTimeWorkRequestBuilder<AgentRunWorker>().setInputData(workDataOf("id" to run.id)).build()
            WorkManager.getInstance(context).enqueueUniqueWork("kai-run:${run.id}",ExistingWorkPolicy.KEEP,work).result.get()
            val finished=withTimeout(120000) { store.state.first { state -> state.runs.firstOrNull { it.id==run.id }?.status in setOf(RunStatus.COMPLETED,RunStatus.FAILED) } }.runs.first { it.id==run.id }
            assertEquals(finished.detail,RunStatus.COMPLETED,finished.status);assertEquals(2,finished.steps.size);assertEquals(5,server.requestCount)
            assertEquals(2,store.state.value.conversations.first { it.id==run.conversationId }.messages.count { it.role=="assistant" })
            withTimeout(30000) { while(WorkManager.getInstance(context).getWorkInfosForUniqueWork("kai-run:${run.id}").get().any { !it.state.isFinished })delay(200) }
        } finally {
            if(runId.isNotBlank())WorkManager.getInstance(context).cancelUniqueWork("kai-run:$runId").result.get()
            handle.close();server.shutdown();UpgradeFixture.restoreProfile()
        }
    }
}
