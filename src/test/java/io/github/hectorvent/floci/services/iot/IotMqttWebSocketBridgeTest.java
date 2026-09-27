package io.github.hectorvent.floci.services.iot;

import io.vertx.core.Handler;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        verify(webSocket, never()).close(anyShort(), anyString());
    }

    @Test
    void brokerExceptionAndCloseCloseWebSocketOnlyOnce() {
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

        exceptionCaptor.getValue().handle(new IllegalStateException("already closed"));
        closeCaptor.getValue().handle(null);

        verify(webSocket).close(anyShort(), anyString());
        verify(webSocket, never()).close();
    }

    @Test
    void webSocketExceptionAndCloseCloseBrokerSocketOnlyOnce() {
        ServerWebSocket webSocket = mock(ServerWebSocket.class);
        NetSocket socket = mock(NetSocket.class);
        when(webSocket.isClosed()).thenReturn(false);

        IotMqttWebSocketBridge.BridgeSession session =
                new IotMqttWebSocketBridge.BridgeSession(webSocket, socket);
        session.start();

        ArgumentCaptor<Handler<Throwable>> exceptionCaptor = ArgumentCaptor.forClass(Handler.class);
        verify(webSocket).exceptionHandler(exceptionCaptor.capture());
        ArgumentCaptor<Handler<Void>> closeCaptor = ArgumentCaptor.forClass(Handler.class);
        verify(webSocket).closeHandler(closeCaptor.capture());

        exceptionCaptor.getValue().handle(new IllegalStateException("ws error"));
        closeCaptor.getValue().handle(null);

        verify(socket).close();
        verify(webSocket).close((short) 1011, "MQTT WebSocket failed");
        verify(webSocket, never()).close();
    }

    @Test
    void webSocketCloseAndExceptionCloseBrokerSocketOnlyOnce() {
        ServerWebSocket webSocket = mock(ServerWebSocket.class);
        NetSocket socket = mock(NetSocket.class);
        when(webSocket.isClosed()).thenReturn(false);

        IotMqttWebSocketBridge.BridgeSession session =
                new IotMqttWebSocketBridge.BridgeSession(webSocket, socket);
        session.start();

        ArgumentCaptor<Handler<Void>> closeCaptor = ArgumentCaptor.forClass(Handler.class);
        verify(webSocket).closeHandler(closeCaptor.capture());
        ArgumentCaptor<Handler<Throwable>> exceptionCaptor = ArgumentCaptor.forClass(Handler.class);
        verify(webSocket).exceptionHandler(exceptionCaptor.capture());

        closeCaptor.getValue().handle(null);
        exceptionCaptor.getValue().handle(new IllegalStateException("ws error"));

        verify(socket).close();
        verify(webSocket, never()).close();
        verify(webSocket, never()).close(anyShort(), anyString());
    }
}
