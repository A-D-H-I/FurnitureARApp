package com.example.furnitureapp

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.ar.core.Anchor
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Trackable
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import kotlin.math.pow
import kotlin.math.sqrt

class MeasureActivity : AppCompatActivity() {

    private lateinit var arView: ARSceneView
    private lateinit var overlayLine: OverlayLineView
    private lateinit var tvTopInfo: TextView
    private lateinit var tvFloating: TextView
    private lateinit var btnStart: MaterialButton
    private lateinit var btnEnd: MaterialButton
    private lateinit var btnReset: MaterialButton

    private var measuring = false
    private var locked = false

    // ✅ World anchors (this is the key fix)
    private var anchorA: Anchor? = null
    private var anchorB: Anchor? = null

    private var lastUpdateMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_measure)

        arView = findViewById(R.id.measureArView)
        overlayLine = findViewById(R.id.overlayLine)
        tvTopInfo = findViewById(R.id.tvTopInfo)
        tvFloating = findViewById(R.id.tvFloatingMeasure)
        btnStart = findViewById(R.id.btnStart)
        btnEnd = findViewById(R.id.btnEnd)
        btnReset = findViewById(R.id.btnReset)

        arView.lifecycle = lifecycle

        btnEnd.isEnabled = false
        tvFloating.visibility = View.GONE

        btnStart.setOnClickListener { startMeasure() }
        btnEnd.setOnClickListener { endAndLock() }
        btnReset.setOnClickListener { resetAll() }

        arView.onFrame = onFrame@{
            updateHint()

            // Throttle
            val now = SystemClock.elapsedRealtime()
            if (now - lastUpdateMs < 40) return@onFrame
            lastUpdateMs = now

            val frame = arView.frame ?: return@onFrame
            val camera = frame.camera

            // If tracking lost, hide visuals so they don’t freeze on screen
            if (camera.trackingState != TrackingState.TRACKING) {
                overlayLine.clearLine()
                tvFloating.visibility = View.GONE
                return@onFrame
            }

            // Need at least anchorA to show something
            val a = anchorA ?: run {
                overlayLine.clearLine()
                tvFloating.visibility = View.GONE
                return@onFrame
            }

            // If measuring, anchorB follows live hit point; if locked, anchorB stays fixed
            if (measuring && !locked) {
                val hit = hitTestCenter() ?: return@onFrame
                val newPose = hit.hitPose

                // Update a "virtual" end pose using current hit (no anchor yet)
                drawFromPoses(a.pose, newPose)
            } else {
                // locked mode: need both anchors
                val b = anchorB ?: return@onFrame
                drawFromPoses(a.pose, b.pose)
            }
        }
    }

    private fun startMeasure() {
        val hit = hitTestCenter() ?: run {
            toast("Surface not detected. Move phone slowly.")
            return
        }

        resetAll() // clear old anchors safely

        anchorA = hit.createAnchor()
        measuring = true
        locked = false

        btnEnd.isEnabled = true
        tvTopInfo.text = "Point A set • Move phone, then END"
        tvFloating.visibility = View.VISIBLE
    }

    private fun endAndLock() {
        if (!measuring) return

        val hit = hitTestCenter() ?: run {
            toast("Surface not detected. Try again.")
            return
        }

        // Create anchorB at end point so line becomes world-fixed
        anchorB?.detach()
        anchorB = hit.createAnchor()

        measuring = false
        locked = true
        btnEnd.isEnabled = false

        toast("Measurement locked")
    }

    private fun resetAll() {
        measuring = false
        locked = false

        anchorA?.detach()
        anchorB?.detach()
        anchorA = null
        anchorB = null

        btnEnd.isEnabled = false
        tvTopInfo.text = "Move phone to detect surface"
        tvFloating.visibility = View.GONE
        overlayLine.clearLine()
    }

    private fun updateHint() {
        val frame = arView.frame ?: return
        val updatedPlanes = frame.getUpdatedTrackables(Plane::class.java)
        if (updatedPlanes.isNotEmpty() && anchorA == null) {
            tvTopInfo.text = "Aim at surface • START to begin"
        }
    }

    private fun hitTestCenter(): HitResult? {
        val frame = arView.frame ?: return null
        val hits = frame.hitTest(arView.width / 2f, arView.height / 2f)
        for (hit in hits) {
            val t: Trackable = hit.trackable
            if (t is Plane && t.isPoseInPolygon(hit.hitPose)) return hit
        }
        return null
    }

    // ✅ Draw line/label from two world poses (A and B)
    private fun drawFromPoses(poseA: Pose, poseB: Pose) {
        val frame = arView.frame ?: return
        val camera = frame.camera

        val viewMat = FloatArray(16)
        val projMat = FloatArray(16)
        camera.getViewMatrix(viewMat, 0)
        camera.getProjectionMatrix(projMat, 0, 0.1f, 100f)

        val a = floatArrayOf(poseA.tx(), poseA.ty(), poseA.tz())
        val b = floatArrayOf(poseB.tx(), poseB.ty(), poseB.tz())

        val sA = worldToScreen(a, viewMat, projMat, arView.width.toFloat(), arView.height.toFloat())
        val sB = worldToScreen(b, viewMat, projMat, arView.width.toFloat(), arView.height.toFloat())

        if (sA == null || sB == null) {
            overlayLine.clearLine()
            tvFloating.visibility = View.GONE
            return
        }

        overlayLine.setLine(sA[0], sA[1], sB[0], sB[1])

        val meters = distanceMeters(a, b)
        val cm = meters * 100.0

        tvFloating.visibility = View.VISIBLE
        tvFloating.text = "${"%.1f".format(cm)} cm"

        val midX = (sA[0] + sB[0]) / 2f
        val midY = (sA[1] + sB[1]) / 2f - 30f

        tvFloating.post {
            tvFloating.translationX = midX - tvFloating.width / 2f
            tvFloating.translationY = midY - tvFloating.height / 2f
        }
    }

    private fun worldToScreen(
        world: FloatArray,
        view: FloatArray,
        proj: FloatArray,
        screenW: Float,
        screenH: Float
    ): FloatArray? {
        val v = floatArrayOf(world[0], world[1], world[2], 1f)

        val viewV = mulMatVec(view, v)
        val clip = mulMatVec(proj, viewV)

        val w = clip[3]
        if (w == 0f) return null

        val ndcX = clip[0] / w
        val ndcY = clip[1] / w
        val ndcZ = clip[2] / w

        if (ndcZ < -1f || ndcZ > 1f) return null

        val x = (ndcX + 1f) * 0.5f * screenW
        val y = (1f - (ndcY + 1f) * 0.5f) * screenH
        return floatArrayOf(x, y)
    }

    private fun mulMatVec(m: FloatArray, v: FloatArray): FloatArray {
        val r = FloatArray(4)
        for (i in 0..3) {
            r[i] =
                m[i] * v[0] +
                        m[4 + i] * v[1] +
                        m[8 + i] * v[2] +
                        m[12 + i] * v[3]
        }
        return r
    }

    private fun distanceMeters(a: FloatArray, b: FloatArray): Double {
        return sqrt(
            (a[0] - b[0]).toDouble().pow(2) +
                    (a[1] - b[1]).toDouble().pow(2) +
                    (a[2] - b[2]).toDouble().pow(2)
        )
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
