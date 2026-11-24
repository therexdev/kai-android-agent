package com.kai.agent.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.kai.agent.data.*
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
internal fun CompanionConversation(state: KaiUiState, actions: KaiUiActions, character: KaiCharacter, active: Boolean, onSettings: ()->Unit) {
    val configured=state.providerConfig.apiKey.isNotBlank() && state.providerConfig.modelId.isNotBlank()
    val transcriptScroll=rememberScrollState()
    Column(Modifier.fillMaxSize().padding(horizontal=20.dp).padding(top=8.dp,bottom=12.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val avatarHeight=(maxHeight*.48f).coerceIn(96.dp,260.dp).coerceAtMost(maxHeight*.6f)
            Column(Modifier.fillMaxSize(),horizontalAlignment=Alignment.CenterHorizontally) {
                // The live renderer has a stable slot outside the transcript's scroll container.
                Surface(Modifier.fillMaxWidth().height(avatarHeight).testTag("companion-stage"),
                    shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.surfaceContainer,
                    border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.6f))) {
                    CharacterAvatar(character,state.voiceState,Modifier.fillMaxSize().padding(8.dp),animated=active,
                        activity=characterActivity(state,false),speechAudible=state.speechAudible,
                        reducedMotion=state.reducedMotion,hapticsEnabled=state.hapticsEnabled)
                }
                Spacer(Modifier.height(12.dp))
                Column(Modifier.weight(1f).fillMaxWidth().testTag("companion-transcript").verticalScroll(transcriptScroll),
                    horizontalAlignment=Alignment.CenterHorizontally) {
                    state.projects.firstOrNull { it.id==state.projectId }?.name?.takeIf { it.isNotBlank() }?.let {
                        Text(it,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(when { state.voiceState==KaiVoiceState.LISTENING -> "I’m listening.";state.voiceState==KaiVoiceState.SPEAKING -> "${character.name} is speaking";state.isBusy -> "Thinking with you…";else -> "Talk with ${character.name}" },style=MaterialTheme.typography.headlineMedium,textAlign=TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    if(state.partialTranscript.isNotBlank()) Text(state.partialTranscript,color=MaterialTheme.colorScheme.primary,textAlign=TextAlign.Center)
                    val reply=state.streamingText.ifBlank { state.messages.lastOrNull { it.role==KaiRole.ASSISTANT }?.text.orEmpty() }
                    // Retain the full reply so scrolling back never loses earlier sentences.
                    if(reply.isNotBlank()) SelectionContainer { MarkdownReply(reply,textAlign=TextAlign.Center) }
                    else Text("Think out loud, ask for help, or tell me what to do. Your conversation is also available in Chat.",style=MaterialTheme.typography.bodyLarge,textAlign=TextAlign.Center,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(14.dp))
                    ExecutionPanel(state,actions)
                    if(state.pendingApproval!=null) ApprovalCard(state.pendingApproval,actions,autoOpen=true)
                    if(state.temporaryConversation) Text("Temporary conversation · no saved transcript",style=MaterialTheme.typography.labelMedium)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if(!configured) TextButton(onClick=onSettings) { Text("Set up your AI") }
        // One footer row keeps the stage height stable when the interrupt control appears.
        Row(Modifier.fillMaxWidth().height(56.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Button(onClick=actions::toggleVoice,enabled=state.voiceAvailable,
                modifier=Modifier.weight(1f).fillMaxHeight(),shape=RoundedCornerShape(20.dp),
                contentPadding=PaddingValues(horizontal=12.dp)) {
                KaiIcon(if(state.voiceEnabled) KaiGlyph.STOP else KaiGlyph.MIC,tint=MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(10.dp))
                Text(if(state.voiceEnabled) "End conversation" else "Start talking",maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            if(state.voiceEnabled && (state.isBusy || state.voiceState==KaiVoiceState.SPEAKING)) {
                OutlinedButton(onClick=actions::interruptVoice,modifier=Modifier.fillMaxHeight(),
                    shape=RoundedCornerShape(20.dp),contentPadding=PaddingValues(horizontal=12.dp)) {
                    Text("Your turn",maxLines=1,modifier=Modifier.semantics { contentDescription="Your turn · interrupt" })
                }
            }
        }
    }
}

@Composable
internal fun VoiceControls(state: KaiUiState, actions: KaiUiActions, onClose: ()->Unit) {
    val character=state.characters.firstOrNull { it.id==state.selectedCharacterId } ?: builtInKaiCharacters.first()
    LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(.94f),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item { Row(verticalAlignment=Alignment.CenterVertically) { Text("Voice for ${character.name}",style=MaterialTheme.typography.headlineMedium,modifier=Modifier.weight(1f));TextButton(onClick=onClose) { Text("Done") } } }
        item { Text("Kiki is the default voice. Preview it or choose an installed Android voice. Your speaking-speed setting stays the same.",style=MaterialTheme.typography.bodyMedium) }
        item { Text("Speech recognition uses your Android speech service. On device labels describe spoken replies.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        items(state.voices.filter { !state.localSpeechOnly || it.local },key={it.id}) { voice ->
            Surface(onClick={actions.selectVoice(voice.id)},modifier=Modifier.semantics { contentDescription = "Select voice ${voice.label}" },shape=RoundedCornerShape(16.dp),color=if(state.selectedVoiceId==voice.id)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                    RadioButton(selected=state.selectedVoiceId==voice.id,onClick={actions.selectVoice(voice.id)})
                    Column(Modifier.weight(1f)) { Text(voice.label,style=MaterialTheme.typography.titleSmall);Text(voice.detail,style=MaterialTheme.typography.bodySmall) }
                    TextButton(onClick={actions.previewVoice(voice.id)}, modifier=Modifier.semantics { contentDescription = "Preview ${voice.label}" }) { Text("Preview") }
                }
            }
        }
        item { SpeechSettings(state,actions) }
    }
}

@Composable
internal fun AttachmentBar(state: KaiUiState, actions: KaiUiActions) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=18.dp),verticalAlignment=Alignment.CenterVertically) {
        TextButton(onClick={actions.pickFile(false)},enabled=!state.attachmentBusy && !state.temporaryConversation) { Text("+ File") }
        TextButton(onClick={actions.pickFile(true)},enabled=!state.attachmentBusy && !state.temporaryConversation) { Text("Camera") }
        if(state.attachmentBusy) Text("Reading file…",style=MaterialTheme.typography.bodySmall)
        state.documents.filter { it.id in state.selectedDocuments }.forEach { document ->
            InputChip(selected=true,onClick={actions.selectDocument(document.id,false)},label={Text(document.name.take(24)+" ×")})
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorkScreen(state: KaiUiState, actions: KaiUiActions, onChat: ()->Unit) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var editingRoutine by remember { mutableStateOf<AgentRoutine?>(null) }
    var newRoutine by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var skip by remember { mutableStateOf<AgentRun?>(null) }
    var directing by remember { mutableStateOf<AgentRun?>(null) }
    LaunchedEffect(state.requestedRunId) { state.requestedRunId?.let { expanded=it;actions.consumeRunRequest() } }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(22.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item { Text("Things in motion",style=MaterialTheme.typography.headlineMedium);Text("Saved steps, progress, and routines.",color=MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) { Button(onClick={adding=true},modifier=Modifier.weight(1f)) { Text("New task") };OutlinedButton(onClick={newRoutine=true},modifier=Modifier.weight(1f)) { Text("New routine") } } }
        item { TemplatesPanel(state,actions) }
        items(state.executions.filter { it.origin==com.kai.agent.runtime.RunOrigin.CHAT && !it.temporary && it.state !in setOf(com.kai.agent.runtime.ExecutionState.COMPLETED,com.kai.agent.runtime.ExecutionState.CANCELLED) }.sortedByDescending { it.updatedAt },key={"execution-"+it.id}) { execution ->
            Column { Text(execution.goal.request.take(100),style=MaterialTheme.typography.titleSmall);ExecutionPanel(state,actions,execution)
                TextButton(onClick={actions.selectConversation(execution.conversationId);onChat()}) { Text("Open chat") } }
        }
        if(state.pendingApproval!=null && state.pendingApproval.runId.isBlank())item { ApprovalCard(state.pendingApproval,actions) }
        if(state.runs.isEmpty()) item { Text("Give KAI a goal. It will save a plan, work through each step, and ask before outgoing actions.") }
        items(state.runs,key={it.id}) { run ->
            SoftCard {
                TextButton(onClick={expanded=if(expanded==run.id)null else run.id},contentPadding=PaddingValues(0.dp)) { Text(run.title,style=MaterialTheme.typography.titleMedium) }
                Text("${run.status.name.replace('_',' ').lowercase().replaceFirstChar { it.uppercase() }} · ${run.steps.count { it.status in setOf(StepStatus.COMPLETED,StepStatus.SKIPPED) }}/${run.steps.size} steps",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                Text(run.detail,style=MaterialTheme.typography.bodySmall)
                if(expanded==run.id) {
                    Spacer(Modifier.height(10.dp));Text(run.goal)
                    run.steps.forEachIndexed { index,step ->
                        Spacer(Modifier.height(10.dp));Text("${index+1}. ${step.title} · ${step.status.name.lowercase()}",style=MaterialTheme.typography.titleSmall)
                        if(step.output.isNotBlank()) SelectionContainer { Text(step.output,style=MaterialTheme.typography.bodySmall) }
                    }
                    state.runApprovals.filter { it.runId==run.id }.forEach { ApprovalCard(it,actions) }
                    state.runActions[run.id].orEmpty().forEach { ActionReceiptCard(it,actions,false) }
                    if(run.status in setOf(RunStatus.PAUSED,RunStatus.BLOCKED,RunStatus.FAILED,RunStatus.NEEDS_APPROVAL) && run.currentStep!=null)TextButton(onClick={directing=run}) { Text("Answer / change directions") }
                    FlowRow(Modifier.fillMaxWidth()) {
                        if(!run.terminal) {
                            if(run.status in setOf(RunStatus.RUNNING,RunStatus.QUEUED)) TextButton(onClick={actions.pauseRun(run.id)}) { Text("Pause") }
                            else TextButton(onClick={actions.resumeRun(run.id)}) { Text("Resume") }
                            TextButton(onClick={actions.cancelRun(run.id)}) { Text("Cancel task") }
                            if(run.status in setOf(RunStatus.PAUSED,RunStatus.BLOCKED,RunStatus.FAILED) && run.currentStep!=null) TextButton(onClick={skip=run}) { Text("Skip step") }
                        } else TextButton(onClick={actions.deleteRun(run.id)}) { Text("Remove task") }
                        TextButton(onClick={actions.openRunChat(run.id);onChat()}) { Text("Open chat") }
                    }
                }
            }
        }
        item { HorizontalDivider();Text("Routines",style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(top=12.dp));Text("Runs on this phone when Android permits and a connection is available. Times are approximate. Missed occurrences do not stack up.",style=MaterialTheme.typography.bodySmall) }
        items(state.routines,key={it.id}) { routine ->
            SoftCard {
                Row(verticalAlignment=Alignment.CenterVertically) { Text(routine.title,style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f));Switch(checked=routine.enabled,onCheckedChange={actions.toggleRoutine(routine.id,it)}) }
                Text("${routine.frequency.name.lowercase()} · ${if(routine.enabled) "Next: "+DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a z").format(Instant.ofEpochMilli(routine.nextRunAt).atZone(ZoneId.of(routine.zoneId))) else "Paused"}",style=MaterialTheme.typography.bodySmall)
                Row { TextButton(onClick={editingRoutine=routine}) { Text("Edit") };TextButton(onClick={actions.deleteRoutine(routine.id)}) { Text("Delete routine") } }
            }
        }
        if(state.tasks.isNotEmpty()) item { Text("Conversation actions",style=MaterialTheme.typography.titleLarge) }
        items(state.tasks,key={"action-"+it.id}) { task -> ActionReceiptCard(task,actions,true) }
    }
    if(directing!=null) {
        var answer by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest={directing=null},title={Text("Direct this task")},text={Column { Text(directing!!.detail);OutlinedTextField(answer,{answer=it.take(4000)},label={Text("Your answer or directions")},minLines=3) }},confirmButton={TextButton(onClick={actions.directRun(directing!!.id,answer);directing=null},enabled=answer.isNotBlank()) { Text("Continue") }},dismissButton={TextButton(onClick={directing=null}) { Text("Cancel") }})
    }
    if(adding) TaskEditor(state,actions) { adding=false }
    if(newRoutine || editingRoutine!=null) RoutineEditor(state,actions,editingRoutine) { newRoutine=false;editingRoutine=null }
    if(skip!=null) AlertDialog(onDismissRequest={skip=null},title={Text("Skip this step after review?")},text={Text("Check any submitted action in the connected app first. Skipping does not undo it or mark an uncertain action successful. Remaining steps will continue.")},confirmButton={TextButton(onClick={actions.skipRunStep(skip!!.id);skip=null}) { Text("Skip & continue") }},dismissButton={TextButton(onClick={skip=null}) { Text("Keep paused") }})
}

@Composable private fun TaskEditor(state: KaiUiState, actions: KaiUiActions, onClose: ()->Unit) {
    var title by rememberSaveable { mutableStateOf("") };var goal by rememberSaveable { mutableStateOf("") };var steps by rememberSaveable { mutableStateOf("") }
    val lines=steps.lines().filter { it.isNotBlank() }
    AlertDialog(onDismissRequest=onClose,title={Text("Give KAI a task")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("Project: "+(state.projects.firstOrNull { it.id==state.projectId }?.name ?: "Personal"),style=MaterialTheme.typography.bodySmall)
        OutlinedTextField(title,{title=it.take(200)},label={Text("Task title")},singleLine=true)
        OutlinedTextField(goal,{goal=it.take(12000)},label={Text("What should KAI accomplish?")},minLines=3)
        OutlinedTextField(steps,{steps=it.take(12000)},label={Text("Optional steps · one per line")},minLines=2)
        Text("Leave steps blank for KAI to plan. Up to 8 steps. Uses your selected AI; provider usage charges may apply. Outgoing actions still need approval.",style=MaterialTheme.typography.bodySmall)
    }},confirmButton={TextButton(onClick={actions.createRun(title.trim(),goal.trim(),lines);onClose()},enabled=title.isNotBlank() && goal.isNotBlank() && lines.size<=8) { Text("Start task") }},dismissButton={TextButton(onClick=onClose) { Text("Cancel") }})
}
@Composable private fun RoutineEditor(state: KaiUiState, actions: KaiUiActions, existing: AgentRoutine?, onClose: ()->Unit) {
    var title by remember { mutableStateOf(existing?.title.orEmpty()) };var goal by remember { mutableStateOf(existing?.goal.orEmpty()) }
    var frequency by remember { mutableStateOf(existing?.frequency ?: RoutineFrequency.DAILY) }
    var time by remember { mutableStateOf("%02d:%02d".format(existing?.hour ?: 9,existing?.minute ?: 0)) }
    var day by remember { mutableIntStateOf(existing?.weekday ?: 1) }
    val parsed=runCatching { LocalTime.parse(time,DateTimeFormatter.ofPattern("HH:mm")) }.getOrNull()
    AlertDialog(onDismissRequest=onClose,title={Text(if(existing==null) "New routine" else "Edit routine")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(title,{title=it.take(200)},label={Text("Routine name")})
        OutlinedTextField(goal,{goal=it.take(12000)},label={Text("What should KAI do?")},minLines=3)
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) { RoutineFrequency.entries.forEach { f -> FilterChip(selected=frequency==f,onClick={frequency=f},label={Text(f.name.lowercase().replaceFirstChar { it.uppercase() })}) } }
        if(frequency!=RoutineFrequency.HOURLY)OutlinedTextField(time,{time=it.take(5)},label={Text("Time · HH:mm")},isError=parsed==null,singleLine=true)
        if(frequency==RoutineFrequency.WEEKLY)Row(Modifier.horizontalScroll(rememberScrollState())) { (1..7).forEach { value -> FilterChip(selected=day==value,onClick={day=value},label={Text(DayOfWeek.of(value).name.take(3))}) } }
        Text("${existing?.zoneId ?: ZoneId.systemDefault().id} · ${state.projects.firstOrNull { it.id==(existing?.projectId ?: state.projectId) }?.name ?: "Personal"}",style=MaterialTheme.typography.bodySmall)
        Text("Provider usage may be billed each time this runs. Android can delay runs while offline or conserving battery. A task awaiting review blocks duplicate occurrences.",style=MaterialTheme.typography.bodySmall)
    }},confirmButton={TextButton(onClick={
        val routine=(existing ?: AgentRoutine(title=title.trim(),goal=goal.trim(),projectId=state.projectId,characterId=state.selectedCharacterId,nextRunAt=0)).copy(title=title.trim(),goal=goal.trim(),frequency=frequency,hour=parsed?.hour ?: 9,minute=parsed?.minute ?: 0,weekday=day)
        actions.saveRoutine(routine);onClose()
    },enabled=title.isNotBlank() && goal.isNotBlank() && (parsed!=null || frequency==RoutineFrequency.HOURLY)) { Text("Save routine") }},dismissButton={TextButton(onClick=onClose) { Text("Cancel") }})
}

