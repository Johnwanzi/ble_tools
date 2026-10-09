package com.bletools.app;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Deterministic upload scenarios using the same sender invoked by the Android activity. */
public final class FileUploaderTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private interface Action { void run() throws Exception; }
    private static void fails(Action action, String expected) throws Exception {
        try { action.run(); } catch (IOException | IllegalArgumentException e) {
            check(e.getMessage().contains(expected), "Expected " + expected + ", got " + e);
            return;
        }
        throw new AssertionError("Expected failure: " + expected);
    }
    private static final class Block {
        final long offset, total;
        final byte[] data;
        final boolean overwrite;
        Block(byte[] frame) {
            List<Protocol.Message> decoded = new Protocol.Decoder().feed(frame);
            check(decoded.size() == 1 && decoded.get(0).type == Protocol.FILE_WRITE, "One complete FileWrite frame per send");
            byte[] body = decoded.get(0).body, file = null;
            for (Protocol.Field field : Protocol.fields(body)) if (field.number == 1) file = field.data;
            check(file != null && Protocol.text(file, 1).equals("vol0:test.bin"), "Device path");
            offset = Protocol.number(file, 2, -1);
            total = Protocol.number(file, 3, -1);
            byte[] bytes = null;
            for (Protocol.Field field : Protocol.fields(file)) if (field.number == 4) bytes = field.data;
            check(bytes != null, "File data present");
            data = bytes;
            overwrite = Protocol.number(body, 2, -1) == 1;
            check(Protocol.number(body, 3, -1) == 0, "append=false");
        }
        long end() { return offset + data.length; }
    }
    private interface Hook { void run(FakeTransport transport) throws Exception; }
    private static final class FakeTransport implements FileUploader.Transport {
        long now = TimeUnit.SECONDS.toNanos(1);
        final List<Block> blocks = new ArrayList<>();
        final Deque<FileUploader.Response> responses = new ArrayDeque<>();
        final List<Long> deadlines = new ArrayList<>();
        final List<Long> progress = new ArrayList<>();
        Hook onSend, onWait;
        boolean cancelled;

        @Override public void send(byte[] frame) throws Exception {
            blocks.add(new Block(frame));
            now += TimeUnit.MILLISECONDS.toNanos(10);
            if (onSend != null) onSend.run(this);
        }
        @Override public boolean hasResponse() { return !responses.isEmpty(); }
        @Override public FileUploader.Response receive(long deadline) throws Exception {
            deadlines.add(deadline);
            if (responses.isEmpty() && onWait != null) onWait.run(this);
            if (responses.isEmpty()) { now = Math.max(now, deadline); return null; }
            return responses.removeFirst();
        }
        void response(int type, byte[] body) {
            Protocol.Message message = new Protocol.Decoder().feed(new Protocol().frame(type, body, 0, 1)).get(0);
            responses.addLast(new FileUploader.Response(message, now));
        }
        void ack(long processed) { response(Protocol.FILE, Protocol.uint(6, processed)); }
    }

    private static void upload(byte[] data, int window, FakeTransport transport) throws Exception {
        Path path = Files.createTempFile("ble-upload-window-", ".bin");
        try {
            Files.write(path, data);
            try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
                FileUploader.upload(file, "vol0:test.bin", 1800, window, new Protocol(), transport,
                        () -> transport.cancelled,
                        (acked, total, speed, rtt) -> {
                            check(total == data.length && acked >= 0 && acked <= total, "Progress within file bounds");
                            check(speed >= 0 && rtt >= 0, "Nonnegative speed and RTT");
                            transport.progress.add(acked);
                        }, () -> transport.now);
            }
        } finally { Files.deleteIfExists(path); }
    }

    public static int run() throws Exception {
        checks = 0;
        byte[] data = new byte[12723];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i % 251);
        for (int window = 1; window <= FileUploader.MAX_WINDOW; window++) {
            final int limit = window;
            int[] confirmed = {0};
            FakeTransport transport = new FakeTransport();
            transport.onWait = t -> {
                check(t.blocks.size() == Math.min(confirmed[0] + limit, 8), "Fill/refill exactly N slots: " + limit);
                check(t.progress.get(t.progress.size() - 1) == Math.min(confirmed[0] * 1800L, data.length), "Only ACKs advance progress");
                t.ack(t.blocks.get(confirmed[0]++).end());
            };
            upload(data, window, transport);
            check(confirmed[0] == 8 && transport.progress.get(transport.progress.size() - 1) == data.length, "Wait for final ACK");
            for (int i = 0; i < transport.blocks.size(); i++) {
                Block block = transport.blocks.get(i);
                check(block.offset == i * 1800L && block.total == data.length, "Ordered offsets and total size");
                check(block.overwrite == (i == 0), "Only first block overwrites");
                check(Arrays.equals(block.data, Arrays.copyOfRange(data, (int) block.offset, (int) block.end())), "File bytes and short tail preserved");
            }
        }

        for (int kind : new int[]{Protocol.FILE, Protocol.SUCCESS}) {
            FakeTransport transport = new FakeTransport();
            transport.onWait = t -> t.response(kind, new byte[0]);
            upload(new byte[5000], 2, transport);
            check(transport.progress.equals(Arrays.asList(0L, 1800L, 3600L, 5000L)), "Legacy ACKs use FIFO order");
        }

        FakeTransport cumulative = new FakeTransport();
        cumulative.onWait = t -> {
            if (t.blocks.size() == 2) {
                t.ack(3600);
                t.ack(1800); // Late ACK for a block already confirmed cumulatively.
                t.ack(3600);
                t.response(60601, new byte[0]);
            } else {
                check(t.blocks.size() == 4 && t.progress.get(t.progress.size() - 1) == 3600, "Stale/unrelated ACKs do not release slots");
                t.ack(7200);
            }
        };
        upload(new byte[7200], 2, cumulative);
        check(cumulative.progress.equals(Arrays.asList(0L, 3600L, 7200L)), "Cumulative ACK covers multiple blocks");

        for (long bad : new long[]{100, 5000}) {
            FakeTransport transport = new FakeTransport();
            transport.onWait = t -> t.ack(bad);
            fails(() -> upload(new byte[5000], 2, transport), "无效确认偏移");
            check(transport.blocks.size() == 2 && transport.progress.equals(Arrays.asList(0L)), "Reject partial/unsent offsets without skipping data");
        }
        FakeTransport failure = new FakeTransport();
        failure.onWait = t -> t.response(Protocol.FAILURE, Protocol.concat(Protocol.uint(1,7), Protocol.string(2,"disk full")));
        fails(() -> upload(data, 2, failure), "disk full");
        check(failure.blocks.size() == 2, "Failure does not refill window");

        FakeTransport timeout = new FakeTransport();
        fails(() -> upload(data, 2, timeout), "offset=0");
        check(timeout.blocks.size() == 2, "No ACK means no third block or retry");
        check(timeout.deadlines.get(0) == TimeUnit.SECONDS.toNanos(4) + TimeUnit.MILLISECONDS.toNanos(10), "Three-second deadline starts at write completion");

        FakeTransport finalTimeout = new FakeTransport();
        finalTimeout.onWait = t -> { if (t.progress.size() == 1) t.ack(1800); };
        fails(() -> upload(new byte[3600], 2, finalTimeout), "offset=1800");
        check(finalTimeout.progress.equals(Arrays.asList(0L, 1800L)), "Missing last ACK cannot report completion");

        FakeTransport unrelated = new FakeTransport();
        unrelated.onWait = t -> {
            if (t.deadlines.size() == 1) t.response(60601, new byte[0]);
        };
        fails(() -> upload(data, 2, unrelated), "offset=0");
        check(unrelated.deadlines.size() == 2 && unrelated.deadlines.get(0).equals(unrelated.deadlines.get(1)), "Unrelated messages do not reset deadline");

        FakeTransport slowWrite = new FakeTransport();
        slowWrite.onSend = t -> {
            if (t.blocks.size() == 2) {
                t.ack(1800);
                t.now += TimeUnit.SECONDS.toNanos(4);
                t.ack(3600);
            }
        };
        upload(new byte[3600], 2, slowWrite);
        check(slowWrite.progress.equals(Arrays.asList(0L, 1800L, 3600L)), "Timely ACK remains valid while next write is slow");

        FakeTransport lateAck = new FakeTransport();
        lateAck.onSend = t -> { if (t.blocks.size() == 2) { t.now += TimeUnit.SECONDS.toNanos(4); t.ack(1800); } };
        fails(() -> upload(data, 2, lateAck), "offset=0");

        FakeTransport cancelled = new FakeTransport();
        cancelled.onSend = t -> { if (t.blocks.size() == 2) t.cancelled = true; };
        fails(() -> upload(data, 2, cancelled), "上传已取消");
        check(cancelled.blocks.size() == 2 && cancelled.deadlines.isEmpty(), "Cancel stops sends and ACK waits");

        FakeTransport brokenWrite = new FakeTransport();
        brokenWrite.onSend = t -> { if (t.blocks.size() == 2) throw new IOException("write failed"); };
        fails(() -> upload(data, 2, brokenWrite), "write failed");
        check(brokenWrite.blocks.size() == 2, "Write error stops upload without retry");
        FakeTransport disconnected = new FakeTransport();
        disconnected.onWait = t -> { throw new IOException("连接已关闭"); };
        fails(() -> upload(data, 2, disconnected), "连接已关闭");

        for (int invalid : new int[]{0, 6}) fails(() -> upload(data, invalid, new FakeTransport()), "发送窗口");
        fails(() -> upload(new byte[0], 2, new FakeTransport()), "空文件");
        for (int run = 0; run < 2; run++) {
            FakeTransport transport = new FakeTransport();
            transport.onWait = t -> t.ack(32);
            upload(new byte[32], 2, transport);
            check(transport.blocks.size() == 1 && transport.blocks.get(0).offset == 0 && transport.blocks.get(0).overwrite, "Each run starts at offset zero and overwrites");
        }
        return checks;
    }
}
