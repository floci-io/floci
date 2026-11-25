package io.floci.aws.services.mwaa;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.docker.PortAllocator;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.mwaa.model.CreateEnvironmentRequest;
import io.floci.aws.services.mwaa.model.Environment;
import io.floci.aws.services.mwaa.model.EnvironmentRuntime;
import io.floci.aws.services.mwaa.model.EnvironmentStatus;
import io.floci.aws.services.mwaa.model.NetworkConfiguration;
import io.floci.aws.services.mwaa.model.UpdateEnvironmentRequest;
import io.floci.aws.services.mwaa.proxy.MwaaProxyManager;
import io.floci.aws.services.mwaa.proxy.MwaaWebProxy;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import io.floci.aws.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MwaaServiceTest {

    private MwaaService mwaaService;
    private MutableClock clock;
    private AccountAwareStorageBackend<Environment> environmentStorage;
    private String currentAccount;
    private String currentRegion;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = new StorageFactory(null, null) {
            @Override
            public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                    TypeReference<Map<String, V>> typeReference) {
                if (!"mwaa-environments.json".equals(fileName)) {
                    return AccountAwareStorageBackend.inMemory("000000000000");
                }
                environmentStorage = AccountAwareStorageBackend.<Environment>inMemory("000000000000");
                return (AccountAwareStorageBackend<V>) (AccountAwareStorageBackend<?>) environmentStorage;
            }
        };

        EmulatorConfig config = testConfig();
        currentAccount = "000000000000";
        currentRegion = "us-east-1";
        RegionResolver regionResolver = Mockito.spy(new RegionResolver("us-east-1", "000000000000"));
        Mockito.doAnswer(invocation -> currentAccount).when(regionResolver).getAccountId();
        Mockito.doAnswer(invocation -> currentRegion).when(regionResolver).getRegion();
        S3Service s3Service = Mockito.mock(S3Service.class);
        clock = new MutableClock();
        mwaaService = new MwaaService(storageFactory, config, regionResolver, null, null, null, s3Service,
                Runnable::run, clock);
    }

    @Test
    void sameNameEnvironmentsAreIsolatedByAccountAndRegion() {
        currentAccount = "111111111111";
        currentRegion = "us-east-1";
        Environment eastEnvironment = mwaaService.createEnvironment("shared-name",
                createRequest("arn:aws:s3:::east-bucket", "dags"));

        currentAccount = "222222222222";
        currentRegion = "eu-west-1";
        Environment westEnvironment = mwaaService.createEnvironment("shared-name",
                createRequest("arn:aws:s3:::west-bucket", "dags"));

        assertNotEquals(eastEnvironment.getArn(), westEnvironment.getArn());
        assertEquals(westEnvironment.getArn(), mwaaService.getEnvironment("shared-name").getArn());
        assertEquals(List.of("shared-name"), mwaaService.listEnvironments());

        currentAccount = "111111111111";
        currentRegion = "us-east-1";
        assertEquals(eastEnvironment.getArn(), mwaaService.getEnvironment("shared-name").getArn());
        assertEquals(List.of("shared-name"), mwaaService.listEnvironments());
    }

    @Test
    void deletingOneScopedEnvironmentDoesNotDeleteAnotherWithTheSameName() {
        currentAccount = "111111111111";
        currentRegion = "us-east-1";
        Environment eastEnvironment = mwaaService.createEnvironment("shared-name",
                createRequest("arn:aws:s3:::east-bucket", "dags"));

        currentAccount = "222222222222";
        currentRegion = "eu-west-1";
        mwaaService.createEnvironment("shared-name", createRequest("arn:aws:s3:::west-bucket", "dags"));
        mwaaService.deleteEnvironment("shared-name");

        currentAccount = "111111111111";
        currentRegion = "us-east-1";
        assertEquals(eastEnvironment.getArn(), mwaaService.getEnvironment("shared-name").getArn());
    }

    @Test
    void cliTokensAreIsolatedForSameNameEnvironments() {
        currentAccount = "111111111111";
        currentRegion = "us-east-1";
        Environment eastEnvironment = mwaaService.createEnvironment("shared-name",
                createRequest("arn:aws:s3:::east-bucket", "dags"));
        String eastToken = (String) mwaaService.createCliToken("shared-name").get("CliToken");

        currentAccount = "222222222222";
        currentRegion = "eu-west-1";
        Environment westEnvironment = mwaaService.createEnvironment("shared-name",
                createRequest("arn:aws:s3:::west-bucket", "dags"));
        String westToken = (String) mwaaService.createCliToken("shared-name").get("CliToken");

        assertTrue(mwaaService.isValidCliToken(mwaaService.environmentIdentity(westEnvironment), westToken));
        assertFalse(mwaaService.isValidCliToken(mwaaService.environmentIdentity(westEnvironment), eastToken));
        assertTrue(mwaaService.isValidCliToken(mwaaService.environmentIdentity(eastEnvironment), eastToken));
        assertFalse(mwaaService.isValidCliToken(mwaaService.environmentIdentity(eastEnvironment), westToken));
    }

    @Test
    void legacyEnvironmentIsMigratedOnlyWhenItsAccountAndRegionMatch() {
        Environment legacy = new Environment();
        legacy.setName("legacy-env");
        legacy.setArn("arn:aws:airflow:us-east-1:111111111111:environment/legacy-env");
        environmentStorage.putForAccount("111111111111", "legacy-env", legacy);

        currentAccount = "111111111111";
        currentRegion = "us-east-1";
        assertEquals(legacy, mwaaService.getEnvironment("legacy-env"));

        Environment wrongRegion = new Environment();
        wrongRegion.setName("foreign-env");
        wrongRegion.setArn("arn:aws:airflow:eu-west-1:222222222222:environment/foreign-env");
        environmentStorage.putForAccount("222222222222", "foreign-env", wrongRegion);

        currentAccount = "222222222222";
        currentRegion = "us-east-1";
        assertThrows(AwsException.class, () -> mwaaService.getEnvironment("foreign-env"));
    }

    @Test
    void listMigratesMatchingLegacyEnvironmentAndRemovesTheOldKey() {
        Environment legacy = new Environment();
        legacy.setName("legacy-list-env");
        legacy.setArn("arn:aws:airflow:us-east-1:111111111111:environment/legacy-list-env");
        environmentStorage.putForAccount("111111111111", "legacy-list-env", legacy);

        currentAccount = "111111111111";
        currentRegion = "us-east-1";

        assertEquals(List.of("legacy-list-env"), mwaaService.listEnvironments());
        assertTrue(environmentStorage.getForAccount("111111111111", "legacy-list-env").isEmpty());
        assertTrue(environmentStorage.getForAccount("111111111111", "us-east-1/legacy-list-env").isPresent());
    }

    @Test
    void promotionDoesNotLeaveASecondLegacyEnvironmentEntry() {
        Environment legacy = new Environment();
        legacy.setName("promoted-env");
        legacy.setArn("arn:aws:airflow:us-east-1:111111111111:environment/promoted-env");
        legacy.setStatus(EnvironmentStatus.CREATING);
        environmentStorage.putForAccount("111111111111", "promoted-env", legacy);

        currentAccount = "111111111111";
        currentRegion = "us-east-1";
        legacy.setStatus(EnvironmentStatus.AVAILABLE);
        mwaaService.putEnvironment(legacy);

        assertEquals(List.of("promoted-env"), mwaaService.listEnvironments());
        assertTrue(environmentStorage.getForAccount("111111111111", "promoted-env").isEmpty());
    }

    private EmulatorConfig testConfig() {
        return testConfig(List.of("2.10.5", "2.9.3", "2.8.4"));
    }

    private EmulatorConfig testConfig(List<String> supportedVersions) {
        EmulatorConfig.MwaaServiceConfig mwaaConfig = proxy(EmulatorConfig.MwaaServiceConfig.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "enabled", "mock" -> true;
                    case "supportedVersions" -> supportedVersions;
                    case "defaultVersion" -> "2.10.5";
                    case "proxyBasePort" -> 8700;
                    case "proxyMaxPort" -> 8799;
                    case "dagSyncIntervalSeconds" -> 30;
                    default -> defaultValue(method);
                });
        EmulatorConfig.ServicesConfig servicesConfig = proxy(EmulatorConfig.ServicesConfig.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "mwaa" -> mwaaConfig;
                    default -> defaultValue(method);
                });
        EmulatorConfig.TlsConfig tlsConfig = proxy(EmulatorConfig.TlsConfig.class,
                (proxy, method, args) -> defaultValue(method));
        return proxy(EmulatorConfig.class, (proxy, method, args) -> switch (method.getName()) {
            case "services" -> servicesConfig;
            case "tls" -> tlsConfig;
            case "defaultRegion" -> "us-east-1";
            case "defaultAccountId" -> "000000000000";
            case "hostname" -> Optional.empty();
            default -> defaultValue(method);
        });
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> type.getSimpleName() + "TestProxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> method.invoke(this, args);
                };
            }
            return handler.invoke(proxy, method, args);
        });
    }

    private Object defaultValue(Method method) {
        Class<?> returnType = method.getReturnType();
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == Optional.class) {
            return Optional.empty();
        }
        if (returnType == String.class) {
            return "";
        }
        if (returnType == List.class) {
            return List.of();
        }
        return null;
    }

    private CreateEnvironmentRequest createRequest(String bucketArn, String dagPath) {
        CreateEnvironmentRequest request = new CreateEnvironmentRequest();
        request.setExecutionRoleArn("arn:aws:iam::000000000000:role/mwaa-execution-role");
        request.setSourceBucketArn(bucketArn);
        request.setDagS3Path(dagPath);
        NetworkConfiguration network = new NetworkConfiguration();
        network.setSubnetIds(List.of("subnet-1", "subnet-2"));
        network.setSecurityGroupIds(List.of("sg-1"));
        request.setNetworkConfiguration(network);
        return request;
    }

    @Test
    void anUpdateChangesAnEnvironmentsSecurityGroupsButNotItsSubnets() {
        mwaaService.createEnvironment("network-env", createRequest("arn:aws:s3:::my-bucket", "dags"));
        NetworkConfiguration requested = new NetworkConfiguration();
        requested.setSubnetIds(List.of("subnet-9"));
        requested.setSecurityGroupIds(List.of("sg-2"));
        UpdateEnvironmentRequest request = new UpdateEnvironmentRequest();
        request.setNetworkConfiguration(requested);

        Environment updated = mwaaService.updateEnvironment("network-env", request);

        assertEquals(List.of("subnet-1", "subnet-2"), updated.getNetworkConfiguration().getSubnetIds());
        assertEquals(List.of("sg-2"), updated.getNetworkConfiguration().getSecurityGroupIds());
    }

    @Test
    void aSubnetAnEnvironmentUsesCannotBeDeletedUntilTheEnvironmentIsGone() {
        mwaaService.createEnvironment("subnet-env", createRequest("arn:aws:s3:::my-bucket", "dags"));

        assertTrue(mwaaService.hasSubnetDependency("us-east-1", "subnet-2"));
        assertFalse(mwaaService.hasSubnetDependency("us-east-1", "subnet-3"));
        assertFalse(mwaaService.hasSubnetDependency("eu-west-1", "subnet-2"));
        mwaaService.deleteEnvironment("subnet-env");
        assertFalse(mwaaService.hasSubnetDependency("us-east-1", "subnet-2"));
    }

    @Test
    void createEnvironment() {
        Environment environment = mwaaService.createEnvironment("test-env",
                createRequest("arn:aws:s3:::my-bucket", "dags"));

        assertNotNull(environment);
        assertEquals("test-env", environment.getName());
        assertEquals(EnvironmentStatus.AVAILABLE, environment.getStatus());
        assertTrue(environment.getArn().contains("airflow"));
        assertTrue(environment.getArn().contains("environment/test-env"));
        assertEquals("2.10.5", environment.getAirflowVersion());
        assertNotNull(environment.getWebserverUrl());
        assertNotNull(environment.getCreatedAt());
    }

    @Test
    void createEnvironmentDuplicateFails() {
        mwaaService.createEnvironment("dup-env", createRequest("arn:aws:s3:::my-bucket", "dags"));

        AwsException ex = assertThrows(AwsException.class,
                () -> mwaaService.createEnvironment("dup-env", createRequest("arn:aws:s3:::my-bucket", "dags")));
        assertEquals(400, ex.getHttpStatus());
        // CreateEnvironment's botocore model declares no "already exists" shape.
        assertEquals("ValidationException", ex.getErrorCode());
    }

    @Test
    void createEnvironmentWithUnsupportedVersionFails() {
        CreateEnvironmentRequest request = createRequest("arn:aws:s3:::my-bucket", "dags");
        request.setAirflowVersion("1.10.15");

        AwsException ex = assertThrows(AwsException.class,
                () -> mwaaService.createEnvironment("bad-version-env", request));
        assertEquals(400, ex.getHttpStatus());
        assertEquals("ValidationException", ex.getErrorCode());
    }

    @Test
    void createEnvironmentRejectsAMalformedConfiguredVersionInsteadOfCrashing() {
        // supported-versions is operator-configurable, so a stray non-numeric entry like "latest"
        // must be rejected as a clean ValidationException here, not reach
        // MwaaEnvironmentManager.pythonTagFor and surface as an internal NumberFormatException
        // well after Postgres has already been created for the environment.
        StorageFactory storageFactory = new StorageFactory(null, null) {
            @Override
            public synchronized <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                    TypeReference<Map<String, V>> typeReference) {
                return AccountAwareStorageBackend.inMemory("000000000000");
            }
        };
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        S3Service s3Service = Mockito.mock(S3Service.class);
        MwaaService misconfiguredService = new MwaaService(storageFactory, testConfig(List.of("2.10.5", "latest")),
                regionResolver, null, null, null, s3Service);

        CreateEnvironmentRequest request = createRequest("arn:aws:s3:::my-bucket", "dags");
        request.setAirflowVersion("latest");

        AwsException ex = assertThrows(AwsException.class,
                () -> misconfiguredService.createEnvironment("latest-version-env", request));
        assertEquals(400, ex.getHttpStatus());
        assertEquals("ValidationException", ex.getErrorCode());
    }

    @Test
    void createEnvironmentWithSupportedVersionRoundTrips() {
        CreateEnvironmentRequest request = createRequest("arn:aws:s3:::my-bucket", "dags");
        request.setAirflowVersion("2.9.3");

        Environment environment = mwaaService.createEnvironment("versioned-env", request);
        assertEquals("2.9.3", environment.getAirflowVersion());
    }

    @Test
    void createEnvironmentWithValidAirflowConfigurationOptionsRoundTrips() {
        CreateEnvironmentRequest request = createRequest("arn:aws:s3:::my-bucket", "dags");
        request.setAirflowConfigurationOptions(Map.of("core.dags_are_paused_at_creation", "false"));

        Environment environment = mwaaService.createEnvironment("config-options-env", request);
        assertEquals(Map.of("core.dags_are_paused_at_creation", "false"),
                environment.getAirflowConfigurationOptions());
    }

    @Test
    void createEnvironmentRejectsAnOverlongAirflowConfigurationOptionKey() {
        CreateEnvironmentRequest request = createRequest("arn:aws:s3:::my-bucket", "dags");
        request.setAirflowConfigurationOptions(Map.of("core." + "x".repeat(64), "value"));

        AwsException ex = assertThrows(AwsException.class,
                () -> mwaaService.createEnvironment("bad-key-length-env", request));
        assertEquals(400, ex.getHttpStatus());
        assertEquals("ValidationException", ex.getErrorCode());
    }

    @Test
    void createEnvironmentRejectsAnAirflowConfigurationOptionKeyWithInvalidCharacters() {
        CreateEnvironmentRequest request = createRequest("arn:aws:s3:::my-bucket", "dags");
        request.setAirflowConfigurationOptions(Map.of("Core.DagsArePaused", "false"));

        AwsException ex = assertThrows(AwsException.class,
                () -> mwaaService.createEnvironment("bad-key-chars-env", request));
        assertEquals(400, ex.getHttpStatus());
        assertEquals("ValidationException", ex.getErrorCode());
    }

    @Test
    void createEnvironmentRejectsAnOverlongAirflowConfigurationOptionValue() {
        CreateEnvironmentRequest request = createRequest("arn:aws:s3:::my-bucket", "dags");
        request.setAirflowConfigurationOptions(Map.of("core.some_key", "x".repeat(65537)));

        AwsException ex = assertThrows(AwsException.class,
                () -> mwaaService.createEnvironment("bad-value-length-env", request));
        assertEquals(400, ex.getHttpStatus());
        assertEquals("ValidationException", ex.getErrorCode());
    }

    @Test
    void createEnvironmentRejectsANonPrintableAirflowConfigurationOptionValue() {
        CreateEnvironmentRequest request = createRequest("arn:aws:s3:::my-bucket", "dags");
        request.setAirflowConfigurationOptions(Map.of("core.some_key", "line one\nline two"));

        AwsException ex = assertThrows(AwsException.class,
                () -> mwaaService.createEnvironment("bad-value-chars-env", request));
        assertEquals(400, ex.getHttpStatus());
        assertEquals("ValidationException", ex.getErrorCode());
    }

    @Test
    void getEnvironment() {
        mwaaService.createEnvironment("my-env", createRequest("arn:aws:s3:::my-bucket", "dags"));

        Environment described = mwaaService.getEnvironment("my-env");
        assertEquals("my-env", described.getName());
    }

    @Test
    void getEnvironmentNotFound() {
        AwsException ex = assertThrows(AwsException.class, () -> mwaaService.getEnvironment("nonexistent"));
        assertEquals(404, ex.getHttpStatus());
    }

    @Test
    void listEnvironments() {
        mwaaService.createEnvironment("env-a", createRequest("arn:aws:s3:::my-bucket", "dags"));
        mwaaService.createEnvironment("env-b", createRequest("arn:aws:s3:::my-bucket", "dags"));

        List<String> names = mwaaService.listEnvironments();
        assertEquals(2, names.size());
        assertTrue(names.contains("env-a"));
        assertTrue(names.contains("env-b"));
    }

    @Test
    void deleteEnvironment() {
        mwaaService.createEnvironment("to-delete", createRequest("arn:aws:s3:::my-bucket", "dags"));

        Environment deleted = mwaaService.deleteEnvironment("to-delete");
        assertEquals(EnvironmentStatus.DELETING, deleted.getStatus());
        assertTrue(mwaaService.listEnvironments().isEmpty());
    }

    @Test
    void updateEnvironmentAppliesMetadataFields() {
        mwaaService.createEnvironment("update-env", createRequest("arn:aws:s3:::my-bucket", "dags"));

        UpdateEnvironmentRequest request = new UpdateEnvironmentRequest();
        request.setEnvironmentClass("mw1.large");
        request.setDagS3Path("dags-v2");

        Environment updated = mwaaService.updateEnvironment("update-env", request);
        assertNotNull(updated.getArn());

        Environment described = mwaaService.getEnvironment("update-env");
        assertEquals("mw1.large", described.getEnvironmentClass());
        assertEquals("dags-v2", described.getDagS3Path());
        assertEquals("SUCCESS", described.getLastUpdate().getStatus());
    }

    @Test
    void updateEnvironmentRejectsAirflowVersionChange() {
        mwaaService.createEnvironment("no-version-change-env", createRequest("arn:aws:s3:::my-bucket", "dags"));

        UpdateEnvironmentRequest request = new UpdateEnvironmentRequest();
        request.setAirflowVersion("2.9.3");

        AwsException ex = assertThrows(AwsException.class,
                () -> mwaaService.updateEnvironment("no-version-change-env", request));
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void updateEnvironmentRejectsAirflowConfigurationOptionsChange() {
        mwaaService.createEnvironment("no-config-change-env", createRequest("arn:aws:s3:::my-bucket", "dags"));

        UpdateEnvironmentRequest request = new UpdateEnvironmentRequest();
        request.setAirflowConfigurationOptions(Map.of("core.parallelism", "10"));

        AwsException ex = assertThrows(AwsException.class,
                () -> mwaaService.updateEnvironment("no-config-change-env", request));
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void updateEnvironmentRejectsStartupScriptS3PathChange() {
        CreateEnvironmentRequest createRequest = createRequest("arn:aws:s3:::my-bucket", "dags");
        createRequest.setStartupScriptS3Path("startup.sh");
        Environment environment = mwaaService.createEnvironment("no-startup-script-change-env", createRequest);
        assertEquals("startup.sh", environment.getStartupScriptS3Path());

        UpdateEnvironmentRequest request = new UpdateEnvironmentRequest();
        request.setStartupScriptS3Path("startup-v2.sh");

        AwsException ex = assertThrows(AwsException.class,
                () -> mwaaService.updateEnvironment("no-startup-script-change-env", request));
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void taggingOperations() {
        Environment environment = mwaaService.createEnvironment("tagged-env",
                createRequest("arn:aws:s3:::my-bucket", "dags"));
        String arn = environment.getArn();

        mwaaService.tagResource(null, arn, Map.of("env", "test", "team", "data"));
        Map<String, String> tags = mwaaService.listTags(null, arn);
        assertEquals("test", tags.get("env"));
        assertEquals("data", tags.get("team"));

        mwaaService.untagResource(null, arn, List.of("env"));
        tags = mwaaService.listTags(null, arn);
        assertFalse(tags.containsKey("env"));
        assertEquals("data", tags.get("team"));
    }

    @Test
    void taggingRejectsAResourceArnOutsideTheRequestScope() {
        currentAccount = "111111111111";
        currentRegion = "us-east-1";
        Environment first = mwaaService.createEnvironment("shared-name",
                createRequest("arn:aws:s3:::first-bucket", "dags"));

        currentAccount = "222222222222";
        currentRegion = "eu-west-1";
        mwaaService.createEnvironment("shared-name", createRequest("arn:aws:s3:::second-bucket", "dags"));

        AwsException exception = assertThrows(AwsException.class,
                () -> mwaaService.tagResource(null, first.getArn(), Map.of("owner", "first")));
        assertEquals("ResourceNotFoundException", exception.getErrorCode());
    }

    @Test
    void tagHandlerServiceKeyIsAirflow() {
        assertEquals("airflow", mwaaService.serviceKey());
        assertEquals("Tags", mwaaService.tagsBodyKey());
    }

    @Test
    void createWebLoginTokenReturnsTokenAndHostname() {
        mwaaService.createEnvironment("web-token-env", createRequest("arn:aws:s3:::my-bucket", "dags"));

        Map<String, Object> response = mwaaService.createWebLoginToken("web-token-env");
        assertNotNull(response.get("WebToken"));
        assertNotNull(response.get("WebServerHostname"));
        assertEquals("admin", response.get("AirflowIdentity"));
    }

    @Test
    void webLoginTokenIsValidForSixtySecondsAndOnlyForItsEnvironment() {
        mwaaService.createEnvironment("web-login-env", createRequest("arn:aws:s3:::my-bucket", "dags"));
        String identity = mwaaService.environmentIdentity(mwaaService.getEnvironment("web-login-env"));
        String token = (String) mwaaService.createWebLoginToken("web-login-env").get("WebToken");

        assertTrue(mwaaService.isValidWebToken(identity, token));
        assertFalse(mwaaService.isValidWebToken("other-env", token));
        assertFalse(mwaaService.isValidWebToken(identity, "not-a-real-token"));

        clock.advance(Duration.ofSeconds(60));

        assertFalse(mwaaService.isValidWebToken(identity, token));
    }

    @Test
    void cliTokensAndWebLoginTokensAreNotInterchangeable() {
        mwaaService.createEnvironment("two-tokens-env", createRequest("arn:aws:s3:::my-bucket", "dags"));
        String identity = mwaaService.environmentIdentity(mwaaService.getEnvironment("two-tokens-env"));
        String cliToken = (String) mwaaService.createCliToken("two-tokens-env").get("CliToken");
        String webToken = (String) mwaaService.createWebLoginToken("two-tokens-env").get("WebToken");

        assertFalse(mwaaService.isValidWebToken(identity, cliToken));
        assertFalse(mwaaService.isValidCliToken(identity, webToken));
    }

    @Test
    void deletingAnEnvironmentDropsItsWebLoginTokens() {
        mwaaService.createEnvironment("deleted-web-env", createRequest("arn:aws:s3:::my-bucket", "dags"));
        String identity = mwaaService.environmentIdentity(mwaaService.getEnvironment("deleted-web-env"));
        String token = (String) mwaaService.createWebLoginToken("deleted-web-env").get("WebToken");

        mwaaService.deleteEnvironment("deleted-web-env");

        assertFalse(mwaaService.isValidWebToken(identity, token));
    }

    @Test
    void createCliTokenIsValidatedByIsValidCliToken() {
        mwaaService.createEnvironment("cli-token-env", createRequest("arn:aws:s3:::my-bucket", "dags"));

        Map<String, Object> response = mwaaService.createCliToken("cli-token-env");
        String token = (String) response.get("CliToken");
        assertNotNull(token);
        assertTrue(mwaaService.isValidCliToken(mwaaService.environmentIdentity(
                mwaaService.getEnvironment("cli-token-env")), token));
        assertFalse(mwaaService.isValidCliToken(mwaaService.environmentIdentity(
                mwaaService.getEnvironment("cli-token-env")), "not-a-real-token"));
        assertFalse(mwaaService.isValidCliToken("other-env", token));
    }

    /** Exercises the real (non-mock) create/delete path — the outer class's {@code mwaaService}
     *  always runs with {@code mock=true}, which never touches {@code PortAllocator} at all. */
    @Nested
    class RealModeLifecycle {

        private PortAllocator portAllocator;
        private MwaaEnvironmentManager environmentManager;
        private MwaaProxyManager proxyManager;
        private MwaaService realModeService;
        private S3Service s3Service;
        private final List<Runnable> queuedInstalls = new ArrayList<>();
        private boolean keepRunningOnShutdown;
        private String storageMode = "memory";
        private AccountAwareStorageBackend<Environment> environmentStore;
        private AccountAwareStorageBackend<EnvironmentRuntime> runtimeStore;

        @BeforeEach
        @SuppressWarnings("unchecked")
        void setUpRealMode() {
            StorageFactory storageFactory = new StorageFactory(null, null) {
                @Override
                public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                        TypeReference<Map<String, V>> typeReference) {
                    AccountAwareStorageBackend<V> store = AccountAwareStorageBackend.inMemory("000000000000");
                    if ("mwaa-environments.json".equals(fileName)) {
                        // A spy, so a test can tell a write to storage from a change to the instance it holds.
                        store = Mockito.spy(store);
                        environmentStore = (AccountAwareStorageBackend<Environment>) store;
                    } else {
                        runtimeStore = (AccountAwareStorageBackend<EnvironmentRuntime>) store;
                    }
                    return store;
                }
            };

            EmulatorConfig.MwaaServiceConfig mwaaConfig = proxy(EmulatorConfig.MwaaServiceConfig.class,
                    (proxy, method, args) -> switch (method.getName()) {
                        case "enabled" -> true;
                        case "mock" -> false;
                        case "supportedVersions" -> List.of("2.10.5", "2.9.3", "2.8.4");
                        case "defaultVersion" -> "2.10.5";
                        case "proxyBasePort" -> 8700;
                        case "proxyMaxPort" -> 8799;
                        case "dagSyncIntervalSeconds" -> 30;
                        case "keepRunningOnShutdown" -> keepRunningOnShutdown;
                        case "installRequirements" -> true;
                        default -> defaultValue(method);
                    });
            EmulatorConfig.ServicesConfig servicesConfig = proxy(EmulatorConfig.ServicesConfig.class,
                    (proxy, method, args) -> switch (method.getName()) {
                        case "mwaa" -> mwaaConfig;
                        default -> defaultValue(method);
                    });
            EmulatorConfig.TlsConfig tlsConfig = proxy(EmulatorConfig.TlsConfig.class,
                    (proxy, method, args) -> defaultValue(method));
            EmulatorConfig.StorageConfig storageConfig = proxy(EmulatorConfig.StorageConfig.class,
                    (proxy, method, args) -> "mode".equals(method.getName()) ? storageMode : defaultValue(method));
            EmulatorConfig config = proxy(EmulatorConfig.class, (proxy, method, args) -> switch (method.getName()) {
                case "services" -> servicesConfig;
                case "tls" -> tlsConfig;
                case "storage" -> storageConfig;
                case "defaultRegion" -> "us-east-1";
                case "defaultAccountId" -> "000000000000";
                case "hostname" -> Optional.of("floci.test");
                default -> defaultValue(method);
            });

            RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
            s3Service = Mockito.mock(S3Service.class);
            environmentManager = Mockito.mock(MwaaEnvironmentManager.class);
            proxyManager = Mockito.mock(MwaaProxyManager.class);
            portAllocator = Mockito.mock(PortAllocator.class);
            when(portAllocator.allocateForInProcessListener(8700, 8799)).thenReturn(8701);
            when(s3Service.listObjectsWithPrefixes(any(), any(), any(), anyInt(), any(), any()))
                    .thenReturn(new S3Service.ListObjectsResult(List.of(), List.of(), false, null));

            realModeService = new MwaaService(storageFactory, config, regionResolver,
                    environmentManager, proxyManager, portAllocator, s3Service, queuedInstalls::add, Clock.systemUTC());
        }

        @Test
        void theProxySignsWebLoginsInAsTheEnvironmentsAdminAfterCheckingTheToken() {
            Mockito.doAnswer(invocation -> {
                invocation.getArgument(0, Environment.class).setAirflowAdminPassword("generated-pw");
                return null;
            }).when(environmentManager).startEnvironment(any(), any(), any(), any());

            Environment environment = realModeService.createEnvironment("login-env",
                    createRequest("arn:aws:s3:::my-bucket", "dags"));
            String token = (String) realModeService.createWebLoginToken("login-env").get("WebToken");

            ArgumentCaptor<MwaaWebProxy.TokenValidator> webTokenValidator =
                    ArgumentCaptor.forClass(MwaaWebProxy.TokenValidator.class);
            ArgumentCaptor<MwaaWebProxy.AirflowAccount> account = ArgumentCaptor.forClass(MwaaWebProxy.AirflowAccount.class);
            verify(proxyManager).startProxy(any(), anyInt(), any(), anyInt(), any(), any(), webTokenValidator.capture(),
                    account.capture());
            assertEquals(new MwaaWebProxy.AirflowAccount("admin", "generated-pw"), account.getValue());
            String identity = realModeService.environmentIdentity(environment);
            assertTrue(webTokenValidator.getValue().isValid(identity, token));
            assertFalse(webTokenValidator.getValue().isValid(identity, "not-a-real-token"));
        }

        @Test
        void eachProxyAcceptsOnlyTheTokensOfItsOwnEnvironmentNotOfALaterOneOfTheSameName() {
            ArgumentCaptor<MwaaWebProxy.TokenValidator> validators =
                    ArgumentCaptor.forClass(MwaaWebProxy.TokenValidator.class);
            Environment first = realModeService.createEnvironment("reused-env",
                    createRequest("arn:aws:s3:::my-bucket", "dags"));
            String identity = realModeService.environmentIdentity(first);
            String firstToken = (String) realModeService.createWebLoginToken("reused-env").get("WebToken");
            realModeService.deleteEnvironment("reused-env");
            realModeService.createEnvironment("reused-env", createRequest("arn:aws:s3:::my-bucket", "dags"));
            String secondToken = (String) realModeService.createWebLoginToken("reused-env").get("WebToken");

            verify(proxyManager, Mockito.times(2)).startProxy(any(), anyInt(), any(), anyInt(), any(), any(),
                    validators.capture(), any());
            MwaaWebProxy.TokenValidator firstProxy = validators.getAllValues().get(0);
            MwaaWebProxy.TokenValidator secondProxy = validators.getAllValues().get(1);
            assertTrue(secondProxy.isValid(identity, secondToken));
            assertFalse(secondProxy.isValid(identity, firstToken));
            assertFalse(firstProxy.isValid(identity, secondToken));
        }

        @Test
        void deletingAnEnvironmentReleasesItsProxyPort() {
            Environment environment = realModeService.createEnvironment("real-env",
                    createRequest("arn:aws:s3:::my-bucket", "dags"));
            assertEquals(8701, environment.getProxyPort());

            realModeService.deleteEnvironment("real-env");

            verify(portAllocator).release(8701);
        }

        @Test
        void shutdownLeavesEnvironmentContainersRunningWhenRetentionIsEnabled() {
            keepRunningOnShutdown = true;
            realModeService.createEnvironment("kept-env", createRequest("arn:aws:s3:::my-bucket", "dags"));

            realModeService.shutdown();

            verify(environmentManager, Mockito.never()).stopEnvironment(any());
        }

        @Test
        void proxyStartupFailureRollsBackTheAllocatedPortAndAnyStartedContainers() {
            // Containers start fine; only the proxy bind fails (e.g. the port got taken out from
            // under us between allocate() and bind()). The port must not leak, and whatever
            // startEnvironment() already started must get torn down, not orphaned.
            Mockito.doThrow(new RuntimeException("bind failed"))
                    .when(proxyManager).startProxy(any(), anyInt(), any(), anyInt(), any(), any(), any(), any());

            Environment environment = realModeService.createEnvironment("failed-proxy-env",
                    createRequest("arn:aws:s3:::my-bucket", "dags"));

            assertEquals(EnvironmentStatus.CREATE_FAILED, environment.getStatus());
            verify(portAllocator).release(8701);
            verify(environmentManager).stopEnvironment(environment);
            verify(proxyManager).stopProxy(mwaaService.environmentIdentity(environment));
        }

        private Environment createWithRequirements(String name, byte[] requirements) {
            return createWithRequirements(name, requirements, 0);
        }

        private Environment createWithRequirements(String name, byte[] requirements, int bootstrapExitCode) {
            when(s3Service.getObject("my-bucket", "requirements.txt"))
                    .thenReturn(new S3Object("my-bucket", "requirements.txt", requirements, "text/plain"));
            when(environmentManager.lastRequirementsInstallExitCode(any())).thenReturn(bootstrapExitCode);
            CreateEnvironmentRequest request = createRequest("arn:aws:s3:::my-bucket", "dags");
            request.setRequirementsS3Path("requirements.txt");
            Environment environment = realModeService.createEnvironment(name, request);
            when(environmentManager.isReady(environment)).thenReturn(true);
            realModeService.checkReadiness(environment);
            return environment;
        }

        @Test
        void requirementsReachTheContainerBeforeAirflowStartsAndTheFirstSyncDoesNotReinstall() {
            byte[] requirements = "boto3==1.35.0\n".getBytes(StandardCharsets.UTF_8);

            Environment environment = createWithRequirements("req-env", requirements);
            realModeService.syncDags(environment);

            verify(environmentManager).startEnvironment(environment, "2.10.5", null, requirements);
            assertTrue(queuedInstalls.isEmpty());
        }

        @Test
        void aFailedBootstrapInstallIsInstalledAgainByTheFirstSync() {
            byte[] requirements = "boto3==1.35.0\n".getBytes(StandardCharsets.UTF_8);
            Environment environment = createWithRequirements("req-failed-env", requirements, 1);

            realModeService.syncDags(environment);
            queuedInstalls.forEach(Runnable::run);

            verify(environmentManager).installRequirements(environment, requirements);
        }

        @Test
        void aReinstallStillRunningIsNotStartedAgain() {
            Environment environment = createWithRequirements("req-slow-env",
                    "boto3==1.35.0\n".getBytes(StandardCharsets.UTF_8));
            when(s3Service.getObject("my-bucket", "requirements.txt")).thenReturn(new S3Object("my-bucket",
                    "requirements.txt", "boto3==1.36.0\n".getBytes(StandardCharsets.UTF_8), "text/plain"));

            realModeService.syncDags(environment);
            realModeService.syncDags(environment);

            assertEquals(1, queuedInstalls.size());
        }

        @Test
        void changedRequirementsAreReinstalledByTheNextSync() {
            Environment environment = createWithRequirements("req-change-env",
                    "boto3==1.35.0\n".getBytes(StandardCharsets.UTF_8));
            byte[] changed = "boto3==1.36.0\n".getBytes(StandardCharsets.UTF_8);
            when(s3Service.getObject("my-bucket", "requirements.txt"))
                    .thenReturn(new S3Object("my-bucket", "requirements.txt", changed, "text/plain"));

            realModeService.syncDags(environment);
            queuedInstalls.forEach(Runnable::run);
            realModeService.syncDags(environment);

            verify(environmentManager).installRequirements(environment, changed);
            assertEquals(1, queuedInstalls.size());
        }

        @Test
        void anS3FaultReadingRequirementsFailsTheEnvironment() {
            when(s3Service.getObject("my-bucket", "requirements.txt")).thenThrow(new AwsException("InternalError",
                    "We encountered an internal error. Please try again.", 500));
            CreateEnvironmentRequest request = createRequest("arn:aws:s3:::my-bucket", "dags");
            request.setRequirementsS3Path("requirements.txt");

            Environment environment = realModeService.createEnvironment("faulty-req-env", request);

            assertEquals(EnvironmentStatus.CREATE_FAILED, environment.getStatus());
            verify(environmentManager, Mockito.never()).startEnvironment(any(), any(), any(), any());
        }

        @Test
        void aMissingRequirementsObjectDoesNotFailTheEnvironment() {
            when(s3Service.getObject("my-bucket", "requirements.txt")).thenThrow(new AwsException("NoSuchKey",
                    "The specified key does not exist.", 404));
            CreateEnvironmentRequest request = createRequest("arn:aws:s3:::my-bucket", "dags");
            request.setRequirementsS3Path("requirements.txt");

            Environment environment = realModeService.createEnvironment("missing-req-env", request);

            assertEquals(EnvironmentStatus.CREATING, environment.getStatus());
            verify(environmentManager).startEnvironment(environment, "2.10.5", null, null);
        }

        @Test
        void checkReadinessMarksEnvironmentAvailableOnceReady() {
            Environment environment = realModeService.createEnvironment("ready-env",
                    createRequest("arn:aws:s3:::my-bucket", "dags"));
            when(environmentManager.isReady(environment)).thenReturn(true);

            realModeService.checkReadiness(environment);

            assertEquals(EnvironmentStatus.AVAILABLE, environment.getStatus());
        }

        @Test
        void checkReadinessLeavesEnvironmentCreatingWhileContainerIsStillStartingUp() {
            Environment environment = realModeService.createEnvironment("starting-env",
                    createRequest("arn:aws:s3:::my-bucket", "dags"));
            when(environmentManager.isReady(environment)).thenReturn(false);
            when(environmentManager.hasAnyContainerExited(environment)).thenReturn(false);

            realModeService.checkReadiness(environment);

            assertEquals(EnvironmentStatus.CREATING, environment.getStatus());
        }

        @Test
        void checkReadinessMarksCreateFailedWhenAContainerHasExited() {
            // e.g. a startup script or `airflow db migrate` failed after docker start returned, or
            // the sibling Postgres container died; without this, the environment would poll dead
            // containers forever and never leave CREATING.
            Environment environment = realModeService.createEnvironment("crashed-env",
                    createRequest("arn:aws:s3:::my-bucket", "dags"));
            when(environmentManager.isReady(environment)).thenReturn(false);
            when(environmentManager.hasAnyContainerExited(environment)).thenReturn(true);

            realModeService.checkReadiness(environment);

            assertEquals(EnvironmentStatus.CREATE_FAILED, environment.getStatus());
        }

        @Test
        void checkReadinessIgnoresEnvironmentsNotInCreatingStatus() {
            Environment environment = realModeService.createEnvironment("available-env",
                    createRequest("arn:aws:s3:::my-bucket", "dags"));
            environment.setStatus(EnvironmentStatus.AVAILABLE);

            realModeService.checkReadiness(environment);

            assertEquals(EnvironmentStatus.AVAILABLE, environment.getStatus());
            verify(environmentManager, Mockito.never()).isReady(any());
            verify(environmentManager, Mockito.never()).hasAnyContainerExited(any());
        }

        @Test
        void checkReadinessDoesNotOverwriteAStatusChangedConcurrentlyWhileWaitingOnIsReady() {
            // isReady() is a blocking HTTP call; simulate a concurrent DeleteEnvironment moving the
            // same Environment instance to DELETING while that call is in flight.
            Environment environment = realModeService.createEnvironment("deleted-mid-check-env",
                    createRequest("arn:aws:s3:::my-bucket", "dags"));
            when(environmentManager.isReady(environment)).thenAnswer(invocation -> {
                environment.setStatus(EnvironmentStatus.DELETING);
                return true;
            });

            realModeService.checkReadiness(environment);

            assertEquals(EnvironmentStatus.DELETING, environment.getStatus());
        }

        @Test
        void checkReadinessDoesNotOverwriteAStatusChangedConcurrentlyWhileWaitingOnContainerExitCheck() {
            // Same race, on the hasAnyContainerExited() branch: without the re-check, this would
            // resurrect a just-deleted environment into storage as CREATE_FAILED.
            Environment environment = realModeService.createEnvironment("deleted-mid-crash-check-env",
                    createRequest("arn:aws:s3:::my-bucket", "dags"));
            when(environmentManager.isReady(environment)).thenReturn(false);
            when(environmentManager.hasAnyContainerExited(environment)).thenAnswer(invocation -> {
                environment.setStatus(EnvironmentStatus.DELETING);
                return true;
            });

            realModeService.checkReadiness(environment);

            assertEquals(EnvironmentStatus.DELETING, environment.getStatus());
        }

        @Test
        void createKeepsTheSecretsOfTheEnvironmentsDatabaseForARestartAndDeleteDropsThem() {
            Mockito.doAnswer(invocation -> {
                Environment started = invocation.getArgument(0, Environment.class);
                started.setDbPassword("db-pw");
                started.setFernetKey("fernet-key");
                started.setAirflowAdminPassword("admin-pw");
                return null;
            }).when(environmentManager).startEnvironment(any(), any(), any(), any());

            Environment environment = realModeService.createEnvironment("secret-env",
                    createRequest("arn:aws:s3:::my-bucket", "dags"));

            EnvironmentRuntime runtime = runtimeStore.getForAccount("000000000000", "us-east-1/secret-env")
                    .orElseThrow();
            assertEquals(environment.getCreatedAt(), runtime.getEnvironmentCreatedAt());
            assertEquals("db-pw", runtime.getDbPassword());
            assertEquals("fernet-key", runtime.getFernetKey());
            assertEquals("admin-pw", runtime.getAirflowAdminPassword());

            realModeService.deleteEnvironment("secret-env");

            assertTrue(runtimeStore.getForAccount("000000000000", "us-east-1/secret-env").isEmpty());
        }

        @Test
        void shutdownKeepsTheVolumesOfEnvironmentsStoredForTheNextStart() {
            storageMode = "persistent";
            Environment environment = realModeService.createEnvironment("persisted-env",
                    createRequest("arn:aws:s3:::my-bucket", "dags"));

            realModeService.shutdown();

            InOrder inOrder = Mockito.inOrder(environmentManager);
            inOrder.verify(environmentManager).markShuttingDown();
            inOrder.verify(environmentManager).stopContainers(environment);
            verify(environmentManager, Mockito.never()).stopEnvironment(any());
            verify(proxyManager).stopProxy(realModeService.environmentIdentity(environment));
        }

        @Test
        void shutdownRemovesTheVolumesOfEnvironmentsKeptInMemoryOnly() {
            Environment environment = realModeService.createEnvironment("memory-env",
                    createRequest("arn:aws:s3:::my-bucket", "dags"));

            realModeService.shutdown();

            verify(environmentManager).stopEnvironment(environment);
            verify(environmentManager, Mockito.never()).stopContainers(any());
        }

        @Test
        void shutdownStopsRestoresEvenWhenTheContainersAreKeptRunning() {
            keepRunningOnShutdown = true;
            storageMode = "persistent";
            realModeService.createEnvironment("kept-running-env", createRequest("arn:aws:s3:::my-bucket", "dags"));

            realModeService.shutdown();

            verify(environmentManager).markShuttingDown();
            verify(environmentManager, Mockito.never()).stopContainers(any());
        }

        @Test
        void restoreStartsAnAvailableEnvironmentsContainersAgainWithTheSecretsItKept() {
            Environment stored = storedEnvironment("kept-env", EnvironmentStatus.AVAILABLE);
            storedSecrets(stored);
            Mockito.doAnswer(invocation -> {
                Environment environment = invocation.getArgument(0, Environment.class);
                assertEquals("saved-db-pw", environment.getDbPassword());
                assertEquals("saved-fernet-key", environment.getFernetKey());
                return null;
            }).when(environmentManager).restartEnvironment(any(), any(), any(), any());

            realModeService.restorePersistedRuntime().join();

            verify(environmentManager).restartEnvironment(any(), eq("2.10.5"), isNull(), isNull());
            verify(environmentManager, Mockito.never()).startEnvironment(any(), any(), any(), any());
            verify(environmentManager, Mockito.never()).recoverSecrets(any());
            verify(environmentManager, Mockito.never()).giveNewSecrets(any());
            verify(portAllocator).markReserved(8705);
            ArgumentCaptor<MwaaWebProxy.AirflowAccount> account = ArgumentCaptor.forClass(MwaaWebProxy.AirflowAccount.class);
            verify(proxyManager).startProxy(eq("000000000000/us-east-1/kept-env"), eq(8705), any(), anyInt(), any(),
                    any(), any(), account.capture());
            assertEquals(new MwaaWebProxy.AirflowAccount("admin", "saved-admin-pw"), account.getValue());
            Environment restored = stored("kept-env");
            assertEquals(EnvironmentStatus.MAINTENANCE, restored.getStatus());
            assertEquals("http://floci.test:8705", restored.getWebserverUrl());
            assertEquals("saved-db-pw", runtimeStore.getForAccount("000000000000", "us-east-1/kept-env")
                    .orElseThrow().getDbPassword());
        }

        @Test
        void restoreInstallsTheRequirementsInTheNewContainerAsCreateDoesAndTheFirstSyncDoesNotAgain() {
            byte[] requirements = "boto3==1.35.0\n".getBytes(StandardCharsets.UTF_8);
            when(s3Service.getObject("my-bucket", "requirements.txt"))
                    .thenReturn(new S3Object("my-bucket", "requirements.txt", requirements, "text/plain"));
            when(environmentManager.lastRequirementsInstallExitCode(any())).thenReturn(0);
            Environment stored = storedEnvironment("req-restore-env", EnvironmentStatus.AVAILABLE);
            stored.setRequirementsS3Path("requirements.txt");
            storedSecrets(stored);

            realModeService.restorePersistedRuntime().join();
            stored.setStatus(EnvironmentStatus.AVAILABLE);
            realModeService.syncDags(stored);

            verify(environmentManager).restartEnvironment(any(), eq("2.10.5"), isNull(), eq(requirements));
            assertTrue(queuedInstalls.isEmpty());
        }

        @Test
        void theFirstSyncAfterARestoreRemovesDagsTheBucketNoLongerHasFromTheVolumeItKept() {
            Environment stored = storedEnvironment("pruned-env", EnvironmentStatus.AVAILABLE);
            storedSecrets(stored);
            byte[] kept = "kept".getBytes(StandardCharsets.UTF_8);
            S3Object keptDag = new S3Object("my-bucket", "dags/kept.py", kept, "text/x-python");
            when(s3Service.listObjectsWithPrefixes("my-bucket", "dags/", null, 1000, null, null))
                    .thenReturn(new S3Service.ListObjectsResult(List.of(keptDag), List.of(), false, null));
            when(s3Service.getObject("my-bucket", "dags/kept.py")).thenReturn(keptDag);
            when(environmentManager.listDagFiles(stored)).thenReturn(List.of("kept.py", "deleted.py"));
            realModeService.restorePersistedRuntime().join();
            stored.setStatus(EnvironmentStatus.AVAILABLE);

            realModeService.syncDags(stored);
            realModeService.syncDags(stored);

            verify(environmentManager).removeDagFile(stored, "deleted.py");
            verify(environmentManager).copyDagFile(stored, "kept.py", kept);
            verify(environmentManager).listDagFiles(stored);
        }

        @Test
        void aDagPastTheFirstPageOfTheBucketListingIsNotRemoved() {
            Environment environment = realModeService.createEnvironment("paged-env",
                    createRequest("arn:aws:s3:::my-bucket", "dags"));
            environment.setStatus(EnvironmentStatus.AVAILABLE);
            S3Object first = new S3Object("my-bucket", "dags/a.py", "a".getBytes(StandardCharsets.UTF_8), "text/plain");
            S3Object second = new S3Object("my-bucket", "dags/b.py", "b".getBytes(StandardCharsets.UTF_8), "text/plain");
            when(s3Service.listObjectsWithPrefixes("my-bucket", "dags/", null, 1000, null, null))
                    .thenReturn(new S3Service.ListObjectsResult(List.of(first), List.of(), true, "dags/a.py"));
            when(s3Service.listObjectsWithPrefixes("my-bucket", "dags/", null, 1000, "dags/a.py", null))
                    .thenReturn(new S3Service.ListObjectsResult(List.of(second), List.of(), false, null));
            when(s3Service.getObject("my-bucket", "dags/a.py")).thenReturn(first);
            when(s3Service.getObject("my-bucket", "dags/b.py")).thenReturn(second);
            when(environmentManager.listDagFiles(environment)).thenReturn(List.of("a.py", "b.py"));

            realModeService.syncDags(environment);

            verify(environmentManager, Mockito.never()).removeDagFile(any(), any());
            verify(environmentManager).copyDagFile(environment, "b.py", second.getData());
        }

        @Test
        void aRestoreForgetsTheDagsSyncedBeforeItSoItsFirstSyncReadsTheVolumeAgain() {
            Environment environment = realModeService.createEnvironment("resynced-env",
                    createRequest("arn:aws:s3:::my-bucket", "dags"));
            environment.setStatus(EnvironmentStatus.AVAILABLE);
            realModeService.syncDags(environment);

            realModeService.restorePersistedRuntime().join();
            environment.setStatus(EnvironmentStatus.AVAILABLE);
            realModeService.syncDags(environment);

            verify(environmentManager, Mockito.times(2)).listDagFiles(environment);
        }

        @Test
        void anEnvironmentIsUnderMaintenanceAndLeftToItsRestoreUntilItsContainersAreBack() throws Exception {
            Environment stored = storedEnvironment("maintained-env", EnvironmentStatus.AVAILABLE);
            storedSecrets(stored);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Mockito.doAnswer(invocation -> {
                started.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                return null;
            }).when(environmentManager).restartEnvironment(any(), any(), any(), any());

            CompletableFuture<Void> restore = realModeService.restorePersistedRuntime();
            assertEquals(EnvironmentStatus.MAINTENANCE, stored("maintained-env").getStatus());
            assertTrue(started.await(10, TimeUnit.SECONDS));
            realModeService.checkReadiness(stored);
            verify(environmentManager, Mockito.never()).isReady(any());
            release.countDown();
            restore.get(10, TimeUnit.SECONDS);

            when(environmentManager.isReady(stored)).thenReturn(true);
            realModeService.checkReadiness(stored);
            assertEquals(EnvironmentStatus.AVAILABLE, stored("maintained-env").getStatus());
        }

        @Test
        void checkReadinessMarksARestoredEnvironmentUnavailableWhenAContainerHasExited() {
            Environment environment = realModeService.createEnvironment("restored-crash-env",
                    createRequest("arn:aws:s3:::my-bucket", "dags"));
            environment.setStatus(EnvironmentStatus.MAINTENANCE);
            when(environmentManager.isReady(environment)).thenReturn(false);
            when(environmentManager.hasAnyContainerExited(environment)).thenReturn(true);

            realModeService.checkReadiness(environment);

            assertEquals(EnvironmentStatus.UNAVAILABLE, environment.getStatus());
        }

        @Test
        void restoreTakesOverContainersThatOutlivedFloci() {
            Environment stored = storedEnvironment("survivor-env", EnvironmentStatus.AVAILABLE);
            when(environmentManager.adoptRunningEnvironment(any())).thenAnswer(invocation -> {
                invocation.getArgument(0, Environment.class).setAirflowAdminPassword("running-admin-pw");
                return true;
            });

            realModeService.restorePersistedRuntime().join();

            verify(environmentManager, Mockito.never()).restartEnvironment(any(), any(), any(), any());
            verify(environmentManager, Mockito.never()).startEnvironment(any(), any(), any(), any());
            Mockito.verifyNoInteractions(s3Service);
            verify(proxyManager).startProxy(eq("000000000000/us-east-1/survivor-env"), eq(8705), any(), anyInt(),
                    any(), any(), any(), eq(new MwaaWebProxy.AirflowAccount("admin", "running-admin-pw")));
            assertEquals(EnvironmentStatus.MAINTENANCE, stored.getStatus());
        }

        @Test
        void anEnvironmentStoredBeforeFlociKeptItsSecretsStartsAnewAndKeepsItsNewOnes() {
            storedEnvironment("legacy-secrets-env", EnvironmentStatus.AVAILABLE);
            Mockito.doAnswer(invocation -> {
                Environment started = invocation.getArgument(0, Environment.class);
                started.setDbPassword("new-db-pw");
                started.setFernetKey("new-fernet-key");
                started.setAirflowAdminPassword("new-admin-pw");
                return null;
            }).when(environmentManager).giveNewSecrets(any());

            realModeService.restorePersistedRuntime().join();

            InOrder inOrder = Mockito.inOrder(environmentManager);
            inOrder.verify(environmentManager).giveNewSecrets(any());
            inOrder.verify(environmentManager).restartEnvironment(any(), eq("2.10.5"), isNull(), isNull());
            EnvironmentRuntime runtime = runtimeStore.getForAccount("000000000000", "us-east-1/legacy-secrets-env")
                    .orElseThrow();
            assertEquals("new-db-pw", runtime.getDbPassword());
            assertEquals("new-fernet-key", runtime.getFernetKey());
        }

        @Test
        void anEnvironmentStoredBeforeFlociKeptItsSecretsGetsThemBackFromAContainerLeftUnderItsName() {
            storedEnvironment("recovered-env", EnvironmentStatus.AVAILABLE);
            when(environmentManager.recoverSecrets(any())).thenAnswer(invocation -> {
                Environment environment = invocation.getArgument(0, Environment.class);
                environment.setDbPassword("left-db-pw");
                environment.setFernetKey("left-fernet-key");
                environment.setAirflowAdminPassword("left-admin-pw");
                return true;
            });

            realModeService.restorePersistedRuntime().join();

            verify(environmentManager).restartEnvironment(any(), eq("2.10.5"), isNull(), isNull());
            verify(environmentManager, Mockito.never()).giveNewSecrets(any());
            assertEquals("left-fernet-key", runtimeStore.getForAccount("000000000000", "us-east-1/recovered-env")
                    .orElseThrow().getFernetKey());
        }

        @Test
        void aDockerThatCannotSayWhatIsLeftLeavesTheEnvironmentUnavailableWithNothingRemoved() {
            Environment stored = storedEnvironment("unanswered-env", EnvironmentStatus.AVAILABLE);
            when(environmentManager.adoptRunningEnvironment(any()))
                    .thenThrow(new IllegalStateException("daemon unreachable"));

            assertThrows(CompletionException.class, () -> realModeService.restorePersistedRuntime().join());

            assertEquals(EnvironmentStatus.UNAVAILABLE, stored("unanswered-env").getStatus());
            verify(environmentManager, Mockito.never()).giveNewSecrets(any());
            verify(environmentManager, Mockito.never()).restartEnvironment(any(), any(), any(), any());
            verify(environmentManager, Mockito.never()).startEnvironment(any(), any(), any(), any());
            verify(environmentManager, Mockito.never()).stopEnvironment(any());
        }

        @Test
        void secretsFoundOrGivenAreKeptBeforeTheStartSoAStartThatFailsIsTriedAgainWithThem() {
            storedEnvironment("retried-with-new-env", EnvironmentStatus.AVAILABLE);
            Mockito.doAnswer(invocation -> {
                Environment environment = invocation.getArgument(0, Environment.class);
                environment.setDbPassword("new-db-pw");
                environment.setFernetKey("new-fernet-key");
                environment.setAirflowAdminPassword("new-admin-pw");
                return null;
            }).when(environmentManager).giveNewSecrets(any());
            Mockito.doThrow(new IllegalStateException("Postgres did not start"))
                    .when(environmentManager).restartEnvironment(any(), any(), any(), any());

            assertThrows(CompletionException.class, () -> realModeService.restorePersistedRuntime().join());

            assertEquals(EnvironmentStatus.UNAVAILABLE, stored("retried-with-new-env").getStatus());
            assertEquals("new-db-pw", runtimeStore.getForAccount("000000000000", "us-east-1/retried-with-new-env")
                    .orElseThrow().getDbPassword());
        }

        @Test
        void secretsFoundForAnEnvironmentWhoseRecordWentMeanwhileAreNotKept() {
            storedEnvironment("went-while-recovering-env", EnvironmentStatus.AVAILABLE);
            when(environmentManager.recoverSecrets(any())).thenAnswer(invocation -> {
                environmentStore.deleteForAccount("000000000000", "us-east-1/went-while-recovering-env");
                invocation.getArgument(0, Environment.class).setDbPassword("left-db-pw");
                return true;
            });

            realModeService.restorePersistedRuntime().join();

            assertTrue(runtimeStore.getForAccount("000000000000", "us-east-1/went-while-recovering-env").isEmpty());
        }

        @Test
        void theSecretsOfContainersTakenOverAreKeptEvenWhenTheRestoreThenFails() {
            storedEnvironment("taken-over-env", EnvironmentStatus.AVAILABLE);
            when(environmentManager.adoptRunningEnvironment(any())).thenAnswer(invocation -> {
                Environment environment = invocation.getArgument(0, Environment.class);
                environment.setDbPassword("running-db-pw");
                environment.setFernetKey("running-fernet-key");
                environment.setAirflowAdminPassword("running-admin-pw");
                return true;
            });
            Mockito.doThrow(new RuntimeException("Address already in use"))
                    .when(proxyManager).startProxy(any(), anyInt(), any(), anyInt(), any(), any(), any(), any());

            assertThrows(CompletionException.class, () -> realModeService.restorePersistedRuntime().join());

            assertEquals(EnvironmentStatus.UNAVAILABLE, stored("taken-over-env").getStatus());
            assertEquals("running-fernet-key", runtimeStore.getForAccount("000000000000", "us-east-1/taken-over-env")
                    .orElseThrow().getFernetKey());
        }

        @Test
        void anEnvironmentGoneBeforeItsRestoreIsLeftAloneAndOneCreatedAfterIsTreatedAsAnyOther() throws Exception {
            storedSecrets(storedEnvironment("gone-early-env", EnvironmentStatus.AVAILABLE));
            CountDownLatch reached = new CountDownLatch(1);
            CountDownLatch proceed = new CountDownLatch(1);
            when(environmentManager.isShuttingDown()).thenAnswer(invocation -> {
                reached.countDown();
                assertTrue(proceed.await(10, TimeUnit.SECONDS));
                return false;
            });

            CompletableFuture<Void> restore = realModeService.restorePersistedRuntime();
            assertTrue(reached.await(10, TimeUnit.SECONDS));
            environmentStore.deleteForAccount("000000000000", "us-east-1/gone-early-env");
            proceed.countDown();
            restore.get(10, TimeUnit.SECONDS);

            verify(environmentManager, Mockito.never()).adoptRunningEnvironment(any());
            verify(environmentManager, Mockito.never()).restartEnvironment(any(), any(), any(), any());
            verify(portAllocator).release(8705);
            Environment recreated = storedEnvironment("gone-early-env", EnvironmentStatus.CREATING);
            recreated.setCreatedAt(Instant.parse("2026-11-20T00:00:00Z"));
            realModeService.checkReadiness(recreated);
            verify(environmentManager).isReady(recreated);
        }

        @Test
        void anEnvironmentDeletedDuringARestoreThatFailsIsRemoved() throws Exception {
            Environment stored = storedEnvironment("deleted-failing-env", EnvironmentStatus.AVAILABLE);
            storedSecrets(stored);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Mockito.doAnswer(invocation -> {
                started.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                throw new IllegalStateException("Airflow did not start");
            }).when(environmentManager).restartEnvironment(any(), any(), any(), any());

            CompletableFuture<Void> restore = realModeService.restorePersistedRuntime();
            assertTrue(started.await(10, TimeUnit.SECONDS));
            realModeService.deleteEnvironment("deleted-failing-env");
            release.countDown();

            assertThrows(CompletionException.class, restore::join);
            verify(environmentManager).stopEnvironment(stored);
            assertTrue(environmentStore.getForAccount("000000000000", "us-east-1/deleted-failing-env").isEmpty());
            assertTrue(runtimeStore.getForAccount("000000000000", "us-east-1/deleted-failing-env").isEmpty());
        }

        @Test
        void theSecretsOfAnEarlierEnvironmentOfTheSameNameAreNotUsed() {
            Environment stored = storedEnvironment("renewed-env", EnvironmentStatus.AVAILABLE);
            storedSecrets(stored);
            runtimeStore.getForAccount("000000000000", "us-east-1/renewed-env").orElseThrow()
                    .setEnvironmentCreatedAt(Instant.parse("2026-10-01T00:00:00Z"));

            realModeService.restorePersistedRuntime().join();

            verify(environmentManager).recoverSecrets(any());
            verify(environmentManager).giveNewSecrets(any());
        }

        @Test
        void anEnvironmentStoredUnderALegacyKeyIsRestoredUnderItsKey() {
            Environment legacy = storedEnvironment("legacy-key-env", EnvironmentStatus.AVAILABLE);
            environmentStore.deleteForAccount("000000000000", "us-east-1/legacy-key-env");
            environmentStore.putForAccount("000000000000", "legacy-key-env", legacy);

            realModeService.restorePersistedRuntime().join();

            assertEquals(EnvironmentStatus.MAINTENANCE, stored("legacy-key-env").getStatus());
            assertTrue(environmentStore.getForAccount("000000000000", "legacy-key-env").isEmpty());
            verify(environmentManager).giveNewSecrets(any());
        }

        @Test
        void anEnvironmentWhoseContainersDoNotStartIsUnavailableAndKeepsItsVolumes() {
            Environment stored = storedEnvironment("unstartable-env", EnvironmentStatus.AVAILABLE);
            storedSecrets(stored);
            Mockito.doThrow(new IllegalStateException("Postgres did not start"))
                    .when(environmentManager).restartEnvironment(any(), any(), any(), any());

            CompletionException failure = assertThrows(CompletionException.class,
                    () -> realModeService.restorePersistedRuntime().join());

            assertTrue(failure.getCause().getMessage().contains("unstartable-env"), failure.getCause().getMessage());
            assertEquals(EnvironmentStatus.UNAVAILABLE, stored("unstartable-env").getStatus());
            verify(environmentManager).stopOwnContainers(stored);
            verify(environmentManager, Mockito.never()).stopEnvironment(any());
            verify(proxyManager, Mockito.never()).startProxy(any(), anyInt(), any(), anyInt(), any(), any(), any(),
                    any());
        }

        @Test
        void everyEnvironmentIsTriedAndTheFailuresAreReportedTogether() {
            storedSecrets(storedEnvironment("first-failing-env", EnvironmentStatus.AVAILABLE));
            storedSecrets(storedEnvironment("second-failing-env", EnvironmentStatus.AVAILABLE));
            Mockito.doThrow(new IllegalStateException("no Docker"))
                    .when(environmentManager).restartEnvironment(any(), any(), any(), any());

            CompletionException failure = assertThrows(CompletionException.class,
                    () -> realModeService.restorePersistedRuntime().join());

            verify(environmentManager, Mockito.times(2)).restartEnvironment(any(), any(), any(), any());
            assertEquals(1, failure.getCause().getSuppressed().length);
        }

        @Test
        void anUnavailableEnvironmentIsTriedAgain() {
            storedSecrets(storedEnvironment("retried-env", EnvironmentStatus.UNAVAILABLE));

            realModeService.restorePersistedRuntime().join();

            verify(environmentManager).restartEnvironment(any(), any(), any(), any());
            assertEquals(EnvironmentStatus.MAINTENANCE, stored("retried-env").getStatus());
        }

        @Test
        void anEnvironmentStillBeingCreatedStaysCreatingAndFailsAsACreate() {
            storedSecrets(storedEnvironment("creating-env", EnvironmentStatus.CREATING));
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Mockito.doAnswer(invocation -> {
                started.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                throw new IllegalStateException("Airflow did not start");
            }).when(environmentManager).restartEnvironment(any(), any(), any(), any());

            CompletableFuture<Void> restore = realModeService.restorePersistedRuntime();
            assertEquals(EnvironmentStatus.CREATING, stored("creating-env").getStatus());
            release.countDown();

            assertThrows(CompletionException.class, restore::join);
            assertEquals(EnvironmentStatus.CREATE_FAILED, stored("creating-env").getStatus());
        }

        @Test
        void anEnvironmentThatFailedToCreateIsLeftAsItIs() {
            storedSecrets(storedEnvironment("create-failed-env", EnvironmentStatus.CREATE_FAILED));

            realModeService.restorePersistedRuntime().join();

            verify(environmentManager, Mockito.never()).adoptRunningEnvironment(any());
            verify(environmentManager, Mockito.never()).restartEnvironment(any(), any(), any(), any());
            verify(portAllocator, Mockito.never()).markReserved(anyInt());
            assertEquals(EnvironmentStatus.CREATE_FAILED, stored("create-failed-env").getStatus());
        }

        @Test
        void anEnvironmentStillDeletingAtStartIsRemoved() {
            Environment stored = storedEnvironment("still-deleting-env", EnvironmentStatus.DELETING);
            storedSecrets(stored);

            realModeService.restorePersistedRuntime().join();

            verify(environmentManager).stopEnvironment(stored);
            verify(environmentManager, Mockito.never()).adoptRunningEnvironment(any());
            verify(environmentManager, Mockito.never()).restartEnvironment(any(), any(), any(), any());
            assertTrue(environmentStore.getForAccount("000000000000", "us-east-1/still-deleting-env").isEmpty());
            assertTrue(runtimeStore.getForAccount("000000000000", "us-east-1/still-deleting-env").isEmpty());
        }

        @Test
        void anEnvironmentDeletedDuringItsRestoreIsDeletingUntilItsContainersHaveStartedAndBeenRemoved()
                throws Exception {
            Environment stored = storedEnvironment("deleted-mid-restore-env", EnvironmentStatus.AVAILABLE);
            storedSecrets(stored);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = blockRestart(started);

            CompletableFuture<Void> restore = realModeService.restorePersistedRuntime();
            assertTrue(started.await(10, TimeUnit.SECONDS));
            long writesBefore = writesOf("deleted-mid-restore-env");
            Environment deleting = realModeService.deleteEnvironment("deleted-mid-restore-env");

            assertEquals(writesBefore + 1, writesOf("deleted-mid-restore-env"));
            assertEquals(EnvironmentStatus.DELETING, deleting.getStatus());
            assertEquals(EnvironmentStatus.DELETING, stored("deleted-mid-restore-env").getStatus());
            verify(environmentManager, Mockito.never()).stopEnvironment(any());
            release.countDown();
            restore.get(10, TimeUnit.SECONDS);

            verify(environmentManager).stopEnvironment(stored);
            verify(proxyManager, Mockito.never()).startProxy(any(), anyInt(), any(), anyInt(), any(), any(), any(),
                    any());
            assertTrue(environmentStore.getForAccount("000000000000", "us-east-1/deleted-mid-restore-env").isEmpty());
            assertTrue(runtimeStore.getForAccount("000000000000", "us-east-1/deleted-mid-restore-env").isEmpty());
        }

        @Test
        void aDeleteBeforeTheRestoreReachesTheEnvironmentIsFinishedWithoutStartingIt() throws Exception {
            Environment stored = storedEnvironment("deleted-early-env", EnvironmentStatus.AVAILABLE);
            storedSecrets(stored);
            CountDownLatch reached = new CountDownLatch(1);
            CountDownLatch proceed = new CountDownLatch(1);
            when(environmentManager.isShuttingDown()).thenAnswer(invocation -> {
                reached.countDown();
                assertTrue(proceed.await(10, TimeUnit.SECONDS));
                return false;
            });

            CompletableFuture<Void> restore = realModeService.restorePersistedRuntime();
            assertTrue(reached.await(10, TimeUnit.SECONDS));
            realModeService.deleteEnvironment("deleted-early-env");
            proceed.countDown();
            restore.get(10, TimeUnit.SECONDS);

            verify(environmentManager, Mockito.never()).adoptRunningEnvironment(any());
            verify(environmentManager, Mockito.never()).restartEnvironment(any(), any(), any(), any());
            verify(environmentManager).stopEnvironment(stored);
            assertTrue(environmentStore.getForAccount("000000000000", "us-east-1/deleted-early-env").isEmpty());
        }

        @Test
        void aDeleteAfterTheRestoreRemovesTheEnvironmentAtOnce() {
            storedSecrets(storedEnvironment("deleted-after-restore-env", EnvironmentStatus.AVAILABLE));
            realModeService.restorePersistedRuntime().join();

            realModeService.deleteEnvironment("deleted-after-restore-env");

            verify(environmentManager).stopEnvironment(any());
            assertTrue(environmentStore.getForAccount("000000000000", "us-east-1/deleted-after-restore-env")
                    .isEmpty());
        }

        @Test
        void anEnvironmentWhoseRecordWentDuringItsRestoreIsNotBroughtBack() throws Exception {
            Environment stored = storedEnvironment("wiped-env", EnvironmentStatus.AVAILABLE);
            storedSecrets(stored);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = blockRestart(started);

            CompletableFuture<Void> restore = realModeService.restorePersistedRuntime();
            assertTrue(started.await(10, TimeUnit.SECONDS));
            environmentStore.deleteForAccount("000000000000", "us-east-1/wiped-env");
            release.countDown();
            restore.get(10, TimeUnit.SECONDS);

            assertTrue(environmentStore.getForAccount("000000000000", "us-east-1/wiped-env").isEmpty());
            verify(environmentManager).stopOwnContainers(stored);
            verify(environmentManager, Mockito.never()).stopEnvironment(any());
            verify(portAllocator).release(8705);
            verify(proxyManager, Mockito.never()).startProxy(any(), anyInt(), any(), anyInt(), any(), any(), any(),
                    any());
        }

        @Test
        void anEnvironmentCreatedAgainDuringTheRestoreKeepsItsOwnRecord() throws Exception {
            Environment stored = storedEnvironment("renamed-env", EnvironmentStatus.AVAILABLE);
            storedSecrets(stored);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = blockRestart(started);

            CompletableFuture<Void> restore = realModeService.restorePersistedRuntime();
            assertTrue(started.await(10, TimeUnit.SECONDS));
            Environment recreated = storedEnvironment("renamed-env", EnvironmentStatus.CREATING);
            recreated.setCreatedAt(Instant.parse("2026-11-20T00:00:00Z"));
            release.countDown();
            restore.get(10, TimeUnit.SECONDS);

            assertEquals(EnvironmentStatus.CREATING, stored("renamed-env").getStatus());
            assertEquals(Instant.parse("2026-11-20T00:00:00Z"), stored("renamed-env").getCreatedAt());
            verify(environmentManager).stopOwnContainers(stored);
            verify(environmentManager, Mockito.never()).stopEnvironment(any());
        }

        @Test
        void aShutdownDuringTheRestoreLeavesTheRemainingEnvironmentsForTheNextStart() {
            storedSecrets(storedEnvironment("overtaken-env", EnvironmentStatus.AVAILABLE));
            storedSecrets(storedEnvironment("not-reached-env", EnvironmentStatus.AVAILABLE));
            when(environmentManager.isShuttingDown()).thenReturn(false, true);
            Mockito.doThrow(new MwaaEnvironmentManager.ShuttingDownException("Floci is shutting down"))
                    .when(environmentManager).restartEnvironment(any(), any(), any(), any());

            realModeService.restorePersistedRuntime().join();

            verify(environmentManager).restartEnvironment(any(), any(), any(), any());
            assertEquals(EnvironmentStatus.MAINTENANCE, stored("overtaken-env").getStatus());
            assertEquals(EnvironmentStatus.MAINTENANCE, stored("not-reached-env").getStatus());
            verify(environmentManager, Mockito.never()).stopOwnContainers(any());
        }

        @Test
        void aRestoredEnvironmentWhosePortIsTakenGetsANewWebserverUrl() {
            storedSecrets(storedEnvironment("moved-env", EnvironmentStatus.AVAILABLE));
            Mockito.doThrow(new RuntimeException("Address already in use"))
                    .when(proxyManager).startProxy(any(), eq(8705), any(), anyInt(), any(), any(), any(), any());

            realModeService.restorePersistedRuntime().join();

            verify(portAllocator).release(8705);
            verify(proxyManager).startProxy(any(), eq(8701), any(), anyInt(), any(), any(), any(), any());
            assertEquals("http://floci.test:8701", stored("moved-env").getWebserverUrl());
        }

        @Test
        void aRestoredEnvironmentWithNoProxyIsUnavailable() {
            Environment stored = storedEnvironment("no-proxy-env", EnvironmentStatus.AVAILABLE);
            storedSecrets(stored);
            Mockito.doThrow(new RuntimeException("Address already in use"))
                    .when(proxyManager).startProxy(any(), anyInt(), any(), anyInt(), any(), any(), any(), any());

            assertThrows(CompletionException.class, () -> realModeService.restorePersistedRuntime().join());

            verify(portAllocator).release(8701);
            assertEquals(EnvironmentStatus.UNAVAILABLE, stored("no-proxy-env").getStatus());
            verify(environmentManager).stopOwnContainers(stored);
        }

        /** Holds the restore's restart, once it has begun, until the returned latch is released. */
        private CountDownLatch blockRestart(CountDownLatch started) {
            CountDownLatch release = new CountDownLatch(1);
            Mockito.doAnswer(invocation -> {
                started.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                return null;
            }).when(environmentManager).restartEnvironment(any(), any(), any(), any());
            return release;
        }

        /** An environment as Floci reads it back after a restart: what storage keeps, none of what it holds in memory. */
        private Environment storedEnvironment(String name, EnvironmentStatus status) {
            Environment environment = new Environment();
            environment.setName(name);
            environment.setArn("arn:aws:airflow:us-east-1:000000000000:environment/" + name);
            environment.setCreatedAt(Instant.parse("2026-11-01T00:00:00Z"));
            environment.setStatus(status);
            environment.setAirflowVersion("2.10.5");
            environment.setSourceBucketArn("arn:aws:s3:::my-bucket");
            environment.setDagS3Path("dags");
            environment.setWebserverUrl("http://floci.test:8705");
            environmentStore.putForAccount("000000000000", "us-east-1/" + name, environment);
            return environment;
        }

        private void storedSecrets(Environment environment) {
            EnvironmentRuntime runtime = new EnvironmentRuntime();
            runtime.setEnvironmentCreatedAt(environment.getCreatedAt());
            runtime.setDbPassword("saved-db-pw");
            runtime.setFernetKey("saved-fernet-key");
            runtime.setAirflowAdminPassword("saved-admin-pw");
            runtimeStore.putForAccount("000000000000", "us-east-1/" + environment.getName(), runtime);
        }

        /** How many times the environment's record has been written to storage. */
        private long writesOf(String name) {
            return Mockito.mockingDetails(environmentStore).getInvocations().stream()
                    .filter(invocation -> "putForAccount".equals(invocation.getMethod().getName())
                            && ("us-east-1/" + name).equals(invocation.getArgument(1)))
                    .count();
        }

        private Environment stored(String name) {
            return environmentStore.getForAccount("000000000000", "us-east-1/" + name).orElseThrow();
        }
    }

    @Test
    void restoreLeavesMockEnvironmentsAlone() {
        mwaaService.createEnvironment("mock-env", createRequest("arn:aws:s3:::my-bucket", "dags"));

        assertTrue(mwaaService.restorePersistedRuntime().isDone());
        assertEquals(EnvironmentStatus.AVAILABLE, mwaaService.getEnvironment("mock-env").getStatus());
    }

    @Test
    void theSecretsAnEnvironmentRunsWithAreNeverInTheEnvironmentAsReturned() throws Exception {
        Environment environment = new Environment();
        environment.setName("secret-env");
        environment.setDbPassword("db-secret");
        environment.setFernetKey("fernet-secret");
        environment.setAirflowAdminPassword("admin-secret");

        String json = new ObjectMapper().writeValueAsString(environment);

        assertFalse(json.contains("db-secret"), json);
        assertFalse(json.contains("fernet-secret"), json);
        assertFalse(json.contains("admin-secret"), json);
    }
}
