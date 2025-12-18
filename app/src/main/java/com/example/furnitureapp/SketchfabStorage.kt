package com.example.furnitureapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object SketchfabStorage {
    private const val PREFS_NAME = "sketchfab_prefs"
    private const val KEY_DOWNLOADED = "downloaded_models"

    fun getAll(context: Context): List<DownloadedSketchfabModel> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_DOWNLOADED, "[]") ?: "[]"
        val arr = JSONArray(raw)

        val out = ArrayList<DownloadedSketchfabModel>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val uid = obj.optString("uid", "")
            val name = obj.optString("name", "")
            val localPath = obj.optString("localPath", "")
            val thumb = obj.optString("thumbnailUrl", "").takeIf { it.isNotBlank() }

            if (uid.isBlank() || localPath.isBlank()) continue
            out.add(DownloadedSketchfabModel(uid, name, localPath, thumb))
        }
        return out
    }

    fun upsert(context: Context, model: DownloadedSketchfabModel) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = getAll(context).toMutableList()

        val idx = current.indexOfFirst { it.uid == model.uid }
        if (idx >= 0) current[idx] = model else current.add(0, model)

        val arr = JSONArray()
        current.forEach {
            val o = JSONObject()
            o.put("uid", it.uid)
            o.put("name", it.name)
            o.put("localPath", it.localPath)
            o.put("thumbnailUrl", it.thumbnailUrl ?: "")
            arr.put(o)
        }

        prefs.edit().putString(KEY_DOWNLOADED, arr.toString()).apply()
    }
}
