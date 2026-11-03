package io.github.szymonrychu.booxsketch.api

import io.github.szymonrychu.booxsketch.model.Canvas

/** Placeholder so the module compiles against :core:model; Task 10 replaces it with the real client. */
object ApiInfo {
    fun describe(canvas: Canvas): String = "${canvas.w}x${canvas.h}@${canvas.dpi}"
}
