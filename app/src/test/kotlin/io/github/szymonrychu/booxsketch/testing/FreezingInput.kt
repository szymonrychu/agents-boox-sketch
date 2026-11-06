package io.github.szymonrychu.booxsketch.testing

import android.graphics.Rect
import android.view.View
import io.github.szymonrychu.booxsketch.ink.StrokeEvent
import io.github.szymonrychu.booxsketch.ink.StrokeInput
import io.github.szymonrychu.booxsketch.model.Tool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A screen-freezing input that logs its lifecycle calls, in order, into [log], and the start limits into [limits].
 * [held] counts the outstanding holds; clearing [log] keeps that count in [heldAtClear], so a test that clears
 * the log while a hold is outstanding (dismiss mode while an overlay is open) can still balance holds.
 */
internal class FreezingInput(
    override val events: Flow<StrokeEvent> = emptyFlow(),
) : StrokeInput {
    var held = 0
        private set
    var heldAtClear = 0
        private set
    val log =
        object : ArrayList<String>() {
            override fun clear() {
                heldAtClear = held
                super.clear()
            }
        }

    /** The limit rect of every [start], in order (the log keeps only the excludes). */
    val limits = mutableListOf<Rect>()
    override val freezesUi = true
    override var strokeInProgress = false

    override fun start(
        view: View,
        limit: Rect,
        exclude: List<Rect>,
    ) {
        log += "start:$exclude"
        limits += Rect(limit)
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
        held++
        log += "hold"
    }

    override fun releaseUi() {
        held--
        log += "release"
    }
}
