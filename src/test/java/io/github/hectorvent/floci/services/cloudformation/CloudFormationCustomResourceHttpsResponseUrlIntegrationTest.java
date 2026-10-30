package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.config.SelfSignedTlsProfile;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * With TLS on, a custom resource's {@code ResponseURL} is https, as AWS's presigned S3 URL is
 * (CloudFormation User Guide, CloudFormation custom resource request and response reference), so a
 * Node handler that PUTs its response with {@code https.request}, which refuses an http URL with
 * {@code Protocol "http:" not supported}, completes the resource.
 *
 * <p>The ServiceToken Lambda is mocked, as {@code CloudFormationCustomResourceStackIdIntegrationTest}
 * mocks it, and answers by running a real Node handler on the event. The handler trusts Floci's CA
 * through {@code NODE_EXTRA_CA_CERTS}, as every container Floci launches does. The ports are the
 * ones TLS mode serves in production: the protocol-detecting proxy on {@code floci.port}, the HTTP
 * backend on 4510 and the HTTPS backend on 4511.
 */
@QuarkusTest
@TestProfile(CloudFormationCustomResourceHttpsResponseUrlIntegrationTest.Profile.class)
class CloudFormationCustomResourceHttpsResponseUrlIntegrationTest {

    private static final String SERVICE_TOKEN =
            "arn:aws:lambda:us-east-1:000000000000:function:https-response-probe";
    private static final int FLOCI_PORT = 4592;

    /** A response PUT the way a Node custom resource handler sends it, over node:https. */
    private static final String HANDLER = """
            const { request } = require("node:https");
            const event = JSON.parse(require("node:fs").readFileSync(process.argv[2], "utf8"));
            const body = JSON.stringify({
              Status: "SUCCESS",
              RequestId: event.RequestId,
              StackId: event.StackId,
              LogicalResourceId: event.LogicalResourceId,
              PhysicalResourceId: "https-response-probe",
              Data: { Scheme: new URL(event.ResponseURL).protocol }
            });
            const req = request(new URL(event.ResponseURL), {
              method: "PUT",
              headers: { "content-type": "", "content-length": Buffer.byteLength(body) }
            }, (res) => { res.resume(); res.on("end", () => process.exit(res.statusCode === 200 ? 0 : 1)); });
            req.on("error", (error) => { console.error(error.message); process.exit(1); });
            req.end(body);
            """;

    private final List<String> handlerErrors = new CopyOnWriteArrayList<>();

    @InjectMock
    LambdaService lambdaService;

    @BeforeAll
    static void requireNode() {
        Assumptions.assumeTrue(nodeAvailable(), "node must be on the PATH to run the custom resource handler");
    }

    @BeforeEach
    void handlerRunsInNode() {
        when(lambdaService.invoke(any(), eq(SERVICE_TOKEN), any(), eq(InvocationType.RequestResponse)))
                .thenAnswer(inv -> {
                    runHandler((byte[]) inv.getArgument(2));
                    return new InvokeResult(200, null, "null".getBytes(StandardCharsets.UTF_8), null, "req-1");
                });
    }

    @Test
    void nodeHandlerPutsItsResponseOverHttpsAndTheResourceCompletes() throws Exception {
        String stackName = "cr-https-response-" + Long.toString(System.nanoTime(), 36);
        String template = """
                {
                  "Resources": {
                    "Probe": {"Type": "Custom::HttpsResponseProbe", "Properties": {"ServiceToken": "%s"}}
                  },
                  "Outputs": {"Scheme": {"Value": {"Fn::GetAtt": ["Probe", "Scheme"]}}}
                }
                """.formatted(SERVICE_TOKEN);

        String stackId = XmlParser.extractFirst(cfn(Map.of("Action", "CreateStack", "StackName", stackName,
                "TemplateBody", template)), "StackId", null);
        try {
            String created = awaitTerminal(stackId);
            assertEquals(List.of(), handlerErrors);
            assertTrue(created.contains("<StackStatus>CREATE_COMPLETE</StackStatus>"), created);
            assertEquals("https:", XmlParser.extractPairs(created, "Outputs", "OutputKey", "OutputValue")
                    .get("Scheme"));
        } finally {
            cfn(Map.of("Action", "DeleteStack", "StackName", stackName));
        }
    }

    private void runHandler(byte[] payload) throws IOException, InterruptedException {
        Path event = Files.createTempFile("cr-event-", ".json");
        Path script = Files.createTempFile("cr-handler-", ".js");
        try {
            Files.write(event, payload);
            Files.writeString(script, HANDLER);
            String ca = FlociCertificateAuthority.loadOrCreate(Path.of(SelfSignedTlsProfile.TLS_DIR))
                    .certificatePath().toAbsolutePath().toString();
            ProcessBuilder node = new ProcessBuilder("node", script.toString(), event.toString())
                    .redirectErrorStream(true);
            node.environment().put("NODE_EXTRA_CA_CERTS", ca);
            Process process = node.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                handlerErrors.add(output.trim());
            }
        } catch (Exception e) {
            handlerErrors.add(e.getMessage());
            throw e;
        } finally {
            Files.deleteIfExists(event);
            Files.deleteIfExists(script);
        }
    }

    private static String awaitTerminal(String stackId) throws InterruptedException {
        String xml = "";
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            xml = cfn(Map.of("Action", "DescribeStacks", "StackName", stackId));
            if (xml.contains("_COMPLETE</StackStatus>") && !xml.contains("IN_PROGRESS")) {
                return xml;
            }
            Thread.sleep(50);
        }
        return xml;
    }

    private static String cfn(Map<String, String> params) {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .formParams(params)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract().asString();
    }

    private static boolean nodeAvailable() {
        try {
            Process process = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /**
     * {@link SelfSignedTlsProfile}'s TLS, served the way TLS mode serves it outside tests: the
     * proxy on {@code floci.port}, the backends on 4510 and 4511. The handler reaches Floci on
     * localhost, which the leaf covers.
     */
    public static final class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(new SelfSignedTlsProfile().getConfigOverrides());
            overrides.put("quarkus.http.test-port", "4510");
            overrides.put("quarkus.http.test-ssl-port", "4511");
            overrides.put("floci.port", String.valueOf(FLOCI_PORT));
            overrides.put("floci.services.lambda.docker-host-override", "localhost");
            return overrides;
        }
    }
}
