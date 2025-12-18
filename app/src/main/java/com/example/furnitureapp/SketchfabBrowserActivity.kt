package com.example.furnitureapp

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class SketchfabModel(
    val uid: String,
    val name: String,
    val thumbnailUrl: String
)

class SketchfabBrowserActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SketchfabBrowser"
    }

    private val TOKEN: String by lazy { BuildConfig.SKETCHFAB_TOKEN.trim() }

    private lateinit var searchInput: EditText
    private lateinit var btnSearch: Button
    private lateinit var gridView: GridView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnBack: Button

    private val models = mutableListOf<SketchfabModel>()
    private lateinit var adapter: SketchfabAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sketchfab_browser)

        searchInput = findViewById(R.id.etSearchSketchfab)
        btnSearch = findViewById(R.id.btnSearchSketchfab)
        gridView = findViewById(R.id.gridViewSketchfab)
        progressBar = findViewById(R.id.progressBarSketchfab)
        btnBack = findViewById(R.id.btnBackSketchfab)

        adapter = SketchfabAdapter()
        gridView.adapter = adapter

        btnBack.setOnClickListener { finish() }

        btnSearch.setOnClickListener {
            val q = searchInput.text.toString().trim()
            if (q.isBlank()) {
                Toast.makeText(this, "Enter a search term", Toast.LENGTH_SHORT).show()
            } else {
                searchModels(makeFurnitureQuery(q))
            }
        }

        if (TOKEN.isBlank()) {
            Toast.makeText(this, "Sketchfab token missing", Toast.LENGTH_LONG).show()
            return
        }

        // Auto-load furniture
        searchModels("furniture")
    }

    private fun makeFurnitureQuery(q: String): String {
        val t = q.lowercase()
        return if (t.contains("chair") || t.contains("sofa") || t.contains("table") || t.contains("furniture")) {
            q
        } else {
            "furniture $q"
        }
    }

    private fun searchModels(query: String) {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { searchSketchfab(query) }
                models.clear()
                models.addAll(result)
                adapter.notifyDataSetChanged()

                if (result.isEmpty()) {
                    Toast.makeText(this@SketchfabBrowserActivity, "No results found", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@SketchfabBrowserActivity, e.message ?: "Search failed", Toast.LENGTH_LONG).show()
            } finally {
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun searchSketchfab(query: String): List<SketchfabModel> {
        val q = URLEncoder.encode(query, "UTF-8")
        val urlString =
            "https://api.sketchfab.com/v3/search?type=models&q=$q&downloadable=true&count=24"

        val (code, body) = httpGet(urlString)
        if (code != 200) throw Exception("Sketchfab API error $code")

        val json = JSONObject(body)
        val arr = json.getJSONArray("results")

        val list = mutableListOf<SketchfabModel>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                SketchfabModel(
                    uid = o.getString("uid"),
                    name = o.getString("name"),
                    thumbnailUrl = o.optJSONObject("thumbnails")
                        ?.optJSONArray("images")
                        ?.optJSONObject(0)
                        ?.optString("url", "") ?: ""
                )
            )
        }
        return list
    }

    // ================= DOWNLOAD =================

    private fun startDownload(model: SketchfabModel) {
        progressBar.visibility = View.VISIBLE
        Toast.makeText(this, "Downloading ${model.name}", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { downloadModelAsGlb(model.uid) }

                if (file != null && file.exists()) {
                    SketchfabStorage.upsert(
                        this@SketchfabBrowserActivity,
                        DownloadedSketchfabModel(
                            uid = model.uid,
                            name = model.name,
                            localPath = file.absolutePath,
                            thumbnailUrl = model.thumbnailUrl
                        )
                    )
                    Toast.makeText(this@SketchfabBrowserActivity, "Saved to Assets ✅", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@SketchfabBrowserActivity, "Download failed (no GLB)", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@SketchfabBrowserActivity, e.message ?: "Download error", Toast.LENGTH_LONG).show()
            } finally {
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun downloadModelAsGlb(uid: String): File? {
        val infoUrl = "https://api.sketchfab.com/v3/models/$uid/download"
        val (code, body) = httpGet(infoUrl)
        if (code != 200) return null

        val json = JSONObject(body)
        val modelsDir = File(filesDir, "sketchfab_models").apply { mkdirs() }
        val outGlb = File(modelsDir, "$uid.glb")

        // 1️⃣ direct GLB
        json.optJSONObject("glb")?.optString("url", "")?.takeIf { it.isNotBlank() }?.let {
            return if (downloadToFile(it, outGlb)) outGlb else null
        }

        // 2️⃣ glTF ZIP
        val gltfUrl = json.optJSONObject("gltf")?.optString("url", "") ?: return null
        val zipFile = File(modelsDir, "$uid.zip")

        if (!downloadToFile(gltfUrl, zipFile)) return null

        val tmp = File(modelsDir, "tmp_$uid").apply { mkdirs() }
        unzip(zipFile, tmp)

        val glbInside = tmp.walkTopDown().firstOrNull { it.name.endsWith(".glb", true) }
        glbInside?.copyTo(outGlb, overwrite = true)

        zipFile.delete()
        tmp.deleteRecursively()

        return if (outGlb.exists()) outGlb else null
    }

    // ================= UTILS =================

    private fun downloadToFile(url: String, out: File): Boolean =
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.inputStream.use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            false
        }

    private fun unzip(zip: File, outDir: File) {
        ZipInputStream(FileInputStream(zip)).use { zis ->
            var entry: ZipEntry?
            val buffer = ByteArray(8 * 1024)
            while (zis.nextEntry.also { entry = it } != null) {
                val f = File(outDir, entry!!.name)
                if (entry!!.isDirectory) f.mkdirs()
                else {
                    f.parentFile?.mkdirs()
                    FileOutputStream(f).use {
                        var count: Int
                        while (zis.read(buffer).also { count = it } != -1) {
                            it.write(buffer, 0, count)
                        }
                    }
                }
            }
        }
    }

    private fun httpGet(url: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Token $TOKEN")
        conn.setRequestProperty("Accept", "application/json")
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        return code to body
    }

    // ================= ADAPTER =================

    inner class SketchfabAdapter : BaseAdapter() {
        override fun getCount() = models.size
        override fun getItem(p: Int) = models[p]
        override fun getItemId(p: Int) = p.toLong()

        override fun getView(p: Int, v: View?, parent: ViewGroup): View {
            val view = v ?: LayoutInflater.from(this@SketchfabBrowserActivity)
                .inflate(R.layout.xml_sketchfab_item, parent, false)

            val img = view.findViewById<ImageView>(R.id.ivModelThumbnail)
            val tv = view.findViewById<TextView>(R.id.tvModelName)
            val btn = view.findViewById<Button>(R.id.btnDownloadModel)

            val model = models[p]
            tv.text = model.name

            Glide.with(this@SketchfabBrowserActivity)
                .load(model.thumbnailUrl)
                .placeholder(android.R.drawable.ic_menu_gallery)
                .into(img)

            // 🔥 THIS WAS MISSING BEFORE
            btn.setOnClickListener {
                startDownload(model)
            }

            return view
        }
    }
}
