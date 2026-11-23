package com.kai.agent.voice

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler

data class VoiceAudioRoute(val id: String, val label: String)

/** Foreground communication routing. No Bluetooth discovery, pairing, device names or addresses. */
internal class ConversationAudioRoute(
    context: Context, private val main: Handler,
    private val changed: (List<VoiceAudioRoute>, String, String) -> Unit,
    private val lost: () -> Unit,
) : AutoCloseable {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var selected = "auto"
    private var active = false
    private var requestedId: Int? = null
    var activeType: Int = 0
        private set
    val communicationActive: Boolean get() = active && ownsMode && requestedId != null && ready == null
    private var ready: (() -> Unit)? = null
    private var previousMode = AudioManager.MODE_NORMAL
    private var ownsMode = false
    private var status = "Follows Android audio routing."
    private var closed = false
    private val timeout = Runnable { if (active && ready != null) failRoute() }
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) { publish() }
        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            if (active && removedDevices.any { it.id == requestedId }) { failRoute(); return }
            publish()
        }
    }
    private val communicationChanged = if (Build.VERSION.SDK_INT >= 31)
        AudioManager.OnCommunicationDeviceChangedListener { device ->
            if (!active || requestedId == null) return@OnCommunicationDeviceChangedListener
            // Mode changes can queue intermediate routes before the requested route settles.
            if (audio.communicationDevice?.id != device?.id) return@OnCommunicationDeviceChangedListener
            if (device?.id == requestedId) {
                status = "Conversation audio route connected."
                val callback = ready; ready = null; main.removeCallbacks(timeout)
                publish(); callback?.invoke()
            } else if (ready == null) failRoute()
        } else null
    init {
        audio.registerAudioDeviceCallback(devices, main)
        if (Build.VERSION.SDK_INT >= 31 && communicationChanged != null)
            audio.addOnCommunicationDeviceChangedListener({ main.post(it) }, communicationChanged)
        publish()
    }
    fun select(id: String) {
        if (active || closed) return
        if (id != "auto" && available().none { "device:${it.id}" == id }) return
        selected = id
        status = if (Build.VERSION.SDK_INT >= 31) "Applied when you start talking." else "Follows Android audio routing."
        publish()
    }
    fun start(onReady: () -> Unit) {
        if (closed) return
        if (active) { if (ready == null) onReady(); return }
        active = true
        // Keep the existing media/assistant route as the default, including Bluetooth
        // speakers. Communication routing is an explicit headset/speaker choice.
        if (Build.VERSION.SDK_INT < 31 || selected == "auto") {
            status = "Follows Android audio routing."; publish(); onReady(); return
        }
        try {
            val available = available()
            val chosen = available.firstOrNull { "device:${it.id}" == selected }
            if (chosen == null) { failRoute(); return }
            previousMode = audio.mode
            // Do not take an active phone/other communication session's mode.
            if (previousMode != AudioManager.MODE_NORMAL) { failRoute(); return }
            audio.mode = AudioManager.MODE_IN_COMMUNICATION; ownsMode = true
            requestedId = chosen.id; activeType = chosen.type; ready = onReady
            status = "Connecting conversation audio…"; publish()
            if (!audio.setCommunicationDevice(chosen)) { failRoute(); return }
            if (audio.communicationDevice?.id == chosen.id) {
                val callback = ready; ready = null; status = "Conversation audio route connected."
                publish(); callback?.invoke()
            } else main.postDelayed(timeout, 8000)
        } catch (_: Exception) { failRoute() }
    }
    fun stop() {
        active = false; ready = null; main.removeCallbacks(timeout)
        if (Build.VERSION.SDK_INT >= 31) {
            // Clear only this app's preference; never restore a stale route belonging to another app.
            if (requestedId != null) runCatching { audio.clearCommunicationDevice() }
            if (ownsMode && audio.mode == AudioManager.MODE_IN_COMMUNICATION)
                runCatching { audio.mode = previousMode }
        }
        requestedId = null; activeType = 0; ownsMode = false
        status = if (Build.VERSION.SDK_INT >= 31) "Applied when you start talking." else "Follows Android audio routing."
        publish()
    }
    private fun failRoute() { stop(); lost() }
    private fun available(): List<AudioDeviceInfo> = if (Build.VERSION.SDK_INT >= 31)
        runCatching { audio.availableCommunicationDevices.filter { it.isSink } }.getOrDefault(emptyList()) else emptyList()
    private fun publish() {
        if (closed) return
        val options = available().map { device -> VoiceAudioRoute("device:${device.id}", when (device.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth headset"
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headset"
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
            AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing device"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Phone earpiece"
            else -> "Audio device"
        }) }
        if (!active && selected != "auto" && options.none { it.id == selected }) selected = "auto"
        changed(listOf(VoiceAudioRoute("auto", "System audio")) + options, selected, status)
    }
    override fun close() {
        if (closed) return
        stop(); closed = true; audio.unregisterAudioDeviceCallback(devices)
        if (Build.VERSION.SDK_INT >= 31 && communicationChanged != null)
            audio.removeOnCommunicationDeviceChangedListener(communicationChanged)
    }
}
