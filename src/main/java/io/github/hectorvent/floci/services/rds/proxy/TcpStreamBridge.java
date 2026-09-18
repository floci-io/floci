package io.github.hectorvent.floci.services.rds.proxy;

import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;

/** Relays an opaque database protocol between an RDS client and its backend. */
final class TcpStreamBridge {

    private static final Logger LOG = Logger.getLogger(TcpStreamBridge.class);

    private TcpStreamBridge() {
    }

    static void relay(Socket client, Socket backend) {
        CountDownLatch directions = new CountDownLatch(2);
        Thread.ofVirtual().start(() -> copy(client, backend, directions));
        Thread.ofVirtual().start(() -> copy(backend, client, directions));
        try {
            directions.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.debug("Opaque database relay interrupted", e);
        }
    }

    private static void copy(Socket source, Socket target, CountDownLatch directions) {
        try {
            InputStream input = source.getInputStream();
            OutputStream output = target.getOutputStream();
            input.transferTo(output);
            output.flush();
        } catch (IOException e) {
            LOG.debugv("Opaque database relay closed: {0}", e.getMessage());
        } finally {
            directions.countDown();
            closeOutput(target);
        }
    }

    private static void closeOutput(Socket socket) {
        try {
            socket.shutdownOutput();
        } catch (IOException e) {
            LOG.debugv(e, "Failed to half-close opaque database relay socket: {0}", e.getMessage());
        }
    }
}
