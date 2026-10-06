package com.abhynex.jarvis

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.fragment.app.FragmentActivity

class MainActivity : FragmentActivity() {
    private val vm: JarvisViewModel by viewModels()
    private var lastStop = 0L

    private val micPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.onMicPermission(it) }
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.upd { it.copy(notifications = true) } else vm.note("Notification permission denied.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.requestMic = { micPerm.launch(Manifest.permission.RECORD_AUDIO) }
        vm.requestNotif = {
            if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
            else vm.upd { it.copy(notifications = true) }
        }
        vm.authRunner = { title, ok, fail ->
            if (!BiometricGate.available(this)) fail(false, "Set up a screen lock or biometric on this phone first.")
            else BiometricGate.prompt(this, title, ok, fail)
        }
        if (savedInstanceState == null && vm.cfg.ownerProtection && BiometricGate.available(this)) vm.locked = true
        setContent {
            val keepOn = vm.micOn || vm.wake
            DisposableEffect(keepOn) {
                if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            JarvisApp(vm)
        }
    }

    override fun onStart() {
        super.onStart()
        vm.refreshOnline()
        if (lastStop > 0 && vm.cfg.ownerProtection && SystemClock.elapsedRealtime() - lastStop > 30_000 &&
            BiometricGate.available(this)
        ) vm.locked = true
    }

    override fun onStop() {
        super.onStop()
        lastStop = SystemClock.elapsedRealtime()
        vm.stopMic() // never listen in the background
    }

    override fun onDestroy() {
        super.onDestroy()
        vm.requestMic = null; vm.requestNotif = null; vm.authRunner = null
    }
}
