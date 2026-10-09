package com.bletools.app;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Ordered file frames with a bounded window of unacknowledged blocks. */
public final class FileUploader {
    public static final int DEFAULT_WINDOW = 2, MAX_WINDOW = 5;
    static final long ACK_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(3);

    public static final class Response {
        public final Protocol.Message message;
        public final long arrivedAt;

        public Response(Protocol.Message message, long arrivedAt) {
            this.message = message;
            this.arrivedAt = arrivedAt;
        }
    }

    public interface Transport {
        /** Writes one complete frame before returning; does not wait for a file ACK. */
        void send(byte[] frame) throws Exception;
        boolean hasResponse();
        /** Returns null at the absolute nanoTime deadline; preserves notification arrival time. */
        Response receive(long deadline) throws Exception;
    }

    public interface Progress {
        void update(long acknowledged, long total, double bytesPerSecond, double averageRttMs);
    }

    private static final class Pending {
        final long offset, end, startedAt, deadline;

        Pending(long offset, long end, long startedAt, long deadline) {
            this.offset = offset;
            this.end = end;
            this.startedAt = startedAt;
            this.deadline = deadline;
        }
    }

    public static void upload(RandomAccessFile input, String path, int chunkSize, int windowSize,
                              Protocol protocol, Transport transport, BooleanSupplier cancelled,
                              Progress progress) throws Exception {
        upload(input, path, chunkSize, windowSize, protocol, transport, cancelled, progress, System::nanoTime);
    }

    // Inject the monotonic clock for deterministic transport/timeout tests.
    static void upload(RandomAccessFile input, String path, int chunkSize, int windowSize,
                       Protocol protocol, Transport transport, BooleanSupplier cancelled,
                       Progress progress, LongSupplier clock) throws Exception {
        if (windowSize < 1 || windowSize > MAX_WINDOW) throw new IllegalArgumentException("发送窗口 N 必须为 1–5");
        if (chunkSize < 16 || chunkSize > 2048) throw new IllegalArgumentException("分块大小必须为 16–2048");
        long total = input.length();
        if (total == 0) throw new IOException("不支持上传空文件");
        Deque<Pending> pending = new ArrayDeque<>();
        long nextOffset = 0, acknowledged = 0, start = clock.getAsLong(), rttSum = 0;
        int confirmedBlocks = 0;
        progress.update(0, total, 0, 0);

        while (nextOffset < total || !pending.isEmpty()) {
            if (cancelled.getAsBoolean()) throw new IOException("上传已取消；设备可能保留部分文件");

            // Process queued ACKs first; otherwise fill any free window slot.
            if (nextOffset < total && pending.size() < windowSize
                    && (pending.isEmpty() || !transport.hasResponse())) {
                int count = (int) Math.min(chunkSize, total - nextOffset);
                byte[] bytes = new byte[count];
                input.seek(nextOffset);
                input.readFully(bytes);
                byte[] frame = protocol.frame(Protocol.FILE_WRITE,
                        Protocol.fileWrite(path, nextOffset, total, bytes, nextOffset == 0), 0, 1);
                long startedAt = clock.getAsLong();
                transport.send(frame);
                long end = nextOffset + count;
                pending.addLast(new Pending(nextOffset, end, startedAt, clock.getAsLong() + ACK_TIMEOUT_NANOS));
                nextOffset = end;
                continue;
            }

            Pending oldest = pending.getFirst();
            Response response = transport.receive(oldest.deadline);
            if (response == null || response.arrivedAt > oldest.deadline) {
                throw new IOException("等待文件 ACK 超时，offset=" + oldest.offset);
            }
            Protocol.Message message = response.message;
            if (message.type == Protocol.FAILURE) throw new IOException(Protocol.describe(message));
            if (message.type != Protocol.FILE && message.type != Protocol.SUCCESS) continue;

            long ackEnd = oldest.end;
            if (message.type == Protocol.FILE) {
                long processed = Protocol.number(message.body, 6, -1);
                if (processed != -1) {
                    // Same cumulative processed_byte semantics as the desktop.
                    if (processed <= acknowledged) continue;
                    boolean boundary = false;
                    for (Pending block : pending) if (block.end == processed) { boundary = true; break; }
                    if (!boundary) throw new IOException("无效确认偏移 " + processed
                            + "，待确认范围 " + oldest.offset + ".." + nextOffset);
                    ackEnd = processed;
                }
            }
            // Success/File without a byte count acknowledges one block in FIFO order.
            while (!pending.isEmpty() && pending.getFirst().end <= ackEnd) {
                Pending block = pending.removeFirst();
                rttSum += response.arrivedAt - block.startedAt;
                confirmedBlocks++;
            }
            acknowledged = ackEnd;
            double seconds = Math.max(.001, (response.arrivedAt - start) / 1e9);
            progress.update(acknowledged, total, acknowledged / seconds,
                    rttSum / 1e6 / confirmedBlocks);
        }
    }

    private FileUploader() {}
}
