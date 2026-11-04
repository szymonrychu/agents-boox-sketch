package io.github.szymonrychu.booxsketch.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
@Config(sdk = [35])
class NoIndicationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun two_clickables_share_the_indication() {
        val clicks = mutableListOf<String>()
        compose.setContent {
            EinkTheme {
                Column {
                    for (id in listOf("a", "b")) {
                        Box(Modifier.size(48.dp).clickable { clicks += id }.testTag(id))
                    }
                }
            }
        }
        compose.onNodeWithTag("a").performClick()
        compose.onNodeWithTag("b").performClick()
        assertThat(clicks).containsExactly("a", "b").inOrder()
    }
}
