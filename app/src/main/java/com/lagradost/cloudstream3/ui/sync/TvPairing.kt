package com.lagradost.cloudstream3.ui.sync

import android.net.Uri
import com.google.firebase.firestore.DocumentReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await

object TvPairing {
    const val CODE_LENGTH = 6
    const val COLLECTION = "pairing_codes"

    fun normalizeCode(rawCode: String): String? {
        val trimmed = rawCode.trim()
        if (trimmed.isEmpty()) return null

        val fromUri = runCatching {
            Uri.parse(trimmed).getQueryParameter("code")
        }.getOrNull()

        val fromQuery = fromUri ?: Regex("""(?:^|[?&])code=([^&]+)""")
            .find(trimmed)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { Uri.decode(it) }

        val candidate = fromQuery ?: trimmed
        val normalized = candidate
            .uppercase()
            .filter { it in 'A'..'Z' || it in '0'..'9' }

        return normalized.takeIf { it.length == CODE_LENGTH }
    }

    suspend fun waitForTvCompletion(
        document: DocumentReference,
        timeoutMs: Long = 25_000L,
        pollMs: Long = 1_000L
    ): Boolean {
        val startedAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            delay(pollMs)
            val snapshot = runCatching { document.get().await() }.getOrNull() ?: continue
            if (!snapshot.exists()) return true
            if (snapshot.getString("status") == "completed") return true
        }
        return false
    }
}
