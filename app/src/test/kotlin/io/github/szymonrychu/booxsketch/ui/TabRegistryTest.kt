package io.github.szymonrychu.booxsketch.ui

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

class TabRegistryTest {
    private val registry = TabRegistry()

    @After
    fun tearDown() = registry.clearForTest()

    private fun spec(
        id: String,
        order: Int,
        title: String = id,
    ) = TabSpec(id = id, title = title, order = order) { }

    @Test
    fun empty_registry_has_no_tabs() {
        assertThat(registry.tabs).isEmpty()
    }

    @Test
    fun tabs_sorted_by_order() {
        registry.register(spec("settings", 90))
        registry.register(spec(BOARD_TAB_ID, 10))
        registry.register(spec("spec", 20))
        assertThat(registry.tabs.map { it.id }).containsExactly("board", "spec", "settings").inOrder()
    }

    @Test
    fun register_same_id_replaces_spec() {
        registry.register(spec("spec", 20, "First"))
        registry.register(spec("spec", 20, "Second"))
        assertThat(registry.tabs.map { it.title }).containsExactly("Second")
    }

    @Test
    fun equal_orders_keep_registration_order() {
        registry.register(spec("b", 50))
        registry.register(spec("a", 50))
        registry.register(spec("c", 50))
        assertThat(registry.tabs.map { it.id }).containsExactly("b", "a", "c").inOrder()
    }
}
