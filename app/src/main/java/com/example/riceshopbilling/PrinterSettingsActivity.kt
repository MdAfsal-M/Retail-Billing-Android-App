package com.example.riceshopbilling

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class PrinterSettingsActivity : AppCompatActivity() {

    private lateinit var etWifiIp: EditText
    private lateinit var etWifiPort: EditText
    private lateinit var btnSaveWifi: Button
    private lateinit var etCopies: EditText
    private lateinit var btnSaveCopies: Button
    private lateinit var btnScanBluetooth: Button
    private lateinit var listBluetoothDevices: ListView
    private lateinit var tvSelectedBluetooth: TextView

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private val foundDevices = mutableListOf<BluetoothDevice>()
    private lateinit var deviceArrayAdapter: ArrayAdapter<String>

    private lateinit var sharedPref: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_printer_settings)

        etWifiIp = findViewById(R.id.etWifiIp)
        etWifiPort = findViewById(R.id.etWifiPort)
        btnSaveWifi = findViewById(R.id.btnSaveWifi)
        etCopies = findViewById(R.id.etCopies)
        btnSaveCopies = findViewById(R.id.btnSaveCopies)
        btnScanBluetooth = findViewById(R.id.btnScanBluetooth)
        listBluetoothDevices = findViewById(R.id.listBluetoothDevices)
        tvSelectedBluetooth = findViewById(R.id.tvSelectedBluetooth)

        sharedPref = getSharedPreferences("printer_settings", Context.MODE_PRIVATE)

        // Load saved WiFi settings
        etWifiIp.setText(sharedPref.getString("wifi_ip", ""))
        etWifiPort.setText(sharedPref.getString("wifi_port", "9100"))

        // Load saved copies setting (default 1)
        etCopies.setText(sharedPref.getString("copies", "1"))

        // Load saved Bluetooth MAC
        val savedMac = sharedPref.getString("bluetooth_mac", "")
        if (!savedMac.isNullOrEmpty()) {
            tvSelectedBluetooth.text = "Selected: $savedMac"
        }

        btnSaveWifi.setOnClickListener {
            saveWifiSettings()
        }

        btnSaveCopies.setOnClickListener {
            saveCopiesSetting()
        }

        // Bluetooth setup
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            btnScanBluetooth.isEnabled = false
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_SHORT).show()
        }

        deviceArrayAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        listBluetoothDevices.adapter = deviceArrayAdapter

        btnScanBluetooth.setOnClickListener {
            scanBluetoothDevices()
        }

        listBluetoothDevices.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val device = foundDevices[position]
            val mac = device.address
            sharedPref.edit().putString("bluetooth_mac", mac).apply()
            tvSelectedBluetooth.text = "Selected: ${device.name} ($mac)"
            listBluetoothDevices.visibility = View.GONE
        }

        // Register receiver for found devices
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        registerReceiver(receiver, filter)
    }

    private fun saveWifiSettings() {
        val ip = etWifiIp.text.toString().trim()
        val port = etWifiPort.text.toString().trim()
        if (ip.isEmpty() || port.isEmpty()) {
            Toast.makeText(this, "Enter IP and port", Toast.LENGTH_SHORT).show()
            return
        }
        sharedPref.edit()
            .putString("wifi_ip", ip)
            .putString("wifi_port", port)
            .apply()
        Toast.makeText(this, "WiFi settings saved", Toast.LENGTH_SHORT).show()
    }

    private fun saveCopiesSetting() {
        val copiesStr = etCopies.text.toString().trim()
        if (copiesStr.isEmpty()) {
            Toast.makeText(this, "Enter number of copies", Toast.LENGTH_SHORT).show()
            return
        }
        val copies = copiesStr.toIntOrNull()
        if (copies == null || copies < 1 || copies > 5) {
            Toast.makeText(this, "Please enter a number between 1 and 5", Toast.LENGTH_SHORT).show()
            return
        }
        sharedPref.edit()
            .putString("copies", copiesStr)
            .apply()
        Toast.makeText(this, "Copies setting saved", Toast.LENGTH_SHORT).show()
    }

    private fun scanBluetoothDevices() {
        if (bluetoothAdapter.isDiscovering) {
            bluetoothAdapter.cancelDiscovery()
        }
        foundDevices.clear()
        deviceArrayAdapter.clear()
        listBluetoothDevices.visibility = View.VISIBLE
        bluetoothAdapter.startDiscovery()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)!!
                    if (!foundDevices.contains(device)) {
                        foundDevices.add(device)
                        deviceArrayAdapter.add("${device.name}\n${device.address}")
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
        if (bluetoothAdapter.isDiscovering) {
            bluetoothAdapter.cancelDiscovery()
        }
    }
}