package io.github.szymonrychu.booxsketch.testing

import android.graphics.Rect
import android.view.View
import io.github.szymonrychu.booxsketch.ink.StrokeEvent
import io.github.szymonrychu.booxsketch.ink.StrokeInput
import io.github.szymonrychu.booxsketch.model.Tool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** A screen-freezing input that logs its lifecycle calls, in order, into [log]. */
internal class FreezingInput(
    override val events: Flow<StrokeEvent> = emptyFlow(),
) : StrokeInput {
    val log = mutableListOf<String>()
    override val freezesUi = true
    override var strokeInProgress = false

    override fun start(
        view: View,
        limit: Rect,
        exclude: List<Rect>,
    ) {
        log += "start:$exclude"
    }

    override fun setTool(
        tool: Tool,
        width: Float,
    ) = Unit

    override fun pause() {
        log += "pause"
    }

    override fun resume() {
        log += "resume"
    }

    override fun stop() {
        log += "stop"
    }

    override fun holdUi() {
        log += "hold"
    }

    override fun releaseUi() {
        log += "release"
    }
}
