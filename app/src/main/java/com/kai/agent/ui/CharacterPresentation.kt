package com.kai.agent.ui

/** Presentation is separate from microphone state: a task can need review while voice is off. */
enum class KaiCharacterActivity(val webState: String, val label: String) {
    IDLE("idle", "ready"),
    LISTENING("listening", "listening"),
    THINKING("thinking", "thinking"),
    SPEAKING("speaking", "speaking"),
    WORKING("searching", "working"),
    REVIEW("review", "waiting for your review"),
    SUCCESS("success", "task complete"),
    ERROR("error", "needs attention"),
}

fun KaiVoiceState.characterActivity(): KaiCharacterActivity = when (this) {
    KaiVoiceState.IDLE -> KaiCharacterActivity.IDLE
    KaiVoiceState.LISTENING -> KaiCharacterActivity.LISTENING
    KaiVoiceState.THINKING -> KaiCharacterActivity.THINKING
    KaiVoiceState.SPEAKING -> KaiCharacterActivity.SPEAKING
}

internal fun characterActivity(state: KaiUiState, justCompletedTask: Boolean = false): KaiCharacterActivity = when {
    state.error != null -> KaiCharacterActivity.ERROR
    state.pendingApproval != null -> KaiCharacterActivity.REVIEW
    state.voiceState == KaiVoiceState.LISTENING -> KaiCharacterActivity.LISTENING
    state.voiceState == KaiVoiceState.SPEAKING -> KaiCharacterActivity.SPEAKING
    state.isBusy && state.tasks.any { it.status in setOf(KaiTaskStatus.RUNNING, KaiTaskStatus.WAITING, KaiTaskStatus.ACCEPTED) } -> KaiCharacterActivity.WORKING
    state.isBusy || state.voiceState == KaiVoiceState.THINKING -> KaiCharacterActivity.THINKING
    justCompletedTask -> KaiCharacterActivity.SUCCESS
    else -> KaiCharacterActivity.IDLE
}

/** Only a transition observed in this open conversation earns a celebration, never old history. */
internal fun hasNewTaskCompletion(previous: Map<String, KaiTaskStatus>, tasks: List<KaiTask>): Boolean = tasks.any {
    it.status == KaiTaskStatus.COMPLETED && previous[it.id] in setOf(KaiTaskStatus.RUNNING, KaiTaskStatus.WAITING, KaiTaskStatus.ACCEPTED, KaiTaskStatus.QUEUED)
}

internal fun characterStatus(state: KaiUiState, activity: KaiCharacterActivity): String = when (activity) {
    KaiCharacterActivity.IDLE -> if (state.voiceEnabled) "Voice is ready" else "Here when you need me"
    KaiCharacterActivity.LISTENING -> "Listening · take your time"
    KaiCharacterActivity.THINKING -> "Thinking it through"
    KaiCharacterActivity.SPEAKING -> if (state.speechAudible) "Speaking · tap Your turn to jump in" else "Getting the next words ready"
    KaiCharacterActivity.WORKING -> "Working on your request"
    KaiCharacterActivity.REVIEW -> "Ready for your review"
    KaiCharacterActivity.SUCCESS -> "Task complete"
    KaiCharacterActivity.ERROR -> "Something needs attention"
}
