package io.github.szymonrychu.booxsketch.tasks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import io.github.szymonrychu.booxsketch.App
import io.github.szymonrychu.booxsketch.onyx.Epd

const val TASKS_TAB_ID = "tasks"

/** The Tasks tab of the app shell, backed by the container's [TasksController]. */
@Composable
fun TasksTab(modifier: Modifier = Modifier) {
    val container = (LocalContext.current.applicationContext as App).container
    TasksHost(container.tasks, modifier)
}

/**
 * List, create form and detail with system back. The stream is collected by the screens' effects, so it stops when
 * this leaves the composition. After the keyboard closes the screen gets a partial refresh to clear ghosting.
 */
@Composable
fun TasksHost(
    controller: TasksController,
    modifier: Modifier = Modifier,
) {
    val screen by controller.screen.collectAsState()
    BackHandler(enabled = screen != TasksScreen.List) { controller.back() }
    ImeCloseRefresh()
    val body = modifier.fillMaxSize()
    when (val s = screen) {
        TasksScreen.List -> {
            val vm = controller.list()
            if (vm == null) Text("Pair this device first", body) else TaskListScreen(vm, controller::open, controller::startCreate, body)
        }

        TasksScreen.Create -> {
            val vm = controller.create()
            if (vm == null) Text("Pair this device first", body) else CreateTaskScreen(vm, controller::back, body)
        }

        is TasksScreen.Detail -> {
            val vm = controller.detail(s.key)
            if (vm == null) {
                Text("Pair this device first", body)
            } else {
                TaskDetailScreen(vm, controller::back, controller::filterSession, body)
            }
        }
    }
}

@Composable
private fun ImeCloseRefresh() {
    val view = LocalView.current
    val density = LocalDensity.current
    val open = WindowInsets.ime.getBottom(density) > 0
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (wasOpen && !open) Epd.partialRefresh(view)
        wasOpen = open
    }
}
