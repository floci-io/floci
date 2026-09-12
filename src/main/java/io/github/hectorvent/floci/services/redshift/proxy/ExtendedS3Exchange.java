package io.github.hectorvent.floci.services.redshift.proxy;

import io.github.hectorvent.floci.services.s3.S3Service;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;

final class ExtendedS3Exchange {

    private record CopyResult(boolean succeeded, byte[] deferredReadyForQuery) {
    }

    private ExtendedS3Exchange() {
    }

    static void execute(Socket client, Socket backend,
            PostgresWireDecoder.FrontendMessage executeFrame,
            CopyStatementParser.S3Statement statement, S3Service s3Service,
            BackendResponseCoordinator coordinator, BackendResponseCoordinator.Ticket ticket) throws IOException {
        BackendResponseCoordinator.GateResult gate;
        try {
            gate = coordinator.awaitExtendedExecuteTurn(ticket);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while awaiting the Extended Query Execute turn", e);
        }
        if (gate != BackendResponseCoordinator.GateResult.READY) {
            return;
        }

        boolean failed = true;
        byte[] deferredReadyForQuery = null;
        try {
            switch (statement) {
                case CopyStatementParser.S3CopyFrom copy -> {
                    CopyResult result = runCopy(client, backend, executeFrame, copy, s3Service, coordinator);
                    failed = !result.succeeded();
                    deferredReadyForQuery = result.deferredReadyForQuery();
                }
                case CopyStatementParser.S3Unload unload -> failed = !runUnload(
                        client, backend, executeFrame, unload, s3Service, coordinator);
            }
        } catch (IOException | RuntimeException e) {
            closeQuietly(client);
            closeQuietly(backend);
            throw e;
        } finally {
            coordinator.completeOwnedExecute(ticket, failed);
        }
        if (deferredReadyForQuery != null) {
            coordinator.onBackendFrame('Z', deferredReadyForQuery);
        }
    }

    private static CopyResult runCopy(Socket client, Socket backend,
            PostgresWireDecoder.FrontendMessage executeFrame,
            CopyStatementParser.S3CopyFrom spec, S3Service s3Service,
            BackendResponseCoordinator coordinator) throws IOException {
        OutputStream backendOut = backend.getOutputStream();
        backendOut.write(executeFrame.toPacketBytes());
        backendOut.flush();
        PostgresWireDecoder.FrontendMessage sync = readClientSync(client, backendOut);

        PostgresWireDecoder decoder = new PostgresWireDecoder(backend.getInputStream());
        PostgresWireDecoder.FrontendMessage first = nextOwnedFrame(client, decoder, coordinator);
        if (first.type() == 'E') {
            forward(client, first);
            forwardClientSyncToBackend(sync, backendOut, coordinator);
            return new CopyResult(false, null);
        }
        if (first.type() != 'G') {
            throw unexpected(first, "CopyInResponse");
        }

        S3CopySimulator.CopyInput input;
        try {
            input = S3CopySimulator.prepareCopy(spec, s3Service);
        } catch (S3CopySimulator.S3TransferException e) {
            S3CopySimulator.writeCopyFail(backendOut, e.getMessage());
            forwardClientSyncToBackend(sync, backendOut, coordinator);
            drainExecute(client, decoder, coordinator, false);
            sendError(client, e.sqlState(), e.getMessage());
            return new CopyResult(false, drainReadyForQuery(client, decoder, coordinator));
        }

        try {
            S3CopySimulator.streamCopyInput(input, backendOut);
            backendOut.write(new byte[]{'c', 0, 0, 0, 4});
            backendOut.flush();
        } catch (RuntimeException | IOException e) {
            S3CopySimulator.writeCopyFail(backendOut, e.getMessage());
            forwardClientSyncToBackend(sync, backendOut, coordinator);
            drainExecute(client, decoder, coordinator, false);
            String detail = e.getMessage() != null ? e.getMessage() : e.toString();
            sendError(client, "XX000", "S3 COPY failed: " + detail);
            return new CopyResult(false, drainReadyForQuery(client, decoder, coordinator));
        }

        forwardClientSyncToBackend(sync, backendOut, coordinator);
        PostgresWireDecoder.FrontendMessage terminal = drainExecute(client, decoder, coordinator, true);
        return new CopyResult(terminal.type() != 'E', null);
    }

