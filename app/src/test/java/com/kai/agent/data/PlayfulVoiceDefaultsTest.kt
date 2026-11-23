package com.kai.agent.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlayfulVoiceDefaultsTest {
    @Test fun oldModelMovesOnceAndPreservesTheRestOfTheState() {
        val old = KaiState(settings = AppSettings(playfulVoiceDefaultApplied = false,
            koinos = ProviderProfile(apiKey = "fixture-key"), composioUserId = "exact-owner", speechRate = 1.2f,
            speechPacing = SpeechPacing.PATIENT, speechLanguageTag = "en-GB"),
            characters = defaultCharacters().map { it.copy(voiceId = if (it.id == "kai") DesktopVoiceDefaults.VOICE_ID else "chosen-${it.id}", deviceVoiceBaseId="saved-base") },
            conversations = listOf(Conversation(title = "Keep history")))
        val expected = old.copy(settings=old.settings.copy(playfulVoiceDefaultApplied=true), characters=old.characters.map {
            if(it.id=="kai") it.copy(voiceId=PlayfulVoiceDefaults.CUTE) else it
        })
        assertEquals(expected, PlayfulVoiceDefaults.migrate(old))
        val explicitOriginal = expected.copy(characters = expected.characters.map { it.copy(voiceId=DesktopVoiceDefaults.VOICE_ID) })
        assertEquals(explicitOriginal, PlayfulVoiceDefaults.migrate(KaiJson.decode(KaiJson.encode(explicitOriginal))))
    }
    @Test fun deliberateDeviceAndStyleChoicesSurviveAndFreshInstallUsesCute() {
        for (voice in listOf("system", "chosen-device-voice", PlayfulVoiceDefaults.SQUEAKY)) {
            val original=KaiState(settings=AppSettings(playfulVoiceDefaultApplied=false), characters=defaultCharacters().map { it.copy(voiceId=voice) })
            assertEquals(original.characters,PlayfulVoiceDefaults.migrate(original).characters)
        }
        assertEquals(KikiVoiceDefaults.VOICE_ID, KaiState().characters.first {it.id=="kai"}.voiceId)
        assertEquals(listOf(1.35f,1.55f,1.8f),PlayfulVoiceDefaults.styles.map {it.pitch})
        assertNull(PlayfulVoiceDefaults.style("chosen-device-voice"))
    }
    @Test fun olderJsonAndRetiredFastPresetReachTheNewDefault() {
        for (oldId in listOf(DesktopVoiceDefaults.VOICE_ID,ConversationVoiceDefaults.VOICE_ID)) {
            val old=KaiState(characters=defaultCharacters().map {if(it.id=="kai")it.copy(voiceId=oldId) else it})
            val json=JSONObject(KaiJson.encode(old)).apply {getJSONObject("settings").remove("playfulVoiceDefaultApplied")}
            val restored=PlayfulVoiceDefaults.migrate(ConversationVoiceDefaults.migrate(DesktopVoiceDefaults.migrate(KaiJson.decode(json.toString()))))
            assertEquals(PlayfulVoiceDefaults.CUTE,restored.characters.first {it.id=="kai"}.voiceId)
            assertEquals(restored,PlayfulVoiceDefaults.migrate(restored))
        }
    }
    @Test fun baseVoiceRoundTripsAndCannotPointToAnotherPresetOrTheNativeModel() {
        val state=KaiState(characters=defaultCharacters().map {it.copy(deviceVoiceBaseId="real-engine-voice")})
        assertEquals(state,KaiJson.decode(KaiJson.encode(state)))
        assertEquals(state.characters,KaiJson.decode(KaiJson.encode(state,includeSecrets=false)).characters)
        for (invalid in listOf("",DesktopVoiceDefaults.VOICE_ID,ConversationVoiceDefaults.VOICE_ID)+PlayfulVoiceDefaults.styles.map {it.id}) {
            val invalidState=state.copy(characters=state.characters.map {it.copy(deviceVoiceBaseId=invalid)})
            assertTrue(KaiJson.decode(KaiJson.encode(invalidState)).characters.all {it.deviceVoiceBaseId=="system"})
        }
    }
}
