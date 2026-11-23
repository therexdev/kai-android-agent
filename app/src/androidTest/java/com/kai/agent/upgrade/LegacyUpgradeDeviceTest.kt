package com.kai.agent.upgrade

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kai.agent.data.KaiStore
import com.kai.agent.data.TaskStatus
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run against the archived 0.1.1 APK, before install -r of 0.2.0. No Activity/network starts. */
@RunWith(AndroidJUnit4::class)
class SeedLegacy011DeviceTest {
    @Test fun seedAndReadWithInstalledLegacyCodec() = runBlocking {
        val context = UpgradeFixture.context
        UpgradeFixture.requireEmulator()
        assertEquals("0.1.1-alpha", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
        UpgradeFixture.backupProfile()
        val plaintext = InstrumentationRegistry.getInstrumentation().context.assets
            .open("legacy-state-v011.json").use { it.readBytes() }
        UpgradeFixture.encryptProfile(plaintext)
        plaintext.fill(0)

        // These public getters/load signatures are shared with 0.1.1. The implementation loaded
        // here comes from the INSTALLED OLD APK, not from this instrumentation APK.
        val oldStore = KaiStore(context)
        oldStore.load()
        val state = oldStore.state.value
        assertEquals(UpgradeFixture.FACT, state.memories.single().text)
        assertEquals(UpgradeFixture.INFERENCE_KEY, state.settings.openai.apiKey)
        assertEquals(UpgradeFixture.LEGACY_OWNER, state.settings.accountId)
        assertEquals("", state.settings.connectionsSessionToken)
        assertEquals(UpgradeFixture.COMPOSIO_KEY, state.settings.composioApiKey)
        assertEquals(UpgradeFixture.DIRECT_USER, state.settings.composioUserId)
        assertEquals(TaskStatus.DRAFT, state.tasks.first { it.id.endsWith("ready") }.status)
        assertEquals(TaskStatus.UNKNOWN, state.tasks.first { it.id.endsWith("running") }.status)
        // RUNNING recovery makes the real 0.1.1 codec write its canonical encrypted payload.
        val raw = File(context.noBackupFilesDir, "kai-state/state.enc").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains(UpgradeFixture.FACT))
        assertFalse(raw.contains(UpgradeFixture.INFERENCE_KEY))
        File(UpgradeFixture.backupDirectory(), "old-version-verified").writeText("0.1.1 codec verified")
    }
}

/** Run only AFTER an in-place APK upgrade, with the same package UID and Keystore. */
@RunWith(AndroidJUnit4::class)
class VerifyUpgrade020DeviceTest {
    @Test fun inPlaceUpgradePreservesProfileAndMigratesToEncryptedDatabase() = runBlocking {
        UpgradeFixture.requireEmulator()
        val context = UpgradeFixture.context
        assertEquals("0.2.0-alpha", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
        assertTrue(File(UpgradeFixture.backupDirectory(), "old-version-verified").exists())
        val store = KaiStore(context)
        store.load()
        val state = store.state.value
        assertEquals(UpgradeFixture.FACT, state.memories.single().text)
        assertEquals(2L, state.memories.single().revision)
        assertTrue(state.memories.single().pinned)
        assertEquals("upgrade-message", state.memories.single().source.ref)
        assertEquals("upgrade-forgotten", state.memoryTombstones.single().factId)
        assertEquals(UpgradeFixture.INFERENCE_KEY, state.settings.openai.apiKey)
        assertEquals("kai-local-test", state.settings.openai.model)
        assertEquals("http://10.0.2.2:18765/v1", state.settings.openai.endpoint)
        assertEquals(UpgradeFixture.DIRECT_USER, state.settings.composioUserId)
        assertEquals(UpgradeFixture.COMPOSIO_KEY, state.settings.composioApiKey)
        val ready = state.tasks.first { it.id.endsWith("ready") }
        assertEquals(TaskStatus.DRAFT, ready.status)
        assertEquals("", ready.approvedFingerprint)
        assertEquals(TaskStatus.UNKNOWN, state.tasks.first { it.id.endsWith("running") }.status)
        val directory = File(context.noBackupFilesDir, "kai-state")
        assertTrue(File(directory, "memory.db").isFile)
        assertTrue(File(directory, "database-key.enc").isFile)
        assertTrue("Legacy encrypted recovery input must survive migration", File(directory, "state.enc").isFile)
        assertTrue(state.memoryGraph.facts.any { it.id == "upgrade-memory" && it.displayText == UpgradeFixture.FACT })
        assertTrue(state.memoryGraph.evidence.any { it.sourceMessageId == "upgrade-message" })
        directory.walkTopDown().filter { it.isFile }.forEach { file ->
            val raw = file.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(raw.contains(UpgradeFixture.FACT))
            assertFalse(raw.contains(UpgradeFixture.INFERENCE_KEY))
            assertFalse(raw.contains(UpgradeFixture.COMPOSIO_KEY))
        }
        store.close()
        val reopened = KaiStore(context).also { it.load() }
        assertEquals(UpgradeFixture.DIRECT_USER, reopened.state.value.settings.composioUserId)
        assertEquals(state.memoryGraph, reopened.state.value.memoryGraph)
        reopened.close()
        File(UpgradeFixture.backupDirectory(), "upgrade-verified").writeText("0.2.0 SQLCipher upgrade verified")
    }
}

internal object UpgradeFixture {
    const val FACT = "I prefer upgrade-safe memory."
    const val INFERENCE_KEY = "upgrade-openai-fixture-key"
    const val LEGACY_OWNER = "legacy-owner-42"
    const val DIRECT_USER = "kai:legacy-owner-42"
    const val COMPOSIO_KEY = "composio-local-fixture-key"
    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    fun backupDirectory() = File(context.noBackupFilesDir, "kai-upgrade-020-backup")
    private fun profileDirectory() = File(context.noBackupFilesDir, "kai-state")

    fun requireEmulator() {
        assumeTrue("Only run this fixture on an isolated emulator.", Build.HARDWARE in setOf("ranchu", "goldfish") ||
            Build.FINGERPRINT.contains("generic", true) || Build.MODEL.contains("emulator", true))
    }

    fun backupProfile() {
        val backup = backupDirectory()
        if (!backup.exists()) {
            check(backup.mkdirs())
            if (profileDirectory().exists()) profileDirectory().copyRecursively(File(backup, "profile"))
            else File(backup, "profile.absent").writeText("absent")
        }
        check(!profileDirectory().exists() || profileDirectory().deleteRecursively())
    }

    fun restoreProfile() {
        val backup = backupDirectory()
        if (!backup.exists()) return
        check(!profileDirectory().exists() || profileDirectory().deleteRecursively())
        File(backup, "profile").takeIf { it.exists() }?.copyRecursively(profileDirectory(), overwrite = true)
        check(backup.deleteRecursively())
    }

    fun encryptProfile(plaintext: ByteArray) {
        val alias = context.packageName + ".kai.state.aes.v1"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = keyStore.getKey(alias, null) as? SecretKey ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).setKeySize(256).build())
            }.generateKey()
        val header = byteArrayOf(75, 65, 73, 1)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key); updateAAD(header) }
        check(profileDirectory().mkdirs())
        val file = AtomicFile(File(profileDirectory(), "state.enc"))
        val stream = file.startWrite()
        try { stream.write(header); stream.write(cipher.iv); stream.write(cipher.doFinal(plaintext)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
}
