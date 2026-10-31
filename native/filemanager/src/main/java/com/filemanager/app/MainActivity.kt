package com.filemanager.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val vm: FileViewModel by viewModels()
    private var granted by mutableStateOf(false)
    private var leftAt = 0L
    private var skipHome = false

    private val legacyPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            granted = hasAccess()
            if (granted) vm.reload()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        granted = hasAccess()
        addOnNewIntentListener { i ->
            setIntent(i)
            handleIntent(i)
        }
        setContent {
            FMTheme {
                if (granted) FileManagerApp(vm) else PermissionScreen(onGrant = { requestAccess() })
            }
        }
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // Reopening the app after a while always starts at the home screen.
        if (skipHome) {
            skipHome = false
        } else if (leftAt != 0L && SystemClock.elapsedRealtime() - leftAt > 30_000L && hasAccess()) {
            vm.goHome()
        }
    }

    override fun onResume() {
        super.onResume()
        granted = hasAccess()
        if (granted) {
            vm.enforceLock()
            vm.reload()
        }
    }

    override fun onStop() {
        super.onStop()
        // Locked folders re-lock whenever the app leaves the foreground.
        if (!isChangingConfigurations) {
            vm.relock()
            leftAt = SystemClock.elapsedRealtime()
        }
    }

    private fun handleIntent(i: Intent?) {
        val p = i?.getStringExtra("open_path") ?: return
        i.removeExtra("open_path")
        skipHome = true
        if (hasAccess()) vm.openPath(p)
    }

    private fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            legacyPermission.launch(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            )
        }
    }
}
