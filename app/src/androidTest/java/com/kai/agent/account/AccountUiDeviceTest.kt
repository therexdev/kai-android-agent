package com.kai.agent.account
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kai.agent.ui.*
import org.junit.*
import org.junit.runner.RunWith
import java.lang.reflect.Proxy
import java.io.File
@RunWith(AndroidJUnit4::class)
class AccountUiDeviceTest {
 @get:Rule val compose=createComposeRule()
 private val actions=Proxy.newProxyInstance(KaiUiActions::class.java.classLoader,arrayOf(KaiUiActions::class.java)){p,m,a->when(m.name){"equals"->p===a?.get(0);"hashCode"->System.identityHashCode(p);"toString"->"AccountFixture";else->null}} as KaiUiActions
 @Test fun accountOpensFromToolbarAndSignInStaysVisibleOnSmallScreen(){
  compose.setContent{KaiApp(KaiUiState(reducedMotion=true),actions)}
  compose.onNodeWithText("Account").assertIsDisplayed().performClick()
  compose.onNodeWithText("Sign in / Create account").assertIsDisplayed()
  compose.onNodeWithText("API address").assertDoesNotExist()
  val image=compose.onNodeWithText("Your account").onParent().onParent().captureToImage().asAndroidBitmap()
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  File(context.filesDir,"account-sign-in.png").outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
 }
}
