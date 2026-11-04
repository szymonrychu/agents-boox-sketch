package io.github.szymonrychu.booxsketch.documents

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.szymonrychu.booxsketch.testing.FreezingInput
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DocumentInkWiringTest {
    @Test
    fun page_turn_holds_raw_drawing_until_background_and_rects_applied() =
        runBlocking<Unit> {
            val input = FreezingInput()
            val turner = PageTurner(input, { input.log += "turn:$it" }, { input.log += "applied" })
            turner.turn(1)
            assertThat(input.log).containsExactly("hold", "turn:1", "applied", "release").inOrder()
        }

    @Test
    fun a_failing_turn_still_releases_the_hold() =
        runBlocking<Unit> {
            val input = FreezingInput()
            val turner = PageTurner(input, { error("boom") }, { })
            runCatching { turner.turn(1) }
            assertThat(input.log).containsExactly("hold", "release").inOrder()
        }
}
