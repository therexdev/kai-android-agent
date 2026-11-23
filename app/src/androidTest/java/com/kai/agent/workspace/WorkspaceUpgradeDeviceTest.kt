package com.kai.agent.workspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kai.agent.data.*
import com.kai.agent.upgrade.UpgradeFixture
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SeedWorkspace031DeviceTest {
    @Test fun seedPreviousVersionThroughItsInstalledCodec()=runBlocking<Unit> {
        UpgradeFixture.requireEmulator();UpgradeFixture.backupProfile()
        val context=UpgradeFixture.context
        assertEquals("0.3.1-alpha",context.packageManager.getPackageInfo(context.packageName,0).versionName)
        val store=KaiStore(context)
        try {
            store.load();val root=JSONObject(KaiJson.encode(store.state.value))
            root.getJSONObject("settings").put("speechRate",1.15).put("memoryMode","OFF")
            val decoded=KaiJson.decode(root.toString());store.updateSettings { decoded.settings }
            store.remember("Workspace upgrade fixture retained")
            store.updateCharacter("kai") { it.copy(voiceId="kai:azelma:cute") }
        } finally { store.close() }
    }
}
@RunWith(AndroidJUnit4::class)
class VerifyWorkspace040DeviceTest {
    @Test fun upgradePreservesDesktopVoiceMemoryAndSettings()=runBlocking<Unit> {
        UpgradeFixture.requireEmulator();val context=UpgradeFixture.context
        assertEquals("0.4.0-alpha",context.packageManager.getPackageInfo(context.packageName,0).versionName)
        val store=KaiStore(context)
        try { store.load();assertTrue(store.state.value.memories.any { it.text=="Workspace upgrade fixture retained" })
            assertEquals("kai:azelma:cute",store.state.value.characters.first { it.id=="kai" }.voiceId)
            assertEquals(1.15f,store.state.value.settings.speechRate,.001f)
            assertTrue(store.state.value.projects.isEmpty());assertTrue(store.state.value.runs.isEmpty())
        } finally { store.close();UpgradeFixture.restoreProfile() }
    }
}
