"""Exercise the desktop upload entry point with controlled BLE writes and ACKs."""

import asyncio
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import ble_tool


class FakeClient:
    def __init__(self, *, long_write=True, reject_long_write=False):
        properties = ["notify", "write-without-response"]
        if long_write:
            properties.append("write")
        self.characteristic = SimpleNamespace(uuid="file-io", properties=properties)
        self.services = [SimpleNamespace(characteristics=[self.characteristic])]
        self.mtu_size = 247
        self.reject_long_write = reject_long_write
        self.writes = []
        self.blocks = []
        self.sent = asyncio.Queue()
        self.buffer = bytearray()
        self.notify = None
        self.stopped = False
        self.after_frame = None

    async def start_notify(self, uuid, callback):
        self.notify = callback

    async def stop_notify(self, uuid):
        self.stopped = True

    async def write_gatt_char(self, char, data, *, response):
        self.writes.append((bytes(data), response))
        if response and self.reject_long_write:
            raise RuntimeError("Long write unavailable")
        # Yield on every fragment to expose accidental interleaving of frames.
        await asyncio.sleep(0)
        self.buffer.extend(data)
        if len(self.buffer) < 3:
            return
        length = self.buffer[1] | (self.buffer[2] << 8)
        if len(self.buffer) < length:
            return
        frame = bytes(self.buffer)
        self.buffer.clear()
        if len(frame) != length or ble_tool._crc8(frame, length - 1) != frame[-1]:
            raise AssertionError("Interleaved or corrupt frame")
        kind, body = ble_tool.parse_pb_response(ble_tool.parse_proto_frame(frame))
        if kind != ble_tool._PB_MSG_TYPE_FILEWRITE:
            raise AssertionError("Unexpected request type")
        fields = {number: value for number, wire, value in ble_tool._pb_walk(body)}
        block = ble_tool.pb_decode_file(fields[1])
        block.update(overwrite=bool(fields[2]), append=bool(fields[3]))
        self.blocks.append(block)
        self.sent.put_nowait(block)
        if self.after_frame:
            await self.after_frame(block)

    def respond(self, kind, body=b""):
        frame = ble_tool.build_pb_frame(kind, body, router=1)
        self.notify(0, bytearray(frame))

    def acknowledge(self, processed=None, *, kind=ble_tool._PB_MSG_TYPE_FILE):
        body = (b"" if processed is None else
                ble_tool._encode_pb_uint32(6, processed, required=True))
        self.respond(kind, body)


class UploadHarness:
    """Use the actual upload methods with UI controls replaced by small mocks."""

    _on_fw_send = ble_tool.BLEToolWindow._on_fw_send
    _on_fw_abort = ble_tool.BLEToolWindow._on_fw_abort
    _fio_uuid = ble_tool.BLEToolWindow._fio_uuid
    _fio_find_notify_uuid = ble_tool.BLEToolWindow._fio_find_notify_uuid
    _fio_send_frame = ble_tool.BLEToolWindow._fio_send_frame
    _fio_parse_response = ble_tool.BLEToolWindow._fio_parse_response

    def __init__(self, client, *, size=5000, runs=1, window_size=2):
        self._client = client
        self._fw_file_data = bytes(i % 251 for i in range(size))
        self._async = SimpleNamespace(
            _loop=asyncio.get_running_loop(), run=self.start)
        self.write_char_combo = Mock()
        self.write_char_combo.currentData.return_value = "file-io"
        self.fio_device_path = Mock()
        self.fio_device_path.text.return_value = "vol0:test.bin"
        self.fw_chunk_spin = Mock()
        self.fw_chunk_spin.value.return_value = 1800
        self.fw_window_spin = Mock()
        self.fw_window_spin.value.return_value = window_size
        self.fw_stress_count_spin = Mock()
        self.fw_stress_count_spin.value.return_value = runs
        self.btn_fw_send = Mock()
        self.btn_fw_abort = Mock()
        self.fw_progress = Mock()
        self.progress = []
        self.logs = []
        self.fw_progress_signal = SimpleNamespace(
            emit=lambda pct, text: self.progress.append((pct, text)))
        self.log_signal = SimpleNamespace(emit=self.logs.append)
        self._log = self.logs.append
        self.task = None

    def start(self, coroutine):
        self.task = asyncio.create_task(coroutine)


class UploadWindowTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.uploads = []

    async def asyncTearDown(self):
        for upload in self.uploads:
            if not upload.task.done():
                upload.task.cancel()
            await upload.task

    def start_upload(self, *, size=5000, runs=1, window_size=2, **client_options):
        client = FakeClient(**client_options)
        upload = UploadHarness(client, size=size, runs=runs, window_size=window_size)
        self.uploads.append(upload)
        upload._on_fw_send()
        return upload, client

    async def next_block(self, client, offset):
        block = await asyncio.wait_for(client.sent.get(), timeout=1)
        self.assertEqual(block["offset"], offset)
        return block

    async def settle(self):
        # Allow notify callbacks, queue waiters and the sender to run.
        for _ in range(15):
            await asyncio.sleep(0)

    async def assert_complete(self, upload, client):
        await asyncio.wait_for(upload.task, timeout=1)
        self.assertTrue(client.stopped)
        self.assertTrue(any("File upload stress complete:" in s for s in upload.logs))
        self.assertFalse(any("File upload error:" in s for s in upload.logs))
        self.assertEqual(upload.progress[-1], (-2, ""))

    async def test_two_blocks_before_ack_and_each_ack_refills_one_slot(self):
        upload, client = self.start_upload()
        first = await self.next_block(client, 0)
        second = await self.next_block(client, 1800)
        await self.settle()
        self.assertEqual(len(client.blocks), 2)
        self.assertTrue(all(pct == 0 for pct, text in upload.progress))

        client.acknowledge(1800)
        third = await self.next_block(client, 3600)
        self.assertTrue(any(pct == 36 for pct, text in upload.progress))
        self.assertFalse(upload.task.done())
        client.acknowledge(3600)
        await self.settle()
        self.assertFalse(upload.task.done(), "Must wait for the final block's ACK")
        self.assertTrue(any(pct == 72 for pct, text in upload.progress))
        client.acknowledge(5000)
        await self.assert_complete(upload, client)

        self.assertEqual([b["overwrite"] for b in client.blocks], [True, False, False])
        self.assertTrue(all(not b["append"] for b in client.blocks))
        self.assertTrue(all(b["path"] == "vol0:test.bin" for b in client.blocks))
        self.assertTrue(all(b["total_size"] == 5000 for b in client.blocks))
        self.assertEqual(first["data"] + second["data"] + third["data"], upload._fw_file_data)

    async def test_ack_without_processed_count_releases_oldest_block(self):
        for kind in (ble_tool._PB_MSG_TYPE_SUCCESS, ble_tool._PB_MSG_TYPE_FILE):
            with self.subTest(kind=kind):
                upload, client = self.start_upload()
                await self.next_block(client, 0)
                await self.next_block(client, 1800)
                client.acknowledge(kind=kind)
                await self.next_block(client, 3600)
                client.acknowledge(kind=kind)
                client.acknowledge(kind=kind)
                await self.assert_complete(upload, client)

    async def test_selected_window_size_limits_in_flight_blocks(self):
        for limit in (1, 3, 5):
            with self.subTest(limit=limit):
                upload, client = self.start_upload(size=12600, window_size=limit)
                for i in range(limit):
                    await self.next_block(client, i * 1800)
                await self.settle()
                self.assertEqual(len(client.blocks), limit)
                for i in range(7):
                    client.acknowledge((i + 1) * 1800)
                    if i + limit < 7:
                        await self.next_block(client, (i + limit) * 1800)
                    else:
                        await self.settle()
                await self.assert_complete(upload, client)

    async def test_duplicate_and_unrelated_acks_do_not_free_slots(self):
        upload, client = self.start_upload(size=7200)
        await self.next_block(client, 0)
        await self.next_block(client, 1800)
        client.acknowledge(1800)
        await self.next_block(client, 3600)
        client.acknowledge(1800)
        client.acknowledge(0)
        client.respond(ble_tool._PB_MSG_TYPE_DEVICE_INFO)
        await self.settle()
        self.assertEqual(len(client.blocks), 3)
        client.acknowledge(3600)
        await self.next_block(client, 5400)
        client.acknowledge(5400)
        client.acknowledge(7200)
        await self.assert_complete(upload, client)

    async def test_cumulative_ack_can_confirm_multiple_blocks(self):
        upload, client = self.start_upload()
        await self.next_block(client, 0)
        await self.next_block(client, 1800)
        client.acknowledge(3600)
        await self.next_block(client, 3600)
        client.acknowledge(1800)  # Late ACK for an already confirmed block.
        await self.settle()
        self.assertFalse(upload.task.done())
        client.acknowledge(5000)
        await self.assert_complete(upload, client)
        self.assertTrue(any("(3 packets)" in s for s in upload.logs))

    async def test_partial_or_unsent_offset_stops_without_skipping_bytes(self):
        for processed in (100, 5000):
            with self.subTest(processed=processed):
                upload, client = self.start_upload()
                await self.next_block(client, 0)
                await self.next_block(client, 1800)
                client.acknowledge(processed)
                await asyncio.wait_for(upload.task, timeout=1)
                self.assertEqual(len(client.blocks), 2)
                self.assertTrue(client.stopped)
                self.assertTrue(any("Invalid acknowledged offset" in s for s in upload.logs))
                self.assertFalse(any("complete:" in s for s in upload.logs))

    async def test_device_failure_stops_the_window(self):
        upload, client = self.start_upload()
        await self.next_block(client, 0)
        await self.next_block(client, 1800)
        client.respond(ble_tool._PB_MSG_TYPE_FAILURE,
                       ble_tool._encode_pb_uint32(1, 7) +
                       ble_tool._encode_pb_string(2, "disk full"))
        await asyncio.wait_for(upload.task, timeout=1)
        self.assertEqual(len(client.blocks), 2)
        self.assertTrue(client.stopped)
        self.assertTrue(any("Device error code=7: disk full" in s for s in upload.logs))

    async def test_missing_ack_times_out_oldest_block(self):
        with patch.object(ble_tool, "_FW_ACK_TIMEOUT_S", 0.05):
            upload, client = self.start_upload()
            await self.next_block(client, 0)
            await self.next_block(client, 1800)
            await asyncio.wait_for(upload.task, timeout=1)
        self.assertEqual(len(client.blocks), 2)
        self.assertTrue(client.stopped)
        self.assertTrue(any("ACK timeout at offset 0" in s for s in upload.logs))

    async def test_ack_received_during_slow_next_write_keeps_arrival_time(self):
        with patch.object(ble_tool, "_FW_ACK_TIMEOUT_S", 0.05):
            upload, client = self.start_upload(size=3600)

            async def slow_second_write(block):
                if block["offset"] == 1800:
                    client.acknowledge(1800)
                    await asyncio.sleep(0.08)
                    client.acknowledge(3600)

            client.after_frame = slow_second_write
            await self.assert_complete(upload, client)

    async def test_fragmentation_and_long_write_fallback_preserve_block_order(self):
        for long_write in (False, True):
            with self.subTest(long_write=long_write):
                upload, client = self.start_upload(
                    long_write=long_write, reject_long_write=long_write)
                await self.next_block(client, 0)
                await self.next_block(client, 1800)
                await self.settle()
                self.assertEqual(len(client.blocks), 2)
                client.acknowledge(1800)
                await self.next_block(client, 3600)
                client.acknowledge(3600)
                client.acknowledge(5000)
                await self.assert_complete(upload, client)
                self.assertEqual(sum(response for data, response in client.writes), int(long_write))
                self.assertTrue(all(len(data) <= 244 for data, response in client.writes
                                    if not response))
                self.assertEqual(b"".join(b["data"] for b in client.blocks), upload._fw_file_data)

    async def test_abort_does_not_refill_window(self):
        upload, client = self.start_upload()
        await self.next_block(client, 0)
        await self.next_block(client, 1800)
        upload._on_fw_abort()
        client.acknowledge(1800)
        await asyncio.wait_for(upload.task, timeout=1)
        self.assertEqual(len(client.blocks), 2)
        self.assertTrue(client.stopped)
        self.assertTrue(any("aborted" in s for s in upload.logs))

    async def test_short_file_and_repeated_runs_reset_offsets_and_overwrite(self):
        upload, client = self.start_upload(size=32, runs=2)
        for _ in range(2):
            block = await asyncio.wait_for(client.sent.get(), timeout=2)
            self.assertEqual(block["offset"], 0)
            self.assertTrue(block["overwrite"])
            self.assertEqual(len(block["data"]), 32)
            client.acknowledge(32)
        await self.assert_complete(upload, client)


if __name__ == "__main__":
    unittest.main()
