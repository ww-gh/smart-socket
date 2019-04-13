package org.smartboot.socket.transport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smartboot.socket.buffer.BufferPage;
import org.smartboot.socket.buffer.VirtualBuffer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 包装当前会话分配到的虚拟Buffer,提供流式操作方式
 *
 * @author 三刀
 * @version V1.0 , 2018/11/8
 */

public final class WriteBuffer extends OutputStream {
    private static final Logger LOGGER = LoggerFactory.getLogger(WriteBuffer.class);
    /**
     * 输出缓存块大小
     */
    private static final int WRITE_CHUNK_SIZE = IoServerConfig.getIntProperty(IoServerConfig.Property.SESSION_WRITE_CHUNK_SIZE, 4096);
    ////
    private final VirtualBuffer[] items;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();
    int takeIndex;

    int putIndex;

    int count;
    ////

    private VirtualBuffer writeInBuf;
    private BufferPage bufferPage;
    private boolean closed = false;
    private Function<WriteBuffer, Void> function;
    private byte[] cacheByte = new byte[8];

    WriteBuffer(BufferPage bufferPage, Function<WriteBuffer, Void> flushFunction, int writeQueueSize) {
        this.bufferPage = bufferPage;
        this.function = flushFunction;
        this.items = new VirtualBuffer[writeQueueSize];
    }

    @Override
    public void write(int b) {
        if (writeInBuf == null) {
            writeInBuf = bufferPage.allocate(WRITE_CHUNK_SIZE);
        }
        writeInBuf.buffer().put((byte) b);
        if (writeInBuf.buffer().hasRemaining()) {
            return;
        }
        writeInBuf.buffer().flip();
        lock.lock();
        try {
            this.put(writeInBuf);
        } finally {
            lock.unlock();
        }
        writeInBuf = null;
        function.apply(this);
    }

    public void writeInt(int v) throws IOException {
        cacheByte[0] = (byte) ((v >>> 24) & 0xFF);
        cacheByte[1] = (byte) ((v >>> 16) & 0xFF);
        cacheByte[2] = (byte) ((v >>> 8) & 0xFF);
        cacheByte[3] = (byte) ((v >>> 0) & 0xFF);
        write(cacheByte, 0, 4);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (closed) {
            throw new IOException("OutputStream has closed");
        }
        if (b == null) {
            throw new NullPointerException();
        } else if ((off < 0) || (off > b.length) || (len < 0) ||
                ((off + len) > b.length) || ((off + len) < 0)) {
            throw new IndexOutOfBoundsException();
        } else if (len == 0) {
            return;
        }

        lock.lock();
        try {
            do {
                if (writeInBuf == null) {
                    writeInBuf = bufferPage.allocate(Math.max(WRITE_CHUNK_SIZE, len - off));
                }
                ByteBuffer writeBuffer = writeInBuf.buffer();
                int minSize = Math.min(writeBuffer.remaining(), len - off);
                if (minSize == 0 || closed) {
                    writeInBuf.clean();
                    throw new IOException("writeBuffer.remaining:" + writeBuffer.remaining() + " closed:" + closed);
                }
                writeBuffer.put(b, off, minSize);
                off += minSize;
                if (!writeBuffer.hasRemaining()) {
                    writeBuffer.flip();
                    this.put(writeInBuf);
                    writeInBuf = null;
                    function.apply(this);
                }
            } while (off < len);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void flush() {
        if (closed) {
            throw new RuntimeException("OutputStream has closed");
        }
        int size = this.count;
        if (size > 0) {
            function.apply(this);
        } else if (writeInBuf != null && writeInBuf.buffer().position() > 0 && lock.tryLock()) {
            try {
                if (writeInBuf != null && writeInBuf.buffer().position() > 0) {
                    final VirtualBuffer buffer = writeInBuf;
                    writeInBuf = null;
                    buffer.buffer().flip();
                    this.put(buffer);
                    size++;
                }
            } finally {
                lock.unlock();
            }
            if (size > 0) {
                function.apply(this);
            }
        }

    }

    @Override
    public void close() throws IOException {
        lock.lock();
        try {
            if (closed) {
                throw new IOException("OutputStream has closed");
            }
            flush();

            closed = true;

            VirtualBuffer byteBuf = null;
            while ((byteBuf = poll()) != null) {
                byteBuf.clean();
            }
            if (writeInBuf != null) {
                writeInBuf.clean();
            }
        } finally {
            lock.unlock();
        }
    }

    boolean isClosed() {
        return closed;
    }

    boolean hasData() {
        return count > 0 || (writeInBuf != null && writeInBuf.buffer().position() > 0);
    }


    private VirtualBuffer dequeue() {
        VirtualBuffer x = items[takeIndex];
        items[takeIndex] = null;
        if (++takeIndex == items.length) {
            takeIndex = 0;
        }
        count--;
        notFull.signal();
        return x;
    }


    private void put(VirtualBuffer e) {
        try {
            while (count == items.length) {
                notFull.await();
            }
            items[putIndex] = e;
            if (++putIndex == items.length) {
                putIndex = 0;
            }
            count++;
            notEmpty.signal();
        } catch (InterruptedException e1) {
            throw new RuntimeException(e1);
        }
    }

    VirtualBuffer poll() {
        lock.lock();
        try {
            return (count == 0) ? null : dequeue();
        } finally {
            lock.unlock();
        }
    }

}