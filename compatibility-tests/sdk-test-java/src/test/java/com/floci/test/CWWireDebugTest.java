package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.interceptor.*;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;
import java.util.concurrent.atomic.AtomicReference;

class CWWireDebugTest {
    @Test
    void captureRequestBody() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();

        ExecutionInterceptor interceptor = new ExecutionInterceptor() {
            @Override
            public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attrs) {
                try {
                    var body = context.requestBody();
                    if (body.isPresent()) {
                        String b = new String(body.get().contentStreamProvider().newStream().readAllBytes());
                        if (b.contains("PutMetricData")) {
                            capturedBody.set(b);
                            System.out.println("=== SDK PutMetricData request body ===");
                            System.out.println(b);
                            System.out.println("===");
                        }
                    }
                } catch (Exception e) { e.printStackTrace(); }
            }
        };

        try (CloudWatchClient cw = CloudWatchClient.builder()
                .endpointOverride(TestFixtures.endpoint())
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test")))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .addExecutionInterceptor(interceptor).build())
                .build()) {

            cw.putMetricData(r -> r
                .namespace("WireDebugNS")
                .metricData(d -> d
                    .metricName("WireMetric")
                    .statisticValues(s -> s.sampleCount(5.0).sum(150.0).minimum(20.0).maximum(40.0))
                    .unit(StandardUnit.COUNT)
                )
            );

            System.out.println("Captured: " + capturedBody.get());
        }
    }
}
