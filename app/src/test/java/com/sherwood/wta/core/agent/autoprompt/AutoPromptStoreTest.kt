package com.sherwood.wta.core.agent.autoprompt

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the Auto Prompt persistence contract.
 *
 * The two rules the feature depends on are asserted directly: the master switch gates
 * running without touching the saved list, and every edit survives a fresh store instance
 * (which is what "after the app is closed" means for SharedPreferences).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutoPromptStoreTest {

    private lateinit var ctx: Context
    private lateinit var store: AutoPromptStore

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        // The prefs file is shared between tests in one Robolectric process.
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        store = AutoPromptStore(ctx)
    }

    private fun prompt(name: String, text: String = "Body of $name", enabled: Boolean = true) =
        AutoPrompt(name = name, text = text, enabled = enabled)

    private fun reopen() = AutoPromptStore(ctx)

    @Test
    fun `turning the master switch off keeps every saved prompt`(): Unit = runBlocking {
        store.save(prompt("One")).getOrThrow()
        store.save(prompt("Two")).getOrThrow()

        val off = store.setMasterEnabled(false).getOrThrow()
        assertThat(off.masterEnabled).isFalse()
        assertThat(off.prompts.map { it.name }).containsExactly("One", "Two").inOrder()

        val restarted = reopen().state()
        assertThat(restarted.masterEnabled).isFalse()
        assertThat(restarted.prompts.map { it.name }).containsExactly("One", "Two").inOrder()

        // Turning it back on must bring the same list back untouched.
        val on = store.setMasterEnabled(true).getOrThrow()
        assertThat(on.masterEnabled).isTrue()
        assertThat(on.prompts.map { it.name }).containsExactly("One", "Two").inOrder()
    }

    @Test
    fun `a saved prompt survives a restart`(): Unit = runBlocking {
        val saved = store.save(
            AutoPrompt(
                name = "Audit",
                description = "Security",
                text = "Check permissions",
                category = AutoPromptCategory.SECURITY,
                modelId = "model-1"
            )
        ).getOrThrow().prompts.single()

        val restarted = reopen().state().prompts.single()
        assertThat(restarted.id).isEqualTo(saved.id)
        assertThat(restarted.name).isEqualTo("Audit")
        assertThat(restarted.description).isEqualTo("Security")
        assertThat(restarted.text).isEqualTo("Check permissions")
        assertThat(restarted.category).isEqualTo(AutoPromptCategory.SECURITY)
        assertThat(restarted.modelId).isEqualTo("model-1")
    }

    @Test
    fun `editing replaces the prompt in place and keeps the order`(): Unit = runBlocking {
        val one = store.save(prompt("One")).getOrThrow().prompts.single()
        store.save(prompt("Two")).getOrThrow()

        val edited = store.save(one.copy(name = "One edited")).getOrThrow()
        assertThat(edited.prompts.map { it.name }).containsExactly("One edited", "Two").inOrder()
        assertThat(edited.prompts).hasSize(2)
    }

    @Test
    fun `delete removes only the target`(): Unit = runBlocking {
        val one = store.save(prompt("One")).getOrThrow().prompts.single()
        store.save(prompt("Two")).getOrThrow()

        val after = store.delete(one.id).getOrThrow()
        assertThat(after.prompts.map { it.name }).containsExactly("Two")
    }

    @Test
    fun `duplicate inserts a named copy right after the source`(): Unit = runBlocking {
        val one = store.save(prompt("One")).getOrThrow().prompts.single()
        store.save(prompt("Two")).getOrThrow()

        val after = store.duplicate(one.id, "One copy").getOrThrow()
        assertThat(after.prompts.map { it.name })
            .containsExactly("One", "One copy", "Two").inOrder()
        assertThat(after.prompts[1].id).isNotEqualTo(one.id)
        assertThat(after.prompts[1].text).isEqualTo(one.text)
    }

    @Test
    fun `move reorders and clamps out-of-range targets`(): Unit = runBlocking {
        val a = store.save(prompt("A")).getOrThrow().prompts.single()
        val b = store.save(prompt("B")).getOrThrow().prompts.last()
        val c = store.save(prompt("C")).getOrThrow().prompts.last()

        assertThat(store.move(a.id, 2).getOrThrow().prompts.map { it.name })
            .containsExactly("B", "C", "A").inOrder()
        assertThat(store.moveBy(c.id, -1).getOrThrow().prompts.map { it.name })
            .containsExactly("B", "A", "C").inOrder()
        // A target far past the end lands on the last slot instead of failing.
        assertThat(store.moveBy(b.id, 99).getOrThrow().prompts.map { it.name })
            .containsExactly("A", "C", "B").inOrder()
    }

    @Test
    fun `the per-prompt switch persists`(): Unit = runBlocking {
        val saved = store.save(prompt("One")).getOrThrow().prompts.single()

        val after = store.setPromptEnabled(saved.id, false).getOrThrow()
        assertThat(after.prompts.single().enabled).isFalse()
        assertThat(reopen().state().prompts.single().enabled).isFalse()
    }

    @Test
    fun `blank prompts are rejected without touching the list`(): Unit = runBlocking {
        assertThat(store.save(AutoPrompt(name = "", text = "body")).isFailure).isTrue()
        assertThat(store.save(AutoPrompt(name = "name", text = "  ")).isFailure).isTrue()
        assertThat(store.state().prompts).isEmpty()
    }

    @Test
    fun `the first run seeds once and deleting everything does not re-seed`(): Unit = runBlocking {
        val seeded = store.seedIfFirstRun(listOf(prompt("A"), prompt("B")))
        assertThat(seeded.prompts.map { it.name }).containsExactly("A", "B").inOrder()

        // The document now exists, so a second call must not add the new defaults.
        val again = store.seedIfFirstRun(listOf(prompt("C")))
        assertThat(again.prompts.map { it.name }).containsExactly("A", "B").inOrder()

        seeded.prompts.forEach { store.delete(it.id).getOrThrow() }
        val afterDelete = store.seedIfFirstRun(listOf(prompt("D")))
        assertThat(afterDelete.prompts).isEmpty()
    }

    @Test
    fun `an unreadable document is preserved and never overwritten`(): Unit = runBlocking {
        val broken = "{this is not json"
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DOCUMENT, broken)
            .commit()

        // Reading degrades to an empty list instead of crashing the screen.
        assertThat(store.state().prompts).isEmpty()

        // A mutation must refuse rather than replace data it cannot read.
        assertThat(store.save(prompt("X")).isFailure).isTrue()
        assertThat(store.setMasterEnabled(false).isFailure).isTrue()

        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        assertThat(prefs.getString(KEY_DOCUMENT, null)).isEqualTo(broken)
        assertThat(prefs.getString(KEY_CORRUPT_BACKUP, null)).isEqualTo(broken)
    }

    @Test
    fun `a null prompt array is treated as a corrupt document`(): Unit = runBlocking {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DOCUMENT, """{"version":1,"masterEnabled":true,"prompts":null}""")
            .commit()

        assertThat(store.state().prompts).isEmpty()
        assertThat(store.save(prompt("X")).isFailure).isTrue()
    }

    @Test
    fun `the master switch defaults to on with no saved document`(): Unit = runBlocking {
        assertThat(store.state().masterEnabled).isTrue()
        assertThat(store.state().prompts).isEmpty()
    }

    private companion object {
        // Mirrors AutoPromptStore's private preference names so the test can simulate a
        // damaged document and start each test from a known file.
        const val PREFS = "sherwood_auto_prompts"
        const val KEY_DOCUMENT = "auto_prompts_document"
        const val KEY_CORRUPT_BACKUP = "auto_prompts_corrupt_backup"
    }
}
