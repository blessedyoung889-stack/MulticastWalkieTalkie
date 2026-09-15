package com.multicast.walkietalkie

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.multicast.walkietalkie.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), AudioMulticastService.Listener {

    private lateinit var binding: ActivityMainBinding
    private var service: AudioMulticastService? = null
    private var bound = false
    private val adapter = DeviceAdapter()

    private val prefs by lazy { getSharedPreferences("walkietalkie", Context.MODE_PRIVATE) }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as AudioMulticastService.LocalBinder
            service = localBinder.getService()
            service?.listener = this@MainActivity
            service?.deviceName = prefs.getString(PREF_NAME, defaultDeviceName()) ?: defaultDeviceName()
            service?.setPlaybackVolume(binding.seekVolume.progress / 100f)
            bound = true
            onDevicesChanged(service?.currentDevices() ?: emptyList())
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val micGranted = results[Manifest.permission.RECORD_AUDIO] == true
        if (!micGranted) {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recyclerDevices.layoutManager = LinearLayoutManager(this)
        binding.recyclerDevices.adapter = adapter

        binding.editName.setText(prefs.getString(PREF_NAME, defaultDeviceName()))
        binding.editName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveName()
                true
            } else {
                false
            }
        }
        binding.editName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                saveName()
            }
        })

        binding.seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                service?.setPlaybackVolume(progress / 100f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.buttonPtt.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (hasMicPermission()) {
                        beginTalking()
                    } else {
                        requestPermissions()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    endTalking()
                    true
                }
                else -> false
            }
        }

        requestPermissions()
        startAndBindService()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bound) {
            service?.listener = null
            unbindService(connection)
            bound = false
        }
    }

    private fun defaultDeviceName(): String = Build.MODEL ?: "Device"

    private fun saveName() {
        val name = binding.editName.text?.toString()?.trim().orEmpty().ifEmpty { defaultDeviceName() }
        prefs.edit().putString(PREF_NAME, name).apply()
        service?.deviceName = name
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun startAndBindService() {
        val intent = Intent(this, AudioMulticastService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun beginTalking() {
        service?.startTalking()
        binding.buttonPtt.setBackgroundResource(R.drawable.bg_ptt_active)
        binding.buttonPtt.setText(R.string.ptt_active)
    }

    private fun endTalking() {
        service?.stopTalking()
        binding.buttonPtt.setBackgroundResource(R.drawable.bg_ptt_idle)
        binding.buttonPtt.setText(R.string.ptt_idle)
    }

    // AudioMulticastService.Listener
    override fun onDevicesChanged(devices: List<DeviceInfo>) {
        runOnUiThread {
            adapter.submitList(devices)
            binding.textStatus.text = if (devices.isEmpty()) {
                getString(R.string.status_starting)
            } else if (devices.size == 1) {
                "1 device online"
            } else {
                "${devices.size} devices online"
            }
        }
    }

    companion object {
        private const val PREF_NAME = "device_name"
    }
}
