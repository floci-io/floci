package io.github.hectorvent.floci.services.mwaa;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.mwaa.model.CreateEnvironmentRequest;
import io.github.hectorvent.floci.services.mwaa.model.Environment;
import io.github.hectorvent.floci.services.mwaa.model.EnvironmentStatus;
import io.github.hectorvent.floci.services.mwaa.model.NetworkConfiguration;
import io.github.hectorvent.floci.services.mwaa.model.UpdateEnvironmentRequest;
import io.github.hectorvent.floci.services.mwaa.proxy.MwaaProxyManager;
import io.github.hectorvent.floci.services.s3.S3Service;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MwaaServiceTest {

    private MwaaService mwaaService;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = new StorageFactory(null, null) {
            @Override
            public <V> StorageBackend<String, V> create(String serviceName, String fileName,
                    TypeReference<Map<String, V>> typeReference) {
                return new InMemoryStorage<>();
            }
        };

        EmulatorConfig config = testConfig();
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        S3Service s3Service = Mockito.mock(S3Service.class);
        mwaaService = new MwaaService(storageFactory, config, regionResolver, null, null, null, s3Service);
    }

    private EmulatorConfig testConfig() {
        EmulatorConfig.MwaaServiceConfig mwaaConfig = proxy(EmulatorConfig.MwaaServiceConfig.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "enabled", "mock" -> true;
                    case "supportedVersions" -> List.of("2.10.5", "2.9.3", "2.8.4");
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
    void createEnvironmentWithSupportedVersionRoundTrips() {
        CreateEnvironmentRequest request = createRequest("arn:aws:s3:::my-bucket", "dags");
        request.setAirflowVersion("2.9.3");

        Environment environment = mwaaService.createEnvironment("versioned-env", request);
        assertEquals("2.9.3", environment.getAirflowVersion());
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
    }

    @Test
    void createCliTokenIsValidatedByIsValidCliToken() {
        mwaaService.createEnvironment("cli-token-env", createRequest("arn:aws:s3:::my-bucket", "dags"));

        Map<String, Object> response = mwaaService.createCliToken("cli-token-env");
        String token = (String) response.get("CliToken");
        assertNotNull(token);
        assertTrue(mwaaService.isValidCliToken("cli-token-env", token));
        assertFalse(mwaaService.isValidCliToken("cli-token-env", "not-a-real-token"));
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

        @BeforeEach
        void setUpRealMode() {
            StorageFactory storageFactory = new StorageFactory(null, null) {
                @Override
                public <V> StorageBackend<String, V> create(String serviceName, String fileName,
                        TypeReference<Map<String, V>> typeReference) {
                    return new InMemoryStorage<>();
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
                        default -> defaultValue(method);
                    });
            EmulatorConfig.ServicesConfig servicesConfig = proxy(EmulatorConfig.ServicesConfig.class,
                    (proxy, method, args) -> switch (method.getName()) {
                        case "mwaa" -> mwaaConfig;
                        default -> defaultValue(method);
                    });
            EmulatorConfig.TlsConfig tlsConfig = proxy(EmulatorConfig.TlsConfig.class,
                    (proxy, method, args) -> defaultValue(method));
            EmulatorConfig config = proxy(EmulatorConfig.class, (proxy, method, args) -> switch (method.getName()) {
                case "services" -> servicesConfig;
                case "tls" -> tlsConfig;
                case "defaultRegion" -> "us-east-1";
                case "defaultAccountId" -> "000000000000";
                case "hostname" -> Optional.empty();
                default -> defaultValue(method);
            });

            RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
            S3Service s3Service = Mockito.mock(S3Service.class);
            environmentManager = Mockito.mock(MwaaEnvironmentManager.class);
            proxyManager = Mockito.mock(MwaaProxyManager.class);
            portAllocator = Mockito.mock(PortAllocator.class);
            when(portAllocator.allocate(8700, 8799)).thenReturn(8701);

            realModeService = new MwaaService(storageFactory, config, regionResolver,
                    environmentManager, proxyManager, portAllocator, s3Service);
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
        void proxyStartupFailureRollsBackTheAllocatedPortAndAnyStartedContainers() {
            // Containers start fine; only the proxy bind fails (e.g. the port got taken out from
            // under us between allocate() and bind()). The port must not leak, and whatever
            // startEnvironment() already started must get torn down, not orphaned.
            Mockito.doThrow(new RuntimeException("bind failed"))
                    .when(proxyManager).startProxy(any(), anyInt(), any(), anyInt(), any(), any());

            Environment environment = realModeService.createEnvironment("failed-proxy-env",
                    createRequest("arn:aws:s3:::my-bucket", "dags"));

            assertEquals(EnvironmentStatus.CREATE_FAILED, environment.getStatus());
            verify(portAllocator).release(8701);
            verify(environmentManager).stopEnvironment(environment);
            verify(proxyManager).stopProxy("failed-proxy-env");
        }
    }
}
