package com.autograde.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Measurements taken from a video (or a reference photo). All values 0..1. */
data class Stats(
    val r: Float, val g: Float, val b: Float,
    val luma: Float, val p1: Float, val p99: Float, val sat: Float
)

/** The "look" we want to reach. Defaults match the bright, clean reference. */
data class Target(val luma: Float = 0.62f, val sat: Float = 0.30f)

data class Params(
    val gainR: Float, val gainG: Float, val gainB: Float,
    val black: Float, val white: Float,
    val gamma: Float, val sat: Float, val contrast: Float
)

object ColorGrade {

    // ---------- 1. ANALYSIS ----------

    fun statsOf(frames: List<Bitmap>): Stats {
        val hist = IntArray(256)
        var n = 0L
        var sr = 0.0; var sg = 0.0; var sb = 0.0; var sl = 0.0; var ss = 0.0
        for (bmp in frames) {
            val w = bmp.width; val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            for (p in px) {
                val r = ((p shr 16) and 255) / 255f
                val g = ((p shr 8) and 255) / 255f
                val b = (p and 255) / 255f
                val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
                hist[(l * 255f).toInt().coerceIn(0, 255)]++
                val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
                ss += if (mx > 0f) (mx - mn) / mx else 0f
                sr += r; sg += g; sb += b; sl += l
                n++
            }
        }
        val nn = max(n, 1L).toDouble()
        fun pct(q: Float): Float {
            val goal = (n * q).toLong()
            var acc = 0L
            for (i in 0..255) { acc += hist[i]; if (acc >= goal) return i / 255f }
            return 1f
        }
        return Stats(
            (sr / nn).toFloat(), (sg / nn).toFloat(), (sb / nn).toFloat(),
            (sl / nn).toFloat(), pct(0.01f), pct(0.99f), (ss / nn).toFloat()
        )
    }

    /** Samples 8 frames spread across the video. */
    fun analyzeVideo(context: Context, uri: Uri): Stats {
        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(context, uri)
        val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val frames = (0 until 8).mapNotNull { i ->
            val t = (durMs * 1000L * (i + 0.5) / 8).toLong()
            mmr.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 180, 320)
        }
        mmr.release()
        return statsOf(frames)
    }

    // ---------- 2. DECIDING THE GRADE ----------

    fun paramsFor(s: Stats, t: Target): Params {
        // Partial white balance (60%) so brown wood / coloured scenes aren't over-corrected.
        val avg = (s.r + s.g + s.b) / 3f
        fun gain(c: Float) = (1f + (avg / max(c, 0.01f) - 1f) * 0.6f).coerceIn(0.8f, 1.25f)

        val black = s.p1.coerceIn(0f, 0.12f)
        val white = s.p99.coerceIn(0.8f, 1f)
        val m = ((s.luma - black) / (white - black)).coerceIn(0.05f, 0.95f)
        val gamma = (ln(t.luma) / ln(m)).coerceIn(0.45f, 1.2f)
        val sat = (t.sat / max(s.sat, 0.05f)).coerceIn(0.85f, 1.35f)

        return Params(gain(s.r), gain(s.g), gain(s.b), black, white, gamma, sat, 0.15f)
    }

    // ---------- 3. THE GRADE ITSELF ----------

    private fun smooth(x: Float) = x * x * (3f - 2f * x)

    fun grade(r0: Float, g0: Float, b0: Float, p: Params): FloatArray {
        val range = p.white - p.black
        fun ch(v: Float, gain: Float): Float {
            var x = ((v * gain - p.black) / range).coerceIn(0f, 1f)
            x = x.pow(p.gamma)
            x += p.contrast * (smooth(x) - x)
            return x
        }
        var r = ch(r0, p.gainR); var g = ch(g0, p.gainG); var b = ch(b0, p.gainB)
        val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
        r = (l + (r - l) * p.sat).coerceIn(0f, 1f)
        g = (l + (g - l) * p.sat).coerceIn(0f, 1f)
        b = (l + (b - l) * p.sat).coerceIn(0f, 1f)
        return floatArrayOf(r, g, b)
    }

    /** Bakes the grade into a 33x33x33 3D LUT (used for both live preview and export). */
    fun buildCube(p: Params, strength: Float, size: Int = 33): Array<Array<IntArray>> {
        val k = (size - 1).toFloat()
        return Array(size) { ri ->
            Array(size) { gi ->
                IntArray(size) { bi ->
                    val r = ri / k; val g = gi / k; val b = bi / k
                    val o = grade(r, g, b, p)
                    fun mix(a: Float, c: Float) = ((a + (c - a) * strength).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                    (0xFF shl 24) or (mix(r, o[0]) shl 16) or (mix(g, o[1]) shl 8) or mix(b, o[2])
                }
            }
        }
    }
}
