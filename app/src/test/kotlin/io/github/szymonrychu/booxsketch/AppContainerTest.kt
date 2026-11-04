package io.github.szymonrychu.booxsketch

import android.app.Application
import android.graphics.Rect
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.ink.InputSelector
import io.github.szymonrychu.booxsketch.ink.RawActivitySource
import io.github.szymonrychu.booxsketch.ink.StrokeEvent
import io.github.szymonrychu.booxsketch.ink.StrokeInput
import io.github.szymonrychu.booxsketch.model.Tool
import io.github.szymonrychu.booxsketch.onyx.ProbeResult
import io.github.szymonrychu.booxsketch.send.SendOutcome
import io.github.szymonrychu.booxsketch.ui.PanelTabs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.ContinuationInterceptor

private class StubOnyx :
    StrokeInput,
    RawActivitySource {
    override var onRawActivity: (() -> Unit)? = null
    override var onFailure: ((Throwable) -> Unit)? = null
    override val events: Flow<StrokeEvent> = emptyFlow()

    override fun start(
        view: View,
        limit: Rect,
        exclude: List<Rect>,
    ) = Unit

    override fun setTool(
        tool: Tool,
        width: Float,
    ) = Unit

    override fun pause() = Unit

    override fun resume() = Unit

    override fun stop() = Unit
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppContainerTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before
    fun reset() = InputSelector.resetForTest()

    private fun container(
        available: Boolean,
        onyx: () -> StrokeInput = { StubOnyx() },
    ) = AppContainer(
        ApplicationProvider.getApplicationContext<Application>(),
        tmp.root,
        probe = { ProbeResult(available, "test") },
        onyxFactory = onyx,
    )

    @Test
    fun diagnostics_panel_tab_is_registered() {
        PanelTabs.clearForTest()
        try {
            container(available = true)
            assertThat(PanelTabs.tabs.map { it.id }).contains("diagnostics")
        } finally {
            PanelTabs.clearForTest()
        }
    }

    @Test
    fun finger_enabled_before_any_selection() {
        assertThat(container(available = true).fingerEnabled()).isTrue()
    }

    @Test
    fun finger_enabled_when_the_probe_fails() {
        val c = container(available = false)
        c.inputSelector.select()
        assertThat(c.fingerEnabled()).isTrue()
    }

    @Test
    fun finger_disabled_while_onyx_is_active() {
        val c = container(available = true)
        c.inputSelector.select()
        assertThat(c.onyxFailure.value).isNull()
        assertThat(c.fingerEnabled()).isFalse()
    }

    @Test
    fun finger_enabled_again_after_onyx_fallback() {
        val c = container(available = true, onyx = { error("no sdk") })
        c.inputSelector.select()
        assertThat(c.onyxFailure.value).isNotNull()
        assertThat(c.fingerEnabled()).isTrue()
    }

    @Test
    fun store_runs_on_its_own_io_scope() {
        val c = container(available = false)
        assertThat(c.storeScope.coroutineContext[ContinuationInterceptor]).isEqualTo(Dispatchers.IO)
        assertThat(c.storeScope).isNotSameInstanceAs(c.appScope)
    }

    @Test
    fun send_failures_of_any_kind_become_a_message() =
        runBlocking<Unit> {
            val c = container(available = false)
            val room = c.runSend(false, null) { _, _ -> throw android.database.sqlite.SQLiteException("disk full") }
            assertThat(room).contains("disk full")
            val other = c.runSend(true, null) { _, _ -> error("boom") }
            assertThat(other).contains("boom")
            val io = c.runSend(true, null) { _, _ -> throw java.io.IOException("no space") }
            assertThat(io).contains("no space")
            assertThat(c.runSend(false, null) { _, _ -> SendOutcome.NothingToSend }).isNotNull()
        }

    @Test
    fun cancellation_is_not_swallowed_by_send() {
        val c = container(available = false)
        val thrown =
            runCatching { runBlocking { c.runSend(false, null) { _, _ -> throw CancellationException("stop") } } }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun conversations_tab_is_registered_and_the_title_button_selects_it() {
        PanelTabs.clearForTest()
        container(available = false)
        assertThat(PanelTabs.tabs.map { it.id }).contains("conversations")
        PanelTabs.select("other")
        PanelTabs.onOpenConversations()
        assertThat(PanelTabs.selectedId).isEqualTo("conversations")
        PanelTabs.clearForTest()
    }

    @Test
    fun documents_tab_is_registered_after_conversations_and_before_diagnostics() {
        PanelTabs.clearForTest()
        container(available = false)
        val ids = PanelTabs.tabs.map { it.id }
        assertThat(ids).containsAtLeast("conversations", "documents", "diagnostics").inOrder()
        PanelTabs.clearForTest()
    }
}
