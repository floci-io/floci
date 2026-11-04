package io.github.hectorvent.floci.services.s3;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AccountResolver;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudtrail.CloudTrailService;
import io.github.hectorvent.floci.services.firehose.FirehoseLambdaTransformer;
import io.github.hectorvent.floci.services.firehose.FirehoseParquetConverter;
import io.github.hectorvent.floci.services.firehose.FirehoseService;
import io.github.hectorvent.floci.services.firehose.model.Record;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.s3.model.LambdaNotification;
import io.github.hectorvent.floci.services.s3.model.NotificationConfiguration;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockMakers;
import org.mockito.MockedStatic;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class S3GlobalLegacyListingTest {
    private static final String OWNER = "000000000001";
    private static final String CALLER = "000000000002";
    private static final String BUCKET = "global-legacy-bucket";

    private final RequestContext request = new RequestContext();
    private final Map<String, AccountAwareStorageBackend<?>> stores = new HashMap<>();
    private S3Service s3;
    private S3Controller controller;
    private LambdaService lambda;
    private CloudTrailService trail;
    private HttpHeaders headers;
    private UriInfo uri;
    private StorageFactory factory;
    private EmulatorConfig config;
    private RegionResolver region;
    private MockedStatic<RequestScopes> scopes;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        request.setAccountId(OWNER);
        request.setRegion("us-east-1");
        Instance<RequestContext> context = mock(Instance.class);
        when(context.get()).thenReturn(request);
        factory = mock(StorageFactory.class);
        when(factory.create(anyString(), anyString(), any())).thenAnswer(invocation ->
                stores.computeIfAbsent(invocation.getArgument(0) + "/" + invocation.getArgument(1),
                        key -> new AccountAwareStorageBackend<>(new InMemoryStorage<>(), context, OWNER)));
        when(factory.create(anyString(), anyString(), any(), any())).thenAnswer(invocation ->
                stores.computeIfAbsent(invocation.getArgument(0) + "/" + invocation.getArgument(1),
                        key -> new AccountAwareStorageBackend<>(new InMemoryStorage<>(), context, OWNER)));
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.storage().persistentPath()).thenReturn(Path.of("s3-global-legacy-test").toString());
        when(config.storage().mode()).thenReturn("memory");
        when(config.storage().services().s3().mode()).thenReturn(Optional.empty());
        when(config.services().s3().globalBucketNamespace()).thenReturn(true);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        region = new RegionResolver("us-east-1", OWNER);
        lambda = mock(LambdaService.class);
        Instance<LambdaService> provider = mock(Instance.class);
        when(provider.isResolvable()).thenReturn(true);
        when(provider.get()).thenReturn(lambda);
        s3 = new S3Service(factory, config, null, null, provider, null, null, region, new ObjectMapper(), null);
        trail = mock(CloudTrailService.class);
        when(trail.hasTrails()).thenReturn(true);
        CurrentVertxRequest vertx = mock(CurrentVertxRequest.class, RETURNS_DEEP_STUBS);
        when(vertx.getCurrent().request().path()).thenReturn("/" + BUCKET + "/object.txt");
        when(vertx.getCurrent().request().getHeader("Authorization")).thenAnswer(invocation ->
                "AWS " + request.getAccountId() + ":local-signature");
        AccountResolver accounts = mock(AccountResolver.class);
        when(accounts.extractS3AccessKeyId(anyString())).thenAnswer(invocation -> request.getAccountId());
        controller = new S3Controller(s3, null, region, null, vertx, null, trail, accounts,
                request, null, null, config, Clock.systemUTC());
        headers = mock(HttpHeaders.class);
        uri = mock(UriInfo.class);
        when(uri.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(uri.getRequestUri()).thenReturn(URI.create("http://localhost:4566/" + BUCKET + "/object.txt"));
        scopes = mockStatic(RequestScopes.class, withSettings().mockMaker(MockMakers.INLINE));
        scopes.when(() -> RequestScopes.callAs(anyString(), any(Supplier.class))).thenAnswer(invocation ->
                inAccount(invocation.getArgument(0), invocation.getArgument(1)));
        scopes.when(() -> RequestScopes.runAs(anyString(), any(Runnable.class))).thenAnswer(invocation ->
                inAccount(invocation.getArgument(0), () -> {
                    invocation.<Runnable>getArgument(1).run();
                    return null;
                }));
        s3.createBucket(BUCKET, "us-east-1");
        s3.putObject(BUCKET, "object.txt", "owner-data".getBytes(UTF_8), "text/plain", Map.of());
    }

    @AfterEach
    void tearDown() {
        if (scopes != null) {
            scopes.close();
        }
    }

    @Test
    void crossAccountDeleteKeepsTheFullNotificationArnAndOwnerScope() {
        String destination = "arn:aws:lambda:us-east-1:000000000003:function:shared-name:PROD";
        NotificationConfiguration notifications = new NotificationConfiguration();
        notifications.setLambdaFunctionConfigurations(List.of(
                new LambdaNotification("foreign-destination", destination, List.of("s3:ObjectRemoved:*"))));
        s3.putBucketNotificationConfiguration(BUCKET, notifications, true);
        doAnswer(invocation -> {
            assertEquals(OWNER, request.getAccountId());
            return null;
        }).when(lambda).invoke(anyString(), anyString(), any(byte[].class), eq(InvocationType.Event));
        request.setAccountId(CALLER);

        assertEquals(204, delete(null).getStatus());

        verify(lambda).invoke(eq("us-east-1"), eq(destination), any(byte[].class), eq(InvocationType.Event));
        assertEquals(CALLER, request.getAccountId());
        assertEquals("NoSuchKey", assertThrows(AwsException.class,
                () -> s3.getObject(BUCKET, "object.txt")).getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletesUseTheOwnerAndRestoreTheLegacyCaller(boolean versioned) {
        if (versioned) {
            s3.deleteObject(BUCKET, "object.txt", null, false);
            s3.putBucketVersioning(BUCKET, "Enabled");
        }
        String version = s3.putObject(BUCKET, "object.txt", "owner-data".getBytes(UTF_8),
                "text/plain", Map.of()).getVersionId();
        request.setAccountId(CALLER);
        doAnswer(invocation -> {
            assertEquals(CALLER, request.getAccountId());
            return null;
        }).when(trail).emitS3DataEvent(any());

        Response removed = delete(null);
        assertEquals(204, removed.getStatus());
        assertEquals(CALLER, request.getAccountId());
        assertEquals("NoSuchKey", assertThrows(AwsException.class,
                () -> s3.getObject(BUCKET, "object.txt")).getErrorCode());
        if (versioned) {
            assertEquals("owner-data", new String(s3.getObject(BUCKET, "object.txt", version).getData(), UTF_8));
            assertEquals(204, delete(removed.getHeaderString("x-amz-version-id")).getStatus());
            assertEquals("owner-data", new String(s3.getObject(BUCKET, "object.txt").getData(), UTF_8));
            assertEquals(204, delete(version).getStatus());
        } else {
            assertEquals(204, delete(null).getStatus());
        }
        assertThrows(AwsException.class, () -> s3.deleteBucket(BUCKET));
        request.setAccountId(OWNER);
        s3.headBucket(BUCKET);
    }

    @Test
    void bothListingVersionsReadTheOwnerAndRecordTheLegacyCaller() {
        request.setAccountId(CALLER);
        assertEquals("BucketAlreadyExists", assertThrows(AwsException.class,
                () -> s3.createBucket(BUCKET, "us-east-1")).getErrorCode());
        doAnswer(invocation -> {
            CloudTrailService.S3EventInput input = invocation.getArgument(0);
            assertEquals(CALLER, request.getAccountId());
            assertEquals(CALLER, input.accessKeyId());
            return null;
        }).when(trail).emitS3DataEvent(any());
        for (String listType : new String[]{null, "2"}) {
            Response response = controller.listObjects(BUCKET, null, null, null, listType,
                    null, null, null, null, null, null, uri, headers);
            assertEquals(200, response.getStatus());
            assertTrue(response.getEntity().toString().contains("<Key>object.txt</Key>"));
            assertEquals(CALLER, request.getAccountId());
        }
    }

    @Test
    void defaultFirehoseDeliveriesStayInEachStreamsAccount() {
        when(config.services().firehose().enabled()).thenReturn(true);
        when(config.services().firehose().tickIntervalSeconds()).thenReturn(10L);
        FirehoseService firehose = new FirehoseService(factory, s3, new KinesisService(factory, region),
                region, Clock.systemUTC(), config, mock(FirehoseParquetConverter.class),
                mock(FirehoseLambdaTransformer.class));
        for (String account : List.of(OWNER, CALLER)) {
            request.setAccountId(account);
            firehose.createDeliveryStream("us-east-1", account, "shared", null, List.of(), null, null);
            firehose.putRecord(account, "us-east-1", "shared", new Record(account.getBytes(UTF_8)));
            firehose.flush(account, "us-east-1", "shared");
        }
        for (String account : List.of(OWNER, CALLER)) {
            request.setAccountId(account);
            assertTrue(s3.ownsBucket("floci-firehose-results"));
            List<S3Object> delivered = s3.listObjects("floci-firehose-results", null, null, 100);
            assertEquals(1, delivered.size());
            assertEquals(account + "\n", new String(
                    s3.getObject("floci-firehose-results", delivered.getFirst().getKey()).getData(), UTF_8));
        }
    }

    private Response delete(String version) {
        return controller.deleteObject(BUCKET, "object.txt", null, version, uri, headers);
    }

    private <T> T inAccount(String account, Supplier<T> action) {
        String previous = request.getAccountId();
        request.setAccountId(account);
        try {
            return action.get();
        } finally {
            request.setAccountId(previous);
        }
    }
}
