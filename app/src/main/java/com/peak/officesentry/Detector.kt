package com.peak.officesentry

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** What the camera saw at the moment a visitor was confirmed. */
data class Sighting(val faces: Int, val labels: List<String>, val snapshot: Bitmap?, val pet: String? = null)

/**
 * On-device presence detection with ML Kit (no network, no cost).
 * - Face detection every ~400 ms. A face in 3 consecutive frames = visitor.
 * - Image labeling every ~2 s to catch pets (dogs/cats have no human face) and
 *   to give Claude extra hints (glasses, mug, hat...).
 */
class Detector(
    private val ctx: Context,
    private val onVisitor: (Sighting) -> Unit,
    private val onActivity: () -> Unit,
) {
    private val exec = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    @Volatile var armed = true

    private val faces = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.10f)
            .build()
    )
    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder().setConfidenceThreshold(0.6f).build()
    )

    private var lastFrame = 0L
    private var lastLabel = 0L
    private var streak = 0
    private var petStreak = 0
    @Volatile var lastFaceSeen = 0L; private set

    fun start(owner: LifecycleOwner, front: Boolean) {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val provider = future.get()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    ).build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(exec) { analyze(it) }

            var selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            if (!provider.hasCamera(selector)) selector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                provider.unbindAll()
                provider.bindToLifecycle(owner, selector, analysis)
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    private fun analyze(proxy: ImageProxy) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFrame < 400 || busy.get()) { proxy.close(); return }
        lastFrame = now
        val bmp: Bitmap = try {
            val raw = proxy.toBitmap()
            val rot = proxy.imageInfo.rotationDegrees
            if (rot == 0) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
        } catch (e: Exception) {
            Log.w(TAG, "frame convert failed", e); proxy.close(); return
        } finally {
            proxy.close()
        }
        busy.set(true)
        val image = InputImage.fromBitmap(bmp, 0)
        faces.process(image)
            .addOnSuccessListener { list ->
                if (list.isNotEmpty()) {
                    lastFaceSeen = SystemClock.uptimeMillis()
                    onActivity()
                    streak++
                } else streak = 0

                val wantLabels = streak >= 3 || now - lastLabel > 2000
                if (!wantLabels) { busy.set(false); return@addOnSuccessListener }
                lastLabel = now
                labeler.process(image)
                    .addOnSuccessListener { labels ->
                        val names = labels.sortedByDescending { it.confidence }.map { it.text }
                        val pet = labels.firstOrNull { (it.text == "Dog" || it.text == "Cat") && it.confidence > 0.75f }?.text
                        if (pet != null && list.isEmpty()) petStreak++ else petStreak = 0

                        if (armed && streak >= 3) {
                            armed = false; streak = 0
                            onVisitor(Sighting(list.size, names.take(8), bmp))
                        } else if (armed && petStreak >= 2) {
                            armed = false; petStreak = 0
                            onActivity()
                            onVisitor(Sighting(0, names.take(8), bmp, pet))
                        }
                    }
                    .addOnCompleteListener { busy.set(false) }
            }
            .addOnFailureListener { busy.set(false) }
    }

    fun shutdown() {
        exec.shutdown(); faces.close(); labeler.close()
    }

    companion object { const val TAG = "Detector" }
}
