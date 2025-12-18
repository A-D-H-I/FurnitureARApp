package com.example.furnitureapp

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import com.google.ar.core.HitResult as ArHitResult
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.arcore.createAnchorOrNull
import io.github.sceneview.ar.arcore.isValid
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.collision.HitResult
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.*

class ArActivity : AppCompatActivity() {

    // ---------- UI ----------
    private lateinit var arSceneView: ARSceneView
    private lateinit var btnLogout: Button
    private lateinit var btnAI: ImageButton
    private lateinit var btnArrangeRoom: ImageButton
    private lateinit var btnRotate: ImageButton
    private lateinit var btnScale: ImageButton
    private lateinit var btnDelete: ImageButton
    private lateinit var btnDeleteAll: ImageButton
    private lateinit var btnAssetList: ImageButton

    private lateinit var btnAssetCouch: Button
    private lateinit var btnAssetChair: Button
    private lateinit var btnAssetTable: Button

    // ---------- STATE ----------
    private var modelFile: String = "Couch.glb"
    private var isExternalModel: Boolean = false

    private val placedNodes = mutableListOf<ModelNode>()
    private val nodeToModelKey = HashMap<ModelNode, String>()
    private var currentSelectedNode: ModelNode? = null

    private enum class InteractionMode { NONE, ROTATE, SCALE }
    private var mode: InteractionMode = InteractionMode.NONE

    // Gesture tracking
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    // Tuning
    private val tapThreshold = 10f
    private val moveSlop = 4f
    private val rotationFactor = 0.3f
    private val scaleFactor = 0.005f
    private val minScale = 0.3f
    private val maxScale = 2.0f
    private val selectBoost = 1.02f

    // Arrange tuning
    private val gapMeters = 0.18f  // ✅ increased (prevents overlap)
    private val arrangeMinZ = -3.0f
    private val arrangeMaxZ = -0.8f
    private val arrangeMinX = -2.4f
    private val arrangeMaxX = 2.4f

    private var roomClassifier: RoomClassifier? = null

    // ✅ Single pick ONLY
    private val pickAssetLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult

            modelFile = data.getStringExtra("MODEL_FILE") ?: modelFile
            isExternalModel = data.getBooleanExtra("IS_EXTERNAL_MODEL", false)
            Toast.makeText(this, "Selected model updated", Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ar)

        modelFile = intent.getStringExtra("MODEL_FILE") ?: "Couch.glb"
        isExternalModel = intent.getBooleanExtra("IS_EXTERNAL_MODEL", false)

        arSceneView = findViewById(R.id.sceneView)
        btnLogout = findViewById(R.id.btnLogoutAr)
        btnAI = findViewById(R.id.btnAIAr)
        btnArrangeRoom = findViewById(R.id.btnArrangeRoom)
        btnRotate = findViewById(R.id.btnRotateAr)
        btnScale = findViewById(R.id.btnScaleAr)
        btnDelete = findViewById(R.id.btnDeleteAr)
        btnDeleteAll = findViewById(R.id.btnDeleteAllAr)
        btnAssetList = findViewById(R.id.btnAssetList)

        btnAssetCouch = findViewById(R.id.btnAssetCouch)
        btnAssetChair = findViewById(R.id.btnAssetChair)
        btnAssetTable = findViewById(R.id.btnAssetTable)

        arSceneView.lifecycle = lifecycle
        arSceneView.planeRenderer.isEnabled = true
        btnDelete.visibility = View.GONE

        roomClassifier = RoomClassifier(this)

        setupAR()
        setupTouchHandling()

        btnLogout.setOnClickListener { logout() }

        btnAssetList.setOnClickListener {
            val intent = Intent(this, AssetListActivity::class.java)
            intent.putExtra("PICK_MODE", true)
            pickAssetLauncher.launch(intent)
        }

