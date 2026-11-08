package io.floci.aws.core.common;

import io.floci.aws.testutil.LogCapture;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClosedException;
import io.vertx.core.http.HttpConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebSocketUpgradesTest {

    @Mock
    HttpClientRequest request;

    @Mock
    HttpConnection connection;

    @Captor
    ArgumentCaptor<Handler<Throwable>> exceptionHandler;

    @BeforeEach
    void configureConnection() {
        when(request.connection()).thenReturn(connection);
        when(connection.close()).thenReturn(Future.succeededFuture());
    }

    @Test
    void deliberateCloseDoesNotWarnForExpectedClosedConnection() {
        List<LogRecord> records = LogCapture.capture(WebSocketUpgrades.class, () -> {
            WebSocketUpgrades.closeRefusedRequest(request);
            verify(request).exceptionHandler(exceptionHandler.capture());
            exceptionHandler.getValue().handle(new HttpClosedException("Connection was closed"));
        });

        verify(connection).close();
        assertTrue(records.stream().allMatch(record -> record.getLevel().intValue() < Level.WARNING.intValue()));
    }

    @Test
    void unexpectedRequestFailureKeepsItsWarningAndCause() {
        IllegalStateException failure = new IllegalStateException("unexpected transport failure");
        List<LogRecord> records = LogCapture.capture(WebSocketUpgrades.class, () -> {
            WebSocketUpgrades.closeRefusedRequest(request);
            verify(request).exceptionHandler(exceptionHandler.capture());
            exceptionHandler.getValue().handle(failure);
        });

        assertEquals(1, records.size());
        assertEquals(Level.WARNING, records.getFirst().getLevel());
        assertSame(failure, records.getFirst().getThrown());
    }

    @Test
    void failedConnectionCloseKeepsItsWarningAndCause() {
        IllegalStateException failure = new IllegalStateException("close failed");
        when(connection.close()).thenReturn(Future.failedFuture(failure));

        List<LogRecord> records = LogCapture.capture(WebSocketUpgrades.class,
                () -> WebSocketUpgrades.closeRefusedRequest(request));

        assertEquals(1, records.size());
        assertEquals(Level.WARNING, records.getFirst().getLevel());
        assertSame(failure, records.getFirst().getThrown());
    }
}
