package com.kai.agent.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun TasksScreen(state:KaiUiState,actions:KaiUiActions,onTalk:()->Unit) {
    val uriHandler=LocalUriHandler.current
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item {SectionHeader("A little follow-through","Things in motion","A clear view of what KAI is doing, and what needs you.")}
        if(state.pendingApproval!=null) item {ApprovalCard(state.pendingApproval,actions)}
        if(state.tasks.isEmpty()) item {EmptyState(KaiGlyph.TASKS,"Big plans start small.","Ask KAI to help with a task. Its real progress and results will appear here.","Start a conversation",onTalk)}
        items(state.tasks,key={it.id}) {task ->
            val cancellable=task.status in listOf(KaiTaskStatus.QUEUED,KaiTaskStatus.WAITING,KaiTaskStatus.NEEDS_APPROVAL)
            val safeResult=task.resultUrl.takeIf{url->runCatching{val parsed=android.net.Uri.parse(url);parsed.scheme=="https" && !parsed.host.isNullOrBlank()}.getOrDefault(false)}
            val color=when(task.status){KaiTaskStatus.COMPLETED->MaterialTheme.colorScheme.secondary;KaiTaskStatus.FAILED->MaterialTheme.colorScheme.error;KaiTaskStatus.UNKNOWN->MaterialTheme.colorScheme.onSurfaceVariant;else->MaterialTheme.colorScheme.primary}
            SoftCard {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {Box(Modifier.size(6.dp).clip(CircleShape).background(color));Text(task.status.name.replace('_',' ').lowercase().replaceFirstChar{it.uppercase()},style=MaterialTheme.typography.labelMedium,color=color)}
                Spacer(Modifier.height(12.dp));Text(task.title,style=MaterialTheme.typography.titleMedium)
                if(task.detail.isNotBlank()){Spacer(Modifier.height(8.dp));Text(task.detail,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                if(task.status==KaiTaskStatus.ACCEPTED) {Spacer(Modifier.height(8.dp));Text("Request accepted. Completion has not been confirmed.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                if(task.status==KaiTaskStatus.UNKNOWN) {Spacer(Modifier.height(8.dp));Text("Result could not be verified. Check the connected service before trying again.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                if(task.receiptId.isNotBlank()){Spacer(Modifier.height(12.dp));Text("RECEIPT",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant);androidx.compose.foundation.text.selection.SelectionContainer {Text(task.receiptId,style=MaterialTheme.typography.bodySmall)}}
                if(safeResult!=null){Spacer(Modifier.height(8.dp));TextButton(onClick={runCatching{uriHandler.openUri(safeResult)}},contentPadding=PaddingValues(0.dp)){Text("Open result",style=MaterialTheme.typography.labelMedium);Spacer(Modifier.width(6.dp));KaiIcon(KaiGlyph.ARROW,Modifier.size(15.dp),MaterialTheme.colorScheme.primary)}}
                if(cancellable){Spacer(Modifier.height(8.dp));TextButton(onClick={actions.cancelTask(task.id)},contentPadding=PaddingValues(0.dp)){Text("Cancel task",style=MaterialTheme.typography.labelMedium)}}
            }
        }
        item {Spacer(Modifier.height(12.dp))}
    }
}

@Composable
internal fun MemoryScreen(state:KaiUiState,actions:KaiUiActions) {
    var editing by remember {mutableStateOf<KaiMemory?>(null)}
    var adding by rememberSaveable {mutableStateOf(false)}
    var deleting by remember {mutableStateOf<KaiMemory?>(null)}
    var query by rememberSaveable {mutableStateOf("")}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item {SectionHeader("The things that matter","A little more you.","Give KAI the context worth keeping. Your memories stay visible, editable, and yours.")}
        if(state.memoryNotice!=null) item {MemorySavedNotice(state.memoryNotice,actions)}
        item {MemoryControls(state,actions)}
        item {SoftCard {
            Text("Memory location",style=MaterialTheme.typography.labelLarge)
            Text("Personal and project memories are kept separately. Choose a location to find saved details.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            state.memoryScopes.forEach {scope ->
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    RadioButton(selected=state.memoryScope==scope.id,onClick={query="";actions.selectMemoryScope(scope.id)})
                    TextButton(onClick={query="";actions.selectMemoryScope(scope.id)}) {Text("${scope.name} (${scope.count})")}
                }
            }
        }}
        if(state.pendingMemories.isNotEmpty()) {
            item {Text("WAITING FOR YOUR SAY",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.secondary,modifier=Modifier.padding(top=8.dp))}
            items(state.pendingMemories,key={"suggestion-${it.id}"}) {suggestion->MemorySuggestionCard(suggestion,actions)}
        }
        item {SoftCard {Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(13.dp)){Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.secondaryContainer),contentAlignment=Alignment.Center){KaiIcon(KaiGlyph.MEMORY,tint=MaterialTheme.colorScheme.secondary)};Column(Modifier.weight(1f)){Text("${state.memories.size} saved ${if(state.memories.size==1) "memory" else "memories"}",style=MaterialTheme.typography.titleMedium);Text("Shared across characters in this location",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};FilledIconButton(onClick={adding=true},modifier=Modifier.size(40.dp)){KaiIcon(KaiGlyph.PLUS,tint=MaterialTheme.colorScheme.onPrimary,label="Add memory")}}}}
        if(state.memories.isNotEmpty()) item {OutlinedTextField(value=query,onValueChange={query=it},modifier=Modifier.fillMaxWidth(),placeholder={Text("Find a memory")},leadingIcon={KaiIcon(KaiGlyph.SEARCH,tint=MaterialTheme.colorScheme.onSurfaceVariant)},singleLine=true,shape=RoundedCornerShape(16.dp))}
        if(state.memories.isEmpty()) item {EmptyState(KaiGlyph.MEMORY,"Good to know. Hard to forget.","Your preferences, people, and plans belong here. Start with something you'd like KAI to remember.","Add a memory",{adding=true})}
        val filtered=state.memories.filter {it.text.contains(query,true) || it.category.contains(query,true) || it.relatedPeople.any{person->person.contains(query,true)}}
        items(filtered,key={it.id}) {memory ->
            SoftCard {
                Row(verticalAlignment=Alignment.Top){Text(if(memory.editable) "SAVED NOTE" else "LINKED FACT",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary,modifier=Modifier.weight(1f).padding(top=10.dp));if(memory.editable) IconButton(onClick={editing=memory},modifier=Modifier.size(34.dp)){KaiIcon(KaiGlyph.EDIT,Modifier.size(17.dp),MaterialTheme.colorScheme.onSurfaceVariant,label="Edit memory")};IconButton(onClick={deleting=memory},modifier=Modifier.size(34.dp)){KaiIcon(KaiGlyph.DELETE,Modifier.size(17.dp),MaterialTheme.colorScheme.onSurfaceVariant,label="Delete memory")}}
                Text(memory.text,style=MaterialTheme.typography.bodyLarge)
                MemoryProvenance(memory)
            }
        }
        if(state.memories.isNotEmpty() && filtered.isEmpty()) item {Text("No memories match that search.",color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item {Row(horizontalArrangement=Arrangement.spacedBy(9.dp),modifier=Modifier.padding(vertical=12.dp)){KaiIcon(KaiGlyph.SHIELD,Modifier.size(16.dp),MaterialTheme.colorScheme.onSurfaceVariant);Text(if(state.useSavedMemory) "Saved on this device. Relevant memories are included when you talk to your selected AI, except in temporary chats." else "Saved on this device. Saved memories are currently kept out of your conversations.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
    }
    if(adding || editing!=null) {
        var text by remember(editing) {mutableStateOf(editing?.text ?: "")}
        AlertDialog(onDismissRequest={adding=false;editing=null},title={Text(if(editing==null) "Worth remembering" else "Edit memory")},text={Column {Text("A preference, a useful detail, or something important to you.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.height(16.dp));OutlinedTextField(value=text,onValueChange={text=it},minLines=3,maxLines=8,modifier=Modifier.fillMaxWidth(),placeholder={Text("I prefer short, practical answers…")},shape=RoundedCornerShape(14.dp))}},confirmButton={TextButton(onClick={actions.saveMemory(editing?.id,text.trim());adding=false;editing=null},enabled=text.isNotBlank()){Text("Save memory")}},dismissButton={TextButton(onClick={adding=false;editing=null}){Text("Cancel")}})
    }
    if(deleting!=null) AlertDialog(onDismissRequest={deleting=null},title={Text("Forget this memory?")},text={Text("KAI will remove this memory and its older versions. Related facts from the same source may also be removed. Existing chat messages remain, but the forgotten source will no longer be used for memory.")},confirmButton={TextButton(onClick={actions.deleteMemory(deleting!!.id);deleting=null}){Text("Forget",color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton(onClick={deleting=null}){Text("Keep")}})
}

@Composable
internal fun ConnectionsScreen(state:KaiUiState,actions:KaiUiActions) {
    var query by rememberSaveable {mutableStateOf("")}
    var selectedCategory by rememberSaveable {mutableStateOf("All")}
    var disconnecting by remember {mutableStateOf<KaiConnection?>(null)}
    val categories=listOf("All","Connected") + state.connections.map {it.category}.distinct().filter{it.isNotBlank()}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item {SectionHeader("Your world, connected","Make room for more.","Bring your apps into the conversation, with access you control.")}
        item {ComposioSetupCard(state,actions)}
        item {McpConnectionsPanel(state,actions)}
        if(state.connectionsSignedIn) item {OutlinedTextField(value=query,onValueChange={query=it;selectedCategory="All";actions.searchConnections(it)},placeholder={Text("Find an app or service")},leadingIcon={KaiIcon(KaiGlyph.SEARCH,tint=MaterialTheme.colorScheme.onSurfaceVariant)},modifier=Modifier.fillMaxWidth(),singleLine=true,shape=RoundedCornerShape(16.dp))}
        if(categories.size>2) item {Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){categories.forEach{category ->FilterChip(selected=category==selectedCategory,onClick={selectedCategory=category},label={Text(category)},shape=RoundedCornerShape(12.dp))}}}
        val filtered=state.connections.filter { (it.name.contains(query,true)||it.description.contains(query,true)) && (selectedCategory=="All" || (selectedCategory=="Connected" && it.status==KaiConnectionStatus.CONNECTED) || it.category==selectedCategory) }
        items(filtered,key={it.id}) {connection ->
            SoftCard {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),contentAlignment=Alignment.Center){Text(connection.name.take(1).uppercase(),style=MaterialTheme.typography.titleLarge,color=MaterialTheme.colorScheme.primary)}
                    Column(Modifier.weight(1f)){Text(connection.name,style=MaterialTheme.typography.titleMedium);Text(if(connection.status==KaiConnectionStatus.CONNECTED) connection.accountLabel.ifBlank{"Connected"} else connection.category,style=MaterialTheme.typography.bodySmall,color=if(connection.status==KaiConnectionStatus.CONNECTED) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)}
                    if(connection.status==KaiConnectionStatus.CONNECTED) KaiIcon(KaiGlyph.CHECK,Modifier.size(19.dp),MaterialTheme.colorScheme.secondary)
                }
                Spacer(Modifier.height(13.dp));Text(connection.description,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick={actions.openConnection(connection.id)},enabled=state.connectionsSignedIn && connection.status!=KaiConnectionStatus.COMING_SOON && !state.connectionBusy,contentPadding=PaddingValues(0.dp)){Text(if(connection.status==KaiConnectionStatus.COMING_SOON) "Planned" else connection.actionLabel);Spacer(Modifier.width(6.dp));KaiIcon(KaiGlyph.ARROW,Modifier.size(15.dp),if(connection.status==KaiConnectionStatus.COMING_SOON) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)}
                    if(connection.status==KaiConnectionStatus.CONNECTED){Spacer(Modifier.weight(1f));TextButton(onClick={disconnecting=connection},contentPadding=PaddingValues(0.dp)){Text("Disconnect",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
                }
            }
        }
        if(state.connectionsSignedIn && filtered.isEmpty() && !state.connectionBusy) item {EmptyState(KaiGlyph.CONNECTIONS,if(query.isNotBlank()) "No matching connections" else if(selectedCategory=="Connected") "A place for your accounts." else "Your apps belong here.",if(query.isNotBlank()) "Try another name or service." else if(selectedCategory=="Connected") "Choose an app from All to connect your first account." else "Refresh to load the apps available to your Composio project.")}
        if(state.connectionsSignedIn && state.connectionHasMore) item {OutlinedButton(onClick=actions::loadMoreConnections,enabled=!state.connectionBusy,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp)){Text("Load more apps")}}
        if(state.connectionBusy) item {Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp);Spacer(Modifier.width(10.dp));Text("Loading connections…",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
        item {Row(horizontalArrangement=Arrangement.spacedBy(9.dp),modifier=Modifier.padding(vertical=8.dp)){KaiIcon(KaiGlyph.SHIELD,Modifier.size(16.dp),MaterialTheme.colorScheme.onSurfaceVariant);Text("Connecting an app grants its requested access. KAI shows the action and account before asking you to approve a write.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
    }
    if(disconnecting!=null) AlertDialog(onDismissRequest={disconnecting=null},title={Text("Disconnect ${disconnecting!!.name}?")},text={Text("This removes the account connection from your Composio project. Other agents using that connection may also lose access. You can connect it again later.")},confirmButton={TextButton(onClick={actions.disconnectConnection(disconnecting!!.id);disconnecting=null}){Text("Disconnect")}},dismissButton={TextButton(onClick={disconnecting=null}){Text("Keep connected")}})
}

@Composable
private fun ComposioSetupCard(state:KaiUiState,actions:KaiUiActions) {
    var editing by remember {mutableStateOf(false)}
    // A project key is never restored through Android's saved instance state or echoed from storage.
    var apiKey by remember {mutableStateOf("")}
    var userId by remember(state.composioUserId) {mutableStateOf(state.composioUserId)}
    var advanced by remember {mutableStateOf(false)}
    var removing by remember {mutableStateOf(false)}
    val uri=LocalUriHandler.current
    val showEditor=!state.composioKeySaved || editing
    SoftCard {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),contentAlignment=Alignment.Center){KaiIcon(KaiGlyph.LINK,Modifier.size(20.dp),MaterialTheme.colorScheme.primary)}
            Column(Modifier.weight(1f)) {
                Text("Composio setup",style=MaterialTheme.typography.titleMedium)
                Text(if(state.composioKeySaved) "Your project key is saved on this phone" else "Your apps. Your own project.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(14.dp))
        if(showEditor) {
            Text("Use your own Composio project API key to connect apps directly. No KAI website login or desktop is needed.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(value=apiKey,onValueChange={apiKey=it},label={Text("Composio API key")},placeholder={Text(if(state.composioKeySaved) "Leave blank to keep saved key" else "Paste your project API key")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false),singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),supportingText={Text("Use your own Composio project API key. This is different from an AI inference key or KAI-managed connection token.")})
            Spacer(Modifier.height(4.dp))
            TextButton(onClick={advanced=!advanced},contentPadding=PaddingValues(0.dp)){Text(if(advanced) "Hide advanced setup" else "Advanced · user identity");Spacer(Modifier.width(5.dp));KaiIcon(KaiGlyph.CHEVRON,Modifier.size(13.dp),MaterialTheme.colorScheme.primary)}
            if(advanced || userId.isBlank()) {
                OutlinedTextField(value=userId,onValueChange={userId=it},label={Text("Composio user ID")},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),supportingText={Text("Required. Use the same ID as your existing Composio connections.")})
                Spacer(Modifier.height(10.dp))
            } else Text("User ID: $userId",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Text("Already use Composio? The same project and user ID let KAI find your existing connected accounts.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
            Button(onClick={actions.saveComposio(apiKey.trim(),userId.trim());apiKey="";editing=false},enabled=!state.connectionBusy && userId.isNotBlank() && (apiKey.isNotBlank() || state.composioKeySaved),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),shape=RoundedCornerShape(13.dp)){Text("Save & connect")}
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={runCatching{uri.openUri("https://platform.composio.dev/")}},contentPadding=PaddingValues(0.dp)){Text("Open Composio dashboard",style=MaterialTheme.typography.labelMedium);Spacer(Modifier.width(5.dp));KaiIcon(KaiGlyph.ARROW,Modifier.size(14.dp),MaterialTheme.colorScheme.primary)}
                if(state.composioKeySaved){Spacer(Modifier.weight(1f));TextButton(onClick={editing=false;apiKey="";userId=state.composioUserId}){Text("Cancel")}}
            }
        } else {
            Text("User ID: ${state.composioUserId}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            Text("Choose an app below. Your browser opens for that provider's permission screen, then you can return to KAI.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                TextButton(onClick={editing=true;apiKey="";userId=state.composioUserId},enabled=!state.connectionBusy,contentPadding=PaddingValues(0.dp)){Text("Edit setup")}
                TextButton(onClick=actions::refreshConnections,enabled=!state.connectionBusy,contentPadding=PaddingValues(0.dp)){Text("Refresh connections")}
            }
        }
        if(state.connectionStatus.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(state.connectionStatus,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=4,overflow=TextOverflow.Ellipsis)
        }
        if(state.composioKeySaved) {
            TextButton(onClick=actions::checkComposioAccess,enabled=!state.connectionBusy,contentPadding=PaddingValues(0.dp)){KaiIcon(KaiGlyph.SHIELD,Modifier.size(15.dp),MaterialTheme.colorScheme.primary);Spacer(Modifier.width(6.dp));Text("Check access",style=MaterialTheme.typography.labelMedium)}
            if(state.composioDiagnostics!=null) {
                Surface(color=MaterialTheme.colorScheme.surfaceContainerHighest,shape=RoundedCornerShape(13.dp)) {
                    Column(Modifier.fillMaxWidth().padding(13.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                        Text("PROJECT ACCESS",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                        androidx.compose.foundation.text.selection.SelectionContainer {Text(state.composioDiagnostics,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    }
                }
            }
            if(showEditor) Text("Saving a key doesn’t verify its permissions. A project key can still be denied access to particular operations.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if(state.composioKeySaved && showEditor) {
            TextButton(onClick={removing=true},enabled=!state.connectionBusy,contentPadding=PaddingValues(0.dp)){Text("Remove key from this phone",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
    }
    if(removing) AlertDialog(onDismissRequest={removing=false},title={Text("Remove Composio key?")},text={Text("Removes the saved key from this phone. Your Composio project and connected accounts stay in place, including connections used by other agents.")},confirmButton={TextButton(onClick={apiKey="";editing=false;actions.signOutConnections();removing=false}){Text("Remove key",color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton(onClick={removing=false}){Text("Keep key")}})
}

@Composable
internal fun HistoryContent(state:KaiUiState,actions:KaiUiActions,onClose:()->Unit) {
    var deleting by remember {mutableStateOf<KaiConversation?>(null)}
    var reporting by remember {mutableStateOf<String?>(null)}
    Column(Modifier.fillMaxWidth().fillMaxHeight(.84f).padding(horizontal=24.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically){Text("Your conversations",style=MaterialTheme.typography.headlineMedium,modifier=Modifier.weight(1f));IconButton(onClick=onClose){KaiIcon(KaiGlyph.CLOSE,label="Close conversation history")}}
        Spacer(Modifier.height(16.dp));Button(onClick={actions.newConversation();onClose()},shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth()){KaiIcon(KaiGlyph.PLUS,Modifier.size(18.dp),MaterialTheme.colorScheme.onPrimary);Spacer(Modifier.width(8.dp));Text("New conversation")}
        Spacer(Modifier.height(8.dp));OutlinedButton(onClick={actions.newTemporaryConversation();onClose()},shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth()){KaiIcon(KaiGlyph.SHIELD,Modifier.size(17.dp),MaterialTheme.colorScheme.primary);Spacer(Modifier.width(8.dp));Text("New temporary chat")}
        LazyColumn(contentPadding=PaddingValues(vertical=18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            if(state.conversations.isEmpty()) item {EmptyState(KaiGlyph.TALK,"Room for a new thought.","Your conversations will appear here as you talk with KAI.")}
            items(state.conversations,key={it.id}){conversation->
                Surface(onClick={actions.selectConversation(conversation.id);onClose()},shape=RoundedCornerShape(16.dp),color=if(conversation.id==state.currentConversationId) MaterialTheme.colorScheme.primaryContainer.copy(alpha=.5f) else MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.padding(start=16.dp,top=10.dp,bottom=10.dp,end=5.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(conversation.title,style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis);if(conversation.detail.isNotBlank()) Text(conversation.detail,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};TextButton(onClick={reporting=conversation.id}){Text("Report")};IconButton(onClick={deleting=conversation}){KaiIcon(KaiGlyph.DELETE,Modifier.size(17.dp),MaterialTheme.colorScheme.onSurfaceVariant,label="Delete conversation")}}
                }
            }
        }
    }
    if(reporting!=null) ChatReportDialog(reporting!!,actions,onClose={reporting=null})
    if(deleting!=null) AlertDialog(onDismissRequest={deleting=null},title={Text("Delete this conversation?")},text={Text("Messages and memories saved from this conversation will be removed from this device.")},confirmButton={TextButton(onClick={actions.deleteConversation(deleting!!.id);deleting=null}){Text("Delete",color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton(onClick={deleting=null}){Text("Cancel")}})
}


@Composable
internal fun ChatReportDialog(id: String, actions: KaiUiActions, onClose: () -> Unit) {
    AlertDialog(onDismissRequest=onClose,title={Text("Save chat report")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Regular chats automatically keep an encrypted diagnostic record on this phone. Save a JSON file and attach it when asking for help.")
        Text("Includes your messages, model context and responses, tool choices, approvals, results and errors. Credentials are filtered, but personal content remains. Review the file before sharing.")
        Text("Records start with this update. Older steps, audio and attachment bytes are unavailable. Storage limits can remove older records; forgetting data clears diagnostics. Temporary chats are not recorded.",style=MaterialTheme.typography.bodySmall)
    }},confirmButton={TextButton(onClick={actions.exportChatReport(id);onClose()}){Text("Save report")}},dismissButton={TextButton(onClick=onClose){Text("Cancel")}})
}
