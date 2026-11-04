package io.github.szymonrychu.booxsketch.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TaskLabelsTest {
    @Test
    fun trims_and_lowercases() {
        assertThat(TaskLabels.normalize("  Boox-UI ")).isEqualTo("boox-ui")
    }

    @Test
    fun accepts_charset_a_z_0_9_underscore_dot_colon_dash() {
        assertThat(TaskLabels.normalize("a_z.0:9-x")).isEqualTo("a_z.0:9-x")
    }

    @Test
    fun rejects_empty_space_inside_and_41_chars() {
        assertThat(TaskLabels.normalize("")).isNull()
        assertThat(TaskLabels.normalize("   ")).isNull()
        assertThat(TaskLabels.normalize("two words")).isNull()
        assertThat(TaskLabels.normalize("a".repeat(41))).isNull()
        assertThat(TaskLabels.normalize("café")).isNull()
    }

    @Test
    fun accepts_40_chars() {
        assertThat(TaskLabels.normalize("a".repeat(40))).isEqualTo("a".repeat(40))
    }

    @Test
    fun rejects_21st_label() {
        val twenty = (1..20).map { "l$it" }
        assertThat(TaskLabels.add(twenty, "extra")).isEqualTo(LabelResult.TooMany)
        assertThat(TaskLabels.add(twenty.dropLast(1), "extra")).isEqualTo(LabelResult.Added(twenty.dropLast(1) + "extra"))
    }

    @Test
    fun duplicate_is_noop() {
        assertThat(TaskLabels.add(listOf("a", "b"), " B ")).isEqualTo(LabelResult.Duplicate)
    }

    @Test
    fun invalid_carries_a_reason() {
        val result = TaskLabels.add(emptyList(), "no good")
        assertThat(result).isInstanceOf(LabelResult.Invalid::class.java)
        assertThat((result as LabelResult.Invalid).reason).isNotEmpty()
    }

    @Test
    fun client_keys_are_unique() {
        assertThat(newClientKey()).isNotEqualTo(newClientKey())
        assertThat(newClientKey()).hasLength(36)
    }
}
