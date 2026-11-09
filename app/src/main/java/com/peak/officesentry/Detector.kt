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
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** What the camera saw at the moment a visitor was confirmed. */
data class Sighting(val faces: Int, val labels: List<String>, val snapshot: Bitmap?, val pet: String? = null)

/** Live measurements of the largest face in view (for the control page readout + calibration). */
data class FaceMetrics(
    val frac: Float,        // face width / frame width (bigger = closer)
    val yaw: Float,         // head turned left/right, degrees (0 = facing the camera)
    val pitch: Float,       // head tilted up/down, degrees
    val bothEyes: Boolean,  // both eye landmarks found and open
    val engaged: Boolean,   // meets every rule to be spoken to
    val at: Long,           // uptimeMillis
)

/**
 * On-device presence detection with ML Kit (no network, no cost).
 *
 * Two levels:
 *  - ANY face  -> [onActivity] (wakes the screen so the status is visible)
 *  - ENGAGED   -> [onVisitor]  (Garrick speaks). A face is engaged only when it is
 *    close enough (face width >= [Prefs.minFaceFrac] of the frame), facing the door
 *    (|yaw| and |pitch| <= [Prefs.maxYaw]) and both eyes are visible and open,
 *    for ~1 second in a row. People walking past in profile, or far down the hall
 *    (e.g. heading to another room), never trigger a conversation.
 */
class Detector(
    private val ctx: Context,
    private val prefs: Prefs,
    private val onVisitor: (Sighting) -> Unit,
    private val onActivity: () -> Unit,
) {
    private val exec = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    @Volatile var armed = true
    /** Frame interval: ~350 ms awake, slower while the screen sleeps to save power. */
    @Volatile var intervalMs = 350L

    private val faces = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.06f)          // small enough to notice people down the hall (wakes screen only)
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
    @Volatile var metrics: FaceMetrics? = null; private set

    // calibration: watch for a few seconds and remember the largest frontal face
    @Volatile private var calibrateUntil = 0L
    @Volatile private var calibrateBest = 0f
    @Volatile var onCalibrated: ((Float?) -> Unit)? = null

    fun startCalibration(windowMs: Long = 4000) {
        calibrateBest = 0f
        calibrateUntil = SystemClock.uptimeMillis() + windowMs
    }

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

    private fun measure(f: Face, frameW: Int): FaceMetrics {
        val frac = f.boundingBox.width().toFloat() / frameW
        val yaw = f.headEulerAngleY
        val pitch = f.headEulerAngleX
        val eyesFound = f.getLandmark(FaceLandmark.LEFT_EYE) != null && f.getLandmark(FaceLandmark.RIGHT_EYE) != null
        val lOpen = f.leftEyeOpenProbability
        val rOpen = f.rightEyeOpenProbability
        // ML Kit only reports eye-open probabilities for near-frontal faces; null = can't see both eyes
        val bothEyes = eyesFound && lOpen != null && rOpen != null && lOpen > 0.3f && rOpen > 0.3f
        val maxTurn = prefs.maxYaw.toFloat()
        val engaged = bothEyes && frac >= prefs.minFaceFrac && abs(yaw) <= maxTurn && abs(pitch) <= maxTurn
        return FaceMetrics(frac, yaw, pitch, bothEyes, engaged, SystemClock.uptimeMillis())
    }

    private fun analyze(proxy: ImageProxy) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFrame < intervalMs || busy.get()) { proxy.close(); return }
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
                val main = list.maxByOrNull { it.boundingBox.width() }
                val m = main?.let { measure(it, bmp.width) }
                metrics = m ?: metrics
                if (main != null) {
                    lastFaceSeen = SystemClock.uptimeMillis()
                    onActivity()
                }
                streak = if (m?.engaged == true) streak + 1 else 0

                // calibration window: remember the closest frontal face
                if (calibrateUntil > 0) {
                    if (m != null && m.bothEyes && abs(m.yaw) <= 30f && m.frac > calibrateBest) calibrateBest = m.frac
                    if (SystemClock.uptimeMillis() > calibrateUntil) {
                        calibrateUntil = 0
                        onCalibrated?.invoke(calibrateBest.takeIf { it > 0f })
                    }
                }

                val wantLabels = streak >= 3 || now - lastLabel > 2500
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
                        } else if (armed && petStreak >= 2 && prefs.greetPets) {
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
