package io.github.hectorvent.floci.services.secretsmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import io.github.hectorvent.floci.services.secretsmanager.model.SecretVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RotateSecret with {@code RotateImmediately=false} only tests the rotation configuration: AWS runs
 * the function's testSecret step against an AWSPENDING version it creates, then removes that version.
 */
class SecretsManagerRotateLaterTest {

    private static final String REGION = "us-east-1";
    private static final String NAME = "rotate-later";
    private static final String TOKEN = "11111111-2222-3333-4444-555555555555";
    private static final String OTHER_TOKEN = "66666666-7777-8888-9999-000000000000";
    private static final String LAMBDA_ARN = "arn:aws:lambda:us-east-1:000000000000:function:rotate";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LambdaService lambda = mock(LambdaService.class);
    private final SecretsManagerService service = new SecretsManagerService(new InMemoryStorage<String, Secret>(),
            30, new RegionResolver(REGION, "000000000000"), lambda, MAPPER);

    @AfterEach
    void shutdown() {
        service.shutdown();
    }

    @Test
    void theTestStepFindsAnAwsPendingVersionForItsToken() throws Exception {
        AtomicReference<List<String>> stagesSeen = new AtomicReference<>();
        CountDownLatch tested = new CountDownLatch(1);
        when(lambda.invoke(anyString(), anyString(), any(byte[].class), any())).thenAnswer(invocation -> {
            String step = MAPPER.readTree((byte[]) invocation.getArgument(2)).path("Step").asText();
            if ("testSecret".equals(step)) {
                SecretVersion version = service.describeSecret(NAME, REGION).getVersions().get(TOKEN);
                stagesSeen.set(version == null ? List.of() : new ArrayList<>(version.getVersionStages()));
                tested.countDown();
            }
            return succeeded();
        });
        service.createSecret(NAME, "v1", null, null, null, null, REGION);

        service.rotateSecret(NAME, TOKEN, LAMBDA_ARN, null, false, REGION);

        assertTrue(tested.await(10, TimeUnit.SECONDS), "the testSecret step must run");
        assertEquals(List.of("AWSPENDING"), stagesSeen.get());
    }

    @Test
    void thePendingVersionIsRemovedOnceTheTestStepEnds() {
        when(lambda.invoke(anyString(), anyString(), any(byte[].class), any())).thenReturn(succeeded());
        Secret created = service.createSecret(NAME, "v1", null, null, null, null, REGION);

        service.rotateSecret(NAME, TOKEN, LAMBDA_ARN, null, false, REGION);

        awaitOnlyVersion(created.getCurrentVersionId());
    }

    @Test
    void thePendingVersionIsRemovedWhenTheTestStepFails() {
        InvokeResult failed = succeeded();
        failed.setFunctionError("Unhandled");
        when(lambda.invoke(anyString(), anyString(), any(byte[].class), any())).thenReturn(failed);
        Secret created = service.createSecret(NAME, "v1", null, null, null, null, REGION);

        service.rotateSecret(NAME, TOKEN, LAMBDA_ARN, null, false, REGION);

        awaitOnlyVersion(created.getCurrentVersionId());
    }

    @Test
    void aValueWrittenUnderTheTokenDuringTheTestStepIsKept() {
        when(lambda.invoke(anyString(), anyString(), any(byte[].class), any())).thenAnswer(invocation -> {
            service.putSecretValue(NAME, "written", null, TOKEN, REGION, List.of("AWSPENDING"));
            return succeeded();
        });
        service.createSecret(NAME, "v1", null, null, null, null, REGION);

        service.rotateSecret(NAME, TOKEN, LAMBDA_ARN, null, false, REGION);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            verify(lambda).invoke(anyString(), anyString(), any(byte[].class), any());
            SecretVersion version = service.describeSecret(NAME, REGION).getVersions().get(TOKEN);
            assertTrue(version == null || !version.getVersionStages().contains("AWSPENDING"));
        });
        assertEquals("written", service.getSecretValue(NAME, TOKEN, null, REGION).getSecretString());
    }

    @Test
    void aVersionAnotherClientStagesDuringTheTestStepKeepsItsStage() {
        when(lambda.invoke(anyString(), anyString(), any(byte[].class), any())).thenAnswer(invocation -> {
            service.putSecretValue(NAME, "staged-elsewhere", null, OTHER_TOKEN, REGION, List.of("AWSPENDING"));
            return succeeded();
        });
        service.createSecret(NAME, "v1", null, null, null, null, REGION);

        service.rotateSecret(NAME, TOKEN, LAMBDA_ARN, null, false, REGION);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            verify(lambda).invoke(anyString(), anyString(), any(byte[].class), any());
            assertFalse(service.describeSecret(NAME, REGION).getVersions().containsKey(TOKEN),
                    "the test step's own version must be removed");
        });
        assertEquals(OTHER_TOKEN, service.getSecretValue(NAME, null, "AWSPENDING", REGION).getVersionId());
    }

    /** Waits until the rotation has run its test step and the secret holds only the given version. */
    private void awaitOnlyVersion(String versionId) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            verify(lambda).invoke(anyString(), anyString(), any(byte[].class), any());
            assertEquals(List.of(versionId),
                    List.copyOf(service.describeSecret(NAME, REGION).getVersions().keySet()));
        });
    }

    private static InvokeResult succeeded() {
        InvokeResult result = new InvokeResult();
        result.setStatusCode(200);
        return result;
    }
}