@Composable
internal fun ProjectsScreen(state: KaiUiState, actions: KaiUiActions, onChat: ()->Unit) {
    var adding by rememberSaveable { mutableStateOf(false) };var editing by remember { mutableStateOf<KaiProject?>(null) }
    var deleting by remember { mutableStateOf<KaiProject?>(null) };var document by remember { mutableStateOf<ProjectDocument?>(null) }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(22.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item { Text("Projects & files",style=MaterialTheme.typography.headlineMedium);Text("Give each project its own conversations, files and remembered details.",color=MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Row { Button(onClick={adding=true}) { Text("New project") };TextButton(onClick={actions.openProject("")}) { Text("Personal") } } }
        items(state.projects,key={it.id}) { project -> SoftCard {
            Text(project.name,style=MaterialTheme.typography.titleMedium);if(state.projectId==project.id)Text("Current project",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelMedium)
            if(project.memory.isNotBlank())Text(project.memory.take(180),style=MaterialTheme.typography.bodySmall)
            Row { TextButton(onClick={actions.openProject(project.id)}) { Text("Select") };TextButton(onClick={actions.openProject(project.id);onChat()}) { Text("Talk") };TextButton(onClick={editing=project}) { Text("Edit") };TextButton(onClick={deleting=project}) { Text("Delete") } }
        } }
        item { HorizontalDivider();Text("Files in "+(state.projects.firstOrNull { it.id==state.projectId }?.name ?: "this chat"),style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(top=12.dp));AttachmentBar(state,actions) }
        item { Row(verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Photo analysis with a vision model",style=MaterialTheme.typography.titleSmall);Text("Off: send extracted text. On: selected photos are also sent to your OpenAI-compatible vision model. Choose a model that supports images.",style=MaterialTheme.typography.bodySmall) };Switch(checked=state.visionInputEnabled,onCheckedChange=actions::setVisionInputEnabled) } }
        if(state.documents.isEmpty())item { Text("Add text, Markdown, CSV, JSON, DOCX, a PDF up to 12 pages, or a photo. Text is extracted on this phone. Selected excerpts go to your AI when you ask about them.",style=MaterialTheme.typography.bodyMedium) }
        items(state.documents,key={it.id}) { file -> SoftCard {
            Text(file.name,style=MaterialTheme.typography.titleMedium);Text("${file.sourceKind} · ${file.text.length} text characters"+(if(file.imageBase64.isNotBlank()) " · photo" else ""),style=MaterialTheme.typography.bodySmall)
            Row { TextButton(onClick={document=file}) { Text("Read") };TextButton(onClick={actions.selectDocument(file.id,file.id !in state.selectedDocuments);onChat()}) { Text("Use in chat") };TextButton(onClick={actions.deleteDocument(file.id)}) { Text("Remove") } }
        } }
    }
    if(adding || editing!=null) {
        var name by remember(editing) { mutableStateOf(editing?.name.orEmpty()) };var memory by remember(editing) { mutableStateOf(editing?.memory.orEmpty()) }
        AlertDialog(onDismissRequest={adding=false;editing=null},title={Text(if(editing==null) "New project" else "Project memory")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name,{name=it.take(100)},label={Text("Project name")})
            OutlinedTextField(memory,{memory=it.take(20000)},label={Text("What should KAI remember here?")},minLines=4)
            Text("Used only in this project. Project conversations can also save facts in Memory.",style=MaterialTheme.typography.bodySmall)
        }},confirmButton={TextButton(onClick={actions.saveProject(editing?.id,name.trim(),memory);adding=false;editing=null},enabled=name.isNotBlank()) { Text("Save project") }},dismissButton={TextButton(onClick={adding=false;editing=null}) { Text("Cancel") }})
    }
    if(deleting!=null) AlertDialog(onDismissRequest={deleting=null},title={Text("Delete ${deleting!!.name}?")},text={Text("Removes its files, chats, memory, tasks and routines from this phone. Submitted action details and receipts are kept so actions cannot be replayed accidentally.")},confirmButton={TextButton(onClick={actions.deleteProject(deleting!!.id);deleting=null}) { Text("Delete project") }},dismissButton={TextButton(onClick={deleting=null}) { Text("Keep") }})
    document?.let { file -> AlertDialog(onDismissRequest={document=null},title={Text(file.name)},text={SelectionContainer { Text(file.text.ifBlank { "No text detected. Enable photo analysis to ask a vision model about this image." },modifier=Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())) }},confirmButton={TextButton(onClick={document=null}) { Text("Done") }}) }
}

