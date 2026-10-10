package com.kalathil.intercom.core

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import kotlin.math.ceil
import kotlin.math.sqrt

/** Someone else in the call, as the call screen shows them. */
data class RemoteParticipant(
    val peerId: String,
    val name: String,
    val role: String,
    /** Null until their picture arrives, and for anyone sending no video. */
    val track: VideoTrack? = null,
    val connected: Boolean = false
)

/**
 * Everyone else in the call, one tile each: the whole screen for one person,
 * side by side for two, two by two for three or four.
 *
 * Each picture is shown whole, with bars if its shape differs from its tile's,
 * because cropping a phone held upright cut faces in half. A name sits on each
 * tile once there is more than one, so grandpa can tell who is who.
 */
class VideoGrid @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    private class Tile(val cell: FrameLayout, val renderer: SurfaceViewRenderer, val label: TextView) {
        var track: VideoTrack? = null
    }

    private val tiles = LinkedHashMap<String, Tile>()
    private var egl: EglBase.Context? = null

    /** Name size on each tile; large on the TV, where it is read from a sofa. */
    var labelTextSizeSp = 16f

    /** Fill each tile and crop, instead of showing each picture whole. */
    var fill = false
        set(value) {
            field = value
            tiles.values.forEach { it.renderer.setScalingType(scaling()) }
        }

    private val gap = dp(4)

    private fun dp(value: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun scaling() =
        if (fill) RendererCommon.ScalingType.SCALE_ASPECT_FILL else RendererCommon.ScalingType.SCALE_ASPECT_FIT

    /**
     * Renderers belong to one engine's EGL context and die with it, so a new
     * engine - or none - starts the grid afresh.
     */
    fun attach(eglContext: EglBase.Context?) {
        if (eglContext === egl) return
        clear()
        egl = eglContext
    }

    /** Show exactly these people, keeping the tiles of anyone already shown. */
    fun show(participants: List<RemoteParticipant>) {
        val context = egl
        if (context == null) {
            clear()
            return
        }
        val wanted = participants.map { it.peerId }.toSet()
        (tiles.keys - wanted).forEach { removeTile(it) }

        for (p in participants) {
            val tile = tiles[p.peerId] ?: addTile(p.peerId, context) ?: continue
            tile.label.text = p.name
            tile.label.visibility = if (participants.size > 1) View.VISIBLE else View.GONE
            if (tile.track !== p.track) {
                // A track can be disposed under us when its connection closes.
                runCatching { tile.track?.removeSink(tile.renderer) }
                tile.track = p.track
                runCatching { p.track?.addSink(tile.renderer) }
            }
        }
        requestLayout()
    }

    fun clear() {
        tiles.keys.toList().forEach { removeTile(it) }
    }

    private fun addTile(peerId: String, context: EglBase.Context): Tile? {
        val renderer = SurfaceViewRenderer(this.context)
        val ok = runCatching {
            renderer.init(context, null)
            renderer.setScalingType(scaling())
            renderer.setEnableHardwareScaler(true)
        }.onFailure { Log.e(TAG, "could not set up a video tile", it) }.isSuccess
        if (!ok) return null

        // wrap_content inside the cell, not match_parent: stretched to fill,
        // the renderer crops whatever its scaling type says.
        val cell = FrameLayout(this.context).apply { setBackgroundColor(Color.BLACK) }
        cell.addView(renderer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))
        val label = TextView(this.context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, labelTextSizeSp)
            setBackgroundColor(LABEL_SCRIM)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            maxLines = 1
        }
        cell.addView(label, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START
        ).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) })

        addView(cell)
        return Tile(cell, renderer, label).also { tiles[peerId] = it }
    }

    private fun removeTile(peerId: String) {
        val tile = tiles.remove(peerId) ?: return
        runCatching { tile.track?.removeSink(tile.renderer) }
        tile.track = null
        removeView(tile.cell)
        runCatching { tile.renderer.release() }
    }

    // -----------------------------------------------------------------------
    // Layout
    // -----------------------------------------------------------------------

    /** Columns and rows for [count] tiles, wider than tall on a landscape screen. */
    private fun grid(count: Int, width: Int, height: Int): Pair<Int, Int> {
        if (count <= 1) return 1 to 1
        val major = ceil(sqrt(count.toDouble())).toInt()
        val minor = ceil(count / major.toDouble()).toInt()
        return if (width >= height) major to minor else minor to major
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)

        val (cols, rows) = grid(childCount, width, height)
        val cellWidth = ((width - gap * (cols - 1)) / cols).coerceAtLeast(0)
        val cellHeight = ((height - gap * (rows - 1)) / rows).coerceAtLeast(0)
        for (i in 0 until childCount) {
            getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(cellHeight, MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val height = b - t
        val count = childCount
        if (count == 0) return
        val (cols, rows) = grid(count, width, height)
        val cellWidth = (width - gap * (cols - 1)) / cols
        val cellHeight = (height - gap * (rows - 1)) / rows

        for (i in 0 until count) {
            val row = i / cols
            val col = i % cols
            // A last row that is not full is centred, so three people sit as
            // two above and one in the middle below.
            val inRow = if (row == rows - 1) count - row * cols else cols
            val rowStart = (width - (inRow * cellWidth + (inRow - 1) * gap)) / 2
            val left = rowStart + col * (cellWidth + gap)
            val top = row * (cellHeight + gap)
            getChildAt(i).layout(left, top, left + cellWidth, top + cellHeight)
        }
    }

    override fun onDetachedFromWindow() {
        clear()
        egl = null
        super.onDetachedFromWindow()
    }

    private companion object {
        const val TAG = "VideoGrid"
        const val LABEL_SCRIM = 0x99000000.toInt()
    }
}
