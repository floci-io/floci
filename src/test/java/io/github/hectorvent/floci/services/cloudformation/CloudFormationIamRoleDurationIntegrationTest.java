package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.ValidateSignaturesProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;
import software.amazon.awssdk.services.iam.model.Role;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.StsException;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(ValidateSignaturesProfile.class)
class CloudFormationIamRoleDurationIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private final String name = "cfn-duration-" + UUID.randomUUID();
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> sessions = new ArrayList<>();
    private CloudFormationClient cfn;
    private IamClient iam;
    private StsClient sts;
    private boolean stackCreated;

    @TestHTTPResource
    URI endpoint;

    @Inject
    IamService iamService;

    @BeforeEach
    void clients() {
        cfn = AwsTestClients.create(CloudFormationClient.builder(), "test", Region.EU_WEST_1, endpoint);
        iam = AwsTestClients.create(IamClient.builder(), "test", Region.EU_WEST_1, endpoint);
        sts = AwsTestClients.create(StsClient.builder(), "test", Region.EU_WEST_1, endpoint);
    }

    @AfterEach
    void removeThisTestsStackAndSessions() {
        try {
            sessions.forEach(key -> iamService.unregisterSession(ACCOUNT, key));
            if (stackCreated) {
                cfn.deleteStack(request -> request.stackName(name));
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                        assertThrows(NoSuchEntityException.class,
                                () -> iam.getRole(request -> request.roleName(name))));
            }
        } finally {
            sts.close();
            iam.close();
            cfn.close();
        }
    }

    @Test
    void templateDurationAllowsAnEightHourSession() {
        create(28800);
        assertEquals(28800, role().maxSessionDuration());
        assumeFor(28800);
        assertRejected(28801);
    }

    @Test
    void updatesAndPropertyRemovalRetainRoleIdentityAndChangeNewSessionLimits() {
        create(7200);
        Role original = role();
        AssumeRoleResponse issued = assumeFor(7200);
        Instant expiration = iamService.findSessionExpiration(issued.credentials().accessKeyId()).orElseThrow();

        cfn.updateStack(request -> request.stackName(name).templateBody(template(43200, false))
                .parameters(parameter -> parameter.parameterKey("Duration").parameterValue("43200"))
                .capabilitiesWithStrings("CAPABILITY_NAMED_IAM"));
        awaitStack("UPDATE_COMPLETE");
        assertEquals(original.roleId(), role().roleId());
        assertEquals(43200, role().maxSessionDuration());
        assumeFor(43200);

        cfn.updateStack(request -> request.stackName(name).templateBody(template(null, false))
                .capabilitiesWithStrings("CAPABILITY_NAMED_IAM"));
        awaitStack("UPDATE_COMPLETE");
        assertEquals(original.roleId(), role().roleId());
        assertEquals(3600, role().maxSessionDuration());
        assertRejected(3601);
        assertEquals(expiration, iamService.findSessionExpiration(
                issued.credentials().accessKeyId()).orElseThrow());
    }

    @Test
    void failedUpdateRestoresTheOriginalDuration() {
        create(7200);
        Role original = role();
        cfn.updateStack(request -> request.stackName(name).templateBody(template(14400, true))
                .parameters(parameter -> parameter.parameterKey("Duration").parameterValue("14400"))
                .capabilitiesWithStrings("CAPABILITY_NAMED_IAM"));
        awaitStack("UPDATE_ROLLBACK_COMPLETE");
        assertEquals(original.roleId(), role().roleId());
        assertEquals(7200, role().maxSessionDuration());
        assumeFor(7200);
        assertRejected(7201);
    }

    @Test
    void laterResourceFailureRestoresTheEarlierSuccessfulRoleUpdate() throws Exception {
        create(7200);
        Role original = role();
        ObjectNode updated = (ObjectNode) mapper.readTree(template(14400, false));
        ObjectNode failure = ((ObjectNode) updated.get("Resources")).putObject("Failure")
                .put("Type", "AWS::IAM::Role").put("DependsOn", "Role");
        failure.putObject("Properties").put("RoleName", name + "-invalid")
                .put("MaxSessionDuration", 1);
        cfn.updateStack(request -> request.stackName(name).templateBody(updated.toString())
                .parameters(parameter -> parameter.parameterKey("Duration").parameterValue("14400"))
                .capabilitiesWithStrings("CAPABILITY_NAMED_IAM"));
        awaitStack("UPDATE_ROLLBACK_COMPLETE");
        assertEquals(original.roleId(), role().roleId());
        assertEquals(7200, role().maxSessionDuration());
        assumeFor(7200);
        assertRejected(7201);
        assertThrows(NoSuchEntityException.class, () -> iam.getRole(request -> request.roleName(name + "-invalid")));
    }

    private void create(int duration) {
        cfn.createStack(request -> request.stackName(name).templateBody(template(duration, false))
                .capabilitiesWithStrings("CAPABILITY_NAMED_IAM"));
        stackCreated = true;
        awaitStack("CREATE_COMPLETE");
    }

    private void awaitStack(String status) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertEquals(status, cfn.describeStacks(request -> request.stackName(name))
                        .stacks().getFirst().stackStatusAsString()));
    }

    private Role role() {
        return iam.getRole(request -> request.roleName(name)).role();
    }

    private AssumeRoleResponse assumeFor(int seconds) {
        AssumeRoleResponse response = sts.assumeRole(request -> request.roleArn(role().arn())
                .roleSessionName("duration-check").durationSeconds(seconds));
        sessions.add(response.credentials().accessKeyId());
        assertFalse(response.credentials().expiration().isBefore(Instant.now().plusSeconds(seconds - 5)));
        return response;
    }

    private void assertRejected(int seconds) {
        StsException error = assertThrows(StsException.class, () -> sts.assumeRole(request ->
                request.roleArn(role().arn()).roleSessionName("rejected").durationSeconds(seconds)));
        assertEquals(400, error.statusCode());
        assertEquals("ValidationError", error.awsErrorDetails().errorCode());
    }

    private String template(Integer duration, boolean missingManagedPolicy) {
        ObjectNode template = mapper.createObjectNode();
        ObjectNode properties = template.putObject("Resources").putObject("Role")
                .put("Type", "AWS::IAM::Role").putObject("Properties");
        properties.put("RoleName", name);
        ObjectNode trust = properties.putObject("AssumeRolePolicyDocument").put("Version", "2012-10-17");
        trust.putArray("Statement").addObject().put("Effect", "Allow").put("Action", "sts:AssumeRole")
                .putObject("Principal").put("AWS", "arn:aws:iam::" + ACCOUNT + ":root");
        if (duration != null) {
            template.putObject("Parameters").putObject("Duration").put("Type", "Number").put("Default", duration);
            properties.putObject("MaxSessionDuration").put("Ref", "Duration");
        }
        if (missingManagedPolicy) {
            properties.putArray("ManagedPolicyArns").add("arn:aws:iam::" + ACCOUNT + ":policy/missing-" + name);
        }
        return template.toString();
    }
}
