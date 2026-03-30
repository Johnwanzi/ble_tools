#!/usr/bin/env python3
"""BLE Tool - An nRF Connect-like BLE utility built with PyQt5 and bleak."""

import sys
import asyncio
import signal
import platform
from datetime import datetime

from PyQt5.QtWidgets import (
    QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout,
    QPushButton, QTreeWidget, QTreeWidgetItem, QSplitter, QLabel,
    QHeaderView, QTextEdit, QLineEdit, QComboBox, QGroupBox,
    QMessageBox, QDialog, QDialogButtonBox, QMenu, QAction,
    QInputDialog, QSpinBox,
)
from PyQt5.QtCore import Qt, QTimer, pyqtSignal, QObject
from PyQt5.QtGui import QColor, QFont

from bleak import BleakScanner, BleakClient
from bleak.backends.device import BLEDevice
from bleak.backends.scanner import AdvertisementData

# Check if we're on Linux for dbus support
IS_LINUX = platform.system() == 'Linux'

if IS_LINUX:
    try:
        import dbus
        import dbus.service
        import dbus.mainloop.glib
        HAS_DBUS = True
    except ImportError:
        HAS_DBUS = False
        print("Warning: dbus not available. Pairing features disabled.")
else:
    HAS_DBUS = False


# ---------------------------------------------------------------------------
# Async helper – run coroutines from Qt without qasync
# ---------------------------------------------------------------------------
class AsyncBridge(QObject):
    """Runs an asyncio event loop in the background, driven by a QTimer."""

    def __init__(self):
        super().__init__()
        self._loop = asyncio.new_event_loop()
        self._timer = QTimer()
        self._timer.setInterval(10)
        self._timer.timeout.connect(self._step)
        self._timer.start()

    def _step(self):
        self._loop.stop()
        self._loop.run_forever()

    def run(self, coro):
        return asyncio.run_coroutine_threadsafe(coro, self._loop)

    def stop(self):
        self._timer.stop()
        self._loop.call_soon_threadsafe(self._loop.stop)


# ---------------------------------------------------------------------------
# Scan result item
# ---------------------------------------------------------------------------
class DeviceItem:
    def __init__(self, device: BLEDevice, adv: AdvertisementData):
        self.device = device
        self.adv = adv
        self.rssi = adv.rssi
        self.name = device.name or "N/A"
        self.address = device.address


# ---------------------------------------------------------------------------
# BlueZ Pairing Agent (D-Bus) - Linux only
# ---------------------------------------------------------------------------
if HAS_DBUS:
    AGENT_PATH = "/com/ble_tool/agent"
    AGENT_INTERFACE = "org.bluez.Agent1"
    AGENT_MANAGER_INTERFACE = "org.bluez.AgentManager1"

    class PairingAgent(dbus.service.Object):
        """BlueZ Agent that handles pairing, especially numerical comparison."""

        # Signal to request UI confirmation (passkey, device_path)
        confirm_request_callback = None  # set by BLEToolWindow

        def __init__(self, bus, path):
            super().__init__(bus, path)

        @dbus.service.method(AGENT_INTERFACE, in_signature="", out_signature="")
        def Release(self):
            pass

        @dbus.service.method(AGENT_INTERFACE, in_signature="os", out_signature="")
        def AuthorizeService(self, device, uuid):
            pass

        @dbus.service.method(AGENT_INTERFACE, in_signature="o", out_signature="s")
        def RequestPinCode(self, device):
            return ""

        @dbus.service.method(AGENT_INTERFACE, in_signature="o", out_signature="u")
        def RequestPasskey(self, device):
            return dbus.UInt32(0)

        @dbus.service.method(AGENT_INTERFACE, in_signature="ouq", out_signature="")
        def DisplayPasskey(self, device, passkey, entered):
            if self.confirm_request_callback:
                self.confirm_request_callback(device, passkey, "display")

        @dbus.service.method(AGENT_INTERFACE, in_signature="ou", out_signature="")
        def RequestConfirmation(self, device, passkey):
            """Numerical Comparison: user must confirm the passkey matches."""
            if self.confirm_request_callback:
                accepted = self.confirm_request_callback(device, passkey, "confirm")
                if not accepted:
                    raise dbus.exceptions.DBusException(
                        "org.bluez.Error.Rejected", "Pairing rejected by user"
                    )
            # If no callback or accepted, return normally (accept)

        @dbus.service.method(AGENT_INTERFACE, in_signature="o", out_signature="")
        def RequestAuthorization(self, device):
            pass

        @dbus.service.method(AGENT_INTERFACE, in_signature="", out_signature="")
        def Cancel(self):
            pass


