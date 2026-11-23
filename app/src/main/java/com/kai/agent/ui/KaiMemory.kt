package com.kai.agent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun MemoryControls(state:KaiUiState,actions:KaiUiActions) {
    SoftCard {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)) {
            KaiIcon(KaiGlyph.MEMORY,Modifier.size(19.dp),MaterialTheme.colorScheme.primary)
            Text("How KAI remembers",style=MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(4.dp),horizontalArrangement=Arrangement.spacedBy(3.dp)) {
            KaiMemoryMode.entries.forEach {mode ->
                val selected=state.memoryMode==mode
                Surface(onClick={actions.setMemoryMode(mode)},shape=RoundedCornerShape(10.dp),color=if(selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,modifier=Modifier.weight(1f)) {
                    Box(Modifier.padding(horizontal=4.dp,vertical=11.dp),contentAlignment=Alignment.Center) {
                        Text(when(mode){KaiMemoryMode.AUTOMATIC->"Automatic";KaiMemoryMode.ASK_FIRST->"Ask first";KaiMemoryMode.OFF->"Off"},style=MaterialTheme.typography.labelMedium,color=if(selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(when(state.memoryMode){KaiMemoryMode.AUTOMATIC->"Remember useful details as you talk. Sensitive details still need your confirmation.";KaiMemoryMode.ASK_FIRST->"Suggest useful details and ask before saving them.";KaiMemoryMode.OFF->"Don’t save new details from conversation. You can still add a memory yourself."},style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.padding(vertical=13.dp),color=MaterialTheme.colorScheme.outlineVariant)
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Use saved memory",style=MaterialTheme.typography.labelLarge)
                Text(if(state.useSavedMemory) "Bring relevant details into replies" else "Keep saved details out of replies",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked=state.useSavedMemory,onCheckedChange=actions::setUseSavedMemory,modifier=Modifier.semantics{contentDescription="Use saved memory"})
        }
    }
}

@Composable
internal fun MemorySuggestionCard(suggestion:KaiMemorySuggestion,actions:KaiUiActions,modifier:Modifier=Modifier) {
    var entityChoice by remember(suggestion.id) {mutableStateOf<String?>(null)}
    Surface(modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.secondaryContainer.copy(alpha=.38f),border=BorderStroke(1.dp,MaterialTheme.colorScheme.secondary.copy(alpha=.2f))) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                KaiIcon(KaiGlyph.MEMORY,Modifier.size(17.dp),MaterialTheme.colorScheme.secondary)
                Text(if(suggestion.needsSpelling) "CHECK SPELLING" else if(suggestion.updating) "UPDATE MEMORY?" else "WORTH REMEMBERING?",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.secondary)
            }
            Text(suggestion.text,style=MaterialTheme.typography.bodyLarge)
            if(suggestion.needsSpelling) Text("Type the exact name in chat before saving this detail.",style=MaterialTheme.typography.bodyMedium)
            if(suggestion.reason.isNotBlank()) Text(suggestion.reason,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(suggestion.relatedPeople.isNotEmpty()) MemoryPeople(suggestion.relatedPeople)
            Text("From your message: " + suggestion.source,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(suggestion.entityChoices.isNotEmpty()) {
                Text("Which one did you mean?",style=MaterialTheme.typography.labelLarge)
                suggestion.entityChoices.forEach {choice ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable{entityChoice=choice.id}.padding(end=8.dp),verticalAlignment=Alignment.CenterVertically) {
                        RadioButton(selected=entityChoice==choice.id,onClick={entityChoice=choice.id})
                        Text(choice.label,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.weight(1f))
                    }
                }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick={actions.dismissMemorySuggestion(suggestion.id)},modifier=Modifier.weight(1f),shape=RoundedCornerShape(12.dp)){Text("No, dismiss")}
                Button(onClick={actions.acceptMemorySuggestion(suggestion.id,entityChoice)},enabled=!suggestion.needsSpelling && (suggestion.entityChoices.isEmpty() || entityChoice!=null),modifier=Modifier.weight(1f),shape=RoundedCornerShape(12.dp)){Text(if(suggestion.updating) "Yes, update" else if(suggestion.entityChoices.isEmpty()) "Yes, save" else "Confirm & save")}
            }
        }
    }
}

@Composable
internal fun MemorySavedNotice(notice:KaiMemoryNotice,actions:KaiUiActions,modifier:Modifier=Modifier) {
    Surface(modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.secondaryContainer.copy(alpha=.6f)) {
        Row(Modifier.padding(start=12.dp,end=2.dp,top=2.dp,bottom=2.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            KaiIcon(KaiGlyph.CHECK,Modifier.size(16.dp),MaterialTheme.colorScheme.secondary)
            Text(notice.text,style=MaterialTheme.typography.bodySmall,modifier=Modifier.weight(1f),maxLines=2,overflow=TextOverflow.Ellipsis)
            TextButton(onClick={actions.undoMemorySave(notice.id)},contentPadding=PaddingValues(horizontal=6.dp)){Text("Undo",style=MaterialTheme.typography.labelMedium)}
            IconButton(onClick=actions::dismissMemoryNotice,modifier=Modifier.size(36.dp)){KaiIcon(KaiGlyph.CLOSE,Modifier.size(14.dp),MaterialTheme.colorScheme.onSurfaceVariant,label="Dismiss saved memory notice")}
        }
    }
}

@Composable
internal fun MemoryPeople(people:List<String>) {
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        people.distinct().forEach {person ->
            Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=RoundedCornerShape(8.dp)) {
                Text(person,Modifier.padding(horizontal=9.dp,vertical=5.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSecondaryContainer,maxLines=1)
            }
        }
    }
}

@Composable
internal fun MemoryProvenance(memory:KaiMemory) {
    if(memory.relatedPeople.isNotEmpty()) {Spacer(Modifier.height(13.dp));MemoryPeople(memory.relatedPeople)}
    if(memory.relatedFacts.isNotEmpty()) {
        Spacer(Modifier.height(13.dp))
        Text("CONNECTED DETAILS",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.secondary)
        memory.relatedFacts.take(4).forEach {fact ->
            Row(Modifier.padding(top=6.dp),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                KaiIcon(KaiGlyph.LINK,Modifier.size(13.dp).padding(top=2.dp),MaterialTheme.colorScheme.onSurfaceVariant)
                Text(fact,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(memory.source,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    if(memory.sourceDetail.isNotBlank()) {
        Spacer(Modifier.height(7.dp))
        Surface(shape=RoundedCornerShape(10.dp),color=MaterialTheme.colorScheme.surfaceContainerHighest) {
            SelectionContainer {Text(memory.sourceDetail,Modifier.padding(10.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=5,overflow=TextOverflow.Ellipsis)}
        }
    }
    if(!memory.editable) {Spacer(Modifier.height(8.dp));Text("To correct this linked fact, tell KAI what has changed.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
}

@Composable
internal fun TemporaryChatBanner(actions:KaiUiActions) {
    Surface(Modifier.fillMaxWidth().padding(horizontal=22.dp,vertical=5.dp),shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(start=12.dp,end=5.dp,top=7.dp,bottom=7.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)) {
            KaiIcon(KaiGlyph.SHIELD,Modifier.size(18.dp),MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text("Temporary chat",style=MaterialTheme.typography.labelMedium)
                Text("This chat stays in this session. It won’t use or save memories.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick=actions::newConversation,contentPadding=PaddingValues(8.dp)){Text("End",style=MaterialTheme.typography.labelMedium)}
        }
    }
}
