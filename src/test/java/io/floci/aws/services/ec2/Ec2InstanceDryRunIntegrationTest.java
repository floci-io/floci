package io.floci.aws.services.ec2;

import io.floci.aws.testing.QueryRequests;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

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
 */
@QuarkusTest
class Ec2InstanceDryRunIntegrationTest {

    /** Each test works in an account of its own, so DescribeInstances lists only the fixture's instances. */
    private static final AtomicLong ACCOUNTS = new AtomicLong(726200000000L);
    private static final String MISSING_INSTANCE = "i-0123456789abcdef0";
    private static final String IMAGE = "ami-1234567890abcdef0";
    private static final String USER_DATA = "IyEvYmluL3NoCg==";
    private static final String INSTANCE = "DescribeInstancesResponse.reservationSet.item.instancesSet.item";

    private String account;
    private String runningId;
    private String stoppedId;

    @BeforeEach
    void launchARunningAndAStoppedInstance() {
        account = Long.toString(ACCOUNTS.incrementAndGet());
        runningId = launch();
        stoppedId = launch();
        ec2("StopInstances", "InstanceId.1", stoppedId);
        awaitState(stoppedId, "stopped");
    }

    @AfterEach
    void terminateTheInstances() {
        ec2("TerminateInstances", "InstanceId.1", runningId, "InstanceId.2", stoppedId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"StartInstances", "StopInstances", "RebootInstances", "TerminateInstances"})
    void aDryRunIsAnsweredWithDryRunOperationAndChangesNothing(String action) {
        Map<String, String> before = states();

        request(action, "InstanceId.1", "StartInstances".equals(action) ? stoppedId : runningId)
                .formParam("DryRun", "true").post("/").then()
                .statusCode(412)
                .body("Response.Errors.Error.Code", equalTo("DryRunOperation"))
                .body("Response.Errors.Error.Message",
                        equalTo("Request would have succeeded, but DryRun flag is set."));

        assertEquals(before, states());
    }

    /** Every instance is checked before the dry run is answered, so one the call would refuse refuses it. */
    @ParameterizedTest
    @ValueSource(strings = {"StartInstances", "StopInstances", "RebootInstances", "TerminateInstances"})
    void aDryRunNamingAMissingInstanceKeepsTheRealCallsError(String action) {
        Map<String, String> before = states();

        request(action, "InstanceId.1", "StartInstances".equals(action) ? stoppedId : runningId,
                "InstanceId.2", MISSING_INSTANCE).formParam("DryRun", "true").post("/").then()
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidInstanceID.NotFound"));

        assertEquals(before, states());
    }

    @Test
    void aDryRunStartOfATerminatedInstanceKeepsTheRealCallsError() {
        ec2("TerminateInstances", "InstanceId.1", runningId);
        awaitState(runningId, "terminated");
        Map<String, String> before = states();

        for (String dryRun : List.of("true", "false")) {
            request("StartInstances", "InstanceId.1", runningId).formParam("DryRun", dryRun).post("/").then()
                    .statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("IncorrectInstanceState"));
        }

        assertEquals(before, states());
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
        Map<String, String> before = states();

        request(action, "InstanceId.1", "StartInstances".equals(action) ? stoppedId : runningId)
                .formParam("DryRun", value).post("/").then()
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"))
                .body("Response.Errors.Error.Message", equalTo("DryRun is not a valid boolean."));

        assertEquals(before, states());
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
        String before = snapshot();

        request(action, params(params)).formParam("DryRun", "true").post("/").then()
                .statusCode(412)
                .body("Response.Errors.Error.Code", equalTo("DryRunOperation"));

