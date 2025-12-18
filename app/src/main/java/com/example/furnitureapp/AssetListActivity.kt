package com.example.furnitureapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import java.io.File

data class AssetRow(
    val displayName: String,
    val fileOrPath: String,       // assets filename OR local absolute path
    val isExternal: Boolean,      // false = assets/, true = local file
    val thumbnailUrl: String?,    // for external
    val iconResId: Int? = null    // ✅ for built-in PNG icons
)

class AssetListActivity : AppCompatActivity() {

    // ✅ built-in models + their PNG icons in res/drawable/
    private val builtIn = listOf(
        AssetRow("Couch", "Couch.glb", false, null, R.drawable.ic_couch),
        AssetRow("Chair", "Chair.glb", false, null, R.drawable.ic_chair),
        AssetRow("Table", "Table.glb", false, null, R.drawable.ic_table)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_asset_list)

        val pickMode = intent.getBooleanExtra("PICK_MODE", false)

        val listView: ListView = findViewById(R.id.listAssets)
        val btnLogout: Button = findViewById(R.id.btnLogoutAssets)
        val btnBrowse: Button = findViewById(R.id.btnBrowseOnline)

        // Build list = built-in + downloaded
        val downloaded = SketchfabStorage.getAll(this)
            .filter { File(it.localPath).exists() }
            .map {
                AssetRow(
                    displayName = "🌐 ${it.name}",
                    fileOrPath = it.localPath,
                    isExternal = true,
                    thumbnailUrl = it.thumbnailUrl,
                    iconResId = null
                )
            }

        val items = ArrayList<AssetRow>().apply {
            addAll(builtIn)
            addAll(downloaded)
        }

        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = items.size
            override fun getItem(position: Int): Any = items[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val view = convertView ?: LayoutInflater.from(this@AssetListActivity)
                    .inflate(R.layout.item_asset, parent, false)

                val iv = view.findViewById<ImageView>(R.id.ivIcon)
                val tv = view.findViewById<TextView>(R.id.tvName)

                val item = items[position]
                tv.text = item.displayName

                if (item.isExternal) {
                    // external model => try thumbnail; if missing use fallback icon
                    if (!item.thumbnailUrl.isNullOrBlank()) {
                        Glide.with(this@AssetListActivity)
                            .load(item.thumbnailUrl)
                            .placeholder(android.R.drawable.ic_menu_gallery)
                            .error(android.R.drawable.ic_menu_gallery)
                            .into(iv)
                    } else {
                        iv.setImageResource(android.R.drawable.ic_menu_gallery)
                    }
                } else {
                    // ✅ built-in model => use your PNG icon
                    val resId = item.iconResId ?: android.R.drawable.ic_menu_gallery
                    iv.setImageResource(resId)
                }

                return view
            }
        }

        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            val asset = items[position]

            if (pickMode) {
                val data = Intent().apply {
                    putExtra("MODEL_FILE", asset.fileOrPath)
                    putExtra("IS_EXTERNAL_MODEL", asset.isExternal)
                }
                setResult(RESULT_OK, data)
                finish()
            } else {
                val intent = Intent(this, ArActivity::class.java)
                intent.putExtra("MODEL_FILE", asset.fileOrPath)
                intent.putExtra("IS_EXTERNAL_MODEL", asset.isExternal)
                startActivity(intent)
            }
        }

        btnBrowse.setOnClickListener {
            startActivity(Intent(this, SketchfabBrowserActivity::class.java))
        }

        btnLogout.setOnClickListener { logout() }
    }

    private fun logout() {
        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("logged_in", false).apply()
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }
}
