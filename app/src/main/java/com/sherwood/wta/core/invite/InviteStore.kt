package com.sherwood.wta.core.invite

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where a future reward backend is plugged in.
 *
 * The reward program has no server yet, so the app records shares locally and nothing else.
 * This interface exists so that adding a Cloudflare Worker or a Supabase table later is a
 * change to one object, not to the settings screen: the screen only ever asks
 * [InviteStore.recordShare] and renders [InviteStore.state].
 *
 * Implementations must be safe to call from a background coroutine, must not block, and
 * must not throw for a network problem — a remote miss is information, not a reason to lose
 * the share that did happen.
 */
interface InviteTracker {

    /**
     * Reports that [code] was shared with [link] from this device.
     *
     * @return true when the backend acknowledged the share; false when there is no backend
     * or it declined. The local counter is authoritative either way.
     */
    suspend fun recordShare(code: String, link: String): Boolean

    companion object {
        /** Installed until a backend exists. Performs no I/O at all. */
        @JvmField
        val NO_OP: InviteTracker = NoOpInviteTracker
    }
}

/**
 * The default tracker: no network, no clock, nothing to keep in sync.
 *
 * Returning false (rather than pretending to succeed) is what keeps the app honest: the card
 * can say the reward program is not live without a second source of truth saying otherwise.
 */
object NoOpInviteTracker : InviteTracker {

    override suspend fun recordShare(code: String, link: String): Boolean = false
}

/**
 * The device's own invite identity: one code, one link built from it, and how many times the
 * share sheet was opened from here.
 *
 * Only local facts live in this store — the code, its link, and a counter. It never stores a
 * count of friends who arrived (the app cannot know that without a backend) and it never
 * stores anybody else's code, so the card cannot show an invented number.
 *
 * The code is generated on first read and persisted before it is returned, so the link a
 * user shares today is the link they share tomorrow. A stored value that does not parse is
 * replaced rather than displayed, because a corrupt preference must not become a link that
 * attributes nothing.
 */
class InviteStore internal constructor(
    context: Context,
    private val tracker: InviteTracker = InviteTracker.NO_OP
) {

    companion object {
        /** Matches the preference file this store owns; cleared by tests. */
        const val PREFS_NAME: String = "sherwood_invite"

        private const val KEY_CODE: String = "invite_code"
        private const val KEY_SHARE_COUNT: String = "invite_share_count"
    }

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /**
     * What the settings card renders. [code] and [link] are stable for the life of the
     * install; [shareCount] only ever grows.
     */
    data class Snapshot(
        val code: String,
        val link: String,
        val shareCount: Int
    )

    fun snapshot(): Snapshot = _state.value

    val code: String get() = _state.value.code

    val link: String get() = _state.value.link

    /**
     * Records one share from this device and reports it to the tracker, if any.
     *
     * The local counter is persisted and published *before* the remote call, so a slow or
     * unreachable backend cannot postpone the number the user just caused. The remote call
     * is fire-and-forget by contract: its failure is not a user-visible error because the
     * user's action (opening the share sheet) did happen.
     *
     * @return the state after the increment.
     */
    suspend fun recordShare(): Snapshot {
        val updated = synchronized(this) {
            val current = _state.value
            val next = current.copy(shareCount = current.shareCount + 1)
            prefs.edit().putInt(KEY_SHARE_COUNT, next.shareCount).apply()
            _state.value = next
            next
        }

        // A tracker that fails must not undo the share the user actually performed, and a
        // tracker that is absent must not look like a failure to the caller. Cancellation is
        // the one exception: swallowing it would strand the caller's own scope, so a future
        // backend still cancels like every other suspend call.
        try {
            tracker.recordShare(updated.code, updated.link)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // Reported nowhere on purpose: the local counter is the device's own record, and
            // the reward program has no live backend to be out of sync with.
        }
        return updated
    }

    private fun read(): Snapshot {
        val code = loadOrCreateCode()
        return Snapshot(
            code = code,
            link = InviteCode.link(code),
            // A negative value can only come from a corrupt write; clamp rather than render it.
            shareCount = prefs.getInt(KEY_SHARE_COUNT, 0).coerceAtLeast(0)
        )
    }

    private fun loadOrCreateCode(): String {
        val stored = prefs.getString(KEY_CODE, null)
        if (InviteCode.isValid(stored)) return InviteCode.normalize(stored)

        val generated = InviteCode.generate()
        // Written immediately: the code is the user's referral identity, and a code that
        // changed between two launches would silently orphan an invite already sent.
        prefs.edit().putString(KEY_CODE, generated).apply()
        return generated
    }
}
