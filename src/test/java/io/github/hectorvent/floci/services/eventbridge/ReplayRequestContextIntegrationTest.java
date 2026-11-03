package io.github.hectorvent.floci.services.eventbridge;

import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.eventbridge.model.ArchivedEvent;
import io.github.hectorvent.floci.services.eventbridge.model.Replay;
import io.github.hectorvent.floci.services.eventbridge.model.ReplayState;
import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.common.vertx.VertxContext;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ReplayRequestContextIntegrationTest {

    @Inject
    Vertx vertx;

    @Inject
    ReplayDispatcher dispatcher;

    @Inject
    RequestContext requestContext;

    @Test
    void replayDeliveryOutlivesTheInitiatingRequest() throws Exception {
        String account = "111122223333";
        Context origin = VertxContext.createNewDuplicatedContext(vertx.getOrCreateContext());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<String> delivered = new CompletableFuture<>();
        CompletableFuture<Void> started = new CompletableFuture<>();
        CompletableFuture<Void> terminated = new CompletableFuture<>();
        CompletableFuture<ReplayState> terminal = new CompletableFuture<>();
        Replay replay = new Replay();
        replay.setReplayName("request-lifetime");
        replay.setAccountId(account);
        replay.setReplayArn("arn:aws-cn:events:cn-north-1:" + account + ":replay/request-lifetime");
        replay.setEventStartTime(Instant.EPOCH);
        replay.setEventEndTime(Instant.MAX);
        ArchivedEvent event = new ArchivedEvent();
        event.setEventTime(Instant.EPOCH.plusSeconds(1));
        origin.runOnContext(ignored -> {
            try {
                Arc.container().requestContext().activate();
                requestContext.setAccountId("999900001111");
                requestContext.setRegion("us-west-2");
                dispatcher.dispatch(replay, List.of(event), events -> {
                    try {
                        assertEquals(account, requestContext.getAccountId());
                        RequestScopes.runAs(account, () -> {
                            entered.countDown();
                            try {
                                assertTrue(release.await(10, TimeUnit.SECONDS));
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError(e);
                            }
                            delivered.complete(requestContext.getAccountId() + "|" + requestContext.getRegion()
                                    + "|" + requestContext.getPartition());
                        });
                    } catch (Throwable failure) {
                        delivered.completeExceptionally(failure);
                        throw new IllegalStateException(failure);
                    }
                }, (name, state) -> {
                    if (state == ReplayState.COMPLETED || state == ReplayState.FAILED) {
                        terminal.complete(state);
                    }
                }, ignoredTime -> { });
                started.complete(null);
            } catch (Throwable failure) {
                started.completeExceptionally(failure);
            }
        });
        try {
            started.get(10, TimeUnit.SECONDS);
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            origin.runOnContext(ignored -> {
                try {
                    Arc.container().requestContext().terminate();
                    terminated.complete(null);
                } catch (Throwable failure) {
                    terminated.completeExceptionally(failure);
                } finally {
                    release.countDown();
                }
            });
            terminated.get(10, TimeUnit.SECONDS);
            assertEquals(account + "|cn-north-1|aws-cn", delivered.get(10, TimeUnit.SECONDS));
            assertEquals(ReplayState.COMPLETED, terminal.get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            CompletableFuture<Void> cleaned = new CompletableFuture<>();
            origin.runOnContext(ignored -> {
                try {
                    if (Arc.container().requestContext().isActive()) {
                        Arc.container().requestContext().terminate();
                    }
                    cleaned.complete(null);
                } catch (Throwable failure) {
                    cleaned.completeExceptionally(failure);
                }
            });
            cleaned.get(10, TimeUnit.SECONDS);
        }
    }
}
