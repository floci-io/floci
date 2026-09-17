package io.github.hectorvent.floci.services.iot;

import io.vertx.core.Handler;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IotMqttWebSocketBridgeTest {

    @Test
    void brokerCloseAndExceptionCloseWebSocketOnlyOnce() {
        ServerWebSocket webSocket = mock(ServerWebSocket.class);
        NetSocket socket = mock(NetSocket.class);
        when(webSocket.isClosed()).thenReturn(false);

        IotMqttWebSocketBridge.BridgeSession session =
                new IotMqttWebSocketBridge.BridgeSession(webSocket, socket);
        session.start();

        ArgumentCaptor<Handler<Void>> closeCaptor = ArgumentCaptor.forClass(Handler.class);
        verify(socket).closeHandler(closeCaptor.capture());
        ArgumentCaptor<Handler<Throwable>> exceptionCaptor = ArgumentCaptor.forClass(Handler.class);
        verify(socket).exceptionHandler(exceptionCaptor.capture());

        closeCaptor.getValue().handle(null);
        exceptionCaptor.getValue().handle(new IllegalStateException("already closed"));

        verify(webSocket).close();
    }
}
