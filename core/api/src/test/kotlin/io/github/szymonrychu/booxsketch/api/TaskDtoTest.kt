package io.github.szymonrychu.booxsketch.api

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class TaskDtoTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    private fun fixture(name: String): String = checkNotNull(javaClass.getResource("/tasks/$name")) { "$name missing" }.readText()

    @Test
    fun decodes_list_page_with_cursor() {
        val page = json.decodeFromString<TaskPageDto>(fixture("list.json"))
        assertThat(page.items.map { it.key }).containsExactly("T-12", "T-11").inOrder()
        assertThat(page.nextCursor).isEqualTo("c-11")
        val first = page.items.first()
        assertThat(first.openDecisions).isEqualTo(1)
        assertThat(first.assigneeSession).isEqualTo("sess-a")
        assertThat(first.waitingSessions).containsExactly(WaitingSessionDto("sess-a", "alpha-fox"))
        assertThat(page.items.last().assigneeSession).isNull()
    }

    @Test
    fun decodes_full_task_with_decisions_todo_comments_events() {
        val task = json.decodeFromString<TaskDto>(fixture("task_full.json"))
        assertThat(task.version).isEqualTo(4)
        assertThat(task.labels).containsExactly("boox", "ui").inOrder()
        assertThat(task.todo.map { it.status }).containsExactly("done", "inprogress").inOrder()
        assertThat(task.todo.first().id).isEqualTo(7L)
        val open = task.decisions.first()
        assertThat(open.options.map { it.label }).containsExactly("Prev/Next", "Infinite scroll").inOrder()
        assertThat(open.options.first().recommended).isTrue()
        assertThat(open.options.last().description).isNull()
        assertThat(task.comments.map { it.authorKind }).containsExactly("user", "agent").inOrder()
        assertThat(task.comments.last().authorSlug).isEqualTo("alpha-fox")
        assertThat(task.events.map { it.kind }).containsExactly("created", "todo_updated").inOrder()
        assertThat(task.events.last().actorSlug).isEqualTo("alpha-fox")
        assertThat(
            task.events
                .last()
                .detail!!
                .jsonObject["item_id"]!!
                .jsonPrimitive.content,
        ).isEqualTo("7")
    }

    @Test
    fun decodes_task_with_nothing_attached() {
        val task = json.decodeFromString<TaskDto>(fixture("task_empty.json"))
        assertThat(task.openDecisions).isEqualTo(0)
        assertThat(task.waitingSessions).isEmpty()
        assertThat(task.events).isEmpty()
        assertThat(task.todo).isEmpty()
        assertThat(task.decisions).isEmpty()
        assertThat(task.comments).isEmpty()
    }

    @Test
    fun ignores_unknown_fields() {
        val task = json.decodeFromString<TaskDto>(fixture("task_full.json"))
        assertThat(task.key).isEqualTo("T-12")
    }

    @Test
    fun answered_and_withdrawn_decisions_decode() {
        val decisions = json.decodeFromString<TaskDto>(fixture("task_full.json")).decisions
        val byId = decisions.associateBy { it.id }
        val byChoice = byId.getValue(22L)
        assertThat(byChoice.status).isEqualTo("answered")
        assertThat(byChoice.answer?.choice).isEqualTo(1)
        assertThat(byChoice.answer?.label).isEqualTo("No")
        assertThat(byChoice.note).isEqualTo("No badge on e-ink")
        val byText = byId.getValue(23L)
        assertThat(byText.answer?.text).isEqualTo("Something else")
        assertThat(byText.answer?.choice).isNull()
        assertThat(byText.askingSlug).isNull()
        val withdrawn = byId.getValue(24L)
        assertThat(withdrawn.status).isEqualTo("withdrawn")
        assertThat(withdrawn.answer).isNull()
    }
}
