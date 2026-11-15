package io.floci.aws.services.redshift.proxy;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.s3.S3Service;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class ExtendedS3Exchange {

    private ExtendedS3Exchange() {
    }

    static void execute(Socket client, Socket backend,
            PostgresWireDecoder.FrontendMessage executeFrame,
            CopyStatementParser.S3Statement statement, S3Service s3Service, IamService iamService,
            String clusterAccountId, List<String> iamRoleArns, BackendResponseCoordinator coordinator,
            BackendResponseCoordinator.Ticket ticket, CopyBackendSession copyBackend) throws IOException {
        execute(client, backend, executeFrame, statement, s3Service, iamService, clusterAccountId, iamRoleArns,
                coordinator, ticket, copyBackend, null);
    }

    static void execute(Socket client, Socket backend,
            PostgresWireDecoder.FrontendMessage executeFrame,
            CopyStatementParser.S3Statement statement, S3Service s3Service, IamService iamService,
            String clusterAccountId, List<String> iamRoleArns, BackendResponseCoordinator coordinator,
            BackendResponseCoordinator.Ticket ticket, CopyBackendSession copyBackend,
            RedshiftParquetWriter parquetWriter) throws IOException {
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
        try {
            failed = !switch (statement) {
                case CopyStatementParser.S3CopyJobRun run -> runEtlCopy(client, backend, executeFrame, run,
                        s3Service, iamService, clusterAccountId, iamRoleArns, coordinator, copyBackend);
                case CopyStatementParser.S3CopyFrom copy -> runEtlCopy(client, backend, executeFrame, copy,
                        s3Service, iamService, clusterAccountId, iamRoleArns, coordinator, copyBackend);
                case CopyStatementParser.S3Unload unload -> runOwnedUnload(client, backend, executeFrame, unload,
                        s3Service, iamService, clusterAccountId, iamRoleArns, coordinator, ticket, copyBackend, parquetWriter);
            };
        } catch (IOException | RuntimeException e) {
            closeQuietly(client);
            closeQuietly(backend);
            throw e;
        } finally {
            coordinator.completeOwnedExecute(ticket, failed);
        }
    }

    private static boolean runOwnedUnload(Socket client, Socket backend,
            PostgresWireDecoder.FrontendMessage executeFrame, CopyStatementParser.S3Unload spec,
            S3Service s3, IamService iam, String account, List<String> roles,
            BackendResponseCoordinator coordinator, BackendResponseCoordinator.Ticket ticket,
            CopyBackendSession copyBackend, RedshiftParquetWriter parquetWriter) throws IOException {
        OutputStream output = backend.getOutputStream();
        List<RedshiftParquetWriter.Column> columns = List.of();
        if (spec.parquet()) {
            output.write(new byte[]{'H', 0, 0, 0, 4});
            output.flush();
            PostgresWireDecoder preamble = new PostgresWireDecoder(backend.getInputStream());
            while (!coordinator.isFirstPending(ticket)) {
                PostgresWireDecoder.FrontendMessage message = preamble.nextMessage();
                if (message == null) {
                    throw new IOException("Backend closed before Parquet UNLOAD description");
                }
                coordinator.onBackendFrame(message.type(), message.body());
                forward(client, message);
                if (message.type() == 'E') {
                    copyBackend.onBackendError();
                    return false;
                }
            }
            RedshiftParquetWriter.Description description = RedshiftParquetWriter.describe(client, backend, spec.selectQuery());
            if (description.error() != null) {
                copyBackend.onBackendError();
                forward(client, description.error());
                return false;
            }
            columns = description.columns();
        }
        output.write(executeFrame.toPacketBytes());
        output.write(new byte[]{'H', 0, 0, 0, 4});
        output.flush();
        PostgresWireDecoder decoder = new PostgresWireDecoder(backend.getInputStream());
        PostgresWireDecoder.FrontendMessage first = nextOwnedFrame(client, decoder, coordinator);
        if (first.type() == 'E') {
            copyBackend.onBackendError();
            forward(client, first);
            return false;
        }
        if (first.type() != 'H') {
            throw unexpected(first, "CopyOutResponse");
        }
        S3CopySimulator.UnloadCollector collector = null;
        S3CopySimulator.S3TransferException failure = null;
        try {
            try {
                collector = S3CopySimulator.prepareUnload(spec, s3, iam, account, roles, parquetWriter);
                collector.columns(columns);
            } catch (S3CopySimulator.S3TransferException exception) {
                failure = exception;
            }
            while (true) {
                PostgresWireDecoder.FrontendMessage response = nextOwnedFrame(client, decoder, coordinator);
                if (response.type() == 'd' && failure == null && collector != null) {
                    try {
                        collector.accept(response.body());
                    } catch (S3CopySimulator.S3TransferException exception) {
                        failure = exception;
                        collector.abort();
                    }
                } else if (response.type() == 'E') {
                    copyBackend.onBackendError();
                    forward(client, response);
                    return false;
                } else if (response.type() == 'C') {
                    if (failure == null && collector != null) {
                        try {
                            collector.complete();
                        } catch (S3CopySimulator.S3TransferException exception) {
                            failure = exception;
                        }
                    }
                    if (failure == null) {
                        forward(client, response);
                        return true;
                    }
                    copyBackend.begin(coordinator.lastReadyStatus() != 'I');
                    copyBackend.failTransaction();
                    sendError(client, failure.sqlState(), failure.getMessage());
                    return false;
                }
            }
        } finally {
            if (collector != null) {
                if (failure != null) {
                    collector.abort();
                }
                collector.close();
            }
        }
    }

    private static boolean runEtlCopy(Socket client, Socket backend,
            PostgresWireDecoder.FrontendMessage executeFrame, CopyStatementParser.S3Statement spec,
            S3Service s3, IamService iam, String account, List<String> roles,
            BackendResponseCoordinator coordinator, CopyBackendSession copyBackend) throws IOException {
        OutputStream output = backend.getOutputStream();
        output.write(executeFrame.toPacketBytes());
        output.write(new byte[]{'H', 0, 0, 0, 4});
        output.flush();
        PostgresWireDecoder decoder = new PostgresWireDecoder(backend.getInputStream());
        PostgresWireDecoder.FrontendMessage response = nextOwnedFrame(client, decoder, coordinator);
        if (response.type() == 'E') {
            copyBackend.onBackendError();
            forward(client, response);
            return false;
        }
        if (response.type() != 'C') {
            throw unexpected(response, "COPY execution placeholder completion");
        }
        try {
            long count = CopyEtlLoader.execute(copyBackend, spec, s3, iam, account, roles,
                    coordinator.lastReadyStatus() != 'I');
            forward(client, new PostgresWireDecoder.FrontendMessage('C',
                    ("COPY " + count + "\0").getBytes(StandardCharsets.UTF_8)));
            return true;
        } catch (S3CopySimulator.S3TransferException exception) {
            sendError(client, exception.sqlState(), exception.getMessage());
            return false;
        } catch (AwsException exception) {
            sendError(client, "42501", "S3 COPY access denied");
            return false;
        } catch (RuntimeException exception) {
            sendError(client, "XX000", "S3 COPY execution failed");
            return false;
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
            if (type == '1' || type == '2' || type == 'T' || type == 't' || type == 'n') {
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