    private static boolean runUnload(Socket client, Socket backend,
            PostgresWireDecoder.FrontendMessage executeFrame,
            CopyStatementParser.S3Unload spec, S3Service s3Service,
            BackendResponseCoordinator coordinator) throws IOException {
        OutputStream backendOut = backend.getOutputStream();
        backendOut.write(executeFrame.toPacketBytes());
        backendOut.flush();
        forwardClientSyncToBackend(client, backendOut, coordinator);

        PostgresWireDecoder decoder = new PostgresWireDecoder(backend.getInputStream());
        PostgresWireDecoder.FrontendMessage first = nextOwnedFrame(client, decoder, coordinator);
        if (first.type() == 'E') {
            forward(client, first);
            return false;
        }
        if (first.type() != 'H') {
            throw unexpected(first, "CopyOutResponse");
        }

        S3CopySimulator.UnloadCollector collector;
        try {
            collector = S3CopySimulator.prepareUnload(spec, s3Service);
        } catch (S3CopySimulator.S3TransferException e) {
            S3CopySimulator.writeCopyFail(backendOut, e.getMessage());
            drainExecute(client, decoder, coordinator, false);
            sendError(client, e.sqlState(), e.getMessage());
            return false;
        }

        try (collector) {
            while (true) {
                PostgresWireDecoder.FrontendMessage message = nextOwnedFrame(client, decoder, coordinator);
                if (message.type() == 'd') {
                    try {
                        collector.accept(message.body());
                    } catch (S3CopySimulator.S3TransferException e) {
                        collector.abort();
                        S3CopySimulator.writeCopyFail(backendOut, e.getMessage());
                        drainExecute(client, decoder, coordinator, false);
                        sendError(client, e.sqlState(), e.getMessage());
                        return false;
                    }
                } else if (message.type() == 'c') {
                    continue;
                } else if (message.type() == 'E') {
                    collector.abort();
                    forward(client, message);
                    return false;
                } else if (message.type() == 'C') {
                    try {
                        collector.complete();
                    } catch (S3CopySimulator.S3TransferException e) {
                        S3CopySimulator.writeCopyFail(backendOut, e.getMessage());
                        sendError(client, e.sqlState(), e.getMessage());
                        return false;
                    }
                    forward(client, message);
                    return true;
                } else {
                    throw unexpected(message, "CopyData, CopyDone, or CommandComplete");
                }
            }
        }
    }

    private static PostgresWireDecoder.FrontendMessage drainExecute(Socket client,
            PostgresWireDecoder decoder, BackendResponseCoordinator coordinator,
            boolean forwardTerminal) throws IOException {
        while (true) {
            PostgresWireDecoder.FrontendMessage message = nextOwnedFrame(client, decoder, coordinator);
            char type = message.type();
            if (type == 'C' || type == 'E' || type == 'I' || type == 's') {
                if (forwardTerminal) {
                    forward(client, message);
                }
                return message;
            }
            if (type != 'd' && type != 'c') {
                throw unexpected(message, "CopyData, CopyDone, or an Execute terminal response");
            }
        }
    }

    private static byte[] drainReadyForQuery(Socket client, PostgresWireDecoder decoder,
            BackendResponseCoordinator coordinator) throws IOException {
        PostgresWireDecoder.FrontendMessage message = nextOwnedFrame(client, decoder, coordinator);
        if (message.type() != 'Z') {
            throw unexpected(message, "ReadyForQuery");
        }
        forward(client, message);
        return message.body();
    }

    private static PostgresWireDecoder.FrontendMessage nextOwnedFrame(Socket client,
            PostgresWireDecoder decoder, BackendResponseCoordinator coordinator) throws IOException {
        while (true) {
            PostgresWireDecoder.FrontendMessage message = decoder.nextMessage();
            if (message == null) {
                throw new IOException("Backend closed during an Extended Query S3 exchange");
            }
            char type = message.type();
            if (type == 'N' || type == 'A' || type == 'S') {
                coordinator.onBackendFrame(type, message.body());
                forward(client, message);
                continue;
            }
            if (type == '1' || type == '2' || type == 'T' || type == 't' || type == 'n') {
                coordinator.onBackendFrame(type, message.body());
                forward(client, message);
                continue;
            }
            return message;
        }
    }

    private static PostgresWireDecoder.FrontendMessage readClientSync(Socket client, OutputStream backendOut)
            throws IOException {
        PostgresWireDecoder decoder = new PostgresWireDecoder(client.getInputStream());
        while (true) {
            PostgresWireDecoder.FrontendMessage message = decoder.nextMessage();
            if (message == null) {
                throw new IOException("Client closed during an Extended Query S3 exchange");
            }
            if (message.type() == 'S') {
                return message;
            }
            if (message.type() != 'H') {
                throw new IOException("Expected Flush or Sync after an Extended Query S3 Execute");
            }
            backendOut.write(message.toPacketBytes());
            backendOut.flush();
        }
    }

    private static void forwardClientSyncToBackend(Socket client, OutputStream backendOut,
            BackendResponseCoordinator coordinator) throws IOException {
        forwardClientSyncToBackend(readClientSync(client), backendOut, coordinator);
    }

    private static void forwardClientSyncToBackend(PostgresWireDecoder.FrontendMessage sync, OutputStream backendOut,
            BackendResponseCoordinator coordinator) throws IOException {
        coordinator.register(BackendResponseCoordinator.Operation.SYNC, null);
        backendOut.write(sync.toPacketBytes());
        backendOut.flush();
    }

    private static IOException unexpected(PostgresWireDecoder.FrontendMessage message, String expected) {
        return new IOException("Expected " + expected + " but backend sent " + message.type());
    }

    private static void sendError(Socket client, String sqlState, String message) throws IOException {
        forward(client, new PostgresWireDecoder.FrontendMessage(
                'E', S3CopySimulator.errorBody(sqlState, message)));
    }

    private static void forward(Socket client, PostgresWireDecoder.FrontendMessage message) throws IOException {
        OutputStream out = client.getOutputStream();
        out.write(message.toPacketBytes());
        out.flush();
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // The original protocol failure is more useful than a secondary socket-close failure.
        }
    }
}
