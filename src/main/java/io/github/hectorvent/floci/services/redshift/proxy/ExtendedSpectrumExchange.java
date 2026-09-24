package io.github.hectorvent.floci.services.redshift.proxy;

import io.github.hectorvent.floci.services.redshift.spectrum.PostgresBackendSession;
import io.github.hectorvent.floci.services.redshift.spectrum.SpectrumInterceptor;
import io.github.hectorvent.floci.services.redshift.spectrum.SpectrumReadException;
import io.github.hectorvent.floci.services.redshift.spectrum.SpectrumSession;
import io.github.hectorvent.floci.services.redshift.spectrum.SpectrumSqlException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

final class ExtendedSpectrumExchange {
    private ExtendedSpectrumExchange() { }

    static void execute(Socket client, Socket backend, SpectrumInterceptor.Plan plan,
                        SpectrumInterceptor interceptor, SpectrumSession session,
                        BackendResponseCoordinator coordinator, BackendResponseCoordinator.Ticket ticket) throws IOException {
        boolean failed = true;
        try {
            SpectrumInterceptor.Decision decision = interceptor.execute(plan, session, new PostgresBackendSession(backend));
            if (decision instanceof SpectrumInterceptor.Decision.Handled handled) {
                forward(client, new PostgresWireDecoder.FrontendMessage('C', (handled.commandTag() + '\0').getBytes(StandardCharsets.UTF_8)));
                failed = false;
                return;
            }
            throw new SpectrumSqlException("0A000", "Spectrum plan did not produce an executable statement");
        } catch (RuntimeException exception) {
            String state = exception instanceof SpectrumSqlException sql ? sql.sqlState()
                    : exception instanceof SpectrumReadException read ? read.sqlState() : "22023";
            forward(client, new PostgresWireDecoder.FrontendMessage('E', S3CopySimulator.errorBody(state, exception.getMessage())));
        } finally {
            coordinator.completeOwnedExecute(ticket, failed);
        }
    }

    private static void forward(Socket client, PostgresWireDecoder.FrontendMessage message) throws IOException {
        OutputStream output = client.getOutputStream();
        output.write(message.toPacketBytes());
        output.flush();
    }
}
