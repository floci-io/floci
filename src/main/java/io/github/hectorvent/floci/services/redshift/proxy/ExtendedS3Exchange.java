package io.github.hectorvent.floci.services.redshift.proxy;

import io.github.hectorvent.floci.services.s3.S3Service;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;

final class ExtendedS3Exchange {

    private ExtendedS3Exchange() {
    }

    static void execute(Socket client, Socket backend,
            PostgresWireDecoder.FrontendMessage executeFrame,
            CopyStatementParser.S3Statement statement, S3Service s3Service,
            BackendResponseCoordinator coordinator, BackendResponseCoordinator.Ticket ticket) throws IOException {
        BackendResponseCoordinator.GateResult gate;
        try {
            gate = coordinator.awaitTurn(ticket);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while awaiting the Extended Query Execute turn", e);
        }
        if (gate != BackendResponseCoordinator.GateResult.READY) {
            return;
        }

        boolean failed = true;
        try {
            failed = !switch (statement) {
                case CopyStatementParser.S3CopyFrom copy -> runCopy(
                        client, backend, executeFrame, copy, s3Service, coordinator);
                case CopyStatementParser.S3Unload unload -> runUnload(
                        client, backend, executeFrame, unload, s3Service, coordinator);
            };
        } catch (IOException | RuntimeException e) {
            closeQuietly(client);
            closeQuietly(backend);
            throw e;
        } finally {
            coordinator.completeOwnedExecute(ticket, failed);
        }
    }

    private static boolean runCopy(Socket client, Socket backend,
            PostgresWireDecoder.FrontendMessage executeFrame,
            CopyStatementParser.S3CopyFrom spec, S3Service s3Service,
            BackendResponseCoordinator coordinator) throws IOException {
        OutputStream backendOut = backend.getOutputStream();
        backendOut.write(executeFrame.toPacketBytes());
        backendOut.flush();

        PostgresWireDecoder decoder = new PostgresWireDecoder(backend.getInputStream());
        PostgresWireDecoder.FrontendMessage first = nextOwnedFrame(client, decoder, coordinator);
        if (first.type() == 'E') {
            forward(client, first);
            return false;
        }
        if (first.type() != 'G') {
            throw unexpected(first, "CopyInResponse");
        }

        S3CopySimulator.CopyInput input;
        try {
            input = S3CopySimulator.prepareCopy(spec, s3Service);
        } catch (S3CopySimulator.S3TransferException e) {
            S3CopySimulator.writeCopyFail(backendOut, e.getMessage());
            drainExecute(client, decoder, coordinator, false);
            sendError(client, e.sqlState(), e.getMessage());
            return false;
        }

        try {
            S3CopySimulator.streamCopyInput(input, backendOut);
            backendOut.write(new byte[]{'c', 0, 0, 0, 4});
            backendOut.flush();
        } catch (RuntimeException | IOException e) {
            S3CopySimulator.writeCopyFail(backendOut, e.getMessage());
            drainExecute(client, decoder, coordinator, false);
            String detail = e.getMessage() != null ? e.getMessage() : e.toString();
            sendError(client, "XX000", "S3 COPY failed: " + detail);
            return false;
        }

        PostgresWireDecoder.FrontendMessage terminal = drainExecute(client, decoder, coordinator, true);
        return terminal.type() != 'E';
    }

    private static boolean runUnload(Socket client, Socket backend,
            PostgresWireDecoder.FrontendMessage executeFrame,
            CopyStatementParser.S3Unload spec, S3Service s3Service,
            BackendResponseCoordinator coordinator) throws IOException {
        OutputStream backendOut = backend.getOutputStream();
        backendOut.write(executeFrame.toPacketBytes());
        backendOut.flush();

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
            return message;
        }
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
