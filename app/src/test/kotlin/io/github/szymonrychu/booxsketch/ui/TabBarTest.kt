package io.github.szymonrychu.booxsketch.ui

import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1240dp-h930dp-land-xhdpi")
class TabBarTest {
    @get:Rule val compose = createComposeRule()

    private val specs =
        listOf(
            TabSpec("board", "Drawings", 10) { },
            TabSpec("spec", "Spec", 20) { },
            TabSpec("settings", "Settings", 90) { },
        )

    private fun show(
        selected: String = "board",
        onSelect: (String) -> Unit = {},
    ) = compose.setContent { TabBar(specs, selected, onSelect) }

    @Test
    fun shows_tabs_in_given_order() {
        show()
        val texts = specs.map { compose.onNodeWithTag(TAG_TAB_ITEM_PREFIX + it.id) }
        texts[0].assertTextEquals("Drawings")
        texts[1].assertTextEquals("Spec")
        texts[2].assertTextEquals("Settings")
        val lefts =
            specs.map {
                compose.onNodeWithTag(TAG_TAB_ITEM_PREFIX + it.id).getBoundsInRoot().left
            }
        assertThat(lefts).isInOrder()
    }

    @Test
    fun selected_tab_is_inverted() {
        show(selected = "spec")
        compose.onNodeWithTag(TAG_TAB_ITEM_PREFIX + "spec").assertIsSelected()
        compose.onNodeWithTag(TAG_TAB_ITEM_PREFIX + "board").assertIsNotSelected()
        compose.onNodeWithTag(TAG_TAB_ITEM_PREFIX + "settings").assertIsNotSelected()
    }

    @Test
    fun tap_calls_on_select_with_tab_id() {
        val picked = mutableListOf<String>()
        show(onSelect = { picked += it })
        compose.onNodeWithTag(TAG_TAB_ITEM_PREFIX + "settings").performClick()
        assertThat(picked).containsExactly("settings")
    }

    @Test
    fun bar_height_is_48dp() {
        show()
        compose.onNodeWithTag(TAG_TAB_BAR).assertHeightIsEqualTo(48.dp)
    }
}
