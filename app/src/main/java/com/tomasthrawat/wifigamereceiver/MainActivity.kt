package com.tomasthrawat.wifigamereceiver

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tomasthrawat.wifigamereceiver.databinding.ActivityMainBinding
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity(), Shizuku.OnRequestPermissionResultListener {

    private lateinit var binding: ActivityMainBinding
    private var serviceRunning = false

    companion object {
        private const val SHIZUKU_REQUEST_CODE = 9001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Shizuku.addBinderReceivedListenerSticky { updateStatus() }
        Shizuku.addBinderDeadListener { updateStatus() }

        binding.editPort.setText("5577")
        updateStatus()

        binding.btnGrantShizuku.setOnClickListener { requestShizuku() }
        binding.btnBatteryOpt.setOnClickListener { requestBatteryOptimizationExemption() }
        binding.btnStart.setOnClickListener {
            if (!serviceRunning) startReceiver() else stopReceiver()
        }
    }

    override fun onResume() {
        super.onResume()
        Shizuku.addRequestPermissionResultListener(this)
        updateStatus()
    }

    override fun onPause() {
        Shizuku.removeRequestPermissionResultListener(this)
        super.onPause()
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, getString(R.string.battery_optimization_already_disabled), Toast.LENGTH_SHORT).show()
            updateStatus()
            return
        }
        // No system "battery optimization" screen exists on Android TV
        // (TvSettings only stubs the intent for CTS) — whitelist directly
        // through the Shizuku shell-UID UserService instead, no dialog.
        val svc = GamepadBridge.service
        if (svc == null) {
            Toast.makeText(this, getString(R.string.grant_shizuku_first), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            svc.ignoreBatteryOptimizations(packageName)
        } catch (_: Exception) {
        }
        updateStatus()
    }

    private fun requestShizuku() {
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, getString(R.string.shizuku_not_running), Toast.LENGTH_LONG).show()
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            GamepadBridge.bind()
            updateStatus()
        } else {
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
        if (requestCode == SHIZUKU_REQUEST_CODE) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                GamepadBridge.bind()
            } else {
                Toast.makeText(this, getString(R.string.shizuku_permission_denied), Toast.LENGTH_LONG).show()
            }
            updateStatus()
        }
    }

    private fun startReceiver() {
        val port = binding.editPort.text.toString().toIntOrNull()
        if (port == null || port !in 1024..65535) {
            Toast.makeText(this, getString(R.string.invalid_port), Toast.LENGTH_SHORT).show()
            return
        }
        if (!GamepadBridge.isBound) {
            Toast.makeText(this, getString(R.string.grant_shizuku_first), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, UdpReceiverService::class.java).putExtra(UdpReceiverService.EXTRA_PORT, port)
        ContextCompat.startForegroundService(this, intent)
        serviceRunning = true
        binding.btnStart.setText(R.string.stop)
        updateStatus()
    }

    private fun stopReceiver() {
        stopService(Intent(this, UdpReceiverService::class.java))
        serviceRunning = false
        binding.btnStart.setText(R.string.start)
        updateStatus()
    }

    private fun updateStatus() {
        val shizukuReady = Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        if (shizukuReady && !GamepadBridge.isBound) {
            GamepadBridge.bind()
        }
        binding.textStatus.text = if (shizukuReady) getString(R.string.status_ready) else getString(R.string.status_not_ready)
        binding.btnGrantShizuku.visibility = if (shizukuReady) View.GONE else View.VISIBLE

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val batteryExempt = pm.isIgnoringBatteryOptimizations(packageName)
        binding.btnBatteryOpt.visibility = if (batteryExempt) View.GONE else View.VISIBLE
    }
}