        btnAssetCouch.setOnClickListener {
            modelFile = "Couch.glb"; isExternalModel = false
            Toast.makeText(this, "Selected Couch", Toast.LENGTH_SHORT).show()
        }
        btnAssetChair.setOnClickListener {
            modelFile = "Chair.glb"; isExternalModel = false
            Toast.makeText(this, "Selected Chair", Toast.LENGTH_SHORT).show()
        }
        btnAssetTable.setOnClickListener {
            modelFile = "Table.glb"; isExternalModel = false
            Toast.makeText(this, "Selected Table", Toast.LENGTH_SHORT).show()
        }

        // ✅ Arrange button: classify current room → show label → arrange
        btnArrangeRoom.setOnClickListener {
            if (placedNodes.isEmpty()) {
                Toast.makeText(this, "No objects to arrange", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            captureSceneBitmapSafe { bitmap ->
                val (label, conf) = classifyRoom(bitmap)
                Toast.makeText(this, "Room: $label (${(conf * 100).toInt()}%)", Toast.LENGTH_LONG).show()
                arrangePlacedObjectsForRoomSmart(label)
            }
        }

        // ✅ AI button: classify → SHOW label → place spread-out on planes → arrange
        btnAI.setOnClickListener {
            captureSceneBitmapSafe { bitmap ->
                val (label, conf) = classifyRoom(bitmap)
                Toast.makeText(this, "Detected: $label (${(conf * 100).toInt()}%)", Toast.LENGTH_LONG).show()

                val (files, flags) = aiChooseDefaultAssets(label)

                lifecycleScope.launch {
                    val placed = placeMultipleOnPlaneSpread(files, flags)
                    if (placed.isEmpty()) {
                        Toast.makeText(this@ArActivity, "AI placement failed (no plane found)", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    arSceneView.postDelayed({
                        arrangePlacedObjectsForRoomSmart(label)
                    }, 250)
                }
            }
        }

        btnRotate.setOnClickListener {
            mode = InteractionMode.ROTATE
            Toast.makeText(this, "Rotate: swipe left/right", Toast.LENGTH_SHORT).show()
        }
        btnScale.setOnClickListener {
            mode = InteractionMode.SCALE
            Toast.makeText(this, "Scale: swipe up/down", Toast.LENGTH_SHORT).show()
        }

        btnDelete.setOnClickListener { deleteSelectedNode() }
        btnDeleteAll.setOnClickListener { deleteAllNodes() }
    }

    override fun onDestroy() {
        roomClassifier?.close()
        if (::arSceneView.isInitialized) arSceneView.destroy()
        super.onDestroy()
    }

    // ---------------- AR Setup ----------------

    private fun setupAR() {
        arSceneView.configureSession { session, cfg ->
            cfg.depthMode = if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                Config.DepthMode.AUTOMATIC
            } else Config.DepthMode.DISABLED
            cfg.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            cfg.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        }
    }

    // ---------------- Touch Handling ----------------

    private fun setupTouchHandling() {
        arSceneView.onTouchEvent = { e: MotionEvent, hit: HitResult? ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y
                    lastX = e.x; lastY = e.y
                    downTime = System.currentTimeMillis()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val node = currentSelectedNode
                    if (node != null && mode != InteractionMode.NONE) {
                        val dx = e.x - lastX
                        val dy = e.y - lastY
                        when (mode) {
                            InteractionMode.ROTATE -> handleRotateGesture(node, dx)
                            InteractionMode.SCALE -> handleScaleGesture(node, dy)
                            else -> Unit
                        }
                        lastX = e.x; lastY = e.y
                        true
                    } else false
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = e.x - downX
                    val dy = e.y - downY
                    val dist = sqrt(dx * dx + dy * dy)
                    val dur = System.currentTimeMillis() - downTime
                    if (dist < tapThreshold && dur < 300L) {
                        handleTap(e, hit); true
                    } else false
                }

                else -> false
            }
        }
    }

    private fun handleTap(e: MotionEvent, hit: HitResult?) {
        val hitNode = getModelNodeFromHit(hit)
        if (hitNode != null && placedNodes.contains(hitNode)) {
            if (hitNode == currentSelectedNode) selectNode(null) else selectNode(hitNode)
        } else {
            placeModelAtScreenPosition(e.x, e.y)
        }
    }

