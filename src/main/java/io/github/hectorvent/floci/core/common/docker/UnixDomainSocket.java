package io.github.hectorvent.floci.core.common.docker;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.concurrent.TimeUnit;

/**
 * A {@link Socket} over a Unix domain socket that honours {@link #setSoTimeout(int)}.
 *
 * <p>docker-java's {@code UnixSocket} reads through a blocking channel stream, which ignores the read
 * timeout, so on a {@code unix://} Docker host no call ever timed out and httpclient5's stale-connection
 * check (a read with a 1 ms timeout) would block forever. This socket keeps the channel non-blocking and
 * waits on a {@link Selector}: a read with a timeout fails with {@link SocketTimeoutException} once the
 * timeout passes without data, and a timeout of zero waits indefinitely, as {@code SO_TIMEOUT} does.
 * Reads and writes have their own selector and lock, so a hijacked exec can write stdin on one thread
 * while another reads its output.
 *
 * <p>Failures keep the blocking channel's shape, so callers that expect an {@link IOException} still get
 * one: closing the socket while another thread waits in a read or write ends that wait with a
 * {@link SocketException} rather than the selector's unchecked {@link ClosedSelectorException}, and an
 * interrupted thread closes the socket and fails with {@link ClosedByInterruptException} instead of
 * spinning, since a selector returns at once while the interrupt flag is set.
 */
final class UnixDomainSocket extends Socket {

    private final SocketChannel channel;
    private final SocketAddress address;
    private final Selector readSelector;
    private final Selector writeSelector;
    private final Object readLock = new Object();
    private final Object writeLock = new Object();
    private final InputStream input = new ChannelInputStream();
    private final OutputStream output = new ChannelOutputStream();
    private volatile int soTimeoutMillis;
    private volatile boolean closed;
    private volatile boolean inputShutdown;
    private volatile boolean outputShutdown;

    private UnixDomainSocket(SocketChannel channel, SocketAddress address) throws IOException {
        this.channel = channel;
        this.address = address;
        this.readSelector = Selector.open();
        this.writeSelector = Selector.open();
        channel.register(readSelector, SelectionKey.OP_READ);
        channel.register(writeSelector, SelectionKey.OP_WRITE);
    }

    /** Connects to the Unix domain socket at {@code path}. */
    static UnixDomainSocket connect(String path) throws IOException {
        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(path);
        SocketChannel channel = SocketChannel.open(address);
        try {
            channel.configureBlocking(false);
            return new UnixDomainSocket(channel, address);
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    @Override
    public void connect(SocketAddress endpoint) {
        // Already connected by connect(String): httpclient5 calls this on the socket its factory returned.
    }

    @Override
    public void connect(SocketAddress endpoint, int timeout) {
        // Already connected by connect(String).
    }

    @Override
    public InputStream getInputStream() throws IOException {
        ensureOpen();
        if (inputShutdown) {
            throw new SocketException("Socket input is shutdown");
        }
        return input;
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        ensureOpen();
        if (outputShutdown) {
            throw new SocketException("Socket output is shutdown");
        }
        return output;
    }

    @Override
    public void setSoTimeout(int timeout) throws SocketException {
        if (timeout < 0) {
            throw new IllegalArgumentException("timeout can't be negative");
        }
        soTimeoutMillis = timeout;
    }

    @Override
    public int getSoTimeout() {
        return soTimeoutMillis;
    }

    @Override
    public void shutdownInput() throws IOException {
        ensureOpen();
        channel.shutdownInput();
        inputShutdown = true;
    }

    @Override
    public void shutdownOutput() throws IOException {
        ensureOpen();
        channel.shutdownOutput();
        outputShutdown = true;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        readSelector.wakeup();
        writeSelector.wakeup();
        try {
            channel.close();
        } finally {
            readSelector.close();
            writeSelector.close();
        }
    }

    @Override
    public boolean isConnected() {
        return channel.isConnected();
    }

    @Override
    public boolean isBound() {
        return true;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public boolean isInputShutdown() {
        return inputShutdown;
    }

    @Override
    public boolean isOutputShutdown() {
        return outputShutdown;
    }

    @Override
    public SocketAddress getLocalSocketAddress() {
        return address;
    }

    @Override
    public SocketAddress getRemoteSocketAddress() {
        return address;
    }

    @Override
    public InetAddress getInetAddress() {
        return null;
    }

    @Override
    public InetAddress getLocalAddress() {
        return null;
    }

    private void ensureOpen() throws SocketException {
        if (closed) {
            throw new SocketException("Socket is closed");
        }
    }

    private int read(ByteBuffer buffer) throws IOException {
        synchronized (readLock) {
            int timeout = soTimeoutMillis;
            long deadline = timeout > 0 ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout) : 0L;
            try {
                while (true) {
                    ensureOpen();
                    int read = channel.read(buffer);
                    if (read != 0) {
                        return read;
                    }
                    if (timeout > 0) {
                        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                        if (remaining <= 0) {
                            throw new SocketTimeoutException("Read timed out");
                        }
                        readSelector.select(remaining);
                    } else {
                        readSelector.select();
                    }
                    failIfInterrupted();
                    readSelector.selectedKeys().clear();
                }
            } catch (ClosedSelectorException e) {
                throw new SocketException("Socket is closed");
            }
        }
    }

    private void write(ByteBuffer buffer) throws IOException {
        synchronized (writeLock) {
            try {
                while (buffer.hasRemaining()) {
                    ensureOpen();
                    if (channel.write(buffer) == 0) {
                        writeSelector.select();
                        failIfInterrupted();
                        writeSelector.selectedKeys().clear();
                    }
                }
            } catch (ClosedSelectorException e) {
                throw new SocketException("Socket is closed");
            }
        }
    }

    private void failIfInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            close();
            throw new ClosedByInterruptException();
        }
    }

    private final class ChannelInputStream extends InputStream {

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int read = read(one, 0, 1);
            return read == -1 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            return UnixDomainSocket.this.read(ByteBuffer.wrap(bytes, offset, length));
        }

        @Override
        public void close() throws IOException {
            UnixDomainSocket.this.close();
        }
    }

    private final class ChannelOutputStream extends OutputStream {

        @Override
        public void write(int value) throws IOException {
            write(new byte[] {(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            UnixDomainSocket.this.write(ByteBuffer.wrap(bytes, offset, length));
        }

        @Override
        public void close() throws IOException {
            UnixDomainSocket.this.close();
        }
    }
}