@Composable private fun ActionReceiptCard(task: KaiTask, actions: KaiUiActions, allowCancel: Boolean) {
    var reconciling by remember(task.id) { mutableStateOf(false) }
    var checked by remember(task.id) { mutableStateOf(false) }
    val uriHandler=androidx.compose.ui.platform.LocalUriHandler.current
    val safeUrl=task.resultUrl.takeIf { url -> runCatching { val uri=android.net.Uri.parse(url);uri.scheme=="https" && !uri.host.isNullOrBlank() }.getOrDefault(false) }
    SoftCard {
        Text(task.title,style=MaterialTheme.typography.titleMedium)
        Text(task.status.name.replace('_',' ').lowercase(),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
        Text(task.detail,style=MaterialTheme.typography.bodySmall)
        if(task.status==KaiTaskStatus.ACCEPTED)Text("Request accepted. Completion has not been confirmed.",style=MaterialTheme.typography.bodySmall)
        if(task.status==KaiTaskStatus.UNKNOWN)Text("Check the connected service before taking another action. Automatic replay is disabled.",style=MaterialTheme.typography.bodySmall)
        if(task.status==KaiTaskStatus.UNKNOWN) TextButton(onClick={reconciling=true}) { Text("Record checked outcome") }
        if(task.receiptId.isNotBlank())SelectionContainer { Text("Receipt: ${task.receiptId}",style=MaterialTheme.typography.bodySmall) }
        if(safeUrl!=null)TextButton(onClick={runCatching { uriHandler.openUri(safeUrl) }}) { Text("Open result") }
        if(allowCancel && task.status in setOf(KaiTaskStatus.QUEUED,KaiTaskStatus.WAITING,KaiTaskStatus.NEEDS_APPROVAL))TextButton(onClick={actions.cancelTask(task.id)}) { Text("Cancel task") }
    }
    if(reconciling) AlertDialog(onDismissRequest={reconciling=false},title={Text("Check ${task.title}")},text={Column {
        Text("Check this exact action in the connected service. KAI will record your confirmation without submitting or retrying anything.")
        Row { Checkbox(checked=checked,onCheckedChange={checked=it});Text("I checked the connected service") }
    }},confirmButton={TextButton(onClick={actions.confirmTaskOutcome(task.id,true);reconciling=false},enabled=checked) { Text("It occurred") }},dismissButton={Row {
        TextButton(onClick={actions.confirmTaskOutcome(task.id,false);reconciling=false},enabled=checked) { Text("It did not occur") }
        TextButton(onClick={reconciling=false}) { Text("Keep uncertain") }
    }})
}
