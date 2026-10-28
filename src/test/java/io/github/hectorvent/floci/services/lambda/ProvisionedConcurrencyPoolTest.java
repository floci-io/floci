package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.lambda.launcher.ContainerHandle;
import io.github.hectorvent.floci.services.lambda.launcher.LambdaRuntimeLauncher;
import io.github.hectorvent.floci.services.lambda.model.ContainerState;
import io.github.hectorvent.floci.services.lambda.model.LambdaAlias;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.runtime.RuntimeApiServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.hectorvent.floci.testing.ThreadStates.awaitBlockedOrDone;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ProvisionedConcurrencyPoolTest {
    @TempDir
    Path storagePath;

    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private final AccountAwareStorageBackend<LambdaFunction> functionStorage =
            AccountAwareStorageBackend.inMemory(ACCOUNT);
    private final AccountAwareStorageBackend<LambdaAlias> aliasStorage =
            AccountAwareStorageBackend.inMemory(ACCOUNT);
    private final AccountAwareStorageBackend<ProvisionedConcurrencyPool.Configuration> storage =
            AccountAwareStorageBackend.inMemory(ACCOUNT);
    private final LambdaFunctionStore functions = new LambdaFunctionStore(functionStorage);
    private final LambdaAliasStore aliases = new LambdaAliasStore(aliasStorage);
    private final Launcher launcher = new Launcher();
    private ProvisionedConcurrencyPool pool;
    private LambdaFunction version;

    @BeforeEach
    void setUp() {
        version = function(ACCOUNT, REGION, "1");
        function(ACCOUNT, REGION, "$LATEST");
        alias(ACCOUNT, REGION, "live", "1", Map.of());
        pool = newPool();
    }

    @AfterEach
    void tearDown() {
        launcher.launchGate.complete(null);
        pool.shutdown();
    }

    private ProvisionedConcurrencyPool newPool() {
        return new ProvisionedConcurrencyPool(launcher, functions, aliases, storage, Clock.systemUTC(), 4, 8, false, 15);
    }

    private LambdaFunction function(String account, String region, String number) {
        LambdaFunction fn = LambdaFunctionTestFixtures.function(account, region, "example", number);
        fn.setTimeout(3);
        functions.saveForAccount(account, region, fn);
        return fn;
    }

    private void alias(String account, String region, String name, String number, Map<String, Double> weights) {
        LambdaAlias alias = LambdaFunctionTestFixtures.alias(account, region, "example", name, number);
        alias.setRoutingConfig(weights);
        aliasStorage.putForAccount(account, "alias::" + region + "::example::" + name, alias);
    }

    private ProvisionedConcurrencyPool.Status put(int count) {
        return pool.put(REGION, "example", "live", count, ACCOUNT);
    }

    @ParameterizedTest
    @CsvSource({"1, None", "live, None", "1, PublishedVersions", "live, PublishedVersions"})
    void snapStartEligibilityUsesThePublishedSnapshot(String qualifier, String latestApplyOn) {
        version.setSnapStartApplyOn("PublishedVersions");
        functions.getForAccount(ACCOUNT, REGION, "example").orElseThrow().setSnapStartApplyOn(latestApplyOn);

        AwsException error = assertThrows(AwsException.class,
                () -> pool.put(REGION, "example", qualifier, 1, ACCOUNT));

        assertSnapStartConflict(error);
        assertTrue(storage.scanAllAccounts().isEmpty());
        assertEquals("ProvisionedConcurrencyConfigNotFoundException",
                assertThrows(AwsException.class, () -> pool.get(REGION, "example", qualifier, ACCOUNT)).getErrorCode());
        assertDoesNotThrow(() -> pool.delete(REGION, "example", qualifier, ACCOUNT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "live"})
    void latestSnapStartDoesNotDisqualifyAnOlderVersion(String qualifier) {
        functions.getForAccount(ACCOUNT, REGION, "example").orElseThrow().setSnapStartApplyOn("PublishedVersions");

        assertEquals(1, pool.put(REGION, "example", qualifier, 1, ACCOUNT).requested());
        assertEquals(1, pool.put(REGION, "example", qualifier, 1, ACCOUNT).requested());
    }

    @Test
    void provisionedAliasCannotRetargetToASnapStartVersion() {
        function(ACCOUNT, REGION, "2").setSnapStartApplyOn("PublishedVersions");
        put(1);

        assertSnapStartConflict(assertThrows(AwsException.class,
                () -> pool.validateAliasUpdate(version, "live", "2")));
        assertEquals("1", storage.scanAllAccounts().getFirst().version());
        assertEquals(1, get().requested());
        assertDoesNotThrow(() -> pool.validateAliasUpdate(version, "live", null));
        assertDoesNotThrow(() -> pool.validateAliasUpdate(version, "unconfigured", "2"));
        pool.delete(REGION, "example", "live", ACCOUNT);
        assertDoesNotThrow(() -> pool.validateAliasUpdate(version, "live", "2"));
    }

    @Test
    void aliasSnapStartValidationUsesTheFunctionOwnerAndRegion() {
        function(ACCOUNT, REGION, "2");
        String owner = "222222222222";
        String region = "eu-west-1";
        LambdaFunction foreign = function(owner, region, "$LATEST");
        function(owner, region, "1");
        function(owner, region, "2").setSnapStartApplyOn("PublishedVersions");
        alias(owner, region, "live", "1", Map.of());
        pool.put(region, "example", "live", 1, owner);

        assertSnapStartConflict(assertThrows(AwsException.class,
                () -> pool.validateAliasUpdate(foreign, "live", "2")));
        put(1);
        assertDoesNotThrow(() -> pool.validateAliasUpdate(version, "live", "2"));
    }

    private static void assertSnapStartConflict(AwsException error) {
        assertEquals("ResourceConflictException", error.getErrorCode());
        assertEquals(409, error.getHttpStatus());
        assertEquals("The operation cannot be performed at this time. "
                + "Provisioned Concurrency is not supported for SnapStart enabled functions", error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"example", "absent"})
    void unpublishedVersionErrorsDependOnTheOperation(String name) {
        AwsException put = assertThrows(AwsException.class,
                () -> pool.put(REGION, name, "$LATEST", 1, ACCOUNT));
        assertEquals("InvalidParameterValueException", put.getErrorCode());
        assertEquals("Provisioned Concurrency Configs cannot be applied to unpublished function versions.",
                put.getMessage());
        for (Runnable operation : List.<Runnable>of(
                () -> pool.get(REGION, name, "$LATEST", ACCOUNT),
                () -> pool.delete(REGION, name, "$LATEST", ACCOUNT))) {
            AwsException error = assertThrows(AwsException.class, operation::run);
            assertEquals("InvalidParameterValueException", error.getErrorCode());
            assertEquals("The function resource provided must be an alias or a published version.",
                    error.getMessage());
        }
        assertEquals(0, launcher.starts.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void invalidCountUsesAwsValidationBeforeAllocating(int count) {
        AwsException error = assertThrows(AwsException.class, () -> put(count));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals("1 validation error detected: Value '" + count
                + "' at 'provisionedConcurrentExecutions' failed to satisfy constraint: "
                + "Member must have value greater than or equal to 1", error.getMessage());
        assertTrue(storage.scanAllAccounts().isEmpty());
        assertEquals(0, launcher.starts.get());
    }

    @Test
    void missingConfigurationUsesAwsMessageAndDeleteIsIdempotent() {
        AwsException error = assertThrows(AwsException.class, this::get);
        assertEquals("ProvisionedConcurrencyConfigNotFoundException", error.getErrorCode());
        assertEquals("No Provisioned Concurrency Config found for this function", error.getMessage());
        assertDoesNotThrow(() -> pool.delete(REGION, "example", "live", ACCOUNT));
        assertTrue(storage.scanAllAccounts().isEmpty());
        assertEquals(0, launcher.starts.get());
    }

    @ParameterizedTest
    @CsvSource({
            "example, missing, Cannot find alias arn: arn:aws:lambda:us-east-1:111111111111:function:example:missing",
            "example, 99, Function not found: arn:aws:lambda:us-east-1:111111111111:function:example:99",
            "absent, missing, Cannot find alias arn: arn:aws:lambda:us-east-1:111111111111:function:absent:missing",
            "absent, 99, Function not found: arn:aws:lambda:us-east-1:111111111111:function:absent:99"
    })
    void missingTargetsUseTheQualifiedAwsMessageForEveryOperation(String name, String qualifier, String message) {
        for (String reference : List.of(name, ACCOUNT + ":function:" + name,
                "arn:aws:lambda:" + REGION + ":" + ACCOUNT + ":function:" + name,
                "arn:aws:lambda:" + REGION + ":" + ACCOUNT + ":function:" + name + ":" + qualifier)) {
            for (Runnable operation : List.<Runnable>of(
                    () -> pool.get(REGION, reference, qualifier, ACCOUNT),
                    () -> pool.put(REGION, reference, qualifier, 1, ACCOUNT),
                    () -> pool.delete(REGION, reference, qualifier, ACCOUNT))) {
                AwsException error = assertThrows(AwsException.class, operation::run);
                assertEquals("ResourceNotFoundException", error.getErrorCode());
                assertEquals(404, error.getHttpStatus());
                assertEquals(message, error.getMessage());
            }
        }
        assertTrue(storage.scanAllAccounts().isEmpty());
        assertEquals(0, launcher.starts.get());
    }

    @Test
    void missingTargetMessageUsesTheNamedAccountAndRegionalPartition() {
        AwsException error = assertThrows(AwsException.class,
                () -> pool.get("cn-north-1", "222222222222:function:absent", "live", ACCOUNT));
        assertEquals("Cannot find alias arn: arn:aws-cn:lambda:cn-north-1:222222222222:function:absent:live",
                error.getMessage());
        assertTrue(storage.scanAllAccounts().isEmpty());
        assertEquals(0, launcher.starts.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "peer"})
    void provisionedAliasRefusesAnotherQualifierOfTheSameVersion(String qualifier) throws Exception {
        alias(ACCOUNT, REGION, "peer", "1", Map.of());
        put(1);
        launched();
        AwsException error = assertThrows(AwsException.class,
                () -> pool.put(REGION, "example", qualifier, 1, ACCOUNT));
        assertEquals("ResourceConflictException", error.getErrorCode());
        assertEquals(qualifier.equals("1") ? "Version is pointed by a Provisioned Concurrency alias"
                : "Alias can't be used for Provisioned Concurrency configuration on an already Provisioned version",
                error.getMessage());
        assertEquals(1, storage.scanAllAccounts().size());
        assertEquals(1, launcher.starts.get());
    }

    @Test
    void provisionedVersionRefusesAliasButAllowsADifferentVersion() throws Exception {
        pool.put(REGION, "example", "1", 1, ACCOUNT);
        launched();
        AwsException error = assertThrows(AwsException.class, () -> put(1));
        assertEquals("ResourceConflictException", error.getErrorCode());
        assertEquals("Alias can't be used for Provisioned Concurrency configuration on an already Provisioned version",
                error.getMessage());
        function(ACCOUNT, REGION, "2");
        pool.put(REGION, "example", "2", 1, ACCOUNT);
        launched();
        assertEquals(2, storage.scanAllAccounts().size());
        assertEquals(2, launcher.starts.get());
    }

    @Test
    void lastModifiedUsesLambdasUtcOffsetForm() {
        pool.shutdown();
        pool = new ProvisionedConcurrencyPool(launcher, functions, aliases, storage,
                Clock.fixed(Instant.parse("2026-10-20T20:11:30.987Z"), ZoneOffset.UTC), 4, 8, false, 15);
        assertEquals("2026-10-20T20:11:30+0000", put(1).lastModified());
    }

    private ProvisionedConcurrencyPool.Status get() {
        return pool.get(REGION, "example", "live", ACCOUNT);
    }

    private Environment launched() throws Exception {
        Environment environment = launcher.launched.poll(5, TimeUnit.SECONDS);
        assertNotNull(environment);
        return environment;
    }

    private void ready() {
        await().atMost(Duration.ofSeconds(5)).until(() -> "READY".equals(get().status()));
    }

    @Test
    void listingReportsCurrentCapacityAndRemovesDeletedConfigurations() throws Exception {
        put(1);
        Environment environment = launched();
        assertEquals(get(), pool.list(REGION, "example", ACCOUNT, null, null).items().getFirst().status());
        environment.initialized.complete(null);
        ready();
        ContainerHandle leased = pool.acquire(version, "live");
        assertNotNull(leased);
        ProvisionedConcurrencyPool.ListedConfiguration entry =
                pool.list(REGION, "example", ACCOUNT, null, null).items().getFirst();
        assertEquals(AwsArnUtils.Arn.of("lambda", REGION, ACCOUNT, "function:example:live").toString(),
                entry.functionArn());
        assertEquals(get(), entry.status());
        assertEquals(1, entry.status().allocated());
        assertEquals(0, entry.status().available());
        assertTrue(pool.release(leased, false));
        pool.delete(REGION, "example", "live", ACCOUNT);
        assertTrue(pool.list(REGION, "example", ACCOUNT, null, null).items().isEmpty());
    }

    @Test
    void listingKeepsFailedInitializationVisible() throws Exception {
        put(1);
        launched().initialized.completeExceptionally(new IllegalStateException("initialization failed"));
        await().atMost(Duration.ofSeconds(5)).until(() -> "FAILED".equals(get().status()));
        assertEquals(get(), pool.list(REGION, "example", ACCOUNT, null, null).items().getFirst().status());
    }

    @Test
    void listingSeparatesAccountsRegionsAndPartitions() throws Exception {
        for (String region : List.of(REGION, "cn-north-1")) {
            for (String account : List.of(ACCOUNT, "222222222222")) {
                function(account, region, "$LATEST");
                function(account, region, "1");
                alias(account, region, "live", "1", Map.of());
                pool.put(region, "example", "live", 1, account);
                launched().initialized.complete(null);
            }
        }
        for (String region : List.of(REGION, "cn-north-1")) {
            for (String account : List.of(ACCOUNT, "222222222222")) {
                List<ProvisionedConcurrencyPool.ListedConfiguration> entries =
                        pool.list(region, "example", account, null, null).items();
                assertEquals(1, entries.size());
                assertEquals(AwsArnUtils.Arn.of("lambda", region, account, "function:example:live").toString(),
                        entries.getFirst().functionArn());
            }
        }
    }

    @Test
    void listingPagesByQualifierAndRefusesForeignMarkers() throws Exception {
        put(1);
        launched().initialized.complete(null);
        function(ACCOUNT, REGION, "2");
        pool.put(REGION, "example", "2", 1, ACCOUNT);
        launched().initialized.complete(null);
        PaginatedResult<ProvisionedConcurrencyPool.ListedConfiguration> first =
                pool.list(REGION, "example", ACCOUNT, "1", null);
        assertEquals(1, first.items().size());
        assertTrue(first.items().getFirst().functionArn().endsWith(":2"));
        assertNotNull(first.nextToken());
        pool.delete(REGION, "example", "2", ACCOUNT);
        PaginatedResult<ProvisionedConcurrencyPool.ListedConfiguration> last =
                pool.list(REGION, "example", ACCOUNT, "1", first.nextToken());
        assertEquals(1, last.items().size());
        assertTrue(last.items().getFirst().functionArn().endsWith(":live"));
        assertNull(last.nextToken());
        assertThrows(AwsException.class, () -> pool.list(REGION, "example", ACCOUNT, "1", "fabricated"));
        function("222222222222", REGION, "$LATEST");
        function(ACCOUNT, "us-west-2", "$LATEST");
        functions.save(REGION, LambdaFunctionTestFixtures.function(ACCOUNT, REGION, "other", "$LATEST"));
        assertThrows(AwsException.class,
                () -> pool.list(REGION, "example", "222222222222", "1", first.nextToken()));
        assertThrows(AwsException.class,
                () -> pool.list("us-west-2", "example", ACCOUNT, "1", first.nextToken()));
        assertThrows(AwsException.class,
                () -> pool.list(REGION, "other", ACCOUNT, "1", first.nextToken()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "51", "-1", "one"})
    void listingRejectsInvalidPageSizes(String maxItems) {
        assertThrows(AwsException.class, () -> pool.list(REGION, "example", ACCOUNT, maxItems, null));
        assertEquals(0, launcher.starts.get());
    }

    @Test
    void initializationThenExactQualifierReuseDoesNotInvokeHandler() throws Exception {
        assertEquals("IN_PROGRESS", put(1).status());
        Environment first = launched();
        assertEquals("IN_PROGRESS", get().status());
        assertEquals(0, get().allocated());
        assertNull(pool.acquire(version, "live"));
        first.initialized.complete(null);
        ready();
        assertEquals(1, get().allocated());
        assertEquals(1, get().available());
        assertNull(pool.acquire(version, null));
        assertNull(pool.acquire(version, "1"));
        assertNull(pool.acquire(version, "other"));
        assertNull(pool.acquire(function("222222222222", REGION, "1"), "live"));
        assertNull(pool.acquire(function(ACCOUNT, "eu-west-1", "1"), "live"));
        assertSame(first.handle, pool.acquire(version, "live"));
        assertEquals(0, get().available());
        assertTrue(pool.release(first.handle, false));
        assertSame(first.handle, pool.acquire(version, "live"));
        assertTrue(pool.release(first.handle, false));
        verify(first.runtime, never()).enqueue(any());
        assertEquals(1, launcher.starts.get());
    }

    @Test
    void repeatedPutPreservesTheCurrentAllocation() throws Exception {
        put(1);
        Environment first = launched();
        first.initialized.complete(null);
        ready();
        String modified = get().lastModified();
        assertEquals("READY", put(1).status());
        assertEquals(modified, get().lastModified());
        assertEquals(1, launcher.starts.get());
    }

    @Test
    void replacingCapacityDefersAUsedEnvironmentUntilRelease() throws Exception {
        put(1);
        Environment first = launched();
        first.initialized.complete(null);
        ready();
        assertSame(first.handle, pool.acquire(version, "live"));
        put(2);
        assertEquals(0, first.stops.get());
        assertEquals(ContainerState.BUSY, first.handle.getState());
        assertTrue(pool.release(first.handle, false));
        assertEquals(1, first.stops.get());
    }

    @ParameterizedTest
    @CsvSource({"1,0,false", "4,1,false", "4,1,true"})
    void retiredLeasesStillCountAgainstContainerCeilings(int perFunction, int total,
                                                       boolean differentAccount) throws Exception {
        pool.shutdown();
        pool = new ProvisionedConcurrencyPool(launcher, functions, aliases, storage,
                Clock.systemUTC(), perFunction, total, false, 15);
        put(1);
        Environment first = launched();
        first.initialized.complete(null);
        ready();
        assertSame(first.handle, pool.acquire(version, "live"));
        String account = replacementTarget(differentAccount);
        pool.put(REGION, "example", "live", 1, account);
        assertNull(launcher.launched.poll(100, TimeUnit.MILLISECONDS));
        assertEquals(0, first.stops.get());

        assertTrue(pool.release(first.handle, false));
        pool.reconcile();
        Environment replacement = launched();
        replacement.initialized.complete(null);
        await().atMost(Duration.ofSeconds(5)).until(() ->
                "READY".equals(pool.get(REGION, "example", "live", account).status()));
        assertEquals(1, first.stops.get());
        assertEquals(2, launcher.starts.get());
    }

    @ParameterizedTest
    @CsvSource({"1,0,false", "4,1,false", "4,1,true"})
    void retiredLaunchesStillCountAgainstContainerCeilings(int perFunction, int total,
                                                         boolean differentAccount) throws Exception {
        pool.shutdown();
        pool = new ProvisionedConcurrencyPool(launcher, functions, aliases, storage,
                Clock.systemUTC(), perFunction, total, false, 15);
        launcher.launchGate = new CompletableFuture<>();
        put(1);
        Environment first = launched();
        String account = replacementTarget(differentAccount);
        pool.put(REGION, "example", "live", 1, account);
        assertNull(launcher.launched.poll(100, TimeUnit.MILLISECONDS));

        launcher.launchGate.complete(null);
        await().atMost(Duration.ofSeconds(5)).until(() -> first.stops.get() == 1);
        await().atMost(Duration.ofSeconds(5)).until(() -> {
            pool.reconcile();
            return launcher.starts.get() == 2;
        });
        Environment replacement = launched();
        replacement.initialized.complete(null);
        await().atMost(Duration.ofSeconds(5)).until(() ->
                "READY".equals(pool.get(REGION, "example", "live", account).status()));
        assertEquals(2, launcher.starts.get());
    }

    private String replacementTarget(boolean differentAccount) {
        String account = differentAccount ? "222222222222" : ACCOUNT;
        if (differentAccount) {
            function(account, REGION, "$LATEST");
            pool.delete(REGION, "example", "live", ACCOUNT);
        }
        function(account, REGION, "2");
        alias(account, REGION, "live", "2", Map.of());
        return account;
    }

    @Test
    void deletingCapacityDefersAUsedEnvironmentUntilRelease() throws Exception {
        put(1);
        Environment first = launched();
        first.initialized.complete(null);
        ready();
        assertSame(first.handle, pool.acquire(version, "live"));
        pool.delete(REGION, "example", "live", ACCOUNT);
        assertEquals(0, first.stops.get());
        assertThrows(AwsException.class, this::get);
        assertTrue(pool.release(first.handle, false));
        assertEquals(1, first.stops.get());
    }

    @Test
    void synchronousReconciliationFailureReachesTheCaller() throws Exception {
        put(1);
        Environment first = launched();
        first.initialized.complete(null);
        ready();
        IllegalStateException failure = new IllegalStateException("readiness lookup failed");
        when(first.runtime.isInitialized()).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, this::get));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void provisioningRechecksFunctionAndReservationAfterTheirMutation(boolean deleted) throws Exception {
        LambdaFunction latest = functions.getForAccount(ACCOUNT, REGION, "example").orElseThrow();
        AtomicReference<Thread> requester = new AtomicReference<>();
        CompletableFuture<ProvisionedConcurrencyPool.Status> request;
        try (ExecutorService tasks = Executors.newSingleThreadExecutor()) {
            synchronized (functions.lockForConcurrencyOp(latest.getFunctionArn())) {
                request = CompletableFuture.supplyAsync(() -> {
                    requester.set(Thread.currentThread());
                    return put(2);
                }, tasks);
                awaitBlockedOrDone(requester, request);
                assertFalse(request.isDone());
                if (deleted) {
                    functionStorage.deleteForAccount(ACCOUNT, "lambda::" + REGION + "::example::$LATEST");
                } else {
                    latest.setReservedConcurrentExecutions(1);
                    functions.saveForAccount(ACCOUNT, REGION, latest);
                }
            }
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> request.get(5, TimeUnit.SECONDS));
            AwsException error = assertInstanceOf(AwsException.class, failure.getCause());
            assertEquals(deleted ? "ResourceNotFoundException" : "InvalidParameterValueException",
                    error.getErrorCode());
            assertTrue(storage.scanAllAccounts().isEmpty());
            assertEquals(0, launcher.starts.get());
        }
    }

    @Test
    void deleteDuringInitializationStopsOnceAndNeverRestoresReady() throws Exception {
        put(1);
        Environment first = launched();
        pool.delete(REGION, "example", "live", ACCOUNT);
        await().atMost(Duration.ofSeconds(5)).until(() -> first.stops.get() == 1);
        first.initialized.complete(null);
        AwsException missing = assertThrows(AwsException.class, this::get);
        assertEquals("ProvisionedConcurrencyConfigNotFoundException", missing.getErrorCode());
        pool.reconcile();
        assertEquals(1, launcher.starts.get());
        assertEquals(1, first.stops.get());
    }

    @Test
    void deleteBeforeLaunchReturnsStopsTheLateHandle() throws Exception {
        launcher.launchGate = new CompletableFuture<>();
        put(1);
        Environment first = launched();
        pool.delete(REGION, "example", "live", ACCOUNT);
        launcher.launchGate.complete(null);
        await().atMost(Duration.ofSeconds(5)).until(() -> first.stops.get() == 1);
        assertThrows(AwsException.class, this::get);
    }

    @Test
    void failedStopRemainsOwnedAfterDeleteCommits() throws Exception {
        put(1);
        Environment first = launched();
        first.initialized.complete(null);
        ready();
        launcher.stopFailures.set(1);
        assertDoesNotThrow(() -> pool.delete(REGION, "example", "live", ACCOUNT));
        assertEquals("ProvisionedConcurrencyConfigNotFoundException",
                assertThrows(AwsException.class, this::get).getErrorCode());
        assertTrue(first.alive);
        pool.reconcileSafely();
        assertFalse(first.alive);
        assertEquals(2, first.stops.get());
        assertDoesNotThrow(() -> pool.delete(REGION, "example", "live", ACCOUNT));
    }

    @Test
    void replacingCapacityCommitsAndWaitsForCleanupBeforeInitializing() throws Exception {
        put(1);
        Environment first = launched();
        first.initialized.complete(null);
        ready();
        launcher.stopFailures.set(2);

        assertEquals("IN_PROGRESS", assertDoesNotThrow(() -> put(2)).status());
        assertTrue(first.alive);
        assertEquals(1, launcher.starts.get());
        assertEquals("IN_PROGRESS", get().status());
        assertEquals(0, get().allocated());
        assertEquals(2, get().requested());
        assertEquals("ResourceConflictException", assertThrows(AwsException.class, () -> put(2)).getErrorCode());

        pool.reconcileSafely();
        assertTrue(first.alive);
        assertEquals(1, launcher.starts.get());

        pool.reconcileSafely();
        assertFalse(first.alive);
        Environment second = launched();
        Environment third = launched();
        second.initialized.complete(null);
        third.initialized.complete(null);
        ready();
        assertEquals(3, first.stops.get());
        assertEquals(3, launcher.starts.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"configuration", "alias", "function"})
    void deletionCommitsWhileAnEarlierStopIsStillRunning(String scope) throws Exception {
        put(1);
        Environment first = launched();
        first.initialized.complete(null);
        ready();
        first.faulted.set(true);
        first.stopGate = new CompletableFuture<>();
        CompletableFuture<ContainerHandle> discard = CompletableFuture.supplyAsync(() -> pool.acquire(version, "live"));
        try {
            assertSame(first, launcher.stopping.poll(2, TimeUnit.SECONDS));
            assertDoesNotThrow(() -> {
                switch (scope) {
                    case "configuration" -> pool.delete(REGION, "example", "live", ACCOUNT);
                    case "alias" -> pool.deleteQualifier(version, "live");
                    case "function" -> pool.deleteFunction(version);
                    default -> throw new AssertionError(scope);
                }
            });
            assertEquals("ProvisionedConcurrencyConfigNotFoundException",
                    assertThrows(AwsException.class, this::get).getErrorCode());
            assertEquals(1, first.stops.get(), "The admitted stopper retains exclusive ownership");
        } finally {
            first.stopGate.complete(null);
            assertNull(discard.get(5, TimeUnit.SECONDS));
        }
        assertFalse(first.alive);
        assertDoesNotThrow(() -> put(1));
        Environment replacement = launched();
        replacement.initialized.complete(null);
        ready();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lateReleaseCleansUpAfterFailedOrExpiredShutdown(boolean expiredDeadline) throws Exception {
        put(1);
        Environment first = launched();
        first.initialized.complete(null);
        ready();
        assertSame(first.handle, pool.acquire(version, "live"));
        int initialAttempts;
        if (expiredDeadline) {
            pool.stopManagedContainers(System.nanoTime() - 1);
            initialAttempts = 0;
        } else {
            launcher.stopFailures.set(1);
            pool.stopManagedContainers(System.nanoTime() + TimeUnit.SECONDS.toNanos(2));
            initialAttempts = 1;
        }
        assertEquals(initialAttempts, first.stops.get());
        assertTrue(first.alive);
        assertTrue(pool.release(first.handle, false));
        assertFalse(first.alive);
        assertEquals(initialAttempts + 1, first.stops.get());
        assertFalse(pool.release(first.handle, false));
        pool.shutdown();
        assertEquals(initialAttempts + 1, first.stops.get(),
                "Repeated release and shutdown do not create another stop");
    }

    @Test
    void failedReleaseRetainsOwnedCleanupWithoutChangingCompletedInvocationDispatch() throws Exception {
        put(1);
        Environment first = launched();
        first.initialized.complete(null);
        ready();
        assertSame(first.handle, pool.acquire(version, "live"));
        pool.delete(REGION, "example", "live", ACCOUNT);
        launcher.stopFailures.set(1);

        assertTrue(pool.release(first.handle, false));
        assertTrue(first.alive);
        assertEquals(1, first.stops.get());
        assertThrows(AwsException.class, this::get);

        pool.reconcileSafely();
        assertFalse(first.alive);
        assertEquals(2, first.stops.get());
    }

    @Test
    void initializationFailureIsFailedAndExplicitPutCanRetry() throws Exception {
        put(1);
        Environment first = launched();
        first.initialized.completeExceptionally(new IllegalStateException("controlled init failure"));
        await().atMost(Duration.ofSeconds(5)).until(() -> "FAILED".equals(get().status()));
        assertTrue(get().reason().contains("controlled init failure"));
        assertEquals(0, get().allocated());
        assertEquals(1, first.stops.get());
        put(1);
        Environment second = launched();
        second.initialized.complete(null);
        ready();
        assertEquals(2, launcher.starts.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"function", "version", "alias"})
    void lookupFailuresPropagateWithoutRetiringReadyCapacity(String lookup) throws Exception {
        pool.shutdown();
        LambdaFunctionStore functionLookup = spy(functions);
        LambdaAliasStore aliasLookup = spy(aliases);
        pool = new ProvisionedConcurrencyPool(launcher, functionLookup, aliasLookup,
                storage, Clock.systemUTC(), 4, 8, false, 15);
        put(1);
        Environment first = launched();
        first.initialized.complete(null);
        ready();
        IllegalStateException failure = new IllegalStateException("controlled lookup outage");
        switch (lookup) {
            case "function" -> doThrow(failure).when(functionLookup).getForAccount(ACCOUNT, REGION, "example");
            case "version" -> doThrow(failure).when(functionLookup).getForAccount(ACCOUNT, REGION, "example", "1");
            case "alias" -> doThrow(failure).when(aliasLookup).getForAccount(ACCOUNT, REGION, "example", "live");
            default -> throw new AssertionError(lookup);
        }
        assertSame(failure, assertThrows(IllegalStateException.class, () -> pool.acquire(version, "live")));
        if ("alias".equals(lookup)) {
            assertDoesNotThrow(pool::reconcile);
            assertDoesNotThrow(pool::reconcileSafely);
        } else {
            assertSame(failure, assertThrows(IllegalStateException.class, pool::reconcile));
            assertSame(failure, assertThrows(IllegalStateException.class, pool::reconcileSafely));
        }
        assertTrue(first.alive);
        assertEquals(0, first.stops.get());
        reset(functionLookup, aliasLookup);
        assertEquals("READY", get().status());
        assertSame(first.handle, pool.acquire(version, "live"));
        assertTrue(pool.release(first.handle, false));
    }

    @Test
    void scheduledReconciliationRetriesAfterLookupOutages() throws Exception {
        put(1);
        Environment first = launched();
        first.initialized.complete(null);
        ready();
        pool.shutdown();
        LambdaAliasStore aliasLookup = spy(aliases);
        doThrow(new IllegalStateException("controlled scheduler lookup outage"))
                .when(aliasLookup).getForAccount(ACCOUNT, REGION, "example", "live");
        pool = new ProvisionedConcurrencyPool(launcher, functions, aliasLookup,
                storage, Clock.systemUTC(), 4, 8, false, 15);
        pool.start();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                verify(aliasLookup, atLeast(2)).getForAccount(ACCOUNT, REGION, "example", "live"));
        doCallRealMethod().when(aliasLookup).getForAccount(ACCOUNT, REGION, "example", "live");
        Environment replacement = launched();
        replacement.initialized.complete(null);
        ready();
        assertEquals("READY", get().status());
        assertEquals(1, first.stops.get());
    }

    @Test
    void failedEnvironmentIsReplacedAfterReady() throws Exception {
        put(1);
        Environment first = launched();
        first.initialized.complete(null);
        ready();
        first.alive = false;
        pool.reconcile();
        assertEquals("IN_PROGRESS", get().status());
        Environment replacement = launched();
        replacement.initialized.complete(null);
        ready();
        assertEquals(1, first.stops.get());
    }

    @Test
    void runtimeFaultReplacesCapacityWithoutDockerHealthChecks() throws Exception {
        launcher.rejectHealthChecks = true;
        put(1);
        Environment first = launched();
        first.initialized.complete(null);
        ready();
        assertSame(first.handle, pool.acquire(version, "live"));
        assertTrue(pool.release(first.handle, false));
        assertEquals("READY", put(1).status());
        first.faulted.set(true);

        pool.reconcile();
        assertEquals("IN_PROGRESS", get().status());
        Environment replacement = launched();
        replacement.initialized.complete(null);
        ready();
        assertSame(replacement.handle, pool.acquire(version, "live"));
        assertTrue(pool.release(replacement.handle, false));
        assertEquals(1, first.stops.get());
    }

    @Test
    void faultReplacementWaitsUntilTheOldEnvironmentCanBeRemoved() throws Exception {
        put(1);
        Environment first = launched();
        first.initialized.complete(null);
        ready();
        first.faulted.set(true);
        launcher.stopFailures.set(1);

        pool.reconcile();
        assertTrue(first.alive);
        assertEquals(1, launcher.starts.get());
        assertEquals("IN_PROGRESS", get().status());
        assertEquals(1, launcher.starts.get());

        pool.reconcileSafely();
        assertFalse(first.alive);
        Environment replacement = launched();
        replacement.initialized.complete(null);
        ready();
        assertEquals(2, first.stops.get());
        assertEquals(2, launcher.starts.get());
    }

    @Test
    void targetChangeRefusesOldInitializedEnvironment() throws Exception {
        put(1);
        Environment first = launched();
        first.initialized.complete(null);
        ready();
        function(ACCOUNT, REGION, "2");
        alias(ACCOUNT, REGION, "live", "2", Map.of());
        assertNull(pool.acquire(version, "live"));
        assertEquals("IN_PROGRESS", get().status());
        assertEquals(1, first.stops.get());
        pool.reconcile();
        Environment second = launched();
        second.initialized.complete(null);
        ready();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void movedAliasFreesItsOldVersionButRetainsItsReservedBudget(boolean previouslyFailed) throws Exception {
        LambdaFunction latest = functions.getForAccount(ACCOUNT, REGION, "example").orElseThrow();
        latest.setReservedConcurrentExecutions(2);
        put(1);
        Environment first = launched();
        first.initialized.complete(null);
        ready();
        if (previouslyFailed) {
            alias(ACCOUNT, REGION, "live", "1", Map.of("2", 0.1));
            assertEquals("FAILED", get().status());
        }
        function(ACCOUNT, REGION, "2");
        alias(ACCOUNT, REGION, "live", "2", Map.of());

        pool.put(REGION, "example", "1", 1, ACCOUNT);
        assertEquals("2", storage.scanAllAccounts().stream()
                .filter(value -> "live".equals(value.qualifier())).findFirst().orElseThrow().version());
        assertEquals(1, first.stops.get());
        assertEquals(2, storage.scanAllAccounts().size());
        AwsException budget = assertThrows(AwsException.class,
                () -> pool.put(REGION, "example", "1", 2, ACCOUNT));
        assertEquals("InvalidParameterValueException", budget.getErrorCode());
        AwsException bothInvalid = assertThrows(AwsException.class,
                () -> pool.put(REGION, "example", "2", 1, ACCOUNT));
        assertEquals("InvalidParameterValueException", bothInvalid.getErrorCode());
        latest.setReservedConcurrentExecutions(3);
        AwsException conflict = assertThrows(AwsException.class,
                () -> pool.put(REGION, "example", "2", 1, ACCOUNT));
        assertEquals("Version is pointed by a Provisioned Concurrency alias", conflict.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"weighted", "latest"})
    void changedAliasRemainsReadableAndDeletable(String change) throws Exception {
        put(1);
        Environment first = launched();
        first.initialized.complete(null);
        ready();
        alias(ACCOUNT, REGION, "live", "latest".equals(change) ? "$LATEST" : "1",
                "weighted".equals(change) ? Map.of("2", 0.1) : Map.of());
        assertEquals(1, get().requested());
        pool.delete(REGION, "example", "live", ACCOUNT);
        assertTrue(storage.scanAllAccounts().isEmpty());
        assertEquals(1, first.stops.get());
    }

    @Test
    void aliasCannotMoveToLatestUntilItsProvisionedConfigurationIsDeleted() throws Exception {
        put(1);
        launched();
        AwsException error = assertThrows(AwsException.class,
                () -> pool.validateAliasUpdate(version, "live", "$LATEST"));
        assertEquals("Provisioned Concurrency Configs cannot be applied to unpublished function versions.",
                error.getMessage());
        assertDoesNotThrow(() -> pool.validateAliasUpdate(version, "live", "2"));
        pool.delete(REGION, "example", "live", ACCOUNT);
        assertDoesNotThrow(() -> pool.validateAliasUpdate(version, "live", "$LATEST"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void configuredAliasRejectsUnsupportedTargetBeforeMutation(boolean durable) throws Exception {
        put(1);
        Environment first = launched();
        first.initialized.complete(null);
        ready();
        LambdaFunction target = function(ACCOUNT, REGION, "2");
        if (durable) {
            target.setDurableExecutionTimeout(60);
        } else {
            target.setHotReloadHostPath("/tmp/unsupported-target");
        }
        functions.saveForAccount(ACCOUNT, REGION, target);

        AwsException error = assertThrows(AwsException.class,
                () -> pool.validateAliasUpdate(version, "live", "2"));

        assertEquals("InvalidParameterValueException", error.getErrorCode());
        assertEquals("1", aliases.getForAccount(ACCOUNT, REGION, "example", "live")
                .orElseThrow().getFunctionVersion());
        assertEquals("1", storage.scanAllAccounts().getFirst().version());
        assertEquals(0, first.stops.get());
        assertEquals(1, launcher.starts.get());
        pool.delete(REGION, "example", "live", ACCOUNT);
        assertDoesNotThrow(() -> pool.validateAliasUpdate(version, "live", "2"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unsupportedRetargetPreservesPriorAllocationUntilTargetIsEligible(boolean durable) throws Exception {
        put(1);
        Environment first = launched();
        first.initialized.complete(null);
        ready();
        LambdaFunction target = function(ACCOUNT, REGION, "2");
        if (durable) {
            target.setDurableExecutionTimeout(60);
        } else {
            target.setHotReloadHostPath("/tmp/unsupported-target");
        }
        functions.saveForAccount(ACCOUNT, REGION, target);
        alias(ACCOUNT, REGION, "live", "2", Map.of());

        assertDoesNotThrow(pool::reconcile);
        assertEquals("1", storage.scanAllAccounts().getFirst().version());
        assertEquals(0, first.stops.get());
        assertEquals(1, launcher.starts.get());
        target.setDurableExecutionTimeout(null);
        target.setHotReloadHostPath(null);
        functions.saveForAccount(ACCOUNT, REGION, target);
        pool.reconcile();
        pool.reconcile();
        Environment replacement = launched();
        replacement.initialized.complete(null);
        ready();
        assertEquals("2", storage.scanAllAccounts().getFirst().version());
        assertEquals(1, first.stops.get());
        assertEquals(2, launcher.starts.get());
    }

    @Test
    void aliasRetargetStorageFailurePreservesThePriorAllocation() throws Exception {
        AccountAwareStorageBackend<ProvisionedConcurrencyPool.Configuration> backend = spy(storage);
        pool.closeExecutors();
        pool = new ProvisionedConcurrencyPool(launcher, functions, aliases, backend,
                Clock.systemUTC(), 4, 8, false, 15);
        put(1);
        Environment first = launched();
        first.initialized.complete(null);
        ready();
        function(ACCOUNT, REGION, "2");
        alias(ACCOUNT, REGION, "live", "2", Map.of());
        IllegalStateException failure = new IllegalStateException("controlled alias write failure");
        doThrow(failure).when(backend).putForAccount(eq(ACCOUNT), any(), any());
        assertEquals("READY", get().status());
        assertEquals("1", storage.scanAllAccounts().getFirst().version());
        assertEquals(0, first.stops.get());
        doCallRealMethod().when(backend).putForAccount(eq(ACCOUNT), any(), any());
        assertEquals("IN_PROGRESS", get().status());
        assertEquals("2", storage.scanAllAccounts().getFirst().version());
        assertEquals(1, first.stops.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"storage", "hotReload", "durable"})
    void failedAliasRefreshDoesNotBlockOtherAccountsAndRecovers(String failureKind) throws Exception {
        AccountAwareStorageBackend<ProvisionedConcurrencyPool.Configuration> backend = spy(storage);
        pool.closeExecutors();
        pool = new ProvisionedConcurrencyPool(launcher, functions, aliases, backend,
                Clock.systemUTC(), 4, 8, false, 15);
        put(1);
        Environment original = launched();
        original.initialized.complete(null);
        ready();
        LambdaFunction target = function(ACCOUNT, REGION, "2");
        if ("hotReload".equals(failureKind)) {
            target.setHotReloadHostPath("/tmp/unsupported-target");
        } else if ("durable".equals(failureKind)) {
            target.setDurableExecutionTimeout(60);
        } else {
            doThrow(new IllegalStateException("controlled alias write failure"))
                    .when(backend).putForAccount(eq(ACCOUNT), any(), any());
        }
        functions.saveForAccount(ACCOUNT, REGION, target);
        alias(ACCOUNT, REGION, "live", "2", Map.of());
        String other = "222222222222";
        function(other, REGION, "$LATEST");
        function(other, REGION, "1");
        assertDoesNotThrow(() -> pool.put(REGION, "example", "1", 1, other));
        Environment peer = launched();
        peer.initialized.complete(null);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertEquals("READY", pool.get(REGION, "example", "1", other).status()));
        peer.faulted.set(true);
        assertDoesNotThrow(pool::reconcile);
        assertEquals("IN_PROGRESS", pool.get(REGION, "example", "1", other).status());
        Environment peerReplacement = launched();
        peerReplacement.initialized.complete(null);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertEquals("READY", pool.get(REGION, "example", "1", other).status()));
        assertEquals(1, peer.stops.get());
        assertEquals(0, original.stops.get());
        assertEquals("1", storage.scanAllAccounts().stream()
                .filter(value -> ACCOUNT.equals(value.account())).findFirst().orElseThrow().version());
        if ("storage".equals(failureKind)) {
            doCallRealMethod().when(backend).putForAccount(eq(ACCOUNT), any(), any());
        }
        target.setDurableExecutionTimeout(null);
        target.setHotReloadHostPath(null);
        functions.saveForAccount(ACCOUNT, REGION, target);
        pool.reconcile();
        pool.reconcile();
        Environment replacement = launched();
        replacement.initialized.complete(null);
        ready();
        assertEquals(1, original.stops.get());
        assertEquals(0, peerReplacement.stops.get());
        assertEquals("2", storage.scanAllAccounts().stream()
                .filter(value -> ACCOUNT.equals(value.account())).findFirst().orElseThrow().version());
    }

    @Test
    void restoredDesiredConfigurationMustInitializeNewProcesses() throws Exception {
        put(1);
        Environment first = launched();
        first.initialized.complete(null);
        ready();
        pool.shutdown();
        pool = newPool();
        pool.start();
        Environment second = launched();
        assertEquals("IN_PROGRESS", get().status());
        assertEquals(1, first.stops.get());
        second.initialized.complete(null);
        ready();
    }

    @Test
    void shutdownDrainsIdleAndBusyEnvironmentsConcurrentlyWithoutDuplicateStops() throws Exception {
        put(4);
        List<Environment> environments = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Environment environment = launched();
            environments.add(environment);
            environment.initialized.complete(null);
        }
        ready();
        assertNotNull(pool.acquire(version, "live"));
        launcher.stopGate = new CompletableFuture<>();
        CompletableFuture<Void> drained = CompletableFuture.runAsync(pool::stopManagedContainers);
        try {
            for (Environment environment : environments) {
                assertNotNull(launcher.stopping.poll(2, TimeUnit.SECONDS),
                        "Every environment must start stopping before the others finish");
            }
            assertFalse(drained.isDone(), "Teardown must wait for the container stops");
            assertEquals(409, assertThrows(AwsException.class, () -> put(1)).getHttpStatus());
            pool.stopManagedContainers();
            for (Environment environment : environments) {
                assertEquals(1, environment.stops.get());
            }
        } finally {
            launcher.stopGate.complete(null);
            drained.get(10, TimeUnit.SECONDS);
        }
        for (Environment environment : environments) {
            assertFalse(environment.alive);
            assertEquals(1, environment.stops.get());
        }
    }

    @Test
    void shutdownWaitsForAndRemovesALaunchThatReturnsAfterAdmissionCloses() throws Exception {
        launcher.launchGate = new CompletableFuture<>();
        put(1);
        Environment first = launched();
        first.initialized.complete(null);
        CompletableFuture<Void> shutdown = CompletableFuture.runAsync(() ->
                pool.stopManagedContainers(System.nanoTime() + TimeUnit.SECONDS.toNanos(2)));
        try {
            assertThrows(TimeoutException.class, () -> shutdown.get(100, TimeUnit.MILLISECONDS));
            assertEquals(409, assertThrows(AwsException.class, () -> put(1)).getHttpStatus());
            assertEquals(0, first.stops.get());
            launcher.launchGate.complete(null);
            shutdown.get(3, TimeUnit.SECONDS);
            assertFalse(first.alive);
            assertEquals(1, first.stops.get());
            pool.stopManagedContainers();
            assertEquals(1, first.stops.get());
        } finally {
            launcher.launchGate.complete(null);
            shutdown.get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void pendingLaunchPreservesShutdownDeadlineAndCleansUpWhenItReturns() throws Exception {
        launcher.launchGate = new CompletableFuture<>();
        put(1);
        Environment first = launched();
        first.initialized.complete(null);
        CompletableFuture<Void> shutdown = CompletableFuture.runAsync(() ->
                pool.stopManagedContainers(System.nanoTime() + TimeUnit.SECONDS.toNanos(1)));
        try {
            shutdown.get(3, TimeUnit.SECONDS);
            pool.stopManagedContainers();
            assertEquals(0, first.stops.get());
            assertEquals(409, assertThrows(AwsException.class, () -> put(1)).getHttpStatus());
        } finally {
            launcher.launchGate.complete(null);
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> !first.alive);
        assertEquals(1, first.stops.get());
        pool.stopManagedContainers();
        assertEquals(1, first.stops.get());
        pool.clear();
        assertEquals(1, first.stops.get());
        assertTrue(storage.scan(ignored -> true).isEmpty());
    }

    @Test
    void suppliedShutdownDeadlineCancelsRemovalAndResetCanRetry() throws Exception {
        put(1);
        Environment environment = launched();
        environment.initialized.complete(null);
        ready();
        launcher.stopGate = new CompletableFuture<>();
        CompletableFuture<Void> drained = CompletableFuture.runAsync(() ->
                pool.stopManagedContainers(System.nanoTime() + TimeUnit.SECONDS.toNanos(1)));
        try {
            assertNotNull(launcher.stopping.poll(2, TimeUnit.SECONDS));
            drained.get(3, TimeUnit.SECONDS);
            assertTrue(environment.alive, "Canceled Docker removal must remain owned");
            pool.stopManagedContainers();
            assertEquals(1, environment.stops.get(), "The destruction path must not renew the deadline");
        } finally {
            launcher.stopGate.complete(null);
        }
        pool.clear();
        await().atMost(Duration.ofSeconds(5)).until(() -> {
            pool.reconcileSafely();
            return !environment.alive;
        });
        int completedAttempts = environment.stops.get();
        pool.stopManagedContainers();
        assertEquals(completedAttempts, environment.stops.get());
    }

    @Test
    void configuredBudgetBoundsResetAndNormalRemovalAndRetainsRetry() throws Exception {
        pool.shutdown();
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.LambdaServiceConfig lambda = mock(EmulatorConfig.LambdaServiceConfig.class);
        StorageFactory factory = mock(StorageFactory.class);
        when(config.services()).thenReturn(services);
        when(services.lambda()).thenReturn(lambda);
        when(lambda.warmPoolMaxPerFunction()).thenReturn(Optional.of(4));
        when(lambda.warmPoolMaxTotal()).thenReturn(8);
        when(lambda.containerDrainTimeoutSeconds()).thenReturn(1);
        when(factory.<ProvisionedConcurrencyPool.Configuration>create(
                eq("lambda"), eq("lambda-provisioned-concurrency.json"), any())).thenReturn(storage);
        pool = new ProvisionedConcurrencyPool(launcher, functions, aliases, factory, config, Clock.systemUTC());
        put(1);
        Environment first = launched();
        first.initialized.complete(null);
        ready();
        first.stopGate = new CompletableFuture<>();
        CompletableFuture<Void> removal = CompletableFuture.runAsync(() ->
                pool.delete(REGION, "example", "live", ACCOUNT));
        try {
            assertSame(first, launcher.stopping.poll(2, TimeUnit.SECONDS));
            removal.get(3, TimeUnit.SECONDS);
            assertTrue(first.alive);
        } finally {
            first.stopGate.complete(null);
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> {
            pool.reconcileSafely();
            return !first.alive;
        });

        put(1);
        Environment second = launched();
        second.initialized.complete(null);
        ready();
        second.stopGate = new CompletableFuture<>();
        CompletableFuture<Void> reset = CompletableFuture.runAsync(pool::clear);
        try {
            assertSame(first, launcher.stopping.poll(2, TimeUnit.SECONDS));
            assertSame(second, launcher.stopping.poll(2, TimeUnit.SECONDS));
            reset.get(3, TimeUnit.SECONDS);
            assertTrue(second.alive);
        } finally {
            second.stopGate.complete(null);
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> {
            pool.reconcileSafely();
            return !second.alive;
        });
        assertTrue(storage.scan(ignored -> true).isEmpty());
    }

    @Test
    void interruptedDrainRetainsEveryOwnedEnvironmentForRetry() throws Exception {
        pool.shutdown();
        pool = new ProvisionedConcurrencyPool(
                launcher, functions, aliases, storage, Clock.systemUTC(), 32, 32, false, 15);
        put(32);
        List<Environment> environments = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            Environment environment = launched();
            environments.add(environment);
            environment.initialized.complete(null);
        }
        ready();
        launcher.stopGate = new CompletableFuture<>();
        AtomicBoolean interruptionPreserved = new AtomicBoolean();
        Thread drain = Thread.ofPlatform().start(() -> {
            pool.stopManagedContainers();
            interruptionPreserved.set(Thread.currentThread().isInterrupted());
        });
        try {
            for (int i = 0; i < environments.size(); i++) {
                assertNotNull(launcher.stopping.poll(2, TimeUnit.SECONDS));
            }
            assertEquals(environments.size(),
                    environments.stream().mapToInt(environment -> environment.stops.get()).sum());
            drain.interrupt();
            drain.join(5000);
            assertFalse(drain.isAlive());
            assertTrue(interruptionPreserved.get());
        } finally {
            drain.interrupt();
            launcher.stopGate.complete(null);
            drain.join(5000);
        }
        int attempts = environments.stream().mapToInt(environment -> environment.stops.get()).sum();
        pool.stopManagedContainers();
        pool.reconcileSafely();
        assertEquals(attempts, environments.stream().mapToInt(environment -> environment.stops.get()).sum());
        // A deliberate reset reopens normal reconciliation; repeated shutdown never does.
        pool.clear();
        await().atMost(Duration.ofSeconds(5)).until(() -> {
            pool.reconcileSafely();
            return environments.stream().noneMatch(environment -> environment.alive);
        });
        assertTrue(environments.stream().allMatch(environment -> environment.stops.get() > 0));
    }

    @Test
    void failedShutdownIsNotRetriedByReconciliationOrBeanDestruction() throws Exception {
        put(1);
        Environment first = launched();
        first.initialized.complete(null);
        ready();
        launcher.stopFailures.set(1);

        pool.stopManagedContainers();
        assertTrue(first.alive);
        assertEquals(1, first.stops.get());
        pool.reconcileSafely();
        pool.stopManagedContainers();
        pool.shutdown();

        assertTrue(first.alive, "A failed stop must remain visible rather than imply cleanup succeeded");
        assertEquals(1, first.stops.get(), "Shutdown must not renew the drain budget");
    }

    @Test
    void reconciliationCannotRetryWhileTheShutdownDrainIsRunning() throws Exception {
        put(2);
        Environment held = launched();
        Environment failed = launched();
        held.initialized.complete(null);
        failed.initialized.complete(null);
        ready();
        held.stopGate = new CompletableFuture<>();
        launcher.stopFailures.set(1);
        CompletableFuture<Void> drained = CompletableFuture.runAsync(pool::stopManagedContainers);
        try {
            assertNotNull(launcher.stopping.poll(2, TimeUnit.SECONDS));
            assertNotNull(launcher.stopping.poll(2, TimeUnit.SECONDS));
            await().atMost(Duration.ofSeconds(2)).until(() -> launcher.stopFailures.get() == 0);
            assertFalse(drained.isDone());

            pool.reconcileSafely();
            assertEquals(1, failed.stops.get(), "The scheduler must not compete with shutdown");
            assertEquals(1, held.stops.get());
        } finally {
            held.stopGate.complete(null);
            drained.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void shutdownWaitsForAnAdmittedRetryWithoutStoppingTheHandleTwice() throws Exception {
        put(1);
        Environment first = launched();
        first.initialized.complete(null);
        ready();
        function(ACCOUNT, REGION, "2");
        pool.put(REGION, "example", "2", 1, ACCOUNT);
        Environment second = launched();
        second.initialized.complete(null);
        await().atMost(Duration.ofSeconds(2)).until(() ->
                "READY".equals(pool.get(REGION, "example", "2", ACCOUNT).status()));
        launcher.stopFailures.set(1);
        pool.delete(REGION, "example", "live", ACCOUNT);
        launcher.stopping.clear();
        first.stopGate = new CompletableFuture<>();
        try (ExecutorService tasks = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Void> retry = CompletableFuture.runAsync(pool::reconcileSafely, tasks);
            CompletableFuture<Void> shutdown = null;
            try {
                assertSame(first, launcher.stopping.poll(2, TimeUnit.SECONDS));
                shutdown = CompletableFuture.runAsync(pool::stopManagedContainers, tasks);
                assertSame(second, launcher.stopping.poll(2, TimeUnit.SECONDS),
                        "Shutdown has admitted its own handle while joining the earlier retry");
                CompletableFuture<Void> drain = shutdown;
                assertThrows(TimeoutException.class, () -> drain.get(100, TimeUnit.MILLISECONDS));
                assertEquals(2, first.stops.get());
            } finally {
                first.stopGate.complete(null);
                retry.get(5, TimeUnit.SECONDS);
                if (shutdown != null) {
                    shutdown.get(5, TimeUnit.SECONDS);
                }
            }
        }
        assertFalse(first.alive);
        assertFalse(second.alive);
        assertEquals(2, first.stops.get());
        assertEquals(1, second.stops.get());
        pool.shutdown();
        assertEquals(2, first.stops.get());
    }

    @Test
    void configurationValidationHasNoLaunchSideEffects() {
        assertThrows(AwsException.class, () -> put(0));
        assertThrows(AwsException.class, () -> put(5));
        assertThrows(AwsException.class, () -> pool.put(REGION, "example", null, 1, ACCOUNT));
        assertThrows(AwsException.class, () -> pool.put(REGION, "example", "$LATEST", 1, ACCOUNT));
        assertThrows(AwsException.class, () -> pool.put(REGION, "absent", "live", 1, ACCOUNT));
        assertThrows(AwsException.class, () -> pool.put(REGION, "example", "2", 1, ACCOUNT));
        alias(ACCOUNT, REGION, "weighted", "1", Map.of("2", 0.1));
        assertThrows(AwsException.class, () -> pool.put(REGION, "example", "weighted", 1, ACCOUNT));
        launcher.supported = false;
        assertThrows(AwsException.class, () -> put(1));
        assertEquals(0, launcher.starts.get());
    }

    @Test
    void reservationsAndMultipleQualifiersShareTheFunctionCeiling() throws Exception {
        LambdaFunction latest = functions.getForAccount(ACCOUNT, REGION, "example").orElseThrow();
        latest.setReservedConcurrentExecutions(1);
        assertThrows(AwsException.class, () -> put(2));
        put(1);
        launched();
        assertThrows(AwsException.class, () -> pool.put(REGION, "example", "1", 1, ACCOUNT));
        assertThrows(AwsException.class, () -> pool.validateReservation(latest, 0));
    }

    @Test
    void deletingOneAccountPreservesTheSameFunctionInAnotherAccount() throws Exception {
        function("222222222222", REGION, "$LATEST");
        function("222222222222", REGION, "1");
        alias("222222222222", REGION, "live", "1", Map.of());
        put(1);
        Environment first = launched();
        pool.put(REGION, "example", "live", 1, "222222222222");
        Environment second = launched();
        first.initialized.complete(null);
        second.initialized.complete(null);
        ready();
        pool.deleteFunction(version);
        assertEquals(1, first.stops.get());
        assertEquals(0, second.stops.get());
        await().atMost(Duration.ofSeconds(5)).until(() ->
                "READY".equals(pool.get(REGION, "example", "live", "222222222222").status()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void storedConfigurationSurvivesFactoryRestartAndDeletedConfigurationStaysDeleted(String mode) throws Exception {
        pool.shutdown();
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.services().lambda().executor()).thenReturn("docker");
        when(config.services().lambda().containerDrainTimeoutSeconds()).thenReturn(15);
        when(config.storage().persistentPath()).thenReturn(storagePath.toString());
        when(config.storage().wal().compactionIntervalMs()).thenReturn(60_000L);
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode("lambda")).thenReturn(mode);
        when(access.storageFlushInterval("lambda")).thenReturn(60_000L);
        for (int restart = 0; restart < 3; restart++) {
            StorageFactory factory = new StorageFactory(config, access);
            try {
                pool = new ProvisionedConcurrencyPool(launcher, functions, aliases, factory, config, Clock.systemUTC());
                pool.start();
                if (restart == 0) {
                    put(2);
                    launched().initialized.complete(null);
                    launched().initialized.complete(null);
                    ready();
                    assertEquals(get(), pool.list(REGION, "example", ACCOUNT, null, null).items().getFirst().status());
                } else if (restart == 1) {
                    assertEquals(2, get().requested());
                    assertEquals("IN_PROGRESS", get().status());
                    assertEquals(0, get().allocated());
                    assertEquals(get(), pool.list(REGION, "example", ACCOUNT, null, null).items().getFirst().status());
                    launched().initialized.complete(null);
                    launched().initialized.complete(null);
                    ready();
                    pool.delete(REGION, "example", "live", ACCOUNT);
                } else {
                    assertThrows(AwsException.class, this::get);
                    assertTrue(pool.list(REGION, "example", ACCOUNT, null, null).items().isEmpty());
                }
            } finally {
                pool.shutdown();
                factory.shutdownAll();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void configuredAliasCannotRetargetToCapacityOwnedByAnotherQualifier(boolean otherAlias) throws Exception {
        function(ACCOUNT, REGION, "2");
        alias(ACCOUNT, REGION, "peer", "2", Map.of());
        put(1);
        launched().initialized.complete(null);
        ready();
        String destination = otherAlias ? "peer" : "2";
        pool.put(REGION, "example", destination, 1, ACCOUNT);
        launched().initialized.complete(null);
        AwsException conflict = assertThrows(AwsException.class,
                () -> pool.validateAliasUpdate(version, "live", "2"));
        assertEquals("ResourceConflictException", conflict.getErrorCode());
        assertEquals(409, conflict.getHttpStatus());
        assertEquals("Alias can't be used for Provisioned Concurrency configuration on an already Provisioned version",
                conflict.getMessage());
        assertEquals(1, get().requested());
        assertEquals(2, storage.scanAllAccounts().size());
        assertDoesNotThrow(() -> pool.validateAliasUpdate(version, "live", null));
        assertDoesNotThrow(() -> pool.validateAliasUpdate(version, "live", "1"));
        assertDoesNotThrow(() -> pool.validateAliasUpdate(version, "unconfigured", "2"));
        pool.delete(REGION, "example", destination, ACCOUNT);
        assertDoesNotThrow(() -> pool.validateAliasUpdate(version, "live", "2"));
    }

    private static final class Environment {
        final RuntimeApiServer runtime = mock(RuntimeApiServer.class);
        final CompletableFuture<Void> initialized = new CompletableFuture<>();
        final AtomicInteger stops = new AtomicInteger();
        final AtomicBoolean faulted = new AtomicBoolean();
        final ContainerHandle handle;
        volatile CompletableFuture<Void> stopGate = CompletableFuture.completedFuture(null);
        volatile boolean alive = true;

        Environment(int id, LambdaFunction fn) throws Exception {
            handle = new ContainerHandle("environment-" + id, fn.getFunctionName(), runtime, ContainerState.WARM);
            doAnswer(call -> {
                initialized.get(5, TimeUnit.SECONDS);
                return null;
            }).when(runtime).awaitInitialization(anyLong());
            when(runtime.isInitialized()).thenAnswer(call ->
                    initialized.isDone() && !initialized.isCompletedExceptionally() && alive && !faulted.get());
        }
    }

    private static final class Launcher implements LambdaRuntimeLauncher {
        final BlockingQueue<Environment> launched = new LinkedBlockingQueue<>();
        final BlockingQueue<Environment> stopping = new LinkedBlockingQueue<>();
        final Map<ContainerHandle, Environment> environments = new ConcurrentHashMap<>();
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger stopFailures = new AtomicInteger();
        volatile CompletableFuture<Void> launchGate = CompletableFuture.completedFuture(null);
        volatile CompletableFuture<Void> stopGate = CompletableFuture.completedFuture(null);
        boolean supported = true;
        boolean rejectHealthChecks;

        @Override
        public ContainerHandle launch(LambdaFunction fn) {
            throw new AssertionError("Provisioning must not invoke the on-demand launch path");
        }

        @Override
        public boolean supportsProvisionedConcurrency() {
            return supported;
        }

        @Override
        public ContainerHandle launchProvisioned(LambdaFunction fn) {
            try {
                Environment environment = new Environment(starts.incrementAndGet(), fn);
                environments.put(environment.handle, environment);
                launched.add(environment);
                launchGate.get(5, TimeUnit.SECONDS);
                return environment.handle;
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }

        @Override
        public boolean isAlive(ContainerHandle handle) {
            if (rejectHealthChecks) {
                throw new AssertionError("Provisioned health must use runtime fault/exit signals");
            }
            return environments.get(handle).alive;
        }

        @Override
        public void stop(ContainerHandle handle) {
            Environment environment = environments.get(handle);
            environment.stops.incrementAndGet();
            stopping.add(environment);
            try {
                stopGate.get(5, TimeUnit.SECONDS);
                environment.stopGate.get(5, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
            if (stopFailures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                throw new IllegalStateException("controlled first stop failure");
            }
            environment.alive = false;
            environment.initialized.completeExceptionally(new IllegalStateException("stopped"));
        }
    }
}
