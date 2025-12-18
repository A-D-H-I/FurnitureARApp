package com.example.furnitureapp

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val cardArCam = findViewById<MaterialCardView>(R.id.cardArCam)
        val cardAssets = findViewById<MaterialCardView>(R.id.cardAssets)
        val cardSketchfab = findViewById<MaterialCardView>(R.id.cardSketchfab)
        val cardMeasure = findViewById<MaterialCardView>(R.id.cardMeasure)

        cardArCam.setOnClickListener {
            startActivity(Intent(this, ArActivity::class.java))
        }

        cardAssets.setOnClickListener {
            startActivity(Intent(this, AssetListActivity::class.java))
        }

        cardSketchfab.setOnClickListener {
            startActivity(Intent(this, SketchfabBrowserActivity::class.java))
        }

        cardMeasure.setOnClickListener {
            startActivity(Intent(this, MeasureActivity::class.java))
        }
    }
}
