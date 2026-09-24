package io.github.hectorvent.floci.services.redshift.proxy;

import io.github.hectorvent.floci.services.redshift.spectrum.SpectrumInterceptor;
import io.github.hectorvent.floci.services.redshift.spectrum.SpectrumMaterializer;
import io.github.hectorvent.floci.services.redshift.spectrum.SpectrumQuery;
import io.github.hectorvent.floci.services.redshift.spectrum.SpectrumSession;
import io.github.hectorvent.floci.services.redshift.spectrum.BackendSql;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExtendedSpectrumExchangeTest {
    private Socket client;
    private Socket clientPeer;
    private Socket backend;
    private Socket backendPeer;
    private ServerSocket clientListener;
    private ServerSocket backendListener;

    @AfterEach
    void closeSockets() throws IOException {
        for (Socket socket : new Socket[]{client, clientPeer, backend, backendPeer}) {
            if (socket != null) {
                socket.close();
            }
        }
        if (clientListener != null) {
            clientListener.close();
        }
        if (backendListener != null) {
            backendListener.close();
        }
    }

    @Test
    @Timeout(10)
    void executeDeliversRowsAcrossPortalSuspensions() throws Exception {
        openSocketPairs();
        SpectrumInterceptor interceptor = mock(SpectrumInterceptor.class);
        SpectrumSession session = new SpectrumSession("000000000000", "cluster", "dev", List.of(), false);
        SpectrumMaterializer.Materialization materialization = new SpectrumMaterializer.Materialization("tmp", List.of());
        SpectrumInterceptor.Plan plan = new SpectrumInterceptor.Plan.PhaseOneQuery(
                new SpectrumQuery(null, "events", "*", null, true), null, "tmp");
        when(interceptor.execute(eq(plan), eq(session), any()))
                .thenReturn(new SpectrumInterceptor.Decision.Rewritten("SELECT * FROM \"tmp\"", materialization));
        BackendResponseCoordinator coordinator = new BackendResponseCoordinator(new ExtendedQuerySession());
        Map<String, ExtendedSpectrumExchange.PortalCursor> cursors = new HashMap<>();
        AtomicReference<Throwable> backendFailure = new AtomicReference<>();
        Thread backendThread = Thread.ofVirtual().start(() -> {
            try {
                PostgresWireDecoder decoder = new PostgresWireDecoder(backendPeer.getInputStream());
                assertEquals("SELECT * FROM \"tmp\"", decoder.nextMessage().getSql());
                backendPeer.getOutputStream().write(frame('T', new byte[0]));
                backendPeer.getOutputStream().write(frame('D', row("1")));
                backendPeer.getOutputStream().write(frame('D', row("2")));
                backendPeer.getOutputStream().write(frame('D', row("3")));
                backendPeer.getOutputStream().write(frame('C', cString("SELECT 3")));
                backendPeer.getOutputStream().write(frame('Z', new byte[]{'I'}));
                backendPeer.getOutputStream().flush();
            } catch (Throwable failure) {
                backendFailure.set(failure);
            }
        });

        execute(interceptor, session, plan, coordinator, cursors, 2);
        PostgresWireDecoder clientDecoder = new PostgresWireDecoder(clientPeer.getInputStream());
        assertEquals('D', clientDecoder.nextMessage().type());
        assertEquals('D', clientDecoder.nextMessage().type());
        assertEquals('s', clientDecoder.nextMessage().type());

        execute(interceptor, session, plan, coordinator, cursors, 2);
        assertEquals('D', clientDecoder.nextMessage().type());
        PostgresWireDecoder.FrontendMessage complete = clientDecoder.nextMessage();
        assertEquals('C', complete.type());
        assertEquals("SELECT 3", new String(complete.body(), StandardCharsets.UTF_8).replace("\0", ""));
        backendThread.join();

        assertEquals(null, backendFailure.get());
        assertEquals(0, cursors.size());
        verify(interceptor, times(1)).execute(eq(plan), eq(session), any());
        verify(interceptor).cleanup(any(BackendSql.class), eq(materialization));
    }

    @Test
    void extendedExternalDdlCompletesLocally() throws Exception {
        openSocketPairs();
        SpectrumInterceptor interceptor = mock(SpectrumInterceptor.class);
        SpectrumSession session = new SpectrumSession("000000000000", "cluster", "dev", List.of(), false);
        SpectrumInterceptor.Plan plan = new SpectrumInterceptor.Plan.Forward();
        when(interceptor.execute(eq(plan), eq(session), any()))
                .thenReturn(new SpectrumInterceptor.Decision.Handled("CREATE SCHEMA"));
        BackendResponseCoordinator coordinator = new BackendResponseCoordinator(new ExtendedQuerySession());
        Map<String, ExtendedSpectrumExchange.PortalCursor> cursors = new HashMap<>();

        execute(interceptor, session, plan, coordinator, cursors, 0);

        PostgresWireDecoder.FrontendMessage complete = new PostgresWireDecoder(clientPeer.getInputStream()).nextMessage();
        assertEquals('C', complete.type());
        assertEquals("CREATE SCHEMA", new String(complete.body(), StandardCharsets.UTF_8).replace("\0", ""));
    }

    @Test
    @Timeout(10)
    void backendQueryErrorPreservesSqlStateAndCleansMaterialization() throws Exception {
        openSocketPairs();
        SpectrumInterceptor interceptor = mock(SpectrumInterceptor.class);
        SpectrumSession session = new SpectrumSession("000000000000", "cluster", "dev", List.of(), false);
        SpectrumMaterializer.Materialization materialization = new SpectrumMaterializer.Materialization("tmp", List.of());
        SpectrumInterceptor.Plan plan = new SpectrumInterceptor.Plan.PhaseOneQuery(
                new SpectrumQuery(null, "events", "id", null, false), null, "tmp");
        when(interceptor.execute(eq(plan), eq(session), any()))
                .thenReturn(new SpectrumInterceptor.Decision.Rewritten("SELECT missing FROM \"tmp\"", materialization));
        BackendResponseCoordinator coordinator = new BackendResponseCoordinator(new ExtendedQuerySession());
        Map<String, ExtendedSpectrumExchange.PortalCursor> cursors = new HashMap<>();
        Thread backendThread = Thread.ofVirtual().start(() -> {
            try {
                PostgresWireDecoder decoder = new PostgresWireDecoder(backendPeer.getInputStream());
                assertEquals("SELECT missing FROM \"tmp\"", decoder.nextMessage().getSql());
                backendPeer.getOutputStream().write(frame('E', postgresError("42703", "column does not exist")));
                backendPeer.getOutputStream().write(frame('Z', new byte[]{'I'}));
                backendPeer.getOutputStream().flush();
            } catch (IOException failure) {
                throw new AssertionError(failure);
            }
        });

        execute(interceptor, session, plan, coordinator, cursors, 0);

        PostgresWireDecoder.FrontendMessage error = new PostgresWireDecoder(clientPeer.getInputStream()).nextMessage();
        assertEquals('E', error.type());
        assertTrue(new String(error.body(), StandardCharsets.UTF_8).contains("42703"));
        assertEquals(0, cursors.size());
        backendThread.join();
        verify(interceptor).cleanup(any(BackendSql.class), eq(materialization));
    }

    private void execute(SpectrumInterceptor interceptor, SpectrumSession session, SpectrumInterceptor.Plan plan,
                         BackendResponseCoordinator coordinator,
                         Map<String, ExtendedSpectrumExchange.PortalCursor> cursors, int maxRows) throws IOException {
        BackendResponseCoordinator.Ticket ticket = coordinator.register(BackendResponseCoordinator.Operation.EXECUTE, null);
        ExtendedSpectrumExchange.execute(client, backend, plan, interceptor, session, coordinator, ticket,
                "portal", maxRows, cursors);
    }

    private void openSocketPairs() throws IOException {
        clientListener = new ServerSocket(0);
        clientPeer = new Socket("localhost", clientListener.getLocalPort());
        client = clientListener.accept();
        backendListener = new ServerSocket(0);
        backend = new Socket("localhost", backendListener.getLocalPort());
        backendPeer = backendListener.accept();
    }

    private static byte[] row(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[6 + bytes.length];
        body[1] = 1;
        body[5] = (byte) bytes.length;
        System.arraycopy(bytes, 0, body, 6, bytes.length);
        return body;
    }

    private static byte[] cString(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, result, 0, bytes.length);
        return result;
    }

    private static byte[] frame(char type, byte[] body) {
        int length = body.length + 4;
        byte[] packet = new byte[body.length + 5];
        packet[0] = (byte) type;
        packet[1] = (byte) (length >>> 24);
        packet[2] = (byte) (length >>> 16);
        packet[3] = (byte) (length >>> 8);
        packet[4] = (byte) length;
        System.arraycopy(body, 0, packet, 5, body.length);
        return packet;
    }

    private static byte[] postgresError(String sqlState, String message) {
        byte[] severity = new byte[]{'S', 'E', 'R', 'R', 'O', 'R', 0};
        byte[] code = new byte[sqlState.length() + 2];
        code[0] = 'C';
        System.arraycopy(sqlState.getBytes(StandardCharsets.US_ASCII), 0, code, 1, sqlState.length());
        byte[] text = new byte[message.length() + 2];
        text[0] = 'M';
        System.arraycopy(message.getBytes(StandardCharsets.UTF_8), 0, text, 1, message.length());
        text[text.length - 1] = 0;
        byte[] body = new byte[severity.length + code.length + text.length + 1];
        int offset = 0;
        System.arraycopy(severity, 0, body, offset, severity.length);
        offset += severity.length;
        System.arraycopy(code, 0, body, offset, code.length);
        offset += code.length;
        System.arraycopy(text, 0, body, offset, text.length);
        return body;
    }
}
