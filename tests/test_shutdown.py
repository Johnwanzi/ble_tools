import asyncio
import os
from pathlib import Path
import threading
import time
import unittest
from unittest.mock import patch

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
if Path("C:/Windows/Fonts").is_dir():
    os.environ.setdefault("QT_QPA_FONTDIR", "C:/Windows/Fonts")

from PyQt5.QtCore import Qt
from PyQt5.QtWidgets import QApplication, QTreeWidgetItem

import ble_tool


class AsyncOperation:
    def __init__(self, *, blocked=False, error=None):
        self.started = threading.Event()
        self.release = threading.Event()
        self.calls = 0
        self.error = error
        if not blocked:
            self.release.set()

    async def run(self):
        self.calls += 1
        self.started.set()
        while not self.release.is_set():
            await asyncio.sleep(0.005)
        if self.error:
            raise self.error


class FakeClient:
    def __init__(self, *, blocked=False, error=None):
        self.disconnect_op = AsyncOperation(blocked=blocked, error=error)
        self.connect_op = AsyncOperation()
        self.services = []
        self.mtu_size = 247
        self.is_connected = True

    async def connect(self):
        await self.connect_op.run()

    async def disconnect(self):
        await self.disconnect_op.run()
        self.is_connected = False


class FakeScanner:
    def __init__(self, **kwargs):
        self.stop_op = AsyncOperation(**kwargs)

    async def stop(self):
        await self.stop_op.run()


class ShutdownTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])
        cls.app.setQuitOnLastWindowClosed(False)

    def setUp(self):
        self.window = ble_tool.BLEToolWindow()
        self.window.show()
        self.operations = []
        self.logs = []
        self.window.log_signal.connect(self.logs.append)

    def wait_for(self, predicate):
        deadline = time.monotonic() + 3.0
        while not predicate() and time.monotonic() < deadline:
            self.app.processEvents()
            time.sleep(0.005)
        self.assertTrue(predicate(), "Timed out waiting for shutdown progress")

    def tearDown(self):
        for operation in self.operations:
            operation.release.set()
        if not self.window._close_ready:
            self.window.close()
            self.wait_for(lambda: self.window._close_ready)
        self.app.processEvents()

    def attach_client(self, **kwargs):
        client = FakeClient(**kwargs)
        self.operations.extend([client.connect_op, client.disconnect_op])
        self.window._client = client
        return client

    def assert_closed(self):
        self.wait_for(lambda: self.window._close_ready)
        self.assertFalse(self.window.isVisible())
        self.assertFalse(self.window._async._thread.is_alive())
        self.assertTrue(self.window._async._loop.is_closed())

    def test_close_waits_for_disconnect_and_ignores_repeated_close(self):
        client = self.attach_client(blocked=True)
        self.window.close()
        self.wait_for(client.disconnect_op.started.is_set)
        self.window.close()
        self.assertTrue(self.window.isVisible())
        self.assertTrue(self.window._async._loop.is_running())
        self.assertFalse(self.window._close_ready)
        client.disconnect_op.release.set()
        self.assert_closed()
        self.assertEqual(client.disconnect_op.calls, 1)
        self.assertFalse(client.is_connected)

    def test_scanner_stops_before_disconnect(self):
        scanner = FakeScanner(blocked=True)
        self.operations.append(scanner.stop_op)
        self.window._scanner = scanner
        self.window._scanning = True
        client = self.attach_client()
        self.window.close()
        self.wait_for(scanner.stop_op.started.is_set)
        self.assertEqual(client.disconnect_op.calls, 0)
        scanner.stop_op.release.set()
        self.assert_closed()
        self.assertEqual(scanner.stop_op.calls, 1)
        self.assertFalse(client.is_connected)

    def test_transfer_cleanup_completes_before_disconnect(self):
        client = self.attach_client()
        started = threading.Event()
        cleaned = threading.Event()

        async def transfer():
            try:
                started.set()
                await asyncio.Event().wait()
            finally:
                await asyncio.sleep(0.01)
                self.assertEqual(client.disconnect_op.calls, 0)
                cleaned.set()

        self.window._async.run(transfer())
        self.wait_for(started.is_set)
        self.window.close()
        self.assert_closed()
        self.assertTrue(cleaned.is_set())
        self.assertFalse(client.is_connected)

    def test_close_waits_for_manual_disconnect_already_in_progress(self):
        client = self.attach_client(blocked=True)
        with patch.object(ble_tool, "BLE_RELEASE_DELAY_S", 0):
            self.window._on_disconnect()
            self.wait_for(client.disconnect_op.started.is_set)
            self.assertIsNone(self.window._client)
            self.window.close()
            self.app.processEvents()
            self.assertTrue(self.window._async._loop.is_running())
            client.disconnect_op.release.set()
            self.assert_closed()
        self.assertEqual(client.disconnect_op.calls, 1)
        self.assertFalse(client.is_connected)

    def test_connection_finishing_during_close_is_disconnected(self):
        client = FakeClient()
        client.connect_op.release.clear()
        self.operations.extend([client.connect_op, client.disconnect_op])
        item = QTreeWidgetItem(["Test device", "AA:BB:CC:DD:EE:FF"])
        item.setData(0, Qt.UserRole, "AA:BB:CC:DD:EE:FF")
        self.window.device_tree.addTopLevelItem(item)
        self.window.device_tree.setCurrentItem(item)
        with patch.object(ble_tool, "BleakClient", return_value=client):
            self.window._on_connect()
            self.wait_for(client.connect_op.started.is_set)
            self.window.close()
            self.assertFalse(self.window._close_ready)
            client.connect_op.release.set()
            self.assert_closed()
        self.assertEqual(client.disconnect_op.calls, 1)
        self.assertFalse(client.is_connected)

    def test_scanner_error_does_not_skip_disconnect(self):
        scanner = FakeScanner(error=RuntimeError("scanner failed"))
        self.window._scanner = scanner
        client = self.attach_client()
        self.window.close()
        self.assert_closed()
        self.assertFalse(client.is_connected)
        self.assertTrue(any("scanner failed" in log for log in self.logs))

    def test_disconnect_timeout_allows_exit(self):
        self.attach_client(blocked=True)
        with patch.object(ble_tool, "BLE_CLEANUP_TIMEOUT_S", 0.03):
            self.window.close()
            self.assert_closed()
        self.assertTrue(any("TimeoutError" in log for log in self.logs))

    def test_disconnect_error_allows_exit(self):
        self.attach_client(error=RuntimeError("disconnect failed"))
        self.window.close()
        self.assert_closed()
        self.assertTrue(any("disconnect failed" in log for log in self.logs))

    def test_close_without_a_connection(self):
        self.window.close()
        self.assert_closed()


if __name__ == "__main__":
    unittest.main()
