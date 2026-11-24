package com.kai.agent.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay

private enum class KaiTab(val title: String, val icon: KaiGlyph) { TALK("Agent",KaiGlyph.TALK), TASKS("Tasks",KaiGlyph.TASKS), PROJECTS("Projects",KaiGlyph.MEMORY), MEMORY("Memory",KaiGlyph.MEMORY), CONNECTIONS("Connections",KaiGlyph.CONNECTIONS) }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun KaiApp(state: KaiUiState, actions: KaiUiActions) {
    var tab by rememberSaveable { mutableStateOf(KaiTab.TALK) }
    var account by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var studio by rememberSaveable { mutableStateOf(false) }
    var voiceOptions by rememberSaveable { mutableStateOf(false) }
    var chat by rememberSaveable { mutableStateOf(false) }
    var reportChat by rememberSaveable { mutableStateOf<String?>(null) }
    var history by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.incomingDraftText) { if(state.incomingDraftText!=null) { tab=KaiTab.TALK;chat=true } }
    LaunchedEffect(state.requestedRunId) { if(state.requestedRunId!=null)tab=KaiTab.TASKS }
    val character=state.characters.firstOrNull { it.id==state.selectedCharacterId } ?: builtInKaiCharacters.first()
    val view=LocalView.current
    val keyboardOpen = WindowInsets.isImeVisible
    SideEffect {
        view.context.findActivity()?.let {activity ->
            WindowCompat.getInsetsController(activity.window,view).apply {
                isAppearanceLightStatusBars=!state.isDarkTheme
                isAppearanceLightNavigationBars=!state.isDarkTheme
            }
        }
    }
    KaiTheme(state.isDarkTheme) {
        Scaffold(
            modifier=Modifier.imePadding(),
            containerColor=MaterialTheme.colorScheme.background,
            topBar={
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=22.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically) {
                    Row(Modifier.weight(1f),verticalAlignment=Alignment.CenterVertically) {
                        Text("kai",fontSize=28.sp,fontWeight=FontWeight.Bold,letterSpacing=(-1.6).sp)
                        Spacer(Modifier.width(9.dp))
                    }
                    if(tab==KaiTab.TALK) IconButton(onClick={history=true},modifier=Modifier.size(42.dp)) {KaiIcon(KaiGlyph.MEMORY,label="Conversation history")}
                    TextButton(onClick={account=true;actions.accountRefresh()}) { Text("Account") }
                    IconButton(onClick={settings=true},modifier=Modifier.size(42.dp)) {KaiIcon(KaiGlyph.SETTINGS,label="Settings")}
                }
            },
            bottomBar={
                if (!keyboardOpen) {
                Column(Modifier.background(MaterialTheme.colorScheme.background)) {
                    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))
                    Row(Modifier.navigationBarsPadding().fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp)) {
                        KaiTab.entries.forEach { item ->
                            val selected=tab==item
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).selectable(selected=selected,role=Role.Tab,onClick={tab=item}).padding(vertical=9.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(5.dp)) {
                                Box(Modifier.width(50.dp).height(28.dp).clip(RoundedCornerShape(11.dp)).background(if(selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent),contentAlignment=Alignment.Center) {
                                    KaiIcon(item.icon,tint=if(selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(item.title,style=MaterialTheme.typography.labelSmall,letterSpacing=0.sp,color=if(selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                if(state.error!=null) ErrorBanner(state.error,actions::clearError)
                when(tab) {
                    KaiTab.TALK -> Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal=18.dp).horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                            FilterChip(selected=!chat,onClick={chat=false},label={Text("Character")})
                            FilterChip(selected=chat,onClick={chat=true},label={Text("Chat")})
                            TextButton(onClick={voiceOptions=true}) { KaiIcon(KaiGlyph.VOLUME,Modifier.size(18.dp));Spacer(Modifier.width(5.dp));Text("Voice") }
                            TextButton(onClick={studio=true}) { Text(character.name+" ▾") }
                        }
                        if(chat)TalkScreen(state,actions,character,active=!account && !settings && !studio && !history && !voiceOptions,onCharacter={studio=true},onSettings={settings=true})
                        else CompanionConversation(state,actions,character,active=!account && !settings && !studio && !history && !voiceOptions,onSettings={settings=true})
                    }
                    KaiTab.TASKS -> WorkScreen(state,actions,onChat={tab=KaiTab.TALK;chat=true})
                    KaiTab.PROJECTS -> ProjectsScreen(state,actions,onChat={tab=KaiTab.TALK;chat=true})
                    KaiTab.MEMORY -> MemoryScreen(state,actions)
                    KaiTab.CONNECTIONS -> ConnectionsScreen(state,actions)
                }
            }
        }
        if(account) ModalBottomSheet(onDismissRequest={account=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) { AccountContent(state,actions) { account=false } }
        if(settings) ModalBottomSheet(onDismissRequest={settings=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=MaterialTheme.colorScheme.background) {
            SettingsContent(state,actions,onClose={settings=false},onVoice={settings=false;voiceOptions=true},onReport={settings=false;reportChat=state.currentConversationId})
        }
        if(studio) ModalBottomSheet(onDismissRequest={studio=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=MaterialTheme.colorScheme.background) {
            CharacterStudio(state,actions,onClose={studio=false})
        }
        if(voiceOptions) ModalBottomSheet(onDismissRequest={voiceOptions=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) { VoiceControls(state,actions) { voiceOptions=false } }
        if(reportChat!=null) ChatReportDialog(reportChat!!,actions,onClose={reportChat=null})
        if(history) ModalBottomSheet(onDismissRequest={history=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=MaterialTheme.colorScheme.background) {
            HistoryContent(state,actions,onClose={history=false;chat=true})
        }
    }
}

private fun Context.findActivity():Activity?=when(this){is Activity->this;is ContextWrapper->baseContext.findActivity();else->null}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun TalkScreen(state: KaiUiState, actions: KaiUiActions, character: KaiCharacter, active:Boolean, onCharacter:()->Unit, onSettings:()->Unit) {
    var draft by if(state.temporaryConversation) remember(state.currentConversationId) {mutableStateOf("")} else rememberSaveable(state.currentConversationId) {mutableStateOf("")}
    LaunchedEffect(state.incomingDraftText) {
        state.incomingDraftText?.let {shared ->
            draft=if(draft.isBlank()) shared else "$draft\n\n$shared"
            actions.consumeSharedText()
        }
    }
    val focus=LocalFocusManager.current
    val list=rememberLazyListState()
    var priorTasks by remember(state.currentConversationId) {mutableStateOf(state.tasks.associate {it.id to it.status})}
    var completedAt by remember(state.currentConversationId) {mutableLongStateOf(0L)}
    var celebrate by remember(state.currentConversationId) {mutableStateOf(false)}
    LaunchedEffect(state.tasks) {
        if(hasNewTaskCompletion(priorTasks,state.tasks)) completedAt=System.nanoTime()
        priorTasks=state.tasks.associate {it.id to it.status}
    }
    LaunchedEffect(completedAt) {if(completedAt!=0L) {celebrate=true;delay(2400);celebrate=false}}
    val activity=characterActivity(state,celebrate)
    LaunchedEffect(state.messages.size,state.streamingText,state.pendingMemories.size) {
        val lastVisible=list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if(!list.isScrollInProgress && lastVisible>=list.layoutInfo.totalItemsCount-3 && (state.messages.isNotEmpty() || state.streamingText.isNotEmpty())) {
            // A stream may update many times a second. Follow the latest item without repeated animations.
            list.scrollToItem((state.messages.size+state.pendingMemories.size+2).coerceAtLeast(0))
        }
    }
    val send={ if(draft.isNotBlank()) {actions.sendMessage(draft.trim());draft="";focus.clearFocus()} }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // Reserve the editor before optional chrome on short, keyboard-resized and large-text screens.
    val compact = WindowInsets.isImeVisible || maxHeight < 420.dp
    Column(Modifier.fillMaxSize()) {
        if (!compact) {
        Row(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=6.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            Surface(onClick=onCharacter,shape=RoundedCornerShape(50),color=MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.padding(horizontal=12.dp,vertical=9.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color(character.color)))
                    Text(character.name,style=MaterialTheme.typography.labelLarge)
                    KaiIcon(KaiGlyph.CHEVRON,Modifier.size(12.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Surface(onClick=onSettings,shape=RoundedCornerShape(50),color=Color.Transparent) {
                Row(Modifier.padding(8.dp),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(if(state.providerConnected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant))
                    Text(if(state.providerConfig.endpoint.isNotBlank() && state.providerConfig.apiKey.isNotBlank() && state.providerConfig.modelId.isNotBlank()) if(state.providerConfig.provider==KaiProvider.KOINOS) "Koinos AI" else "OpenAI" else "Set up AI",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        }
        if(state.temporaryConversation) TemporaryChatBanner(actions)
        if(state.messages.isEmpty() && state.streamingText.isBlank()) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=24.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Spacer(Modifier.height(12.dp))
                CharacterAvatar(character,state.voiceState,Modifier.fillMaxWidth().height(224.dp),animated=active,activity=activity,speechAudible=state.speechAudible,reducedMotion=state.reducedMotion,hapticsEnabled=state.hapticsEnabled)
                Text(if(state.voiceState==KaiVoiceState.LISTENING) "I'm listening." else characterWelcome(character.id),style=MaterialTheme.typography.headlineLarge,textAlign=TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(if(state.partialTranscript.isNotBlank()) state.partialTranscript else "Think out loud. Make a plan.\nLet's start with what's on your mind.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text("Tap ${character.name} to say hello",style=MaterialTheme.typography.labelSmall,letterSpacing=0.sp,color=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.7f))
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    SuggestionChip("Plan my day",onClick={draft="Help me plan my day."})
                    SuggestionChip("Brainstorm",onClick={draft="Help me brainstorm an idea."})
                }
                Spacer(Modifier.height(12.dp))
                if(!state.providerConnected) TextButton(onClick=onSettings) {Text("Connect your AI to begin");Spacer(Modifier.width(6.dp));KaiIcon(KaiGlyph.ARROW,Modifier.size(16.dp),MaterialTheme.colorScheme.primary)}
                if(!state.temporaryConversation) TextButton(onClick=actions::newTemporaryConversation) {KaiIcon(KaiGlyph.SHIELD,Modifier.size(14.dp),MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.width(6.dp));Text("Start a temporary chat",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                Spacer(Modifier.height(12.dp))
            }
        } else {
            if (!compact) CompanionStage(state,character,activity,active)
            LazyColumn(state=list,modifier=Modifier.weight(1f).fillMaxWidth().testTag("chat-transcript"),contentPadding=PaddingValues(horizontal=22.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                items(state.messages,key={it.id}) {message -> MessageBubble(message,character)}
                if(state.streamingText.isNotBlank()) item {MessageBubble(KaiMessage("stream",KaiRole.ASSISTANT,state.streamingText),character)}
                if(state.isBusy && state.streamingText.isBlank()) item {Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {if(!state.reducedMotion) CircularProgressIndicator(Modifier.size(14.dp),strokeWidth=1.5.dp) else KaiIcon(KaiGlyph.MORE,Modifier.size(16.dp));Text(characterStatus(state,activity),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
                if(state.partialTranscript.isNotBlank()) item {Text(state.partialTranscript,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.primary)}
                if(!state.temporaryConversation) items(state.pendingMemories,key={"memory-suggestion-${it.id}"}) {suggestion -> MemorySuggestionCard(suggestion,actions)}
                item {ExecutionPanel(state,actions)}
                item {Spacer(Modifier.height(1.dp))}
            }
        }
        if(state.memoryNotice!=null && !state.temporaryConversation) MemorySavedNotice(state.memoryNotice,actions,Modifier.padding(horizontal=20.dp,vertical=5.dp))
        if(state.pendingApproval!=null) ApprovalCard(state.pendingApproval,actions,Modifier.padding(horizontal=20.dp,vertical=8.dp),autoOpen=true)
        if(state.voiceEnabled) Row(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            Text(characterStatus(state,activity),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary,modifier=Modifier.weight(1f))
            if(state.voiceState==KaiVoiceState.SPEAKING || state.voiceState==KaiVoiceState.THINKING || state.isBusy) TextButton(onClick=actions::interruptVoice,contentPadding=PaddingValues(horizontal=7.dp)) {Text("Your turn",style=MaterialTheme.typography.labelMedium)}
            TextButton(onClick=actions::toggleVoice,contentPadding=PaddingValues(0.dp)) {Text("End voice",style=MaterialTheme.typography.labelMedium)}
        }
        if (!compact || state.selectedDocuments.isNotEmpty() || state.attachmentBusy) AttachmentBar(state,actions)
        Row(Modifier.testTag("chat-composer").padding(horizontal=18.dp).padding(top=6.dp,bottom=8.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(MaterialTheme.colorScheme.surfaceContainer).border(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.6f),RoundedCornerShape(26.dp)).padding(start=18.dp,end=7.dp,top=7.dp,bottom=7.dp),verticalAlignment=Alignment.CenterVertically) {
            BasicTextField(value=draft,onValueChange={draft=it},modifier=Modifier.testTag("chat-input").weight(1f).heightIn(min=34.dp,max=if(compact) 80.dp else 120.dp).padding(vertical=7.dp),textStyle=MaterialTheme.typography.bodyLarge.copy(color=MaterialTheme.colorScheme.onSurface),cursorBrush=SolidColor(MaterialTheme.colorScheme.primary),keyboardOptions=KeyboardOptions(imeAction=ImeAction.Send),keyboardActions=KeyboardActions(onSend={send()}),decorationBox={inner -> Box {if(draft.isEmpty()) Text("Tell ${character.name} anything…",style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant);inner()}})
            Spacer(Modifier.width(8.dp))
            IconButton(onClick=actions::toggleVoice,enabled=state.voiceAvailable,modifier=Modifier.size(42.dp)) {KaiIcon(KaiGlyph.MIC,tint=if(state.voiceEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,label=if(state.voiceEnabled) "End voice conversation" else "Start voice conversation")}
            FilledIconButton(onClick={if(draft.isNotBlank()) send() else if(state.isBusy) actions.stopGeneration()},enabled=state.isBusy || draft.isNotBlank(),modifier=Modifier.size(42.dp),colors=IconButtonDefaults.filledIconButtonColors(containerColor=MaterialTheme.colorScheme.primary,disabledContainerColor=MaterialTheme.colorScheme.surfaceContainerHighest)) {KaiIcon(if(state.isBusy && draft.isBlank()) KaiGlyph.STOP else KaiGlyph.SEND,tint=if(state.isBusy || draft.isNotBlank()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,label=if(state.isBusy && draft.isBlank()) "Stop response" else "Send message")}
        }
        if (!compact) Text("Your companion. Your choice of AI.",style=MaterialTheme.typography.labelSmall,letterSpacing=0.sp,color=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.7f),textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth().padding(bottom=10.dp))
    }
    }
}

private fun characterWelcome(id:String)=when(id) {
    "nova" -> "A curious mind.\nA universe of ideas."
    "sage" -> "Room to think.\nA little more clarity."
    "spark" -> "Big energy.\nYour next small step."
    else -> "A little company.\nA lot of possibility."
}

@Composable
private fun CompanionStage(state:KaiUiState,character:KaiCharacter,activity:KaiCharacterActivity,active:Boolean) {
    Surface(modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=5.dp),shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surfaceContainer.copy(alpha=.65f)) {
        Row(Modifier.padding(start=8.dp,end=16.dp,top=6.dp,bottom=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            CharacterAvatar(character,state.voiceState,Modifier.size(if(state.voiceEnabled) 108.dp else 86.dp),animated=active,activity=activity,speechAudible=state.speechAudible,reducedMotion=state.reducedMotion,hapticsEnabled=state.hapticsEnabled)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Text(character.name,style=MaterialTheme.typography.titleMedium)
                Text(characterStatus(state,activity),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(state.voiceEnabled && state.voiceState==KaiVoiceState.LISTENING) Text("No need to get every word perfect.",style=MaterialTheme.typography.labelSmall,letterSpacing=0.sp,color=MaterialTheme.colorScheme.primary)
            }
            when(activity) {
                KaiCharacterActivity.LISTENING -> KaiIcon(KaiGlyph.MIC,Modifier.size(18.dp),Color(character.color))
                KaiCharacterActivity.SPEAKING -> KaiIcon(KaiGlyph.VOLUME,Modifier.size(18.dp),Color(character.color).copy(alpha=if(state.speechAudible) 1f else .45f))
                KaiCharacterActivity.REVIEW -> KaiIcon(KaiGlyph.SHIELD,Modifier.size(18.dp),MaterialTheme.colorScheme.primary)
                KaiCharacterActivity.SUCCESS -> KaiIcon(KaiGlyph.CHECK,Modifier.size(18.dp),MaterialTheme.colorScheme.secondary)
                else -> Unit
            }
        }
    }
}

@Composable private fun SuggestionChip(text:String,onClick:()->Unit) {Surface(onClick=onClick,shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surfaceContainer){Text(text,Modifier.padding(horizontal=16.dp,vertical=12.dp),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}}

@Composable private fun MessageBubble(message:KaiMessage,character:KaiCharacter) {
    val mine=message.role==KaiRole.USER
    Column(Modifier.fillMaxWidth(),horizontalAlignment=if(mine) Alignment.End else Alignment.Start) {
        if(!mine) Text(if(message.role==KaiRole.ASSISTANT) character.name else "KAI",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary,modifier=Modifier.padding(bottom=7.dp))
        Surface(shape=if(mine) RoundedCornerShape(topStart=20.dp,topEnd=20.dp,bottomStart=20.dp,bottomEnd=5.dp) else androidx.compose.ui.graphics.RectangleShape,color=if(mine) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,modifier=if(mine) Modifier.widthIn(max=320.dp) else Modifier.fillMaxWidth()) {
            androidx.compose.foundation.text.selection.SelectionContainer {
                if(message.role==KaiRole.ASSISTANT) MarkdownReply(message.text)
                else Text(message.text,style=MaterialTheme.typography.bodyLarge,modifier=Modifier.padding(if(mine) 15.dp else 0.dp))
            }
        }
    }
}

@Composable private fun ErrorBanner(error:String,onDismiss:()->Unit) {Surface(color=MaterialTheme.colorScheme.errorContainer,shape=RoundedCornerShape(14.dp),modifier=Modifier.padding(horizontal=20.dp,vertical=5.dp)){Row(Modifier.padding(start=14.dp,top=7.dp,bottom=7.dp),verticalAlignment=Alignment.CenterVertically){Text(error,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onErrorContainer,modifier=Modifier.weight(1f));IconButton(onClick=onDismiss){KaiIcon(KaiGlyph.CLOSE,Modifier.size(17.dp),MaterialTheme.colorScheme.onErrorContainer,label="Dismiss error")}}}}

@Composable internal fun SectionHeader(eyebrow:String,title:String,description:String) {Column(Modifier.padding(bottom=25.dp)){Text(eyebrow.uppercase(),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary,letterSpacing=1.7.sp);Spacer(Modifier.height(9.dp));Text(title,style=MaterialTheme.typography.headlineLarge);Spacer(Modifier.height(9.dp));Text(description,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}}

@Composable internal fun SoftCard(modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit) {Surface(modifier=modifier.fillMaxWidth(),color=MaterialTheme.colorScheme.surfaceContainer,shape=RoundedCornerShape(22.dp)){Column(Modifier.padding(20.dp),content=content)}}

@Composable internal fun EmptyState(icon:KaiGlyph,title:String,description:String,button:String?=null,onClick:()->Unit={}) {Column(Modifier.fillMaxWidth().padding(vertical=32.dp,horizontal=16.dp),horizontalAlignment=Alignment.CenterHorizontally){Box(Modifier.size(68.dp).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha=.6f)),contentAlignment=Alignment.Center){KaiIcon(icon,Modifier.size(28.dp),MaterialTheme.colorScheme.primary)};Spacer(Modifier.height(22.dp));Text(title,style=MaterialTheme.typography.titleLarge,textAlign=TextAlign.Center);Spacer(Modifier.height(10.dp));Text(description,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=TextAlign.Center);if(button!=null){Spacer(Modifier.height(20.dp));OutlinedButton(onClick=onClick,shape=RoundedCornerShape(14.dp)){Text(button)}}}}

