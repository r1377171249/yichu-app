package com.xiaoyichu.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.roundToInt

/** Bundled U²-Net-p salient foreground model; does not distinguish a hanger from clothing. */
class AutomaticCutout private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: AutomaticCutout? = null
        fun get(context: Context): AutomaticCutout = instance ?: synchronized(this) {
            instance ?: AutomaticCutout(context.applicationContext).also { instance = it }
        }
    }
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession by lazy {
        val bytes = context.assets.open("models/u2netp.onnx").use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(hash == "309c8469258dda742793dce0ebea8e6dd393174f89934733ecc8b14c76f4ddd8")
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            environment.createSession(bytes, options)
        }
    }

    @Synchronized
    fun removeBackground(source: Bitmap,mode:String="保留细节"): Bitmap {
        val small = Bitmap.createScaledBitmap(source, 320, 320, true)
        val pixels = IntArray(320 * 320)
        small.getPixels(pixels, 0, 320, 0, 0, 320, 320)
        if (small !== source) small.recycle()
        // Composite transparent source pixels onto white for model input; preserve source alpha below.
        val rgb = FloatArray(pixels.size * 3)
        var maximum = 1f
        pixels.forEachIndexed { i, pixel ->
            val alpha = Color.alpha(pixel) / 255f
            for (c in 0..2) {
                val channel = when (c) { 0 -> Color.red(pixel); 1 -> Color.green(pixel); else -> Color.blue(pixel) }
                val value = channel * alpha + 255f * (1f - alpha)
                rgb[c * pixels.size + i] = value
                maximum = maxOf(maximum, value)
            }
        }
        val mean = floatArrayOf(.485f, .456f, .406f)
        val deviation = floatArrayOf(.229f, .224f, .225f)
        for (c in 0..2) for (i in pixels.indices) {
            val index = c * pixels.size + i
            rgb[index] = (rgb[index] / maximum - mean[c]) / deviation[c]
        }
        val activeSession = session
        val mask = OnnxTensor.createTensor(environment, FloatBuffer.wrap(rgb), longArrayOf(1, 3, 320, 320)).use { tensor ->
            activeSession.run(mapOf(activeSession.inputNames.first() to tensor)).use { results ->
                val output = results[0] as OnnxTensor
                FloatArray(pixels.size).also { output.floatBuffer.get(it) }
            }
        }
        check(mask.all { it.isFinite() })
        val low = mask.minOrNull()!!
        val range = mask.maxOrNull()!! - low
        check(range > .00001f) { "No foreground found" }
        val maskPixels = IntArray(mask.size) { i ->
            val value = (((mask[i] - low) / range) * 255f).roundToInt().coerceIn(0, 255)
            Color.rgb(value, value, value)
        }
        val tinyMask = Bitmap.createBitmap(maskPixels, 320, 320, Bitmap.Config.ARGB_8888)
        val fullMask = Bitmap.createScaledBitmap(tinyMask, source.width, source.height, true)
        if (fullMask !== tinyMask) tinyMask.recycle()
        try {
            val count = source.width * source.height
            val outputPixels = IntArray(count)
            val alphas = IntArray(count)
            source.getPixels(outputPixels, 0, source.width, 0, 0, source.width, source.height)
            fullMask.getPixels(alphas, 0, source.width, 0, 0, source.width, source.height)
            val refined=refineCutout(outputPixels,alphas,source.width,source.height,mode)
            val foreground=refined.count{(it ushr 24)>128}
            check(foreground > count / 1000 && foreground < count * .995) { "No usable foreground found" }
            return Bitmap.createBitmap(refined, source.width, source.height, Bitmap.Config.ARGB_8888)
        } finally { fullMask.recycle() }
    }
}
