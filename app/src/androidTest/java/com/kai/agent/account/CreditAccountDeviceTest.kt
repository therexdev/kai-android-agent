package com.kai.agent.account
import android.app.Application
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kai.agent.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
@RunWith(AndroidJUnit4::class)
class CreditAccountDeviceTest {
 private class App(base:Context):Application(){val directory=File(base.noBackupFilesDir,"account-${UUID.randomUUID()}").apply{mkdirs()};init{attachBaseContext(base)};override fun getApplicationContext():Context=this;override fun getNoBackupFilesDir():File=directory}
 @Test fun sessionAndPendingLoginSurviveEncryptedStorageButNeverExport()= runBlocking {
  val app=App(InstrumentationRegistry.getInstrumentation().targetContext);var store=KaiStore(app)
  try {
   store.load();val account=CreditAccount(token="account-device-secret-fixture",id="alice",email="alice@example.test",model="fixture",linkSecret="pending-link-secret-fixture",linkCode="ABCD-2345",linkExpiresAt=9999999999999)
   store.updateSettings{it.copy(creditAccount=account)};val chat=store.createConversation();store.appendMessage(chat.id,"user","local-workspace-marker")
   store.remember("Local memory marker")
   val trace=store.beginChatTrace(chat.id,"fixture")!!;trace.record("credentials",JSONObject().put("text",account.token+account.linkSecret))
   store.close();store=KaiStore(app);store.load();assertEquals(account,store.state.value.settings.creditAccount)
   assertFalse(store.exportChatReport(chat.id).contains(account.token));assertFalse(store.exportChatReport(chat.id).contains(account.linkSecret))
   val db=File(app.directory,"kai-state/memory.db").readBytes().toString(Charsets.ISO_8859_1);assertFalse(db.contains(account.token));assertFalse(db.contains(account.linkSecret))
   val exported=store.exportJson();assertFalse(exported.contains(account.token));assertFalse(exported.contains(account.linkSecret))
   store.updateSettings{it.copy(creditAccount=CreditAccount())};store.close();store=KaiStore(app);store.load()
   assertEquals("",store.state.value.settings.creditAccount.token);assertTrue(store.state.value.conversations.any{it.id==chat.id});assertTrue(store.state.value.memories.isNotEmpty())
   store.importJson(exported);assertEquals("",store.state.value.settings.creditAccount.token)
  }finally{store.close();app.directory.deleteRecursively()}
 }
}
