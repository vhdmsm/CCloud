package com.pira.ccloud.player

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer

/**
 * Adds a second text renderer so two subtitle tracks (Persian + English) can be shown together.
 * Cues of the second renderer are delivered to [secondaryOutput] instead of the player.
 */
@OptIn(UnstableApi::class)
class DualSubtitleRenderersFactory(
    context: Context,
    private val trackSelector: DualSubtitleTrackSelector,
    private val secondaryOutput: TextOutput
) : DefaultRenderersFactory(context) {

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>
    ) {
        // Must come before the regular TextRenderer: when two renderers support a track equally,
        // ExoPlayer maps it to the first one, so the tracks this renderer accepts are routed to it.
        out.add(
            SecondaryTextRenderer(
                TextRenderer(secondaryOutput, outputLooper),
                trackSelector::isSecondaryTrack
            )
        )
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
    }
}

/**
 * Wraps a [TextRenderer] (which is final) and only reports support for the tracks that
 * [acceptsTrack] routes to it. Everything else is forwarded to the wrapped renderer.
 */
@OptIn(UnstableApi::class)
class SecondaryTextRenderer(
    private val delegate: TextRenderer,
    private val acceptsTrack: (Format) -> Boolean
) : Renderer by delegate, RendererCapabilities {

    companion object {
        const val NAME = "SecondaryTextRenderer"
    }

    override fun getName(): String = NAME

    override fun getTrackType(): Int = C.TRACK_TYPE_TEXT

    override fun getCapabilities(): RendererCapabilities = this

    override fun supportsFormat(format: Format): Int =
        if (acceptsTrack(format)) {
            delegate.supportsFormat(format)
        } else {
            RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
        }

    override fun supportsMixedMimeTypeAdaptation(): Int = delegate.supportsMixedMimeTypeAdaptation()

    override fun setListener(listener: RendererCapabilities.Listener) = delegate.setListener(listener)

    override fun clearListener() = delegate.clearListener()

    // Interface default methods are forwarded explicitly so the wrapped renderer's versions run
    override fun getDurationToProgressUs(positionUs: Long, elapsedRealtimeUs: Long): Long =
        delegate.getDurationToProgressUs(positionUs, elapsedRealtimeUs)

    override fun setPlaybackSpeed(currentPlaybackSpeed: Float, targetPlaybackSpeed: Float) =
        delegate.setPlaybackSpeed(currentPlaybackSpeed, targetPlaybackSpeed)

    override fun enableMayRenderStartOfStream() = delegate.enableMayRenderStartOfStream()

    override fun release() = delegate.release()
}
