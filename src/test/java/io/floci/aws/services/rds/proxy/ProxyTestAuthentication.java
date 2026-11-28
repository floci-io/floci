package io.floci.aws.services.rds.proxy;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

record ProxyTestAuthentication(CompletableFuture<PostgresProtocolHandler.AuthenticatedSession> authentication,
                               ProxyTestTask<Void> worker) {

    static ProxyTestAuthentication start(Callable<PostgresProtocolHandler.AuthenticatedSession> authenticate) {
        CompletableFuture<PostgresProtocolHandler.AuthenticatedSession> authentication = new CompletableFuture<>();
        ProxyTestTask<Void> worker = ProxyTestTask.start(() -> {
            try {
                PostgresProtocolHandler.AuthenticatedSession session = authenticate.call();
                authentication.complete(session);
                if (session != null) {
                    PostgresProtocolHandler.bridge(session);
                }
                return null;
            } catch (Exception | Error failure) {
                authentication.completeExceptionally(failure);
                throw failure;
            }
        });
        return new ProxyTestAuthentication(authentication, worker);
    }

    PostgresProtocolHandler.AuthenticatedSession awaitAuthentication() throws Exception {
        return authentication.get(5, TimeUnit.SECONDS);
    }

    void await() throws Exception {
        worker.await();
    }

    boolean isAlive() {
        return worker.thread().isAlive();
    }
}
