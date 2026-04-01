package com.example.bluetoothproj

data class ScannedDevice(
    val name: String,
    val address: String,
    val rssi: Int
)

data class RssiRecord(
    val rssi: Int,
    val timestamp: Long
)