def register_pairing_agent():
    """Register our pairing agent with BlueZ (Linux only)."""
    if not HAS_DBUS:
        return None

    try:
        dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
        bus = dbus.SystemBus()
        agent = PairingAgent(bus, AGENT_PATH)

        manager = dbus.Interface(
            bus.get_object("org.bluez", "/org/bluez"),
            AGENT_MANAGER_INTERFACE
        )
        manager.RegisterAgent(AGENT_PATH, "KeyboardDisplay")
        manager.RequestDefaultAgent(AGENT_PATH)
        return agent
    except Exception as e:
        print(f"Warning: Could not register pairing agent: {e}")
        print("Pairing features may not work. Try running with sudo or adding user to 'bluetooth' group.")
        return None


# ---------------------------------------------------------------------------
# Main Window
# ---------------------------------------------------------------------------
class BLEToolWindow(QMainWindow):
    # Signals for thread-safe UI updates
    device_found = pyqtSignal(object, object)  # BLEDevice, AdvertisementData
    log_signal = pyqtSignal(str)
    connection_done = pyqtSignal(bool, str)  # success, message
    disconnected_signal = pyqtSignal(str)   # reason string (unexpected disconnect)
    services_discovered = pyqtSignal(object)  # list of BleakGATTService
    char_value_read = pyqtSignal(str, object)  # uuid, bytes value
    char_notify_received = pyqtSignal(str, object)  # uuid, bytes value
    pairing_request = pyqtSignal(str, int, str)  # device_path, passkey, mode

    def __init__(self):
        super().__init__()
        self.setWindowTitle(f"BLE Tool (nRF Connect Style) - {platform.system()}")
        self.resize(1100, 700)

        self._async = AsyncBridge()
        self._scanning = False
        self._devices: dict[str, DeviceItem] = {}  # address -> DeviceItem
        self._scanner = None
        self._client: BleakClient | None = None
        self._connected_address: str | None = None
        self._notifying: set[str] = set()  # UUIDs currently subscribed
        self._pairing_result: bool | None = None

        self._init_ui()
        self._connect_signals()
        self._setup_pairing_agent()
        self.disconnected_signal.connect(self._on_unexpected_disconnect)

    # ---- UI setup ---------------------------------------------------------

    def _init_ui(self):
        central = QWidget()
        self.setCentralWidget(central)
        main_layout = QHBoxLayout(central)

        splitter = QSplitter(Qt.Horizontal)
        main_layout.addWidget(splitter)

        # --- Left panel: scan ---
        left = QWidget()
        left_layout = QVBoxLayout(left)

        # Scan controls
        scan_bar = QHBoxLayout()
        self.btn_scan = QPushButton("Start Scan")
        self.btn_scan.setMinimumHeight(36)
        scan_bar.addWidget(self.btn_scan)
        self.btn_clear = QPushButton("Clear")
        self.btn_clear.setMinimumHeight(36)
        scan_bar.addWidget(self.btn_clear)
        left_layout.addLayout(scan_bar)

        # Filter
        filter_bar = QHBoxLayout()
        filter_bar.addWidget(QLabel("Filter:"))
        self.filter_edit = QLineEdit()
        self.filter_edit.setPlaceholderText("Name or Address...")
        filter_bar.addWidget(self.filter_edit)
        left_layout.addLayout(filter_bar)

        # RSSI filter
        rssi_bar = QHBoxLayout()
        rssi_bar.addWidget(QLabel("RSSI ≥"))
        self.rssi_filter = QSpinBox()
        self.rssi_filter.setRange(-120, 0)
        self.rssi_filter.setValue(-100)
        self.rssi_filter.setSuffix(" dBm")
        self.rssi_filter.setToolTip("Only show devices with RSSI above this threshold")
        rssi_bar.addWidget(self.rssi_filter)
        rssi_bar.addStretch()
        left_layout.addLayout(rssi_bar)

        # Device list
        self.device_tree = QTreeWidget()
        self.device_tree.setHeaderLabels(["Name", "Address", "RSSI"])
        self.device_tree.header().setSectionResizeMode(0, QHeaderView.Stretch)
        self.device_tree.setColumnWidth(1, 160)
        self.device_tree.setColumnWidth(2, 60)
        self.device_tree.setSortingEnabled(False)  # we sort manually
        left_layout.addWidget(self.device_tree)

        self.lbl_count = QLabel("Devices: 0")
        left_layout.addWidget(self.lbl_count)

        splitter.addWidget(left)

        # --- Right panel: connection / services ---
        right = QWidget()
        right_layout = QVBoxLayout(right)

        # Connection info
        conn_group = QGroupBox("Connection")
        conn_layout = QVBoxLayout(conn_group)
        self.lbl_conn = QLabel("Not connected")
        self.lbl_conn.setFont(QFont("sans-serif", 10, QFont.Bold))
        conn_layout.addWidget(self.lbl_conn)
        conn_btns = QHBoxLayout()
        self.btn_connect = QPushButton("Connect")
        self.btn_connect.setMinimumHeight(36)
        self.btn_disconnect = QPushButton("Disconnect")
        self.btn_disconnect.setMinimumHeight(36)
        self.btn_disconnect.setEnabled(False)
        self.btn_pair = QPushButton("Pair")
        self.btn_pair.setMinimumHeight(36)
        self.btn_pair.setEnabled(False)

        # Disable pairing on Windows
        if not HAS_DBUS:
            self.btn_pair.setToolTip("Pairing not supported on Windows")

        conn_btns.addWidget(self.btn_connect)
        conn_btns.addWidget(self.btn_disconnect)
        conn_btns.addWidget(self.btn_pair)
        conn_layout.addLayout(conn_btns)
        right_layout.addWidget(conn_group)

        # Service tree
        svc_group = QGroupBox("Services & Characteristics")
        svc_layout = QVBoxLayout(svc_group)
        self.service_tree = QTreeWidget()
        self.service_tree.setHeaderLabels(["UUID", "Properties", "Value"])
        self.service_tree.header().setSectionResizeMode(0, QHeaderView.Stretch)
        self.service_tree.setColumnWidth(1, 140)
        self.service_tree.setColumnWidth(2, 200)
        svc_layout.addWidget(self.service_tree)

        # Characteristic operation bar
        op_bar = QHBoxLayout()
        self.btn_read = QPushButton("Read")
        self.btn_write = QPushButton("Write")
        self.btn_notify = QPushButton("Notify")
        self.btn_indicate = QPushButton("Indicate")
        for btn in (self.btn_read, self.btn_write, self.btn_notify, self.btn_indicate):
            btn.setMinimumHeight(30)
            btn.setEnabled(False)
            op_bar.addWidget(btn)
        svc_layout.addLayout(op_bar)

        # Write input
        write_bar = QHBoxLayout()
        write_bar.addWidget(QLabel("Data (hex):"))
        self.write_input = QLineEdit()
        self.write_input.setPlaceholderText("e.g. 01 02 FF or 0102FF")
        write_bar.addWidget(self.write_input)
        self.combo_write_type = QComboBox()
        self.combo_write_type.addItems(["Write Request", "Write Command"])
        write_bar.addWidget(self.combo_write_type)
        svc_layout.addLayout(write_bar)

        right_layout.addWidget(svc_group)

        # Log
        log_group = QGroupBox("Log")
        log_layout = QVBoxLayout(log_group)
        self.log_text = QTextEdit()
        self.log_text.setReadOnly(True)
        self.log_text.setMaximumHeight(150)
        log_layout.addWidget(self.log_text)
        right_layout.addWidget(log_group)

        splitter.addWidget(right)
        splitter.setSizes([400, 700])

    def _connect_signals(self):
        self.btn_scan.clicked.connect(self._toggle_scan)
        self.btn_clear.clicked.connect(self._clear_devices)
        self.btn_connect.clicked.connect(self._on_connect)
        self.btn_disconnect.clicked.connect(self._on_disconnect)
        self.device_found.connect(self._on_device_found)
        self.log_signal.connect(self._append_log)
        self.connection_done.connect(self._on_connection_done)
        self.filter_edit.textChanged.connect(self._apply_filter)
        self.rssi_filter.valueChanged.connect(self._apply_filter)
        self.device_tree.itemDoubleClicked.connect(self._on_connect)
        self.services_discovered.connect(self._on_services_discovered)
        self.service_tree.setContextMenuPolicy(Qt.CustomContextMenu)
        self.service_tree.customContextMenuRequested.connect(self._char_context_menu)
        self.char_value_read.connect(self._on_char_value_read)
        self.char_notify_received.connect(self._on_notify_received)
        self.service_tree.currentItemChanged.connect(self._on_char_selected)
        self.btn_pair.clicked.connect(self._on_pair)
        self.btn_read.clicked.connect(self._on_char_read)
        self.btn_write.clicked.connect(self._on_char_write)
        self.btn_notify.clicked.connect(self._on_char_notify)
        self.btn_indicate.clicked.connect(self._on_char_indicate)

    # ---- Logging ----------------------------------------------------------

    def _log(self, msg: str):
        ts = datetime.now().strftime("%H:%M:%S")
        self.log_signal.emit(f"[{ts}] {msg}")

    def _append_log(self, msg: str):
        self.log_text.append(msg)

    # ---- Scanning ---------------------------------------------------------

    def _toggle_scan(self):
        if self._scanning:
            self._stop_scan()
        else:
            self._start_scan()

    def _start_scan(self):
        self._scanning = True
        self.btn_scan.setText("Stop Scan")
        self._log("Scanning started...")

        def detection_callback(device: BLEDevice, adv: AdvertisementData):
            self.device_found.emit(device, adv)

        async def _scan():
            try:
                self._scanner = BleakScanner(detection_callback=detection_callback)
                await self._scanner.start()
            except Exception as e:
                self.log_signal.emit(f"Scan error: {e}")
                self._scanning = False
                # Use QTimer to update UI safely from async context
                QTimer.singleShot(0, lambda: self.btn_scan.setText("Start Scan"))

        self._async.run(_scan())

    def _stop_scan(self):
        self._scanning = False
        self.btn_scan.setText("Start Scan")
        self._log("Scanning stopped.")

        async def _stop():
            if self._scanner:
                try:
                    await self._scanner.stop()
                except Exception as e:
                    self.log_signal.emit(f"Stop scan error: {e}")
                self._scanner = None

        self._async.run(_stop())

    def _on_device_found(self, device: BLEDevice, adv: AdvertisementData):
        item = DeviceItem(device, adv)
        self._devices[device.address] = item
        self._refresh_device_list()

    def _clear_devices(self):
        self._devices.clear()
        self.device_tree.clear()
        self.lbl_count.setText("Devices: 0")

    def _refresh_device_list(self):
        """Rebuild device tree sorted by RSSI (strongest first)."""
        self.device_tree.clear()
        sorted_devices = sorted(self._devices.values(), key=lambda d: d.rssi, reverse=True)
        filter_text = self.filter_edit.text().lower()

        rssi_threshold = self.rssi_filter.value()
        count = 0
        for dev in sorted_devices:
            if dev.rssi < rssi_threshold:
                continue
            if filter_text:
                if filter_text not in dev.name.lower() and filter_text not in dev.address.lower():
                    continue

            tw = QTreeWidgetItem([dev.name, dev.address, str(dev.rssi)])
            # Color code RSSI
            rssi = dev.rssi
            if rssi > -50:
                tw.setForeground(2, QColor("#2ecc71"))  # green - excellent
            elif rssi > -70:
                tw.setForeground(2, QColor("#f39c12"))  # orange - good
            else:
                tw.setForeground(2, QColor("#e74c3c"))  # red - weak

            tw.setData(0, Qt.UserRole, dev.address)
            self.device_tree.addTopLevelItem(tw)
            count += 1

        self.lbl_count.setText(f"Devices: {count}")

    def _apply_filter(self):
        self._refresh_device_list()

    # ---- Connection & Service Discovery ------------------------------------

    def _on_connect(self):
        selected = self.device_tree.currentItem()
        if not selected:
            self._log("No device selected.")
            return
        address = selected.data(0, Qt.UserRole)
        name = selected.text(0)
        self._log(f"Connecting to {name} ({address})...")
        self.btn_connect.setEnabled(False)

        def _disconnected_cb(client: BleakClient):
            self.disconnected_signal.emit(f"Device disconnected: {name} ({address})")

        async def _connect():
            try:
                self._client = BleakClient(address, disconnected_callback=_disconnected_cb)
                await self._client.connect()
                self._connected_address = address
                self.connection_done.emit(True, f"Connected to {name} ({address})")
                # Discover services
                services = self._client.services
                self.services_discovered.emit(services)
            except Exception as e:
                self.connection_done.emit(False, f"Connection failed: {e}")

        self._async.run(_connect())

    def _on_connection_done(self, success: bool, msg: str):
        self._log(msg)
        if success:
            self.lbl_conn.setText(msg)
            self.btn_connect.setEnabled(False)   # keep disabled while connected
            self.btn_disconnect.setEnabled(True)
            if HAS_DBUS:
                self.btn_pair.setEnabled(True)
        else:
            self.btn_connect.setEnabled(True)    # re-enable only on failure

    def _on_services_discovered(self, services):
        """Populate the service tree with discovered GATT services."""
        self.service_tree.clear()
        self._log(f"Discovered {len(services.services)} service(s)")

        for service in services:
            # Service node
            svc_item = QTreeWidgetItem([
                f"Service: {service.uuid}",
                f"Handle: 0x{service.handle:04X}",
                service.description or ""
            ])
            svc_item.setFont(0, QFont("sans-serif", 9, QFont.Bold))
            svc_item.setExpanded(True)
            self.service_tree.addTopLevelItem(svc_item)

            for char in service.characteristics:
                # Build properties string
                props = ", ".join(char.properties)

                char_item = QTreeWidgetItem([
                    f"  Char: {char.uuid}",
                    props,
                    char.description or ""
                ])
                char_item.setData(0, Qt.UserRole, char.uuid)
                char_item.setData(0, Qt.UserRole + 1, "characteristic")
                char_item.setData(0, Qt.UserRole + 2, char.handle)
                svc_item.addChild(char_item)

                # Descriptors
                for desc in char.descriptors:
                    desc_item = QTreeWidgetItem([
                        f"    Desc: {desc.uuid}",
                        "",
                        desc.description or ""
                    ])
                    desc_item.setData(0, Qt.UserRole, desc.uuid)
                    desc_item.setData(0, Qt.UserRole + 1, "descriptor")
                    desc_item.setData(0, Qt.UserRole + 2, desc.handle)
                    char_item.addChild(desc_item)

        self.service_tree.expandAll()

    # ---- Characteristic Operations ------------------------------------------

    def _get_selected_char(self):
        """Return (uuid, properties_text) of the selected characteristic, or None."""
        item = self.service_tree.currentItem()
        if not item:
            return None
        if item.data(0, Qt.UserRole + 1) != "characteristic":
            return None
        return item.data(0, Qt.UserRole), item.text(1)

    def _on_char_selected(self, current, previous):
        """Enable/disable operation buttons based on selected characteristic."""
        self.btn_read.setEnabled(False)
        self.btn_write.setEnabled(False)
        self.btn_notify.setEnabled(False)
        self.btn_indicate.setEnabled(False)

        if not current or not self._client:
            return
        if current.data(0, Qt.UserRole + 1) != "characteristic":
            return

        props = current.text(1).lower()
        self.btn_read.setEnabled("read" in props)
        self.btn_write.setEnabled("write" in props)
        uuid = current.data(0, Qt.UserRole)
        if "notify" in props:
            self.btn_notify.setEnabled(True)
            self.btn_notify.setText("Stop Notify" if uuid in self._notifying else "Notify")
        if "indicate" in props:
            self.btn_indicate.setEnabled(True)
            self.btn_indicate.setText("Stop Indicate" if uuid in self._notifying else "Indicate")

    def _char_context_menu(self, pos):
        """Right-click context menu on characteristic items."""
        item = self.service_tree.itemAt(pos)
        if not item or item.data(0, Qt.UserRole + 1) != "characteristic":
            return
        if not self._client:
            return

        props = item.text(1).lower()
        uuid = item.data(0, Qt.UserRole)
        menu = QMenu(self)

        if "read" in props:
            menu.addAction("Read").triggered.connect(self._on_char_read)
        if "write" in props:
            menu.addAction("Write").triggered.connect(self._on_char_write)
        if "notify" in props:
            label = "Stop Notify" if uuid in self._notifying else "Start Notify"
            menu.addAction(label).triggered.connect(self._on_char_notify)
        if "indicate" in props:
            label = "Stop Indicate" if uuid in self._notifying else "Start Indicate"
            menu.addAction(label).triggered.connect(self._on_char_indicate)

        menu.exec_(self.service_tree.viewport().mapToGlobal(pos))

    def _on_char_read(self):
        info = self._get_selected_char()
        if not info or not self._client:
            return
        uuid, _ = info
        self._log(f"Reading {uuid}...")

        async def _read():
            try:
                value = await self._client.read_gatt_char(uuid)
                self.char_value_read.emit(uuid, value)
            except Exception as e:
                self.log_signal.emit(f"Read error: {e}")

        self._async.run(_read())

    def _on_char_value_read(self, uuid: str, value: bytes):
        hex_str = value.hex(" ")
        text_str = value.decode("utf-8", errors="replace")
        self._log(f"Read [{uuid}]: hex={hex_str}  text=\"{text_str}\"")
        # Update the value column in the tree
        self._update_char_value_in_tree(uuid, hex_str)

    def _on_char_write(self):
        info = self._get_selected_char()
        if not info or not self._client:
            return
        uuid, _ = info
        hex_text = self.write_input.text().strip()
        if not hex_text:
            self._log("Write error: no data entered.")
            return

        try:
            data = bytes.fromhex(hex_text.replace(" ", ""))
        except ValueError:
            self._log("Write error: invalid hex string.")
            return

        write_with_response = self.combo_write_type.currentIndex() == 0
        self._log(f"Writing {data.hex(' ')} to {uuid} ({'request' if write_with_response else 'command'})...")

        async def _write():
            try:
                await self._client.write_gatt_char(uuid, data, response=write_with_response)
                self.log_signal.emit(f"Write to [{uuid}] OK")
            except Exception as e:
                self.log_signal.emit(f"Write error: {e}")

        self._async.run(_write())

    def _on_char_notify(self):
        self._toggle_notification("notify")

    def _on_char_indicate(self):
        self._toggle_notification("indicate")

    def _toggle_notification(self, mode: str):
        info = self._get_selected_char()
        if not info or not self._client:
            return
        uuid, _ = info

        if uuid in self._notifying:
            # Stop
            self._log(f"Stopping {mode} on {uuid}...")

            async def _stop():
                try:
                    await self._client.stop_notify(uuid)
                    self._notifying.discard(uuid)
                    self.log_signal.emit(f"Stopped {mode} on [{uuid}]")
                except Exception as e:
                    self.log_signal.emit(f"Stop {mode} error: {e}")

            self._async.run(_stop())
        else:
            # Start
            self._log(f"Starting {mode} on {uuid}...")

            def _callback(handle, data):
                self.char_notify_received.emit(uuid, data)

            async def _start():
                try:
                    await self._client.start_notify(uuid, _callback)
                    self._notifying.add(uuid)
                    self.log_signal.emit(f"Started {mode} on [{uuid}]")
                except Exception as e:
                    self.log_signal.emit(f"Start {mode} error: {e}")

            self._async.run(_start())

        # Update button text
        QTimer.singleShot(500, lambda: self._on_char_selected(
            self.service_tree.currentItem(), None))

    def _on_notify_received(self, uuid: str, value: bytes):
        hex_str = value.hex(" ")
        text_str = value.decode("utf-8", errors="replace")
        self._log(f"Notify [{uuid}]: hex={hex_str}  text=\"{text_str}\"")
        self._update_char_value_in_tree(uuid, hex_str)

    def _update_char_value_in_tree(self, uuid: str, value_str: str):
        """Find the characteristic item by UUID and update its value column."""
        for i in range(self.service_tree.topLevelItemCount()):
            svc = self.service_tree.topLevelItem(i)
            for j in range(svc.childCount()):
                char = svc.child(j)
                if char.data(0, Qt.UserRole) == uuid:
                    char.setText(2, value_str)
                    return

    def _on_pair(self):
        """Initiate pairing (Linux only)."""
        if not HAS_DBUS:
            self._log("Pairing is not supported on Windows. Use Windows Bluetooth settings.")
            QMessageBox.information(self, "Pairing",
                "Pairing is not supported directly on Windows.\n\n"
                "Please use Windows Bluetooth settings to pair devices:\n"
                "1. Open Windows Settings > Bluetooth & devices\n"
                "2. Click 'Add device'\n"
                "3. Select your BLE device from the list")
            return

        if not self._client or not self._connected_address:
            self._log("Not connected.")
            return
        self._log("Initiating pairing...")

        async def _pair():
            try:
                await self._client.pair()
                self.log_signal.emit("Pairing successful!")
            except Exception as e:
                self.log_signal.emit(f"Pairing failed: {e}")

        self._async.run(_pair())

    def _reset_connection_ui(self):
        """Reset all UI state to disconnected. Safe to call from any context."""
        self.lbl_conn.setText("Not connected")
        self.btn_connect.setEnabled(True)
        self.btn_disconnect.setEnabled(False)
        self.btn_pair.setEnabled(False)
        self.service_tree.clear()
        self._notifying.clear()
        self.btn_read.setEnabled(False)
        self.btn_write.setEnabled(False)
        self.btn_notify.setEnabled(False)
        self.btn_indicate.setEnabled(False)

    def _on_unexpected_disconnect(self, reason: str):
        """Handle device-initiated disconnect (via disconnected_callback)."""
        self._log(f"[!] {reason}")
        self._client = None
        self._connected_address = None
        self._reset_connection_ui()

    def _on_disconnect(self):
        if not self._client:
            return
        self._log("Disconnecting...")
        client = self._client
        self._client = None
        self._connected_address = None
        self._reset_connection_ui()

        async def _disconnect():
            try:
                await client.disconnect()
            except Exception:
                pass
            self.log_signal.emit("Disconnected.")

        self._async.run(_disconnect())

    # ---- Pairing Agent (Linux only) --------------------------------------------

    def _setup_pairing_agent(self):
        """Register the BlueZ pairing agent for numerical comparison (Linux only)."""
        if not HAS_DBUS:
            self._log(f"Platform: {platform.system()} - Pairing via tool not supported")
            return

        self._agent = register_pairing_agent()
        if self._agent:
            self._agent.confirm_request_callback = self._handle_pairing_request
            self._log("Pairing agent registered (numerical comparison supported)")
        else:
            self._log("Pairing agent not available (run with appropriate permissions)")

        self.pairing_request.connect(self._show_pairing_dialog)

    def _handle_pairing_request(self, device_path, passkey, mode):
        """Called from D-Bus thread — emit signal for thread-safe UI dialog."""
        if not HAS_DBUS:
            return False

        import threading
        if mode == "display":
            self.log_signal.emit(f"Passkey display: {passkey:06d} for {device_path}")
            return True

        # For confirmation, we need to block until the user responds
        self._pairing_result = None
        event = threading.Event()

        def _on_result(dev, pk, m):
            # This runs in the main thread via signal
            pass

        # Use a blocking approach: emit signal and wait
        self.pairing_request.emit(str(device_path), int(passkey), mode)

        # Wait for user response (the dialog sets self._pairing_result)
        for _ in range(600):  # 60 second timeout
            if self._pairing_result is not None:
                break
            import time
            time.sleep(0.1)

        return self._pairing_result or False

    def _show_pairing_dialog(self, device_path: str, passkey: int, mode: str):
        """Show a dialog asking the user to confirm numerical comparison."""
        device_name = device_path.split("/")[-1] if "/" in device_path else device_path

        if mode == "confirm":
            msg = (
                f"Pairing request from:\n{device_name}\n\n"
                f"Confirm passkey matches:\n\n"
                f"  {passkey:06d}\n\n"
                f"Does this number match the one shown on the device?"
            )
            reply = QMessageBox.question(
                self, "Numerical Comparison - Pairing",
                msg,
                QMessageBox.Yes | QMessageBox.No,
                QMessageBox.No
            )
            self._pairing_result = (reply == QMessageBox.Yes)
            if self._pairing_result:
                self._log(f"Pairing confirmed for {device_name} (passkey: {passkey:06d})")
            else:
                self._log(f"Pairing rejected for {device_name}")
        elif mode == "display":
            self._log(f"Display passkey: {passkey:06d} for {device_name}")
            self._pairing_result = True

    # ---- Cleanup ----------------------------------------------------------

    def closeEvent(self, event):
        if self._scanning:
            self._stop_scan()
        if self._client:
            self._async.run(self._client.disconnect())
        self._async.stop()
        event.accept()


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------
def main():
    signal.signal(signal.SIGINT, signal.SIG_DFL)  # allow Ctrl+C
    app = QApplication(sys.argv)
    app.setStyle("Fusion")
    window = BLEToolWindow()
    window.show()
    sys.exit(app.exec_())


if __name__ == "__main__":
    main()