    private fun getModelNodeFromHit(hit: HitResult?): ModelNode? {
        var node: Node? = hit?.node
        while (node != null) {
            if (node is ModelNode) return node
            node = node.parent
        }
        return null
    }

    private fun handleRotateGesture(node: ModelNode, dx: Float) {
        if (abs(dx) < moveSlop) return
        val r = node.rotation
        node.rotation = Rotation(r.x, r.y + dx * rotationFactor, r.z)
    }

    private fun handleScaleGesture(node: ModelNode, dy: Float) {
        if (abs(dy) < moveSlop) return
        val factor = 1f + (-dy * scaleFactor)
        val sx = (node.scale.x * factor).coerceIn(minScale, maxScale)
        val sy = (node.scale.y * factor).coerceIn(minScale, maxScale)
        val sz = (node.scale.z * factor).coerceIn(minScale, maxScale)
        node.scale = Scale(sx, sy, sz)
    }

    // ---------------- Place (single tap) ----------------

    private fun placeModelAtScreenPosition(x: Float, y: Float) {
        val frame = arSceneView.frame ?: return
        val arHit: ArHitResult = frame.hitTest(x, y)
            .firstOrNull {
                it.isValid(
                    planeTypes = setOf(Plane.Type.HORIZONTAL_UPWARD_FACING),
                    trackingStates = setOf(TrackingState.TRACKING)
                )
            } ?: return

        val anchor = arHit.createAnchorOrNull() ?: return
        lifecycleScope.launch {
            val node = placeModelOnAnchorAwait(anchor, modelFile, isExternalModel)
            if (node == null) Toast.makeText(this@ArActivity, "Failed to load model", Toast.LENGTH_SHORT).show()
        }
    }

    private fun modelUriString(fileOrPath: String, external: Boolean): String? {
        return if (external) {
            val f = File(fileOrPath)
            if (!f.exists()) {
                Toast.makeText(this, "File not found", Toast.LENGTH_LONG).show()
                null
            } else Uri.fromFile(f).toString()
        } else {
            "file:///android_asset/$fileOrPath"
        }
    }

    private suspend fun placeModelOnAnchorAwait(
        anchor: Anchor,
        fileOrPath: String,
        external: Boolean
    ): ModelNode? {
        val uriString = modelUriString(fileOrPath, external) ?: return null

        val instance = arSceneView.modelLoader.loadModelInstance(uriString) ?: return null

        val node = ModelNode(
            modelInstance = instance,
            scaleToUnits = 1.0f,
            centerOrigin = Position(y = 0f)
        ).apply {
            isTouchable = true
            isHittable = true
            editableScaleRange = minScale..maxScale
        }

        val anchorNode = AnchorNode(arSceneView.engine, anchor).apply { addChildNode(node) }

        withContext(Dispatchers.Main) {
            arSceneView.addChildNode(anchorNode)
            placedNodes += node
            nodeToModelKey[node] = normalizeModelKey(fileOrPath)
            spawnAnim(node)
            selectNode(node)
        }

        return node
    }

    // ---------------- AI: classify + show label ----------------

    private fun classifyRoom(bitmap: Bitmap?): Pair<String, Float> {
        if (bitmap == null) return "unknown" to 0f
        val cls = roomClassifier
        if (cls == null || !cls.isReady) return "unknown" to 0f
        val (label, conf, _) = cls.classifyWithConfidence(bitmap)
        return label to conf
    }

    // ---------------- AI placement: spread models on detected plane ----------------

