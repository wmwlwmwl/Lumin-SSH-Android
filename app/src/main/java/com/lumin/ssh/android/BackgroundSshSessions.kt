package com.lumin.ssh.android

import java.util.UUID

object BackgroundSshSessions {
    private const val MAX_TRANSCRIPT_CHUNKS = 2000

    data class Entry(
        val sessionId: String,
        val conn: Connection,
        val shell: SshShellSession,
        val transcript: ArrayList<ByteArray> = ArrayList(),
    )

    private val sessions = linkedMapOf<String, Entry>()
    var requestedSessionId: String? = null

    @Synchronized
    fun put(conn: Connection, shell: SshShellSession, transcript: List<ByteArray>): String {
        val sessionId = UUID.randomUUID().toString()
        sessions[sessionId] = Entry(sessionId, conn, shell, ArrayList(transcript.takeLast(MAX_TRANSCRIPT_CHUNKS)))
        return sessionId
    }

    @Synchronized
    fun append(sessionId: String, bytes: ByteArray) {
        val transcript = sessions[sessionId]?.transcript ?: return
        transcript.add(bytes)
        while (transcript.size > MAX_TRANSCRIPT_CHUNKS) transcript.removeAt(0)
    }

    @Synchronized
    fun get(sessionId: String): Entry? = sessions[sessionId]

    @Synchronized
    fun first(): Entry? = sessions.values.firstOrNull()

    @Synchronized
    fun all(): List<Entry> = sessions.values.toList()

    @Synchronized
    fun take(sessionId: String): Entry? = sessions.remove(sessionId)

    @Synchronized
    fun close(sessionId: String) {
        sessions.remove(sessionId)?.shell?.close()
    }
}
