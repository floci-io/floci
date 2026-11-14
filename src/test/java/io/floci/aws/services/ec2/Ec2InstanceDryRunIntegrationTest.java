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
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DryRun on StartInstances, StopInstances, RebootInstances and TerminateInstances is answered with
 * DryRunOperation and HTTP 412 once every instance it names has passed the checks the real call makes, and
 * it changes no instance's state. A dry run naming an instance the real call would refuse keeps that call's
 * error, even when it also names one it would accept.
 */
@QuarkusTest
class Ec2InstanceDryRunIntegrationTest {

    /** Each test works in an account of its own, so DescribeInstances lists only the fixture's instances. */
    private static final AtomicLong ACCOUNTS = new AtomicLong(726200000000L);
    private static final String MISSING_INSTANCE = "i-0123456789abcdef0";
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
