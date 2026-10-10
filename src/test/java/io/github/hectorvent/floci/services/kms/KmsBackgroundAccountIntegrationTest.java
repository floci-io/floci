package io.github.hectorvent.floci.services.kms;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class KmsBackgroundAccountIntegrationTest {
    @Inject
    KmsService service;

    @Test
    void backgroundKeyLookupCreatesAndClosesItsAccountScope() throws Exception {
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> {
                ManagedContext context = Arc.container().requestContext();
                assertFalse(context.isActive());
                String arn = service.describeKeyForAccount("alias/aws/secretsmanager",
                        "cn-north-1", "333333333333").getArn();
                assertTrue(arn.startsWith("arn:aws-cn:kms:cn-north-1:333333333333:key/"));
                assertFalse(context.isActive());
                assertThrows(AwsException.class, () -> service.describeKeyForAccount("missing",
                        "cn-north-1", "333333333333"));
                assertFalse(context.isActive());
            }).get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void keyLookupRestoresAnExistingContextOnSuccessAndFailure() throws Exception {
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> {
                ManagedContext context = Arc.container().requestContext();
                context.activate();
                try {
                    RequestContext request = Arc.container().instance(RequestContext.class).get();
                    request.setAccountId("444444444444");
                    request.setRegion("us-east-1");
                    request.setPartition("aws");
                    service.describeKeyForAccount("alias/aws/secretsmanager", "cn-north-1", "333333333333");
                    assertEquals("444444444444", request.getAccountId());
                    assertEquals("us-east-1", request.getRegion());
                    assertEquals("aws", request.getPartition());
                    assertThrows(AwsException.class, () -> service.describeKeyForAccount("missing",
                            "cn-north-1", "333333333333"));
                    assertEquals("444444444444", request.getAccountId());
                    assertEquals("us-east-1", request.getRegion());
                    assertEquals("aws", request.getPartition());
                } finally {
                    context.terminate();
                }
            }).get(10, TimeUnit.SECONDS);
        }
    }
}