    /**
     * ✅ This fixes "dumping objects in one point".
     * We hitTest several screen points around the center to get DIFFERENT anchors on the same plane.
     */
    private suspend fun placeMultipleOnPlaneSpread(files: List<String>, flags: BooleanArray): List<ModelNode> {
        val frame = arSceneView.frame ?: return emptyList()
        val width = arSceneView.width.toFloat().coerceAtLeast(1f)
        val height = arSceneView.height.toFloat().coerceAtLeast(1f)

        val n = files.size
        val placed = mutableListOf<ModelNode>()

        // Spread points around center
        val cx = width / 2f
        val cy = height * 0.60f

        // larger spread so big models don't overlap
        val radiusPx = min(width, height) * 0.18f
        val angles = if (n <= 1) listOf(0.0) else (0 until n).map { i ->
            // semicircle in front
            (-60.0 + (120.0 * i / (n - 1).toDouble()))
        }

        for (i in files.indices) {
            val file = files[i]
            val ext = flags.getOrNull(i) ?: false

            val a = Math.toRadians(angles[i])
            val sx = cx + (sin(a) * radiusPx).toFloat()
            val sy = cy + (cos(a) * radiusPx).toFloat()

            val anchor = findPlaneAnchorNear(sx, sy) ?: findPlaneAnchorNear(cx, cy)
            if (anchor == null) continue

            val node = placeModelOnAnchorAwait(anchor, file, ext)
            if (node != null) placed.add(node)
        }

        return placed
    }

    private fun findPlaneAnchorNear(x: Float, y: Float): Anchor? {
        val frame = arSceneView.frame ?: return null

        val hit = frame.hitTest(x, y)
            .firstOrNull {
                it.isValid(
                    planeTypes = setOf(Plane.Type.HORIZONTAL_UPWARD_FACING),
                    trackingStates = setOf(TrackingState.TRACKING)
                )
            } ?: return null

        return hit.createAnchorOrNull()
    }

    // ---------------- SAFE PixelCopy ----------------

    private fun captureSceneBitmapSafe(callback: (Bitmap?) -> Unit) {
        val view = arSceneView

        if (!view.isAttachedToWindow) {
            view.postDelayed({ captureSceneBitmapSafe(callback) }, 100)
            return
        }
        if (view.width == 0 || view.height == 0) {
            view.postDelayed({ captureSceneBitmapSafe(callback) }, 100)
            return
        }

        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val rect = Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)

