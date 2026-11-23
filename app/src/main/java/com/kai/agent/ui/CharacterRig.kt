package com.kai.agent.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val CHARACTER_HOST = "kai-character.invalid"
private val characterAssetIds = setOf("kai", "nova", "spark", "sage")

internal fun characterAssetId(id: String): String = id.takeIf { it in characterAssetIds } ?: "kai"

/** An allowlist, not a general asset server. Neither messages nor account data enter this document. */
internal fun permittedCharacterAsset(id: String, path: String): String? {
    val safeId = characterAssetId(id)
    val directory = "/characters/$safeId/"
    return path.takeIf { it in setOf("$directory$safeId-texture.webp", "$directory$safeId-texture.png") }?.removePrefix("/")
}

/** Android 9 may still ship a Chromium version without :is(); preserve the bundled rig rules. */
internal fun compatibleCharacterCss(css: String): String = Regex("([^{}]*):is\\(([^)]+)\\)([^{}]*)\\{").replace(css) { rule ->
    rule.groupValues[2].split(',').joinToString(",") { choice ->
        rule.groupValues[1] + choice.trim() + rule.groupValues[3]
    } + "{"
}

/** Native connected skin, local face sprites and explicit foreground motion. */
@Composable
internal fun CharacterRig(character: KaiCharacter,activity: KaiCharacterActivity,modifier: Modifier,animated: Boolean,speechAudible: Boolean,reducedMotion: Boolean,hapticsEnabled: Boolean,interactive: Boolean) {
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle){mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))}
    DisposableEffect(lifecycle){val observer=LifecycleEventObserver{_,_->foreground=lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)};lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer)}}
    var greeting by remember(character.id){mutableIntStateOf(0)}
    var visible by remember(character.id){mutableStateOf(true)}
    val haptics=LocalHapticFeedback.current
    val hello={if(hapticsEnabled)haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove);greeting++}
    val motion=animated&&!reducedMotion&&foreground&&visible
    Box(modifier.onGloballyPositioned{visible=!it.boundsInWindow().isEmpty}.testTag("character-${character.id}").semantics(mergeDescendants=true){contentDescription="${character.name}, ${activity.label}";stateDescription=if(motion)"Animated" else "Still";if(interactive){role=Role.Button;onClick(label="Say hello to ${character.name}"){hello();true}}}) {
        key(characterAssetId(character.id)) {
            if(animated) NativeAnimatedCharacter(character.id,activity,speechAudible,motion,foreground&&visible,greeting)
            else CharacterPoster(character.id)
        }
        if(interactive)Box(Modifier.matchParentSize().pointerInput(character.id,hapticsEnabled){detectTapGestures{hello()}})
    }
}
@Composable
private fun NativeAnimatedCharacter(id: String,activity: KaiCharacterActivity,audible: Boolean,motion: Boolean,foreground: Boolean,greeting: Int) {
    val context=LocalContext.current;val safe=characterAssetId(id)
    val art by produceState<CharacterSkinAssets?>(null,safe){value=withContext(Dispatchers.IO){runCatching{CharacterSkinAssets.load(context,safe)}.getOrNull()}}
    val view=remember(art){art?.let{NativeCharacterView(context,it)}}
    DisposableEffect(view){onDispose{view?.release()}}
    if(view!=null)AndroidView(modifier=Modifier.fillMaxSize().testTag("character-rig-live-$safe"),factory={view},update={it.present(activity,audible,motion,foreground,greeting)})
    else CharacterPoster(safe)
}
@Composable
private fun CharacterPoster(id: String) {
    val context=LocalContext.current;val safe=characterAssetId(id)
    val poster by produceState<ImageBitmap?>(null,safe){value=withContext(Dispatchers.IO){runCatching{context.assets.open("characters/$safe/$safe-poster.webp").use{BitmapFactory.decodeStream(it)?.asImageBitmap()}}.getOrNull()}}
    poster?.let{Image(it,contentDescription=null,modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Fit)}
}
