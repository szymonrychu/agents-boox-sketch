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
import io.github.szymonrychu.booxsketch.ui.PanelTabs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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
    fun split_store_runs_on_its_own_io_scope() {
        val c = container(available = false)
        assertThat(c.splitStoreScope.coroutineContext[ContinuationInterceptor]).isEqualTo(Dispatchers.IO)
        assertThat(c.splitStoreScope).isNotSameInstanceAs(c.appScope)
    }
}
