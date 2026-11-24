package com.kai.agent.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],qualifiers="w360dp-h640dp")
@LooperMode(LooperMode.Mode.PAUSED)
class ActionReviewTest {
    @get:Rule val compose=createComposeRule()
    private val approval=KaiApproval("task-one","Create and Populate Table in Google Doc",
        "Review this exact action. Changing instructions cancels this proposal.",
        account="Fixture Google Docs account",tool="GOOGLEDOCS_FIXTURE",
        arguments=(1..200).joinToString("\n") { "Field $it: supplied document content" })

    @Test fun longDetailsAndLargeTextKeepBothButtonsFullyVisibleInShortViewport() {
        val decisions=mutableListOf<String>()
        compose.setContent {
            val density=LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density,2f)) {
                KaiTheme(true) {
                    ApprovalReviewContent(approval,onClose={decisions+="close"},onApprove={decisions+="approve"},
                        onDecline={decisions+="decline"},modifier=Modifier.size(320.dp,320.dp))
                }
            }
        }
        assertControlsInsidePanel()
        val before=compose.onNodeWithTag("approval-approve").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("approval-review-details").performTouchInput { swipeUp() }
        assertControlsInsidePanel()
        assertEquals(before,compose.onNodeWithTag("approval-approve").fetchSemanticsNode().boundsInRoot)
        assertTrue(decisions.isEmpty())
        compose.onNodeWithTag("approval-approve").performClick()
        assertEquals(listOf("approve"),decisions)
    }

    @Test fun detailTapsAndClosingNeverApproveOrDeclineAndReviewCanReopen() {
        val calls=mutableListOf<String>()
        compose.setContent { KaiTheme(true) { ApprovalCard(approval,actions(calls),autoOpen=true) } }
        compose.onNodeWithTag("approval-review-details").performTouchInput { click(Offset(2f,2f)) }
        assertTrue(calls.isEmpty())
        compose.onNodeWithContentDescription("Close review; keep action pending").performClick()
        compose.onNodeWithTag("approval-review").assertDoesNotExist()
        assertTrue(calls.isEmpty())
        compose.onNodeWithTag("approval-review-button").performClick()
        // Exercise the open Dialog's Android Back handler, not the host Activity's
        // synthetic input route. No app callback is invoked directly by this test.
        compose.runOnIdle { requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog()).onBackPressed() }
        compose.onNodeWithTag("approval-review").assertDoesNotExist()
        assertTrue(calls.isEmpty())
        compose.onNodeWithTag("approval-review-button").performClick()
        compose.onNodeWithTag("approval-decline").performClick()
        assertEquals(listOf("denyAction:task-one"),calls)
    }

    @Test fun taskCardsRequireOpeningReviewAndApproveOnlyTheDisplayedProposal() {
        val calls=mutableListOf<String>()
        val selected=mutableStateOf(approval)
        compose.setContent { KaiTheme(true) { ApprovalCard(selected.value,actions(calls)) } }
        compose.onNodeWithTag("approval-review").assertDoesNotExist()
        compose.onNodeWithTag("approval-review-button").performClick()
        compose.runOnIdle { selected.value=approval.copy(id="task-two",title="Second proposal",arguments="different content") }
        // A changed proposal closes a manually opened review until it is opened afresh.
        compose.onNodeWithTag("approval-review").assertDoesNotExist()
        compose.onNodeWithTag("approval-review-button").performClick()
        compose.onNodeWithTag("approval-approve").performClick()
        assertEquals(listOf("approveAction:task-two"),calls)
    }

    @Test fun restoringReviewStateNeverSubmitsAndPreservesAnExplicitClose() {
        val calls=mutableListOf<String>()
        val restoration=StateRestorationTester(compose)
        restoration.setContent { KaiTheme(true) { ApprovalCard(approval,actions(calls),autoOpen=true) } }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("approval-approve").assertIsDisplayed()
        assertTrue(calls.isEmpty())
        compose.onNodeWithContentDescription("Close review; keep action pending").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("approval-review").assertDoesNotExist()
        compose.onNodeWithTag("approval-review-button").assertIsDisplayed()
        assertTrue(calls.isEmpty())
    }

    @Test fun changedAutomaticReviewShowsNewDetailsFromTheTopBeforeDecision() {
        val calls=mutableListOf<String>()
        val selected=mutableStateOf(approval)
        compose.setContent { KaiTheme(true) { ApprovalCard(selected.value,actions(calls),autoOpen=true) } }
        compose.onNodeWithTag("approval-review-details").performTouchInput { swipeUp() }
        compose.runOnIdle { selected.value=approval.copy(id="replacement",title="Replacement proposal") }
        compose.onNode(hasText("Replacement proposal") and hasAnyAncestor(hasTestTag("approval-review-details"))).assertIsDisplayed()
        assertTrue(calls.isEmpty())
        compose.onNodeWithTag("approval-approve").performClick()
        assertEquals(listOf("approveAction:replacement"),calls)
    }

    @Test fun displayLabelRefreshDoesNotCloseOrReopenAnExactReview() {
        val calls=mutableListOf<String>()
        val selected=mutableStateOf(approval.copy(reviewKey="exact-intent-one"))
        compose.setContent { KaiTheme(true) { ApprovalCard(selected.value,actions(calls),autoOpen=true) } }
        compose.runOnIdle { selected.value=selected.value.copy(account="Refreshed display label") }
        compose.onNodeWithTag("approval-review").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close review; keep action pending").performClick()
        compose.runOnIdle { selected.value=selected.value.copy(account="Another display refresh") }
        compose.onNodeWithTag("approval-review").assertDoesNotExist()
        assertTrue(calls.isEmpty())
        compose.runOnIdle { selected.value=selected.value.copy(arguments="Changed reviewed arguments",reviewKey="exact-intent-two") }
        compose.onNodeWithTag("approval-review").assertIsDisplayed()
        assertTrue(calls.isEmpty())
    }

    private fun assertControlsInsidePanel() {
        val panel=compose.onNodeWithTag("approval-review").getUnclippedBoundsInRoot()
        for(tag in listOf("approval-approve","approval-decline")) {
            val bounds=compose.onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("$tag clipped at top",bounds.top>=panel.top)
            assertTrue("$tag clipped at bottom",bounds.bottom<=panel.bottom)
            assertTrue("$tag has no full touch target",bounds.bottom-bounds.top>=48.dp)
        }
    }

    private fun actions(calls: MutableList<String>)=Proxy.newProxyInstance(KaiUiActions::class.java.classLoader,
        arrayOf(KaiUiActions::class.java)) { proxy,method,args ->
        when(method.name) {
            "approveAction","denyAction" -> { calls+="${method.name}:${args!![0]}";null }
            "equals" -> proxy===args?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "Action review fixture"
            else -> null
        }
    } as KaiUiActions
}