        try {
            PixelCopy.request(
                window,
                rect,
                bitmap,
                { result -> callback(if (result == PixelCopy.SUCCESS) bitmap else null) },
                Handler(Looper.getMainLooper())
            )
        } catch (_: IllegalArgumentException) {
            callback(null)
        }
    }

    // ---------------- AI Default chooser ----------------

    private fun aiChooseDefaultAssets(roomLabel: String): Pair<List<String>, BooleanArray> {
        val r = roomLabel.lowercase()
        val files = mutableListOf<String>()
        val flags = mutableListOf<Boolean>()

        fun addBuiltIn(name: String) { files.add(name); flags.add(false) }

        when {
            "living" in r || "hall" in r || "lounge" in r -> {
                addBuiltIn("Couch.glb")
                addBuiltIn("Table.glb")
                addBuiltIn("Chair.glb")
            }
            "kitchen" in r || "dining" in r -> {
                addBuiltIn("Table.glb")
                addBuiltIn("Chair.glb")
                addBuiltIn("Chair.glb")
            }
            "office" in r || "study" in r || "bedroom" in r -> {
                addBuiltIn("Table.glb")
                addBuiltIn("Chair.glb")
            }
            else -> {
                addBuiltIn("Table.glb")
                addBuiltIn("Chair.glb")
                addBuiltIn("Chair.glb")
            }
        }

        return files to flags.toBooleanArray()
    }

    // ---------------- Arrange (your existing logic) ----------------

    private data class Target(
        val node: ModelNode,
        val key: String,
        var x: Float,
        var z: Float,
        val radius: Float
    )

    private fun normalizeModelKey(fileOrPath: String): String {
        val lower = fileOrPath.lowercase()
        return when {
            "couch" in lower || "sofa" in lower -> "couch"
            "chair" in lower -> "chair"
            "table" in lower -> "table"
            else -> "other"
        }
    }

    private fun approxFootprintRadius(key: String, node: ModelNode): Float {
        val base = when (key) {
            "chair" -> 0.33f
            "table" -> 0.55f
            "couch" -> 0.85f
            else -> 0.45f
        }
        val s = (node.scale.x + node.scale.z) / 2f
        return (base * s).coerceIn(0.20f, 1.5f)
    }

    private fun arrangePlacedObjectsForRoomSmart(roomLabel: String) {
        if (placedNodes.isEmpty()) {
            Toast.makeText(this, "No objects placed yet", Toast.LENGTH_SHORT).show()
            return
        }

        val frame = arSceneView.frame ?: return
        val session = arSceneView.session ?: return
        val room = roomLabel.lowercase()
        val camPose = frame.camera.pose

        val baseY = placedNodes.map { it.worldPosition.y }.average().toFloat()

        val keepRotations = HashMap<ModelNode, Rotation>()
        placedNodes.forEach { keepRotations[it] = it.rotation }

        val targets = placedNodes.map { node ->
            val key = nodeToModelKey[node] ?: "other"
            Target(node, key, 0f, -1.6f, approxFootprintRadius(key, node))
        }.toMutableList()

        val couches = targets.filter { it.key == "couch" }
        val tables = targets.filter { it.key == "table" }
        val chairs = targets.filter { it.key == "chair" }
        val others = targets.filter { it.key == "other" }

        val tableR = (tables.maxOfOrNull { it.radius } ?: 0.55f)
        val chairR = (chairs.maxOfOrNull { it.radius } ?: 0.33f)
        val couchR = (couches.maxOfOrNull { it.radius } ?: 0.85f)

        when {
            "living" in room || "hall" in room || "lounge" in room -> {
                placeLineCentered(tables, z = -1.7f, spacing = tableR * 2f + gapMeters)
                val couchZ = -1.7f - (tableR + couchR + 0.45f)
                placeLineCentered(couches, z = couchZ, spacing = couchR * 2f + 0.35f)
                placeRingAround(chairs, 0f, -1.7f, tableR + chairR + 0.55f)
                placeArc(others, 1.0f, -1.9f, 1.25f)
            }
            "kitchen" in room || "dining" in room -> {
                placeLineCentered(tables, z = -1.7f, spacing = tableR * 2f + gapMeters)
                placeRingAround(chairs, 0f, -1.7f, tableR + chairR + 0.55f)
                placeArc(others, 1.0f, -2.0f, 1.25f)
            }
            "office" in room || "study" in room || "bedroom" in room -> {
                placeLineCentered(tables, z = -1.6f, spacing = tableR * 2f + gapMeters)
                placeLineCentered(chairs, z = -1.1f, spacing = chairR * 2f + gapMeters)
                placeArc(others, -1.0f, -2.0f, 1.2f)
            }
            else -> {
                placeArc(targets, 0f, -1.7f, 1.6f)
            }
        }

        relaxNoOverlap(targets)

        targets.forEach {
            it.x = it.x.coerceIn(arrangeMinX, arrangeMaxX)
            it.z = it.z.coerceIn(arrangeMinZ, arrangeMaxZ)
        }

        targets.forEach { t ->
            moveNodeToUpright(session, camPose, t.node, t.x, baseY, t.z)
            keepRotations[t.node]?.let { rot -> t.node.rotation = rot }
        }

        Toast.makeText(this, "Arranged for: $roomLabel", Toast.LENGTH_SHORT).show()
    }

    private fun placeLineCentered(items: List<Target>, z: Float, spacing: Float, startX: Float = 0f) {
        if (items.isEmpty()) return
        val n = items.size
        val total = (n - 1) * spacing
        val left = startX - total / 2f
        for (i in items.indices) {
            items[i].x = left + i * spacing
            items[i].z = z
        }
    }

    private fun placeRingAround(items: List<Target>, centerX: Float, centerZ: Float, ringRadius: Float) {
        if (items.isEmpty()) return
        for (i in items.indices) {
            val a = (2.0 * Math.PI * i / items.size).toFloat()
            items[i].x = centerX + (sin(a.toDouble()) * ringRadius).toFloat()
            items[i].z = centerZ + (cos(a.toDouble()) * ringRadius).toFloat()
        }
    }

    private fun placeArc(items: List<Target>, centerX: Float, centerZ: Float, radius: Float) {
        if (items.isEmpty()) return
        val spread = Math.toRadians(80.0).toFloat()
        val start = -spread / 2f
        val step = if (items.size <= 1) 0f else spread / (items.size - 1)
        for (i in items.indices) {
            val a = start + step * i
            items[i].x = centerX + (sin(a.toDouble()) * radius).toFloat()
            items[i].z = centerZ + (-cos(a.toDouble()) * radius).toFloat()
        }
    }

    private fun relaxNoOverlap(items: List<Target>) {
        if (items.size < 2) return
        val iterations = 120
        val damping = 0.65f

        repeat(iterations) {
            var movedAny = false
            for (i in items.indices) {
                for (j in i + 1 until items.size) {
                    val a = items[i]
                    val b = items[j]
                    val dx = b.x - a.x
                    val dz = b.z - a.z
                    val dist = sqrt(dx * dx + dz * dz).coerceAtLeast(0.0001f)
                    val minDist = a.radius + b.radius + gapMeters
                    if (dist < minDist) {
                        val push = (minDist - dist) * 0.5f * damping
                        val nx = dx / dist
                        val nz = dz / dist
                        a.x -= nx * push; a.z -= nz * push
                        b.x += nx * push; b.z += nz * push
                        movedAny = true
                    }
                }
            }
            if (!movedAny) return
        }
    }

    private fun moveNodeToUpright(
        session: com.google.ar.core.Session,
        camPose: Pose,
        node: ModelNode,
        localX: Float,
        y: Float,
        localZ: Float
    ) {
        val world = FloatArray(3)
        camPose.transformPoint(floatArrayOf(localX, 0f, localZ), 0, world, 0)
        val uprightPose = Pose(floatArrayOf(world[0], y, world[2]), floatArrayOf(0f, 0f, 0f, 1f))
        val newAnchor = session.createAnchor(uprightPose)

        val oldAnchorNode = node.parent as? AnchorNode
        val newAnchorNode = AnchorNode(arSceneView.engine, newAnchor).apply { addChildNode(node) }

        arSceneView.addChildNode(newAnchorNode)
        oldAnchorNode?.destroy()
    }

    // ---------------- Selection / Animation / Delete ----------------

    private fun selectNode(node: ModelNode?) {
        currentSelectedNode?.let { prev ->
            prev.scale = scaleMul(prev.scale, 1f / selectBoost)
        }
        currentSelectedNode = node
        if (node != null) {
            node.scale = scaleMul(node.scale, selectBoost)
            btnDelete.visibility = View.VISIBLE
        } else {
            btnDelete.visibility = View.GONE
            mode = InteractionMode.NONE
        }
    }

    private fun scaleMul(scale: Scale, factor: Float): Scale =
        Scale(scale.x * factor, scale.y * factor, scale.z * factor)

    private fun spawnAnim(node: ModelNode) {
        val endScale = node.scale
        val startScale = scaleMul(endScale, 0.6f)
        node.scale = startScale

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                node.scale = Scale(
                    startScale.x + (endScale.x - startScale.x) * t,
                    startScale.y + (endScale.y - startScale.y) * t,
                    startScale.z + (endScale.z - startScale.z) * t
                )
            }
            start()
        }
    }

    private fun deleteSelectedNode() {
        val node = currentSelectedNode ?: return
        placedNodes.remove(node)
        nodeToModelKey.remove(node)

        val parent = node.parent
        if (parent is AnchorNode) parent.destroy() else node.destroy()

        currentSelectedNode = null
        btnDelete.visibility = View.GONE
        mode = InteractionMode.NONE
    }

    private fun deleteAllNodes() {
        placedNodes.forEach { node ->
            val parent = node.parent
            if (parent is AnchorNode) parent.destroy() else node.destroy()
        }
        placedNodes.clear()
        nodeToModelKey.clear()
        currentSelectedNode = null
        btnDelete.visibility = View.GONE
        mode = InteractionMode.NONE
        Toast.makeText(this, "All objects removed", Toast.LENGTH_SHORT).show()
    }

    // ---------------- Logout ----------------

    private fun logout() {
        getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("logged_in", false)
            .apply()
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }
}
