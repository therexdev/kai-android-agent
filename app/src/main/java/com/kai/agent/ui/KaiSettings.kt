package com.kai.agent.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kai.agent.data.normalizedSpeechLanguageTag
import java.util.Locale

@Composable
internal fun SettingsContent(state:KaiUiState,actions:KaiUiActions,onClose:()->Unit,onVoice:()->Unit = {},onReport:()->Unit = {}) {
    var searchKey by remember { mutableStateOf("") }
    var removeComposioKey by remember {mutableStateOf(false)}
    Column(Modifier.fillMaxWidth().fillMaxHeight(.94f).imePadding().verticalScroll(rememberScrollState()).padding(horizontal=24.dp).padding(bottom=28.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically){Text("Make KAI yours.",style=MaterialTheme.typography.headlineMedium,modifier=Modifier.weight(1f));IconButton(onClick=onClose){KaiIcon(KaiGlyph.CLOSE,label="Close settings")}}
        OutlinedButton(onClick=onVoice,modifier=Modifier.fillMaxWidth()) { KaiIcon(KaiGlyph.VOLUME);Spacer(Modifier.width(8.dp));Text("Voice options & preview") }
        HorizontalDivider(Modifier.padding(vertical=6.dp),color=MaterialTheme.colorScheme.outlineVariant)
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){KaiIcon(if(state.isDarkTheme) KaiGlyph.MOON else KaiGlyph.SUN,tint=MaterialTheme.colorScheme.primary);Column(Modifier.weight(1f)){Text("After-dark look",style=MaterialTheme.typography.titleMedium);Text("Switch between dark and light",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Switch(checked=state.isDarkTheme,onCheckedChange=actions::setDarkTheme)}
        SoftCard {
            Text("Web search", style=MaterialTheme.typography.titleMedium)
            Text("Search and page reading run from this phone with your selected model. Internet is required. Public search queries go to DuckDuckGo by default. Pages may be unavailable or require sign-in.", style=MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Text("Optional: use your own Brave Search API key for a dedicated search service. Your Brave plan applies; DuckDuckGo is the fallback.", style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(value=searchKey,onValueChange={searchKey=it},label={Text("Brave Search API key")},placeholder={Text(if(state.braveSearchKeySaved) "Saved securely on this phone" else "Optional")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
            Row {
                TextButton(enabled=searchKey.isNotBlank(),onClick={actions.saveSearchKey(searchKey);searchKey=""}) { Text("Save search key") }
                if(state.braveSearchKeySaved) TextButton(onClick={actions.saveSearchKey("");searchKey=""}) { Text("Use default search") }
            }
        }
        SpeechSettings(state,actions)
        CharacterExperienceSettings(state,actions)
        MemoryControls(state,actions)
        SoftCard {
            Text("Your data goes with you",style=MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp));Text("Export includes your conversations, memories, and task data. AI, search, and Composio API keys are excluded. The export is not encrypted; keep it somewhere private.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(14.dp));Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick=actions::exportData,modifier=Modifier.weight(1f),shape=RoundedCornerShape(12.dp)){Text("Export data")}
                OutlinedButton(onClick=actions::importData,modifier=Modifier.weight(1f),shape=RoundedCornerShape(12.dp)){Text("Import data")}
            }
        }
        if(state.composioKeySaved) OutlinedButton(onClick={removeComposioKey=true},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(13.dp)){Text("Remove Composio key from this phone")}
        SoftCard {Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){KaiIcon(KaiGlyph.SHIELD,Modifier.size(19.dp),MaterialTheme.colorScheme.secondary);Column {Text("You're in control",style=MaterialTheme.typography.labelLarge);Spacer(Modifier.height(5.dp));Text("Choose what KAI remembers and which accounts it can use. Review outgoing actions before they happen.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
        Text("KAI · Android early access",modifier=Modifier.fillMaxWidth(),textAlign=TextAlign.Center,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick=onReport,enabled=state.conversations.any { !it.temporary },modifier=Modifier.fillMaxWidth()) { Text("Report") }
    }
    if(removeComposioKey) AlertDialog(onDismissRequest={removeComposioKey=false},title={Text("Remove Composio key?")},text={Text("Removes the saved key from this phone. Your Composio project and connected accounts stay in place, including connections used by other agents.")},confirmButton={TextButton(onClick={actions.signOutConnections();removeComposioKey=false}){Text("Remove key",color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton(onClick={removeComposioKey=false}){Text("Keep key")}})
}

@Composable
internal fun CharacterStudio(state:KaiUiState,actions:KaiUiActions,onClose:()->Unit) {
    val selected=state.characters.firstOrNull{it.id==state.selectedCharacterId} ?: builtInKaiCharacters.first()
    Column(Modifier.fillMaxWidth().fillMaxHeight(.94f).verticalScroll(rememberScrollState()).padding(horizontal=24.dp).padding(bottom=30.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically){Text("A personality that fits.",style=MaterialTheme.typography.headlineMedium,modifier=Modifier.weight(1f));IconButton(onClick=onClose){KaiIcon(KaiGlyph.CLOSE,label="Close characters")}}
        Text("Same helpful KAI. A different kind of company.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        CharacterAvatar(selected,state.voiceState,Modifier.fillMaxWidth().height(188.dp),animated=true,speechAudible=state.speechAudible,reducedMotion=state.reducedMotion,hapticsEnabled=state.hapticsEnabled)
        Text(selected.name,style=MaterialTheme.typography.headlineMedium,modifier=Modifier.fillMaxWidth(),textAlign=TextAlign.Center)
        Spacer(Modifier.height(6.dp));Text(selected.description,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp))
        Spacer(Modifier.height(24.dp))
        state.characters.chunked(2).forEach {row ->
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.fillMaxWidth().padding(bottom=12.dp)) {
                row.forEach {character ->
                    val active=character.id==selected.id
                    Surface(onClick={actions.selectCharacter(character.id)},modifier=Modifier.weight(1f),shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceContainer,border=if(active) BorderStroke(1.5.dp,Color(character.color).copy(alpha=.8f)) else BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.4f))) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(9.dp).clip(CircleShape).background(Color(character.color)));Spacer(Modifier.weight(1f));if(active) KaiIcon(KaiGlyph.CHECK,Modifier.size(16.dp),MaterialTheme.colorScheme.primary)}
                            CharacterAvatar(character,KaiVoiceState.IDLE,Modifier.fillMaxWidth().height(86.dp),animated=false)
                            Text(character.name,style=MaterialTheme.typography.titleMedium)
                            Text(character.subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2)
                        }
                    }
                }
                if(row.size==1) Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(13.dp));Text("A VOICE OF THEIR OWN",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,letterSpacing=1.2.sp)
        Spacer(Modifier.height(8.dp));Text("Kiki is the default voice. Tap ▶ to preview it, or choose an installed Android voice for ${selected.name}.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp));Text("Kiki speaks English and runs on your phone, with no speech API key. Its first use prepares the voice engine.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp));Text(if(state.localSpeechOnly) "Local speech only is on. Online voices are hidden, and KAI requires an installed offline voice. Your saved voice choice stays with this character." else "Speech recognition uses your Android speech service and may process audio online. “On device” below refers to spoken replies.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(13.dp))
        val availableVoices=state.voices.filter { !state.localSpeechOnly || it.local }
        if(availableVoices.isEmpty()) SoftCard {Text("No speech voices available",style=MaterialTheme.typography.titleMedium);Spacer(Modifier.height(6.dp));Text("Install a text-to-speech voice in Android's language and speech settings to hear KAI.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        availableVoices.forEach {voice ->
            val active=voice.id==state.selectedVoiceId
            Surface(onClick={actions.selectVoice(voice.id)},shape=RoundedCornerShape(15.dp),color=if(active) MaterialTheme.colorScheme.primaryContainer.copy(alpha=.4f) else MaterialTheme.colorScheme.surfaceContainer,modifier=Modifier.fillMaxWidth().padding(bottom=8.dp)) {
                Row(Modifier.padding(start=15.dp,end=5.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(17.dp).clip(CircleShape).border(if(active) 5.dp else 1.dp,if(active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,CircleShape))
                    Column(Modifier.weight(1f)){Text(voice.label,style=MaterialTheme.typography.labelLarge,maxLines=1,overflow=TextOverflow.Ellipsis);Text(listOf(voice.locale,if(voice.id=="system") "Follows device settings" else if(voice.local) "On device" else "Online",voice.detail).filter{it.isNotBlank()}.joinToString(" · "),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)}
                    IconButton(onClick={actions.previewVoice(voice.id)}){KaiIcon(KaiGlyph.PLAY,Modifier.size(17.dp),MaterialTheme.colorScheme.primary,label="Preview ${voice.label}")}
                }
            }
        }
        Spacer(Modifier.height(16.dp));SpeechSettings(state,actions)
        Spacer(Modifier.height(16.dp));CharacterExperienceSettings(state,actions)
        Spacer(Modifier.height(16.dp));Button(onClick=onClose,shape=RoundedCornerShape(15.dp),modifier=Modifier.fillMaxWidth().height(50.dp)){Text("That's my ${selected.name}")}
    }
}

@Composable
internal fun SpeechSettings(state: KaiUiState, actions: KaiUiActions) {
    var rate by remember(state.speechRate) { mutableFloatStateOf(state.speechRate.coerceIn(.5f,2f)) }
    var language by remember(state.speechLanguageTag) { mutableStateOf(state.speechLanguageTag) }
    val normalizedLanguage=normalizedSpeechLanguageTag(language)
    SoftCard {
        Text("Conversation & voice",style=MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(7.dp))
        Text("Give your thoughts room to land. These preferences apply to all characters.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text("Pause before replying",style=MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
            KaiSpeechPacing.entries.forEach { pacing ->
                FilterChip(selected=state.speechPacing==pacing,onClick={actions.setSpeechPacing(pacing)},label={Text(when(pacing){KaiSpeechPacing.NATURAL->"Natural";KaiSpeechPacing.PATIENT->"Patient";KaiSpeechPacing.QUICK->"Quick"})},modifier=Modifier.weight(1f))
            }
        }
        Text(when(state.speechPacing){
            KaiSpeechPacing.NATURAL->"Responds sooner to a complete thought and leaves more room for an unfinished phrase."
            KaiSpeechPacing.PATIENT->"More time to think, pause, and finish your sentence."
            KaiSpeechPacing.QUICK->"A shorter pause for quick back-and-forth."
        },style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("Speaking speed",style=MaterialTheme.typography.labelLarge,modifier=Modifier.weight(1f))
            Text(String.format(Locale.getDefault(),"%.1f×",rate),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
        }
        Slider(value=rate,onValueChange={rate=it},onValueChangeFinished={actions.setSpeechRate(rate)},valueRange=.5f..2f,steps=14,modifier=Modifier.semantics { contentDescription="Speaking speed" })
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("Slower",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Faster",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(value=language,onValueChange={language=it},label={Text("Recognition language")},placeholder={Text("System default")},singleLine=true,isError=normalizedLanguage==null,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Ascii),supportingText={Text(if(normalizedLanguage==null) "Use a language tag such as en-US, es, or fr." else "Leave empty to follow your device language. Examples: en-US, es, fr.")},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(13.dp))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
            if(language.isNotBlank()) TextButton(onClick={language="";actions.setSpeechLanguageTag("")}) { Text("Use device language") }
            TextButton(onClick={normalizedLanguage?.let { actions.setSpeechLanguageTag(it) }},enabled=normalizedLanguage!=null && normalizedLanguage!=state.speechLanguageTag) { Text("Save language") }
        }
        Text("Kiki speaks English. Installed Android voices follow this language.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        PreferenceSwitch("Local speech only","Use on-device recognition and installed offline voices. Voice is unavailable if your phone cannot provide them. Your selected AI and connected apps still use their services.",state.localSpeechOnly,actions::setLocalSpeechOnly)
        Spacer(Modifier.height(16.dp))
        Text("Conversation audio",style=MaterialTheme.typography.labelLarge)
        Text(state.voiceAudioRouteStatus,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        state.voiceAudioRoutes.forEach { route ->
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                RadioButton(selected=route.id==state.selectedAudioRoute,onClick={actions.selectAudioRoute(route.id)},enabled=!state.voiceEnabled)
                TextButton(onClick={actions.selectAudioRoute(route.id)},enabled=!state.voiceEnabled) { Text(route.label) }
            }
        }
        Text(if(state.voiceEnabled) "End the conversation before changing its audio route." else "System audio follows Android’s current output. To test a headset microphone, connect it in Android first and select its conversation route here. Microphone support depends on your speech service. Older Android versions follow system routing.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text("Long conversation test",style=MaterialTheme.typography.labelLarge)
        Text(if(state.voiceTestRunning) "Test timings are being collected while KAI is open. Save the report after your conversation, or clear it." else "Optional: collect speech timings, interruptions, route changes and error counts. No audio, transcript, keys or account details are included. Starting a test does not turn on the microphone.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            if(!state.voiceTestRunning) TextButton(onClick=actions::startVoiceTest) { Text("Start test") }
            else {
                TextButton(onClick=actions::exportVoiceTest) { Text("Save test report") }
                TextButton(onClick=actions::clearVoiceTest) { Text("Clear test") }
            }
        }
    }
}

@Composable
private fun CharacterExperienceSettings(state: KaiUiState, actions: KaiUiActions) {
    SoftCard {
        Text("Character experience",style=MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        PreferenceSwitch("Reduce motion","Keep character animations still and limit decorative motion.",state.reducedMotion,actions::setReducedMotion)
        HorizontalDivider(Modifier.padding(vertical=10.dp),color=MaterialTheme.colorScheme.outlineVariant)
        PreferenceSwitch("Touch feedback","A gentle vibration when you tap a character. Follows your phone's haptic settings.",state.hapticsEnabled,actions::setHapticsEnabled)
    }
}

@Composable
private fun PreferenceSwitch(title: String, detail: String, checked: Boolean, onCheckedChange: (Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value=checked,role=Role.Switch,onValueChange=onCheckedChange).padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title,style=MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            Text(detail,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked=checked,onCheckedChange=null)
    }
}
