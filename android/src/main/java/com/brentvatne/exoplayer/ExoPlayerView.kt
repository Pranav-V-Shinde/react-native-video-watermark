package com.brentvatne.exoplayer

import android.content.Context
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.media3.common.AdViewProvider
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Assertions
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.SubtitleView
import com.brentvatne.common.api.ResizeMode
import com.brentvatne.common.api.SubtitleStyle
import com.brentvatne.common.api.ViewType
import com.brentvatne.common.toolbox.DebugLog
import android.graphics.Color
import android.graphics.Typeface
import android.widget.TextView
import android.text.TextUtils

import android.os.Handler
import android.os.Looper
import kotlin.random.Random

@UnstableApi
class ExoPlayerView(private val context: Context) :
    FrameLayout(context, null, 0),
    AdViewProvider {

    var surfaceView: View? = null
        private set
    private var shutterView: View
    private var subtitleLayout: SubtitleView
    private var layout: AspectRatioFrameLayout
    private var componentListener: ComponentListener
    private var player: ExoPlayer? = null
    private var layoutParams: ViewGroup.LayoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )
    private var adOverlayFrameLayout: FrameLayout? = null
    val isPlaying: Boolean
        get() = player != null && player?.isPlaying == true
    private var watermarkTextView: TextView? = null
    private var watermarkText: String? = null

    // Watermark random movement
    private var watermarkHandler: Handler? = null
    private var watermarkRunnable: Runnable? = null

    @ViewType.ViewType
    private var viewType = ViewType.VIEW_TYPE_SURFACE
    private var hideShutterView = false

    private var localStyle = SubtitleStyle()

    init {
        componentListener = ComponentListener()

        val aspectRatioParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        )
        aspectRatioParams.gravity = Gravity.CENTER
        layout = AspectRatioFrameLayout(context)
        layout.layoutParams = aspectRatioParams

        shutterView = View(context)
        shutterView.layoutParams = layoutParams
        shutterView.setBackgroundColor(ContextCompat.getColor(context, android.R.color.black))

        subtitleLayout = SubtitleView(context)
        subtitleLayout.layoutParams = layoutParams
        subtitleLayout.setUserDefaultStyle()
        subtitleLayout.setUserDefaultTextSize()

        updateSurfaceView(viewType)

        layout.addView(shutterView, 1, layoutParams)
        if (localStyle.subtitlesFollowVideo) {
            layout.addView(subtitleLayout, layoutParams)
        }

        addViewInLayout(layout, 0, aspectRatioParams)
        if (!localStyle.subtitlesFollowVideo) {
            addViewInLayout(subtitleLayout, 1, layoutParams)
        }
    }

    private fun clearVideoView() {
        when (val view = surfaceView) {
            is TextureView -> player?.clearVideoTextureView(view)

            is SurfaceView -> player?.clearVideoSurfaceView(view)

            else -> {
                Log.w(
                    "clearVideoView",
                    "Unexpected surfaceView type: ${surfaceView?.javaClass?.name}"
                )
            }
        }
    }

    private fun setVideoView() {
        when (val view = surfaceView) {
            is TextureView -> player?.setVideoTextureView(view)

            is SurfaceView -> player?.setVideoSurfaceView(view)

            else -> {
                Log.w(
                    "setVideoView",
                    "Unexpected surfaceView type: ${surfaceView?.javaClass?.name}"
                )
            }
        }
    }

    fun setSubtitleStyle(style: SubtitleStyle) {
        // ensure we reset subtitle style before reapplying it
        subtitleLayout.setUserDefaultStyle()
        subtitleLayout.setUserDefaultTextSize()

        if (style.fontSize > 0) {
            subtitleLayout.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSize.toFloat())
        }
        subtitleLayout.setPadding(
            style.paddingLeft,
            style.paddingTop,
            style.paddingTop,
            style.paddingBottom
        )
        if (style.opacity != 0.0f) {
            subtitleLayout.alpha = style.opacity
            subtitleLayout.visibility = View.VISIBLE
        } else {
            subtitleLayout.visibility = View.GONE
        }
        if (localStyle.subtitlesFollowVideo != style.subtitlesFollowVideo) {
            // No need to manipulate layout if value didn't change
            if (style.subtitlesFollowVideo) {
                removeViewInLayout(subtitleLayout)
                layout.addView(subtitleLayout, layoutParams)
            } else {
                layout.removeViewInLayout(subtitleLayout)
                addViewInLayout(subtitleLayout, 1, layoutParams, false)
            }
            requestLayout()
        }
        localStyle = style
    }


    fun setWatermarkText(text: String?) {
    watermarkText = text
    if (TextUtils.isEmpty(text)) {
        stopWatermarkMovement()
        watermarkTextView?.let { removeView(it) }
        watermarkTextView = null
    } else {
        if (watermarkTextView == null) {
            watermarkTextView = TextView(context).apply {
                setTextColor(Color.RED)
                //setBackgroundColor(Color.parseColor("#66000000"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(Typeface.DEFAULT_BOLD)
                setPadding(8, 4, 8, 4)
                val params = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
                params.setMargins(0, 0, 16, 16)
                layoutParams = params
            }
            addView(watermarkTextView)
        }
        watermarkTextView?.text = text
        watermarkTextView?.visibility = VISIBLE
        watermarkTextView?.bringToFront()
        startWatermarkMovement()
    }
}

    private fun startWatermarkMovement() {
        if (watermarkHandler == null) {
            watermarkHandler = Handler(Looper.getMainLooper())
        }
        // Remove any existing runnable to avoid duplicates
        watermarkRunnable?.let { watermarkHandler?.removeCallbacks(it) }
        watermarkRunnable = object : Runnable {
            override fun run() {
                moveWatermarkRandomly()
                watermarkHandler?.postDelayed(this, 1000)
            }
        }
        watermarkHandler?.post(watermarkRunnable!!)
    }
    private fun stopWatermarkMovement() {
        watermarkRunnable?.let { watermarkHandler?.removeCallbacks(it) }
        watermarkRunnable = null
    }
    private fun moveWatermarkRandomly() {
        val wmView = watermarkTextView ?: return
        val parentWidth = this.width
        val parentHeight = this.height
        if (parentWidth == 0 || parentHeight == 0) return

        wmView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        val wmWidth = wmView.measuredWidth
        val wmHeight = wmView.measuredHeight

        val maxLeft = parentWidth - wmWidth
        val maxTop = parentHeight - wmHeight

        if (maxLeft <= 0 || maxTop <= 0) return

        val randomLeft = Random.nextInt(0, maxLeft)
        val randomTop = Random.nextInt(0, maxTop)

        val params = wmView.layoutParams
        if (params is FrameLayout.LayoutParams) {
            params.leftMargin = randomLeft
            params.topMargin = randomTop
            params.gravity = Gravity.TOP or Gravity.START
            wmView.layoutParams = params
        }
    }

    //fun getCurrentWatermarkText(): String? = watermarkText


    fun setShutterColor(color: Int) {
        shutterView.setBackgroundColor(color)
    }

    fun updateSurfaceView(@ViewType.ViewType viewType: Int) {
        this.viewType = viewType
        var viewNeedRefresh = false
        when (viewType) {
            ViewType.VIEW_TYPE_SURFACE, ViewType.VIEW_TYPE_SURFACE_SECURE -> {
                if (surfaceView !is SurfaceView) {
                    surfaceView = SurfaceView(context)
                    viewNeedRefresh = true
                }
                (surfaceView as SurfaceView).setSecure(viewType == ViewType.VIEW_TYPE_SURFACE_SECURE)
            }

            ViewType.VIEW_TYPE_TEXTURE -> {
                if (surfaceView !is TextureView) {
                    surfaceView = TextureView(context)
                    viewNeedRefresh = true
                }
                // Support opacity properly:
                (surfaceView as TextureView).isOpaque = false
            }

            else -> {
                DebugLog.wtf(TAG, "Unexpected texture view type: $viewType")
            }
        }

        if (viewNeedRefresh) {
            surfaceView?.layoutParams = layoutParams

            if (layout.getChildAt(0) != null) {
                layout.removeViewAt(0)
            }
            layout.addView(surfaceView, 0, layoutParams)

            if (this.player != null) {
                setVideoView()
            }
        }
    }

    var adsShown = false
    fun showAds() {
        if (!adsShown) {
            adOverlayFrameLayout = FrameLayout(context)
            layout.addView(adOverlayFrameLayout, layoutParams)
            adsShown = true
        }
    }

    fun hideAds() {
        if (adsShown) {
            layout.removeView(adOverlayFrameLayout)
            adOverlayFrameLayout = null
            adsShown = false
        }
    }

    fun updateShutterViewVisibility() {
        shutterView.visibility = if (this.hideShutterView) {
            View.INVISIBLE
        } else {
            View.VISIBLE
        }
    }

    override fun requestLayout() {
        super.requestLayout()
        post(measureAndLayout)
    }

    // AdsLoader.AdViewProvider implementation.
    override fun getAdViewGroup(): ViewGroup =
        Assertions.checkNotNull(
            adOverlayFrameLayout,
            "exo_ad_overlay must be present for ad playback"
        )

    /**
     * Set the {@link ExoPlayer} to use. The {@link ExoPlayer#addListener} method of the
     * player will be called and previous
     * assignments are overridden.
     *
     * @param player The {@link ExoPlayer} to use.
     */
    fun setPlayer(player: ExoPlayer?) {
        if (this.player == player) {
            return
        }
        if (this.player != null) {
            this.player!!.removeListener(componentListener)
            clearVideoView()
        }
        this.player = player

        updateShutterViewVisibility()
        if (player != null) {
            setVideoView()
            player.addListener(componentListener)
        }
    }

    /**
     * Sets the resize mode which can be of value {@link ResizeMode.Mode}
     *
     * @param resizeMode The resize mode.
     */
    fun setResizeMode(@ResizeMode.Mode resizeMode: Int) {
        if (layout.resizeMode != resizeMode) {
            layout.resizeMode = resizeMode
            post(measureAndLayout)
        }
    }

    fun setHideShutterView(hideShutterView: Boolean) {
        this.hideShutterView = hideShutterView
        updateShutterViewVisibility()
    }

    private val measureAndLayout: Runnable = Runnable {
        measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
        layout(left, top, right, bottom)
    }

    private fun updateForCurrentTrackSelections(tracks: Tracks?) {
        if (tracks == null) {
            return
        }
        val groups = tracks.groups

        for (group in groups) {
            if (group.type == C.TRACK_TYPE_VIDEO && group.length > 0) {
                // get the first track of the group to identify aspect ratio
                val format = group.getTrackFormat(0)
                if (format.width > 0 || format.height > 0) {
                    layout.updateAspectRatio(format)
                }
                return
            }
        }
        // no video tracks, in that case refresh shutterView visibility
        updateShutterViewVisibility()
    }

    fun invalidateAspectRatio() {
        // Resetting aspect ratio will force layout refresh on next video size changed
        layout.invalidateAspectRatio()
    }

    fun getCurrentWatermarkText(): String? = watermarkText
    
    private inner class ComponentListener : Player.Listener {
        override fun onCues(cues: List<Cue>) {
            subtitleLayout.setCues(cues)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.height == 0 || videoSize.width == 0) {
                // When changing video track we receive an ghost state with height / width = 0
                // No need to resize the view in that case
                return
            }
            // Here we use updateForCurrentTrackSelections to have a consistent behavior.
            // according to: https://github.com/androidx/media/issues/1207
            // sometimes media3 send bad Video size information
            player?.let {
                updateForCurrentTrackSelections(it.currentTracks)
            }
        }

        override fun onRenderedFirstFrame() {
            shutterView.visibility = INVISIBLE
        }

        override fun onTracksChanged(tracks: Tracks) {
            updateForCurrentTrackSelections(tracks)
        }
    }

    companion object {
        private const val TAG = "ExoPlayerView"
    }
}
