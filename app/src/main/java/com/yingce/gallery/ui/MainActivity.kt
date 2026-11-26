package com.yingce.gallery.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.yingce.gallery.data.GalleryAccess

class MainActivity : ComponentActivity() {

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝也能用，只是没有 GPS */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 进入应用时主动申请"读取原始媒体"权限：
        // 没有它时 MediaStore 会抹掉 GPS，且 setRequireOriginal 会直接抛异常。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !GalleryAccess.hasLocationAccess(this)) {
            runCatching { locationPermissionLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION) }
        }
        setContent {
            YingceTheme {
                YingceRoot()
            }
        }
    }
}
