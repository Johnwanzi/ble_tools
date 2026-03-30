# BLE Tool

A desktop BLE (Bluetooth Low Energy) scanner and debugger inspired by nRF Connect, built with Python, PyQt5 and bleak.

## Features

- **Scan & filter** — real-time BLE device scanning, filter by device name / MAC address, and filter by RSSI threshold
- **RSSI indicator** — signal strength color-coded (green / orange / red), sorted strongest-first
- **GATT browser** — connect to a device and browse all Services, Characteristics and Descriptors
- **Read / Write / Notify / Indicate** — full characteristic operations with hex input and decoded text output
- **Write modes** — supports both Write Request and Write Command
- **Pairing** — numerical comparison pairing via BlueZ D-Bus agent (Linux); Windows users are guided to system Bluetooth settings
- **Log panel** — timestamped event log for all operations

## Requirements

- Python 3.8+
- Windows 10 / Linux (macOS untested)
- A Bluetooth adapter that supports BLE

## Quick Start

### First time (install dependencies + run)

Double-click `install_and_run.bat` — it will install all dependencies and launch the tool.

### Daily use

Double-click `start.bat` — detects an existing virtual environment, installs missing packages if needed, then starts the tool.

### Manual

```bash
pip install -r requirements.txt
python ble_tool.py
```

## Usage

1. Click **Start Scan** to discover nearby BLE devices.
2. Use the **Filter** box to search by name or MAC address.
3. Adjust **RSSI ≥** to hide weak/distant devices (e.g. set `-70 dBm` to show only nearby devices).
4. Select a device and click **Connect** (or double-click the row).
5. Browse the GATT tree — select a characteristic to enable Read / Write / Notify / Indicate buttons.
6. Enter hex bytes in the **Data** field (e.g. `01 02 FF`) and click **Write**.
7. Click **Disconnect** when done.

## Dependencies

| Package | Purpose |
|---------|---------|
| [PyQt5](https://pypi.org/project/PyQt5/) | GUI framework |
| [bleak](https://github.com/hbldh/bleak) | Cross-platform BLE library |

## Notes

- On **Linux**, run with a user in the `bluetooth` group (or `sudo`) for pairing support.
- On **Windows**, the Pair button will open instructions to use Windows Bluetooth settings.
- RSSI values are approximate and vary by environment.
