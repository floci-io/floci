package io.floci.aws.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;
import software.amazon.awssdk.services.iam.model.Tag;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * End-to-end check that CloudFormation attaches the {@code Tags} of an AWS::IAM::Role to the
 * role (issue #9199): ListRoleTags returns them after a create, an update drives them to the
 * template's set while keeping a tag added out of band, and a failed update puts the prior
 * tags back.
 */
@QuarkusTest
class CloudFormationIamRoleTagsIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private final String name = "cfn-role-tags-" + UUID.randomUUID();
    private final ObjectMapper mapper = new ObjectMapper();
    private CloudFormationClient cfn;
    private IamClient iam;
    private boolean stackCreated;

    @TestHTTPResource
    URI endpoint;

    @BeforeEach
    void clients() {
        cfn = AwsTestClients.create(CloudFormationClient.builder(), "test", Region.EU_WEST_1, endpoint);
        iam = AwsTestClients.create(IamClient.builder(), "test", Region.EU_WEST_1, endpoint);
    }

    @AfterEach
    void removeThisTestsStack() {
        try {
            if (stackCreated) {
                cfn.deleteStack(request -> request.stackName(name));
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                        assertThrows(NoSuchEntityException.class,
                                () -> iam.getRole(request -> request.roleName(name))));
            }
        } finally {
            iam.close();
            cfn.close();
        }
    }

    @Test
    void createAttachesTheTemplateTagsToTheRole() {
        create(Map.of("team", "payments"));
        assertEquals(Map.of("team", "payments"), roleTags());
    }

    @Test
    void updateDrivesTheTagsToTheTemplateSetAndKeepsOneAddedOutOfBand() {
        create(Map.of("team", "payments"));
        update(Map.of("team", "new-value", "env", "prod"), false);
        assertEquals(Map.of("team", "new-value", "env", "prod"), roleTags());

        iam.tagRole(request -> request.roleName(name).tags(
                Tag.builder().key("extra").value("mine").build()));
        update(Map.of("team", "older"), false);
        Map<String, String> tags = roleTags();
        assertEquals("older", tags.get("team"));
        assertEquals("mine", tags.get("extra"));
        assertFalse(tags.containsKey("env"));
    }

    @Test
    void failedUpdateRestoresThePriorTags() {
        create(Map.of("team", "prior"));
        update(Map.of("team", "new"), true);
        assertEquals(Map.of("team", "prior"), roleTags());
    }

    private void create(Map<String, String> tags) {
        cfn.createStack(request -> request.stackName(name).templateBody(template(tags, false))
                .capabilitiesWithStrings("CAPABILITY_NAMED_IAM"));
        stackCreated = true;
        CfnStackWaits.awaitStatus(cfn, name, "CREATE_COMPLETE");
    }

    private void update(Map<String, String> tags, boolean missingManagedPolicy) {
        cfn.updateStack(request -> request.stackName(name).templateBody(template(tags, missingManagedPolicy))
                .capabilitiesWithStrings("CAPABILITY_NAMED_IAM"));
        CfnStackWaits.awaitStatus(cfn, name, missingManagedPolicy ? "UPDATE_ROLLBACK_COMPLETE" : "UPDATE_COMPLETE");
    }

    private Map<String, String> roleTags() {
        List<Tag> tags = iam.listRoleTags(request -> request.roleName(name)).tags();
        Map<String, String> byKey = new LinkedHashMap<>();
        tags.forEach(tag -> byKey.put(tag.key(), tag.value()));
        return byKey;
    }

    private String template(Map<String, String> tags, boolean missingManagedPolicy) {
        ObjectNode template = mapper.createObjectNode();
        ObjectNode properties = template.putObject("Resources").putObject("Role")
                .put("Type", "AWS::IAM::Role").putObject("Properties");
        properties.put("RoleName", name);
        ObjectNode trust = properties.putObject("AssumeRolePolicyDocument").put("Version", "2012-10-17");
        trust.putArray("Statement").addObject().put("Effect", "Allow").put("Action", "sts:AssumeRole")
                .putObject("Principal").put("AWS", "arn:aws:iam::" + ACCOUNT + ":root");
        if (tags != null) {
            ArrayNode declared = properties.putArray("Tags");
            tags.forEach((key, value) -> declared.addObject().put("Key", key).put("Value", value));
        }
        if (missingManagedPolicy) {
            properties.putArray("ManagedPolicyArns").add("arn:aws:iam::" + ACCOUNT + ":policy/missing-" + name);
        }
        return template.toString();
    }
}
