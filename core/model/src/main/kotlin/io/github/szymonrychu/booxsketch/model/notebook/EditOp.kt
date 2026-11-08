package io.github.szymonrychu.booxsketch.model.notebook

import kotlinx.serialization.Serializable

@Serializable
enum class OpKind { REPLACE, DELETE, INSERT, IMAGE, NOTE }

@Serializable
enum class AtMode { INLINE, AFTER_LINE }

/** Char offsets `[from, to)` in the markdown source line of [lineId] and the text there. */
@Serializable
data class OpTarget(
    val lineId: String,
    val from: Int,
    val to: Int,
    val quote: String,
)

@Serializable
data class OpAt(
    val lineId: String,
    val offset: Int,
    val mode: AtMode,
)

@Serializable
data class RegionRef(
    val id: String,
    val part: String,
    val afterLine: String?,
)

/** A handwritten edit. Stroke ids are model ids; the wire codec maps them to stroke indices. */
@Serializable
data class EditOp(
    val id: String,
    val kind: OpKind,
    val target: OpTarget? = null,
    val at: OpAt? = null,
    val strokeIds: List<String>,
    val rect: List<Float>? = null,
    val region: RegionRef? = null,
)

/** An op sent in the upload [clientId] and not yet reported by a spec version. */
@Serializable
data class PendingOp(
    val clientId: String,
    val op: EditOp,
    /** The spec version the upload was based on (0 when none): a newer version that does not report it releases it. */
    val baseVersion: Int = 0,
)
