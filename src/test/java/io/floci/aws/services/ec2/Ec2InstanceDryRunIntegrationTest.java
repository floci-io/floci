package io.floci.aws.services.ec2;

import io.floci.aws.testing.QueryRequests;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DryRun on the instance calls is answered with DryRunOperation and HTTP 412 once the request has passed
 * the checks the real call makes, and changes nothing: StartInstances, StopInstances, RebootInstances and
 * TerminateInstances, monitoring, metadata options and attributes, the EBS encryption defaults, and Spot
 * requests. A dry run the real call would refuse keeps that call's error, even when it also names an
 * instance it would accept.
 *
 * <p>The cases that only read share one fixture, built once for the class. The two that change an
 * instance or a Spot request, the terminated start and the Spot cancel, build a fixture of their own.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Ec2InstanceDryRunIntegrationTest {

    /** Each fixture works in an account of its own, so DescribeInstances lists only the fixture's instances. */
    private static final AtomicLong ACCOUNTS = new AtomicLong(726200000000L);
    private static final String MISSING_INSTANCE = "i-0123456789abcdef0";
    private static final String IMAGE = "ami-1234567890abcdef0";
    private static final String USER_DATA = "IyEvYmluL3NoCg==";
    private static final String INSTANCE = "DescribeInstancesResponse.reservationSet.item.instancesSet.item";

    private static Fixture shared;

    /** The test port: RestAssured learns it only per test, and the fixture is built before the first one. */
    private static int port;

    @TestHTTPResource("/")
    URI baseUri;

    @BeforeAll
    void launchARunningAndAStoppedInstance() {
        port = baseUri.getPort();
        shared = Fixture.launch();
    }

    @AfterAll
    void terminateTheInstances() {
        shared.terminate();
    }

    @ParameterizedTest
    @ValueSource(strings = {"StartInstances", "StopInstances", "RebootInstances", "TerminateInstances"})
    void aDryRunIsAnsweredWithDryRunOperationAndChangesNothing(String action) {
        Map<String, String> before = shared.states();

        shared.request(action, "InstanceId.1", "StartInstances".equals(action) ? shared.stoppedId : shared.runningId)
                .formParam("DryRun", "true").post("/").then()
                .statusCode(412)
                .body("Response.Errors.Error.Code", equalTo("DryRunOperation"))
                .body("Response.Errors.Error.Message",
                        equalTo("Request would have succeeded, but DryRun flag is set."));

        assertEquals(before, shared.states());
    }

    /** Every instance is checked before the dry run is answered, so one the call would refuse refuses it. */
    @ParameterizedTest
    @ValueSource(strings = {"StartInstances", "StopInstances", "RebootInstances", "TerminateInstances"})
    void aDryRunNamingAMissingInstanceKeepsTheRealCallsError(String action) {
        Map<String, String> before = shared.states();

        shared.request(action, "InstanceId.1", "StartInstances".equals(action) ? shared.stoppedId : shared.runningId,
                "InstanceId.2", MISSING_INSTANCE).formParam("DryRun", "true").post("/").then()
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidInstanceID.NotFound"));

        assertEquals(before, shared.states());
    }

    @Test
    void aDryRunStartOfATerminatedInstanceKeepsTheRealCallsError() {
        Fixture own = Fixture.launch();
        try {
            own.ec2("TerminateInstances", "InstanceId.1", own.runningId);
            own.awaitState(own.runningId, "terminated");
            Map<String, String> before = own.states();

            for (String dryRun : List.of("true", "false")) {
                own.request("StartInstances", "InstanceId.1", own.runningId).formParam("DryRun", dryRun)
                        .post("/").then()
                        .statusCode(400)
                        .body("Response.Errors.Error.Code", equalTo("IncorrectInstanceState"));
            }

            assertEquals(before, own.states());
        } finally {
            own.terminate();
        }
    }

    /** A DryRun value that is not a boolean is refused as one, and no instance changes. */
    @ParameterizedTest
    @CsvSource({
            "StartInstances, maybe", "StartInstances, ''",
            "StopInstances, maybe", "StopInstances, ''",
            "RebootInstances, maybe", "RebootInstances, ''",
            "TerminateInstances, maybe", "TerminateInstances, ''"
    })
    void aDryRunValueThatIsNotABooleanIsRefused(String action, String value) {
        Map<String, String> before = shared.states();

        shared.request(action, "InstanceId.1", "StartInstances".equals(action) ? shared.stoppedId : shared.runningId)
                .formParam("DryRun", value).post("/").then()
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"))
                .body("Response.Errors.Error.Message", equalTo("DryRun is not a valid boolean."));

        assertEquals(before, shared.states());
    }

    /** Requests the real call accepts, for the instance calls other than start, stop, reboot and terminate. */
    static Stream<String[]> otherInstanceCalls() {
        return Stream.of(
                new String[] {"MonitorInstances", "InstanceId.1={running}"},
                new String[] {"UnmonitorInstances", "InstanceId.1={running}"},
                new String[] {"ModifyInstanceMetadataOptions", "InstanceId={running} HttpTokens=required"},
                new String[] {"ModifyInstanceAttribute", "InstanceId={stopped} InstanceType.Value=t3.small"},
                new String[] {"ModifyInstanceAttribute", "InstanceId={stopped} UserData.Value=" + USER_DATA},
                new String[] {"ModifyInstanceAttribute", "InstanceId={running} SourceDestCheck.Value=false"},
                new String[] {"EnableEbsEncryptionByDefault", ""},
                new String[] {"DisableEbsEncryptionByDefault", ""},
                new String[] {"ModifyEbsDefaultKmsKeyId", "KmsKeyId=alias/dry-run"},
                new String[] {"ResetEbsDefaultKmsKeyId", ""},
                new String[] {"RequestSpotInstances", "InstanceCount=2 LaunchSpecification.ImageId=" + IMAGE
                        + " LaunchSpecification.InstanceType=t3.micro"});
    }

    @ParameterizedTest
    @MethodSource("otherInstanceCalls")
    void aDryRunOfAnotherInstanceCallIsAnsweredWithDryRunOperationAndChangesNothing(String action, String params) {
        String before = shared.snapshot();

        shared.request(action, shared.params(params)).formParam("DryRun", "true").post("/").then()
                .statusCode(412)
                .body("Response.Errors.Error.Code", equalTo("DryRunOperation"));

        assertEquals(before, shared.snapshot());
    }

    @ParameterizedTest
    @MethodSource("otherInstanceCalls")
    void aDryRunValueThatIsNotABooleanIsRefusedByTheOtherInstanceCalls(String action, String params) {
        String before = shared.snapshot();

        shared.request(action, shared.params(params)).formParam("DryRun", "maybe").post("/").then()
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"))
                .body("Response.Errors.Error.Message", equalTo("DryRun is not a valid boolean."));

        assertEquals(before, shared.snapshot());
    }

    /** The real call refuses each of these; its dry run keeps the same error, and neither changes anything. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "MonitorInstances | InstanceId.1=" + MISSING_INSTANCE + " | InvalidInstanceID.NotFound",
            "ModifyInstanceMetadataOptions | InstanceId={running} HttpTokens=sometimes | InvalidParameterValue",
            "ModifyInstanceAttribute | InstanceId={running} UserData.Value=" + USER_DATA + " | IncorrectInstanceState",
            "ModifyInstanceAttribute | InstanceId={stopped} UserData.Value=%%% | InvalidParameterValue",
            "ModifyInstanceAttribute | InstanceId={stopped} GroupId.1=sg-0123456789abcdef0 | InvalidGroup.NotFound",
            "ModifyEbsDefaultKmsKeyId | | MissingParameter",
            "CancelSpotInstanceRequests | SpotInstanceRequestId.1=sir-01234567 | InvalidSpotInstanceRequestID.NotFound"
    })
    void aDryRunTheRealCallWouldRefuseKeepsItsError(String action, String params, String code) {
        String before = shared.snapshot();

        for (String dryRun : List.of("true", "false")) {
            shared.request(action, shared.params(params)).formParam("DryRun", dryRun).post("/").then()
                    .statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo(code));
        }

        assertEquals(before, shared.snapshot());
    }

    @Test
    void aDryRunCancelOfASpotRequestCancelsNothing() {
        Fixture own = Fixture.launch();
        XmlPath requested = own.ec2("RequestSpotInstances", "LaunchSpecification.ImageId", IMAGE,
                "LaunchSpecification.InstanceType", "t3.micro");
        String request = "RequestSpotInstancesResponse.spotInstanceRequestSet.item";
        String requestId = requested.getString(request + ".spotInstanceRequestId");
        String instanceId = requested.getString(request + ".instanceId");
        try {
            own.awaitState(instanceId, "running");
            String before = own.snapshot();

            own.request("CancelSpotInstanceRequests", "SpotInstanceRequestId.1", requestId)
                    .formParam("DryRun", "true").post("/").then()
                    .statusCode(412)
                    .body("Response.Errors.Error.Code", equalTo("DryRunOperation"));

            assertEquals(before, own.snapshot());
        } finally {
            own.ec2("CancelSpotInstanceRequests", "SpotInstanceRequestId.1", requestId);
            own.ec2("TerminateInstances", "InstanceId.1", instanceId);
            own.terminate();
        }
    }

    /** An account holding a running instance and a stopped one. */
    private static final class Fixture {

        private final String account;
        private final String runningId;
        private final String stoppedId;

        private Fixture(String account, String runningId, String stoppedId) {
            this.account = account;
            this.runningId = runningId;
            this.stoppedId = stoppedId;
        }

        static Fixture launch() {
            Fixture empty = new Fixture(Long.toString(ACCOUNTS.incrementAndGet()), null, null);
            String runningId = empty.launchInstance();
            String stoppedId = empty.launchInstance();
            empty.ec2("StopInstances", "InstanceId.1", stoppedId);
            empty.awaitState(stoppedId, "stopped");
            return new Fixture(empty.account, runningId, stoppedId);
        }

        void terminate() {
            ec2("TerminateInstances", "InstanceId.1", runningId, "InstanceId.2", stoppedId);
        }

        /**
         * Everything the instance calls can change in the fixture's account, as the describe calls report it:
         * the instances, the stopped instance's user data, the EBS encryption defaults and the Spot requests.
         */
        String snapshot() {
            return String.join("\n",
                    body("DescribeInstances"),
                    body("DescribeInstanceAttribute", "InstanceId", stoppedId, "Attribute", "userData"),
                    body("GetEbsEncryptionByDefault"),
                    body("GetEbsDefaultKmsKeyId"),
                    body("DescribeSpotInstanceRequests"));
        }

        /** A describe call's answer without the request id, the one part that differs on every call. */
        private String body(String action, String... params) {
            return request(action, params).post("/").then().statusCode(200).extract().asString()
                    .replaceAll("<requestId>[^<]*</requestId>", "");
        }

        /** {@code Key=Value} pairs separated by spaces, with {running} and {stopped} standing for the fixture's ids. */
        String[] params(String spec) {
            if (spec == null || spec.isBlank()) {
                return new String[0];
            }
            return Stream.of(spec.trim().split(" +"))
                    .flatMap(pair -> Stream.of(pair.split("=", 2)))
                    .map(part -> part.replace("{running}", runningId).replace("{stopped}", stoppedId))
                    .toArray(String[]::new);
        }

        private String launchInstance() {
            String instanceId = ec2("RunInstances", "ImageId", IMAGE, "InstanceType", "t3.micro",
                    "MinCount", "1", "MaxCount", "1").getString("RunInstancesResponse.instancesSet.item.instanceId");
            awaitState(instanceId, "running");
            return instanceId;
        }

        void awaitState(String instanceId, String state) {
            await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                    assertEquals(state, states().get(instanceId)));
        }

        /** Every instance of the account with its state, ordered by instance id. */
        Map<String, String> states() {
            XmlPath described = ec2("DescribeInstances");
            List<String> ids = described.getList(INSTANCE + ".instanceId", String.class);
            List<String> states = described.getList(INSTANCE + ".instanceState.name", String.class);
            Map<String, String> byId = new TreeMap<>();
            for (int i = 0; i < ids.size(); i++) {
                byId.put(ids.get(i), states.get(i));
            }
            return byId;
        }

        XmlPath ec2(String action, String... params) {
            return request(action, params).post("/").then().statusCode(200).extract().xmlPath();
        }

        RequestSpecification request(String action, String... params) {
            return QueryRequests.request(account, "ec2", action, params).port(port);
        }
    }
}
