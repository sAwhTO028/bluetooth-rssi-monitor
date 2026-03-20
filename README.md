# Bluetooth RSSI Monitor

A simple Android prototype app for scanning nearby Bluetooth devices and monitoring RSSI (signal strength) in real time.

## Features

- Scan nearby Bluetooth devices
- Display device name and MAC address
- Show RSSI value in real time
- Visual signal bars UI
- Select a device to monitor
- Show recent RSSI history
- Filter strong devices only
- Approximate distance estimation
- Signal quality display (Strong / Good / Medium / Weak / Very Weak)

## Tech Stack

- Kotlin
- Android Studio
- Jetpack Compose
- Bluetooth LE Scanner

## Prototype Scope

This project was built as a quick prototype to test Bluetooth signal monitoring and UI visualization.

Main goals:
- Bluetooth device detection
- Real-time RSSI monitoring
- Simple signal visualization
- Basic signal history log
- Approximate distance estimation

## Screenshots

<img width="486" height="1020" alt="image" src="https://github.com/user-attachments/assets/11761ba4-e34c-40b8-90eb-4cff3340838e" />


## How It Works

1. Start scanning for nearby Bluetooth devices
2. View the detected devices in a list
3. Select one device
4. Check:
   - RSSI value
   - Signal strength bars
   - Signal quality
   - Approximate distance
   - Recent RSSI history

## Notes

- Distance estimation is approximate and not accurate
- RSSI values may change frequently depending on environment
- Some devices may appear as "Unknown Device"
- Bluetooth behavior may vary depending on the device

## Future Improvements

- Better UI polish
- Export RSSI log as CSV
- Graph view for RSSI history
- Improved distance calibration
- Device type filtering

## Author

Saw Htoo Thit Hlaing
