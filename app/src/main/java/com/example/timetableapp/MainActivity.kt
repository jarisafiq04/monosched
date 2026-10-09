package com.example.timetableapp

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.example.timetableapp.ui.screen.TimetableScreen
import com.example.timetableapp.ui.theme.TimetableTheme

class MainActivity : ComponentActivity() {
    // ponytail: notification permission for class reminders (Android 13+); alarms work regardless
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        // ponytail: drop the splash theme once the app draws
        setTheme(R.style.Theme_Monosched)
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val sharedUri = extractIncomingUri(intent)
        setContent {
            TimetableTheme {
                // ponytail: single tab — shared/opened files auto-import straight into the timetable
                TimetableScreen(initialUri = sharedUri)
            }
        }
    }

    private fun extractIncomingUri(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            }
            else -> null
        }
    }
}