        assertEquals(before, snapshot());
    }

    @ParameterizedTest
    @MethodSource("otherInstanceCalls")
    void aDryRunValueThatIsNotABooleanIsRefusedByTheOtherInstanceCalls(String action, String params) {
        String before = snapshot();

        request(action, params(params)).formParam("DryRun", "maybe").post("/").then()
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"))
                .body("Response.Errors.Error.Message", equalTo("DryRun is not a valid boolean."));

        assertEquals(before, snapshot());
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
        String before = snapshot();

        for (String dryRun : List.of("true", "false")) {
            request(action, params(params)).formParam("DryRun", dryRun).post("/").then()
                    .statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo(code));
        }

        assertEquals(before, snapshot());
    }

    @Test
    void aDryRunCancelOfASpotRequestCancelsNothing() {
        XmlPath requested = ec2("RequestSpotInstances", "LaunchSpecification.ImageId", IMAGE,
                "LaunchSpecification.InstanceType", "t3.micro");
        String request = "RequestSpotInstancesResponse.spotInstanceRequestSet.item";
        String requestId = requested.getString(request + ".spotInstanceRequestId");
        String instanceId = requested.getString(request + ".instanceId");
        try {
            awaitState(instanceId, "running");
            String before = snapshot();

            request("CancelSpotInstanceRequests", "SpotInstanceRequestId.1", requestId)
                    .formParam("DryRun", "true").post("/").then()
                    .statusCode(412)
                    .body("Response.Errors.Error.Code", equalTo("DryRunOperation"));

            assertEquals(before, snapshot());
        } finally {
            ec2("CancelSpotInstanceRequests", "SpotInstanceRequestId.1", requestId);
            ec2("TerminateInstances", "InstanceId.1", instanceId);
        }
    }

    /**
     * Everything the instance calls can change in the test's account, as the describe calls report it:
     * the instances, the stopped instance's user data, the EBS encryption defaults and the Spot requests.
     */
    private String snapshot() {
        return String.join("\n",
                body("DescribeInstances"),
                body("DescribeInstanceAttribute", "InstanceId", stoppedId, "Attribute", "userData"),
                body("GetEbsEncryptionByDefault"),
                body("GetEbsDefaultKmsKeyId"),
                body("DescribeSpotInstanceRequests"));
    }

    /**
     * A describe call's answer without the ids that differ on every call: the request id, and the
     * reservation ids DescribeInstances gives each instance anew.
     */
    private String body(String action, String... params) {
        return request(action, params).post("/").then().statusCode(200).extract().asString()
                .replaceAll("<(requestId|reservationId)>[^<]*</\\1>", "");
    }

    /** {@code Key=Value} pairs separated by spaces, with {running} and {stopped} standing for the fixture's ids. */
    private String[] params(String spec) {
        if (spec == null || spec.isBlank()) {
            return new String[0];
        }
        return Stream.of(spec.trim().split(" +"))
                .flatMap(pair -> Stream.of(pair.split("=", 2)))
                .map(part -> part.replace("{running}", runningId).replace("{stopped}", stoppedId))
                .toArray(String[]::new);
    }

    private String launch() {
        String instanceId = ec2("RunInstances", "ImageId", "ami-1234567890abcdef0", "InstanceType", "t3.micro",
                "MinCount", "1", "MaxCount", "1").getString("RunInstancesResponse.instancesSet.item.instanceId");
        awaitState(instanceId, "running");
        return instanceId;
    }

    private void awaitState(String instanceId, String state) {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                assertEquals(state, states().get(instanceId)));
    }

    /** Every instance of the account with its state, ordered by instance id. */
    private Map<String, String> states() {
        XmlPath described = ec2("DescribeInstances");
        List<String> ids = described.getList(INSTANCE + ".instanceId", String.class);
        List<String> states = described.getList(INSTANCE + ".instanceState.name", String.class);
        Map<String, String> byId = new TreeMap<>();
        for (int i = 0; i < ids.size(); i++) {
            byId.put(ids.get(i), states.get(i));
        }
        return byId;
    }

    private XmlPath ec2(String action, String... params) {
        return request(action, params).post("/").then().statusCode(200).extract().xmlPath();
    }

    private RequestSpecification request(String action, String... params) {
        return QueryRequests.request(account, "ec2", action, params);
    }
}
