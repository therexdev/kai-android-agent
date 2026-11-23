package com.kai.agent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** A bounded entry point; long proposals never compete with the chat's fixed controls. */
@Composable
internal fun ApprovalCard(
    approval: KaiApproval,
    actions: KaiUiActions,
    modifier: Modifier = Modifier,
    autoOpen: Boolean = false,
) {
    // A changed proposal must be reviewed afresh. Closing the review leaves it pending.
    var reviewing by rememberSaveable(approval) { mutableStateOf(autoOpen) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(reviewing) {
        if (reviewing) { focus.clearFocus(); keyboard?.hide() }
    }
    Surface(modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),
        color=MaterialTheme.colorScheme.primaryContainer.copy(alpha=.4f),
        border=BorderStroke(1.dp,MaterialTheme.colorScheme.primary.copy(alpha=.25f))) {
        Row(Modifier.padding(horizontal=14.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Text("Waiting for your approval",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary,
                    maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(approval.title,style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            OutlinedButton(onClick={reviewing=true},modifier=Modifier.testTag("approval-review-button")) {
                Text("Review action")
            }
        }
    }
    if (reviewing) key(approval) { Dialog(
        onDismissRequest={reviewing=false},
        properties=DialogProperties(dismissOnBackPress=true,dismissOnClickOutside=false,
            usePlatformDefaultWidth=false,decorFitsSystemWindows=false),
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().imePadding(),
            contentAlignment=Alignment.Center) {
            ApprovalReviewContent(approval,
                onClose={reviewing=false},
                onApprove={reviewing=false;actions.approveAction(approval.id)},
                onDecline={reviewing=false;actions.denyAction(approval.id)},
                modifier=Modifier.widthIn(max=720.dp).fillMaxSize())
        }
    } }
}

/** Details scroll independently. Only the two explicit decision buttons change action state. */
@Composable
internal fun ApprovalReviewContent(
    approval: KaiApproval,
    onClose: () -> Unit,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.testTag("approval-review")) {
        Row(Modifier.fillMaxWidth().padding(start=20.dp,end=8.dp,top=4.dp,bottom=4.dp),
            verticalAlignment=Alignment.CenterVertically) {
            Text("Review action",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
            IconButton(onClick=onClose) { KaiIcon(KaiGlyph.CLOSE,label="Close review; keep action pending") }
        }
        HorizontalDivider()
        Column(Modifier.weight(1f).fillMaxWidth().testTag("approval-review-details")
            .verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(approval.title,style=MaterialTheme.typography.titleLarge)
            Text(approval.description,style=MaterialTheme.typography.bodyMedium)
            Text("Closing this review leaves the action waiting. Only Approve runs it.",
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(approval.account.isNotBlank()) Text("Account: ${approval.account}",style=MaterialTheme.typography.bodyMedium)
            if(approval.tool.isNotBlank()) Text("Action: ${approval.tool}",style=MaterialTheme.typography.bodySmall)
            if(approval.arguments.isNotBlank()) Surface(shape=RoundedCornerShape(12.dp),
                color=MaterialTheme.colorScheme.surfaceContainer,modifier=Modifier.fillMaxWidth()) {
                SelectionContainer { Text(approval.arguments,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(12.dp)) }
            }
        }
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Button(onClick=onApprove,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("approval-approve")) {
                Text("Approve")
            }
            OutlinedButton(onClick=onDecline,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("approval-decline")) {
                Text("Decline")
            }
        }
    }
}
