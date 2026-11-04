package io.github.szymonrychu.booxsketch.tasks

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.AppContainer
import io.github.szymonrychu.booxsketch.FreezingInputApp
import io.github.szymonrychu.booxsketch.MainActivity
import io.github.szymonrychu.booxsketch.board.TAG_DOCK
import io.github.szymonrychu.booxsketch.pairing.FakeKeyProvider
import io.github.szymonrychu.booxsketch.tasks.FakeRelay.Companion.json
import io.github.szymonrychu.booxsketch.ui.EinkTheme
import io.github.szymonrychu.booxsketch.ui.TAG_TAB_ITEM_PREFIX
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w930dp-h1240dp-port-xhdpi")
class TasksTabTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var relay: FakeRelay
    private lateinit var scope: CoroutineScope
    private val events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private var unauthorized = 0

    @Before
    fun setUp() {
        relay =
            FakeRelay { r ->
                when {
                    r.path!!.contains("/tasks/T-1") -> json(taskJson("T-1", "title" to "\"First\""))
                    else -> json(pageJson(listOf(taskJson("T-1", "title" to "\"First\""))))
                }
            }
        scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
        relay.shutdown()
    }

    private fun controller() = TasksController({ relay.client }, events, scope, { unauthorized++ })

    private fun container(http: () -> OkHttpClient = { OkHttpClient() }) =
        AppContainer(
            ApplicationProvider.getApplicationContext<Application>(),
            tmp.root,
            keyProvider = FakeKeyProvider(),
            httpFactory = http,
        ).also { runBlocking { it.tokenStore.save("https://connector.example", "test-token") } }

    @Test
    fun tasks_tab_is_registered_with_title_Tasks_in_order() {
        val tabs = container().tabs.tabs
        assertThat(tabs.map { it.title }).containsExactly("Drawing", "Tasks").inOrder()
        assertThat(tabs.last().id).isEqualTo(TASKS_TAB_ID)
        assertThat(tabs.last().order).isEqualTo(AppContainer.TASKS_TAB_ORDER)
        assertThat(AppContainer.TASKS_TAB_ORDER).isGreaterThan(AppContainer.DRAWING_TAB_ORDER)
        assertThat(AppContainer.TASKS_TAB_ORDER).isLessThan(AppContainer.DOCUMENTS_TAB_ORDER)
    }

    @Test
    fun list_to_detail_and_back_keeps_filters() {
        val c = controller()
        compose.setContent { EinkTheme { TasksHost(c) } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(taskRowTag("T-1"))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(taskStatusFilterTag(StatusFilter.DONE)).performClick()
        compose.waitUntil(5_000) {
            c
                .list()!!
                .state.value.status == StatusFilter.DONE &&
                !c
                    .list()!!
                    .state.value.loading
        }
        compose.onNodeWithTag(taskRowTag("T-1")).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(TAG_DETAIL_HEADER)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(TAG_DETAIL_BACK).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(TAG_TASKS_NEW)).fetchSemanticsNodes().isNotEmpty() }
        assertThat(c.screen.value).isEqualTo(TasksScreen.List)
        assertThat(
            c
                .list()!!
                .state.value.status,
        ).isEqualTo(StatusFilter.DONE)
    }

    @Test
    fun system_back_pops_the_detail() {
        val c = controller()
        compose.setContent { EinkTheme { TasksHost(c) } }
        c.open("T-1")
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(TAG_DETAIL_HEADER)).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5_000) { c.screen.value == TasksScreen.List }
    }

    @Test
    fun stream_collected_only_while_tab_visible() {
        val c = controller()
        var visible by mutableStateOf(true)
        compose.setContent { EinkTheme { if (visible) TasksHost(c) } }
        compose.waitUntil(5_000) { events.subscriptionCount.value == 1 }
        // no badge or notification machinery exists for tasks
        compose.onNodeWithTag("tasks_badge").assertDoesNotExistCompat()
        val channels = (compose.activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notificationChannels
        assertThat(channels.map { it.id.lowercase() }.none { "task" in it }).isTrue()
        compose.runOnUiThread { visible = false }
        compose.waitForIdle()
        compose.onNodeWithTag(TAG_TASKS_NEW).assertDoesNotExist()
        compose.waitUntil(5_000) { events.subscriptionCount.value == 0 }
    }

    @Test
    fun unpair_clears_task_state() {
        val c = container()
        val list = c.tasks.list()
        c.tasks.open("T-1")
        assertThat(c.tasks.screen.value).isEqualTo(TasksScreen.Detail("T-1"))
        runBlocking { c.unpair() }
        assertThat(c.tasks.screen.value).isEqualTo(TasksScreen.List)
        assertThat(c.tasks.list()).isNull()
        assertThat(list).isNotNull()
    }

    @Test
    fun revoked_token_401_goes_to_pairing() {
        val unauthorizedHttp = {
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(401)
                        .message("Unauthorized")
                        .body("""{"error":"unauthorized","detail":"x"}""".toResponseBody())
                        .build()
                }.build()
        }
        val c = container(unauthorizedHttp)
        c.tasks.list()
        val deadline = System.currentTimeMillis() + 5_000
        while (c.tokenStore.token() != null && System.currentTimeMillis() < deadline) {
            org.robolectric.shadows.ShadowLooper
                .idleMainLooper()
            Thread.sleep(20)
        }
        assertThat(c.tokenStore.token()).isNull()
    }

    @Test
    fun ime_open_does_not_move_focused_field_off_screen() {
        // Assumption: the window is edge to edge, so the IME arrives as an inset and the forms' imePadding keeps the field in view.
        val c = controller()
        c.startCreate()
        compose.setContent { EinkTheme { TasksHost(c) } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(TAG_CREATE_TITLE)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(TAG_CREATE_TITLE).performTextInput("x")
        val ime = Insets.of(0, 0, 0, IME_PX)
        compose.runOnUiThread {
            val root = compose.activity.window.decorView
            val insets =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(
                        WindowInsetsCompat.Type.ime(),
                        ime,
                    ).setVisible(WindowInsetsCompat.Type.ime(), true)
                    .build()
            ViewCompat.dispatchApplyWindowInsets(root, insets)
        }
        compose.waitForIdle()
        compose.onNodeWithTag(TAG_CREATE_TITLE).assertIsDisplayed()
        val bottom =
            compose
                .onNodeWithTag(TAG_CREATE_TITLE)
                .fetchSemanticsNode()
                .boundsInRoot.bottom
        val screenH = compose.activity.window.decorView.height
        assertThat(bottom).isAtMost((screenH - IME_PX).toFloat())
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistCompat() = assertDoesNotExist()

    private companion object {
        const val IME_PX = 600
    }
}

/** MainActivity hosts the Tasks tab as a real tab; the board (and its raw drawing) is not composed while it shows. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FreezingInputApp::class, sdk = [35], qualifiers = "w1240dp-h930dp-land-xhdpi")
class TasksTabShellTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun waitFor(tag: String) = compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun raw_drawing_is_off_on_the_tasks_tab() {
        waitFor(TAG_DOCK)
        waitFor(TAG_TAB_ITEM_PREFIX + TASKS_TAB_ID)
        compose.onNodeWithTag(TAG_TAB_ITEM_PREFIX + TASKS_TAB_ID).performClick()
        waitFor(TAG_TASKS_NEW)
        compose.onNodeWithTag(TAG_DOCK).assertDoesNotExistCompat()
        assertThat(FreezingInputApp.input.log.last()).isEqualTo("stop")
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistCompat() = assertDoesNotExist()
}
