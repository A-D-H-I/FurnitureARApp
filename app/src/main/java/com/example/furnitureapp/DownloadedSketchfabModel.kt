package com.example.furnitureapp

data class DownloadedSketchfabModel(
    val uid: String,
    val name: String,
    val localPath: String,
    val thumbnailUrl: String?
)
