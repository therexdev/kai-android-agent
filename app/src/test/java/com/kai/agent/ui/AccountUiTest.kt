package com.kai.agent.ui
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.kai.agent.account.*
import java.lang.reflect.Proxy
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],qualifiers="w360dp-h640dp")
@LooperMode(LooperMode.Mode.PAUSED)
class AccountUiTest {
 @get:Rule val compose=createComposeRule()
 private val calls=mutableListOf<String>()
 private val arguments=mutableListOf<Pair<String,List<Any?>>>()
 private val actions=Proxy.newProxyInstance(KaiUiActions::class.java.classLoader,arrayOf(KaiUiActions::class.java)){p,m,a->when(m.name){"equals"->p===a?.get(0);"hashCode"->System.identityHashCode(p);"toString"->"AccountActions";else->{calls+=m.name;arguments+=m.name to a.orEmpty().toList();null}}} as KaiUiActions
 @Test fun signInIsVisibleAndDoesNotAskForAnApiKey(){
  compose.setContent{KaiTheme(true){AccountContent(KaiUiState(),actions){}}}
  compose.onNodeWithText("Sign in / Create account").assertIsDisplayed().performClick()
  Assert.assertTrue(calls.contains("accountSignIn"));compose.onNodeWithText("API key").assertDoesNotExist()
 }
 @Test fun accountRemainsScrollableAndPricesAndWalletActionsAreVisible(){
  val o=AccountOverview("alice","alice@example.test",listOf("wallet-A"),"wallet-A","100000","200000","available",listOf(CreditModel("model","Better model",true,true,2048,32768,"10","20")),vaultAvailable=true)
  val state=KaiUiState(account=AccountUi(true,"alice@example.test",overview=o,wallet="wallet-A"))
  compose.setContent{KaiTheme(true){AccountContent(state,actions){}}}
  compose.onNodeWithText("Purchased: 2").assertExists()
  compose.onNodeWithTag("account-model-selector").performScrollTo().performClick()
  Assert.assertFalse(calls.contains("accountSelectModel"))
  compose.onNodeWithText("Better model").performClick();Assert.assertTrue(calls.contains("accountSelectModel"))
  compose.onNodeWithText("Connect KOIN Vault").performScrollTo().assertIsDisplayed().performClick();Assert.assertTrue(calls.contains("accountWebsite"))
  compose.onNodeWithText("Buy credits").performScrollTo().assertIsDisplayed()
 }
 @Test fun settingsHaveNoManualInferenceConfiguration(){
  compose.setContent{KaiTheme(true){SettingsContent(KaiUiState(),actions,{})}}
  compose.onNodeWithText("API address").assertDoesNotExist();compose.onNodeWithText("Test API").assertDoesNotExist();compose.onNodeWithText("Save AI settings").assertDoesNotExist()
 }
 @Test fun automaticConnectedAccountsCanEnablePurchasedCreditsWithoutManualWalletSelection(){
  val o=AccountOverview("alice","alice@example.test",listOf("wallet-A"),"wallet-A","0","200000000","available",emptyList(),primaryCreditWallet="wallet-A",walletLabels=mapOf("wallet-A" to "Core credits"))
  compose.setContent{KaiTheme(true){AccountContent(KaiUiState(account=AccountUi(true,"alice@example.test",overview=o)),actions){}}}
  compose.onNodeWithText("Purchased: 2000").assertExists()
  compose.onNodeWithTag("account-paid-credits").performScrollTo().assertIsEnabled().assertIsOff().performClick()
  Assert.assertTrue(arguments.contains("accountFreeOnly" to listOf(false)))
  compose.onNodeWithTag("account-credit-source").performScrollTo().performClick()
  compose.onNodeWithText("Core credits · Primary").performClick()
  Assert.assertTrue(arguments.contains("accountSelectWallet" to listOf("wallet-A")))
 }
 @Test fun purchasedCreditsCannotBeEnabledWithoutAConnectedCreditAccount(){
  val o=AccountOverview("alice","alice@example.test",emptyList(),"","100000","0","available",emptyList())
  compose.setContent{KaiTheme(true){AccountContent(KaiUiState(account=AccountUi(true,"alice@example.test",overview=o)),actions){}}}
  compose.onNodeWithTag("account-paid-credits").performScrollTo().assertIsNotEnabled().assertIsOff()
 }
}
