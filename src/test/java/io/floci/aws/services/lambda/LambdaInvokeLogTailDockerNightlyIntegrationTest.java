package io.floci.aws.services.lambda;

import com.github.dockerjava.api.DockerClient;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.LambdaCodeFixtures;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.FunctionCode;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.awssdk.services.lambda.model.LogType;
import software.amazon.awssdk.services.lambda.model.Runtime;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class LambdaInvokeLogTailDockerNightlyIntegrationTest {

    @Inject
    DockerClient dockerClient;

    @Test
    void synchronousInvokeReturnsItsOwnLogsThroughTheSdk() {
        Assumptions.assumeTrue(DockerAvailability.answers(dockerClient),
                "Docker is required for Lambda invocation log tails");

        String name = "invoke-log-tail-" + UUID.randomUUID().toString().substring(0, 8);
        try (LambdaClient client = AwsTestClients.create(LambdaClient.builder(), "000000000000", Region.US_EAST_1)) {
            client.createFunction(request -> request.functionName(name)
                    .runtime(Runtime.NODEJS22_X).handler("index.handler")
                    .role("arn:aws:iam::000000000000:role/lambda-role").timeout(10)
                    .code(FunctionCode.builder().zipFile(SdkBytes.fromByteArray(LambdaCodeFixtures.nodeJsZip("""
                            console.log('initialization output');
                            exports.handler = async (event) => {
                              console.log('hello from log tail probe ' + event.marker);
                              console.error('stderr ' + event.marker);
                              if (event.large) process.stdout.write('é'.repeat(9000) + ' END ' + event.marker + '\\n');
                              if (event.fail) throw new Error('failure ' + event.marker);
                              return {ok: true};
                            };
                            """))).build()));
            try {
                InvokeResponse response = client.invoke(request -> request.functionName(name)
                        .logType(LogType.TAIL).payload(SdkBytes.fromUtf8String("{\"marker\":\"first\"}")));

                assertEquals(200, response.statusCode());
                assertEquals("{\"ok\":true}", response.payload().asUtf8String());
                assertNotNull(response.logResult(), "LogType=Tail must return X-Amz-Log-Result");
                String logs = new String(Base64.getDecoder().decode(response.logResult()), StandardCharsets.UTF_8);
                assertTrue(logs.contains("hello from log tail probe first"), logs);
                assertTrue(logs.contains("initialization output"), logs);
                assertTrue(logs.contains("stderr first"), logs);

                InvokeResponse warm = client.invoke(request -> request.functionName(name)
                        .invocationType(InvocationType.REQUEST_RESPONSE).logType(LogType.TAIL)
                        .payload(SdkBytes.fromUtf8String("{\"marker\":\"second 世界\"}")));
                String warmLogs = decoded(warm);
                assertTrue(warmLogs.contains("second 世界"), warmLogs);
                assertFalse(warmLogs.contains("first"), warmLogs);
                assertFalse(warmLogs.contains("initialization output"), warmLogs);

                InvokeResponse failed = client.invoke(request -> request.functionName(name).logType(LogType.TAIL)
                        .payload(SdkBytes.fromUtf8String("{\"marker\":\"failed\",\"fail\":true}")));
                assertEquals("Unhandled", failed.functionError());
                assertTrue(decoded(failed).contains("failure failed"), decoded(failed));
                assertFalse(decoded(failed).contains("second"), decoded(failed));

                InvokeResponse large = client.invoke(request -> request.functionName(name).logType(LogType.TAIL)
                        .payload(SdkBytes.fromUtf8String("{\"marker\":\"large\",\"large\":true}")));
                byte[] largeOutput = ("é".repeat(9000) + " END large\n").getBytes(StandardCharsets.UTF_8);
                byte[] actualTail = Base64.getDecoder().decode(large.logResult());
                assertArrayEquals(Arrays.copyOfRange(largeOutput, largeOutput.length - 4096, largeOutput.length),
                        actualTail, () -> "Unexpected tail metadata: "
                                + new String(actualTail, StandardCharsets.UTF_8).replace("é", ""));

                InvokeResponse noTail = client.invoke(request -> request.functionName(name)
                        .logType(LogType.NONE).payload(SdkBytes.fromUtf8String("{}")));
                assertNull(noTail.logResult());
                InvokeResponse defaultLogs = client.invoke(request -> request.functionName(name)
                        .payload(SdkBytes.fromUtf8String("{}")));
                assertNull(defaultLogs.logResult());
                InvokeResponse dryRun = client.invoke(request -> request.functionName(name)
                        .invocationType(InvocationType.DRY_RUN).logType(LogType.TAIL));
                assertEquals(204, dryRun.statusCode());
                assertNull(dryRun.logResult());
            } finally {
                client.deleteFunction(request -> request.functionName(name));
            }
        }
    }

    private static String decoded(InvokeResponse response) {
        assertNotNull(response.logResult());
        return new String(Base64.getDecoder().decode(response.logResult()), StandardCharsets.UTF_8);
    }
}
