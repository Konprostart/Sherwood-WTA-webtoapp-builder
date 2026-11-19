package com.sherwood.wta.core.invite

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The device's own invite identity.
 *
 * Two properties matter beyond the trivial round-trip:
 *
 *  - the code is minted once and reused, because a code that changed between launches would
 *    silently orphan invites already sent;
 *  - a share is counted locally no matter what the (currently absent) reward backend says,
 *    because the user did open the share sheet even if no server was there to hear about it.
 */
@RunWith(RobolectricTestRunner::class)
class InviteStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Matches InviteStore.PREFS_NAME; cleared so tests never see each other's identity.
        context.getSharedPreferences(InviteStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `the code is minted once and reused by a later instance`() {
        val first = InviteStore(context)
        assertThat(InviteCode.isValid(first.code)).isTrue()

        val second = InviteStore(context)
        assertThat(second.code).isEqualTo(first.code)
        assertThat(second.snapshot().shareCount).isEqualTo(0)
    }

    @Test
    fun `the link is built from the stored code`() {
        val store = InviteStore(context)
        assertThat(store.link).isEqualTo(InviteCode.PUBLIC_URL + "?ref=" + store.code)
        assertThat(InviteCode.refFromQuery(store.link.substringAfter('?'))).isEqualTo(store.code)
    }

    @Test
    fun `a stored code that does not parse is replaced instead of shown`() {
        context.getSharedPreferences(InviteStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("invite_code", "this is not a code").commit()

        val store = InviteStore(context)

        assertThat(InviteCode.isValid(store.code)).isTrue()
        assertThat(store.link).contains("?ref=")
    }

    @Test
    fun `a lower case stored code is canonicalised`() {
        context.getSharedPreferences(InviteStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("invite_code", "shw-abc123").commit()

        assertThat(InviteStore(context).code).isEqualTo("SHW-ABC123")
    }

    @Test
    fun `sharing increments the local counter and persists it`() {
        runBlocking {
            val store = InviteStore(context)
            assertThat(store.snapshot().shareCount).isEqualTo(0)

            store.recordShare()
            store.recordShare()

            assertThat(store.snapshot().shareCount).isEqualTo(2)
            // A fresh instance reads the persisted count, not a per-instance variable.
            assertThat(InviteStore(context).snapshot().shareCount).isEqualTo(2)
        }
    }

    @Test
    fun `the default tracker performs no work and does not block the count`() {
        runBlocking {
            val store = InviteStore(context)

            val updated = store.recordShare()

            assertThat(updated.shareCount).isEqualTo(1)
            // The no-op default reports "no backend acknowledged this", honestly.
            assertThat(NoOpInviteTracker.recordShare(updated.code, updated.link)).isFalse()
        }
    }

    @Test
    fun `the tracker receives exactly the code and link that were shared`() {
        val seen = mutableListOf<Pair<String, String>>()
        val recording = object : InviteTracker {
            override suspend fun recordShare(code: String, link: String): Boolean {
                seen += code to link
                return true
            }
        }

        runBlocking {
            val store = InviteStore(context, recording)
            val snapshot = store.recordShare()

            assertThat(seen).containsExactly(snapshot.code to snapshot.link)
            assertThat(seen).hasSize(1)
        }
    }

    @Test
    fun `an unreachable backend cannot undo a share the user made`() {
        val offline = object : InviteTracker {
            override suspend fun recordShare(code: String, link: String): Boolean =
                throw IllegalStateException("offline")
        }

        runBlocking {
            val store = InviteStore(context, offline)

            val snapshot = store.recordShare()

            assertThat(snapshot.shareCount).isEqualTo(1)
            assertThat(store.snapshot().shareCount).isEqualTo(1)
        }
    }
}
