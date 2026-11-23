package com.kai.agent.data

import android.content.Context

/** UI and WorkManager share one transaction mutex and state stream in this process. */
object KaiStores {
    private data class Entry(val store: KaiStore, var references: Int)
    private val entries = mutableMapOf<String, Entry>()
    class Lease internal constructor(val store: KaiStore, private val key: String) : AutoCloseable {
        private var closed = false
        override fun close() = synchronized(entries) {
            if (!closed) {
                closed = true
                entries[key]?.let { if (--it.references == 0) { entries.remove(key); it.store.close() } }
            }
        }
    }
    fun acquire(context: Context): Lease = synchronized(entries) {
        val key = context.applicationContext.noBackupFilesDir.canonicalPath + ":" + context.applicationContext.packageName
        val entry = entries.getOrPut(key) { Entry(KaiStore(context.applicationContext), 0) }
        entry.references++
        Lease(entry.store,key)
    }
}
