package io.github.hectorvent.floci.services.cognito;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.ReservedTags;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cognito.model.CognitoUser;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import io.github.hectorvent.floci.services.cognito.model.UserPoolClient;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CognitoSignUpCreationScopeTest {
    private static final String FIRST_ACCOUNT = "111111111111";
    private static final String SECOND_ACCOUNT = "222222222222";
    private static final String POOL_ID = "us-east-1_sharedpool";
    private static final String FUNCTION = "pre-signup";

    private final ThreadLocal<RequestContext> requestContext = ThreadLocal.withInitial(() -> {
        RequestContext context = new RequestContext();
        context.setAccountId(FIRST_ACCOUNT);
        return context;
    });

    @ParameterizedTest
    @CsvSource({"false, false", "true, false", "false, true", "true, true"})
    void overlappingSignUpReservesOnlyTheSameAccountAndUsername(
            boolean firstRequestIsAdmin, boolean differentAccount) throws Exception {
        Instance<RequestContext> contexts = mock(Instance.class);
        when(contexts.get()).thenAnswer(invocation -> requestContext.get());
        RegionResolver regionResolver = new RegionResolver("us-east-1", FIRST_ACCOUNT) {
            @Override
            public String getAccountId() {
                return requestContext.get().getAccountId();
            }
        };
        LambdaService lambdaService = mock(LambdaService.class);
        CognitoService service = new CognitoService(
                store(contexts), store(contexts), store(contexts), store(contexts),
                store(contexts), store(contexts), store(contexts), store(contexts),
                "http://localhost:4566", regionResolver, lambdaService, null, null, null, null);
        UserPoolClient firstClient = inAccount(FIRST_ACCOUNT, () -> createClient(service));
        String overlappingAccount = differentAccount ? SECOND_ACCOUNT : FIRST_ACCOUNT;
        UserPoolClient overlappingClient = differentAccount
                ? inAccount(SECOND_ACCOUNT, () -> createClient(service)) : firstClient;
        CountDownLatch firstTriggerEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstTrigger = new CountDownLatch(1);
        List<String> invokedAccounts = new CopyOnWriteArrayList<>();
        when(lambdaService.invoke(anyString(), eq(FUNCTION), any(byte[].class),
                eq(InvocationType.RequestResponse))).thenAnswer(invocation -> {
                    String account = requestContext.get().getAccountId();
                    invokedAccounts.add(account);
                    if (FIRST_ACCOUNT.equals(account) && firstTriggerEntered.getCount() != 0) {
                        firstTriggerEntered.countDown();
                        assertTrue(releaseFirstTrigger.await(30, TimeUnit.SECONDS),
                                "first account's trigger must be released by the test");
                    }
                    return new InvokeResult(200, null,
                            "{\"response\":{}}".getBytes(StandardCharsets.UTF_8), null, "request");
                });

        ExecutorService requests = Executors.newFixedThreadPool(2);
        List<Future<?>> pending = new ArrayList<>();
        try {
            Future<CognitoUser> first = requests.submit(() -> inAccount(FIRST_ACCOUNT, () ->
                    firstRequestIsAdmin
                            ? service.adminCreateUser(POOL_ID, "alice", Map.of("name", "first"),
                                    "Perm1234!", "SUPPRESS")
                            : service.signUp(firstClient.getClientId(), "alice", "Perm1234!",
                                    Map.of("name", "first"))));
            pending.add(first);
            assertTrue(firstTriggerEntered.await(5, TimeUnit.SECONDS), "first request must enter PreSignUp");

            Future<CognitoUser> overlapping = requests.submit(() -> inAccount(overlappingAccount, () ->
                    service.signUp(overlappingClient.getClientId(), "alice", "Perm1234!",
                            Map.of("name", "overlapping"))));
            pending.add(overlapping);
            if (differentAccount) {
                assertEquals("overlapping", overlapping.get(5, TimeUnit.SECONDS).getAttributes().get("name"));
                assertEquals("overlapping", inAccount(SECOND_ACCOUNT, () ->
                        service.adminGetUser(POOL_ID, "alice").getAttributes().get("name")));
            } else {
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> overlapping.get(5, TimeUnit.SECONDS));
                AwsException duplicate = assertInstanceOf(AwsException.class, failure.getCause());
                assertEquals("UsernameExistsException", duplicate.getErrorCode());
            }
            assertEquals(1L, releaseFirstTrigger.getCount(), "overlap must finish while first trigger is paused");
            assertFalse(first.isDone(), "first request must still be waiting for its trigger");
            assertEquals(1, Collections.frequency(invokedAccounts, FIRST_ACCOUNT));
            assertEquals(differentAccount ? 1 : 0, Collections.frequency(invokedAccounts, SECOND_ACCOUNT));

            releaseFirstTrigger.countDown();
            assertEquals("first", first.get(5, TimeUnit.SECONDS).getAttributes().get("name"));
            assertEquals("first", inAccount(FIRST_ACCOUNT, () ->
                    service.adminGetUser(POOL_ID, "alice").getAttributes().get("name")));
            if (differentAccount) {
                assertEquals("overlapping", inAccount(SECOND_ACCOUNT, () ->
                        service.adminGetUser(POOL_ID, "alice").getAttributes().get("name")));
            }
        } finally {
            releaseFirstTrigger.countDown();
            pending.forEach(task -> task.cancel(true));
            requests.shutdownNow();
            requestContext.remove();
            assertTrue(requests.awaitTermination(5, TimeUnit.SECONDS), "request executor must terminate");
        }
    }

    private static <V> AccountAwareStorageBackend<V> store(Instance<RequestContext> contexts) {
        return new AccountAwareStorageBackend<>(new InMemoryStorage<>(), contexts, FIRST_ACCOUNT);
    }

    private static UserPoolClient createClient(CognitoService service) {
        UserPool pool = service.createUserPool(Map.of("PoolName", "creation-scope",
                "UserPoolTags", Map.of(ReservedTags.OVERRIDE_ID_KEY, POOL_ID),
                "LambdaConfig", Map.of("PreSignUp", FUNCTION)), "us-east-1");
        assertEquals(POOL_ID, pool.getId());
        return service.createUserPoolClient(pool.getId(), "client", false, false, List.of(), List.of());
    }

    private <T> T inAccount(String accountId, Callable<T> action) throws Exception {
        String previous = requestContext.get().getAccountId();
        requestContext.get().setAccountId(accountId);
        try {
            return action.call();
        } finally {
            requestContext.get().setAccountId(previous);
        }
    }
}
