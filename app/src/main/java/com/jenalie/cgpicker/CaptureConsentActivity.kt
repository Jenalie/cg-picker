package com.jenalie.cgpicker

import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** Invisible screen that shows Android's "start recording or casting?" prompt, then hands the answer to the overlay. */
class CaptureConsentActivity : ComponentActivity() {

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            OverlayService.startReading(this, result.resultCode, data)
        } else {
            Toast.makeText(this, "Auto-read needs screen capture permission", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            consent.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
        }
    }
}
