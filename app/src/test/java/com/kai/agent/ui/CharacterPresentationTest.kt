package com.kai.agent.ui

import org.junit.Assert.*
import org.junit.Test

class CharacterPresentationTest {
    @Test fun `old completed tasks do not trigger celebration`() {
        val completed = KaiTask("old", "Old task", status = KaiTaskStatus.COMPLETED)
        assertFalse(hasNewTaskCompletion(emptyMap(), listOf(completed)))
        assertFalse(hasNewTaskCompletion(mapOf("old" to KaiTaskStatus.COMPLETED), listOf(completed)))
        assertTrue(hasNewTaskCompletion(mapOf("old" to KaiTaskStatus.RUNNING), listOf(completed)))
        assertFalse(hasNewTaskCompletion(mapOf("old" to KaiTaskStatus.UNKNOWN), listOf(completed)))
    }

    @Test fun `review and errors never show a success celebration`() {
        val review = KaiUiState(pendingApproval = KaiApproval("a", "Send", "Review message"))
        assertEquals(KaiCharacterActivity.REVIEW, characterActivity(review, true))
        assertEquals(KaiCharacterActivity.ERROR, characterActivity(review.copy(error = "Needs attention"), true))
    }

    @Test fun `speech pose remains speaking while audible output pauses`() {
        val speaking = KaiUiState(voiceState = KaiVoiceState.SPEAKING, speechAudible = true)
        assertEquals(KaiCharacterActivity.SPEAKING, characterActivity(speaking))
        assertEquals(KaiCharacterActivity.SPEAKING, characterActivity(speaking.copy(speechAudible = false)))
        assertFalse(characterStatus(speaking.copy(speechAudible = false), KaiCharacterActivity.SPEAKING).startsWith("Speaking"))
    }

    @Test fun `unfinished historical tasks do not make idle character claim to work`() {
        val state = KaiUiState(tasks = listOf(KaiTask("t", "Task", status = KaiTaskStatus.RUNNING)))
        assertEquals(KaiCharacterActivity.IDLE, characterActivity(state))
        assertEquals(KaiCharacterActivity.WORKING, characterActivity(state.copy(isBusy = true)))
        assertEquals(KaiCharacterActivity.LISTENING, characterActivity(state.copy(isBusy = true, voiceState = KaiVoiceState.LISTENING)))
    }

    @Test fun `asset server only permits known local character textures`() {
        assertEquals("characters/kai/kai-texture.webp", permittedCharacterAsset("kai", "/characters/kai/kai-texture.webp"))
        assertEquals("characters/nova/nova-texture.png", permittedCharacterAsset("nova", "/characters/nova/nova-texture.png"))
        assertNull(permittedCharacterAsset("kai", "/characters/nova/nova-texture.png"))
        assertNull(permittedCharacterAsset("kai", "/characters/kai/../../memory/model_manifest.json"))
        assertNull(permittedCharacterAsset("kai", "/characters/kai/kai-texture.webp?remote=true"))
        assertNull(permittedCharacterAsset("kai", "/files/settings.json"))
        assertEquals("kai", characterAssetId("../../files"))
    }

    @Test fun `desktop state selectors work on original Android 9 webview`() {
        val css = "body:is([data-state=thinking],[data-state=searching]) .head{transform:rotate(5deg)}"
        assertEquals("body[data-state=thinking] .head,body[data-state=searching] .head{transform:rotate(5deg)}", compatibleCharacterCss(css))
        val staticRule = "body.motion-off .head{animation:none}"
        assertEquals(staticRule, compatibleCharacterCss(staticRule))
        val nestedRule = "@media(prefers-reduced-motion:reduce){body:not(.waving):is([data-state=thinking],[data-state=voicing]) .arm{transform:rotate(3deg)}}"
        assertEquals("@media(prefers-reduced-motion:reduce){body:not(.waving)[data-state=thinking] .arm,body:not(.waving)[data-state=voicing] .arm{transform:rotate(3deg)}}", compatibleCharacterCss(nestedRule))
    }
}
