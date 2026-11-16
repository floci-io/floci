package io.floci.aws.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.ValidateSignaturesProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AttachedPolicy;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(ValidateSignaturesProfile.class)
class IamManagedPolicyCaseIntegrationTest {

    private static final String CANONICAL = "arn:aws:iam::aws:policy/AWSXRayDaemonWriteAccess";
    private static final String ALIAS = "arn:aws:iam::aws:policy/AWSXrayDaemonWriteAccess";

    @TestHTTPResource
    URI endpoint;

    @ParameterizedTest
    @ValueSource(strings = {"Role", "User"})
    void cfnRetainsOnePolicyAcrossCaseChangesAndRemovesIt(String kind) {
        String name = "policy-case-" + UUID.randomUUID();
        try (CloudFormationClient cfn = AwsTestClients.create(
                CloudFormationClient.builder(), "test", Region.US_EAST_1, endpoint);
             IamClient iam = AwsTestClients.create(IamClient.builder(), "test", Region.US_EAST_1, endpoint)) {
            assertEquals(CANONICAL, iam.getPolicy(request -> request.policyArn(ALIAS)).policy().arn());
            try {
                CfnStackWaits.createStack(cfn, name, template(kind, name, ALIAS), "CREATE_COMPLETE");
                assertEquals(List.of(CANONICAL), attached(iam, kind, name));
                for (String policy : new String[] {null, ALIAS, CANONICAL, ALIAS, null}) {
                    CfnStackWaits.updateStack(cfn, name, template(kind, name, policy), "UPDATE_COMPLETE");
                    assertEquals(policy == null ? List.of() : List.of(CANONICAL), attached(iam, kind, name));
                }
            } finally {
                CfnStackWaits.deleteStack(cfn, name);
                if ("Role".equals(kind)) {
                    assertThrows(NoSuchEntityException.class,
                            () -> iam.getRole(request -> request.roleName(name)));
                } else {
                    assertThrows(NoSuchEntityException.class,
                            () -> iam.getUser(request -> request.userName(name)));
                }
            }
        }
    }

    private static List<String> attached(IamClient iam, String kind, String name) {
        List<AttachedPolicy> policies = "Role".equals(kind)
                ? iam.listAttachedRolePolicies(request -> request.roleName(name)).attachedPolicies()
                : iam.listAttachedUserPolicies(request -> request.userName(name)).attachedPolicies();
        return policies.stream().map(AttachedPolicy::policyArn).toList();
    }

    private static String template(String kind, String name, String policy) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        ObjectNode resource = root.putObject("Resources").putObject("Principal");
        resource.put("Type", "AWS::IAM::" + kind);
        ObjectNode properties = resource.putObject("Properties").put(kind + "Name", name);
        if ("Role".equals(kind)) {
            ObjectNode trust = properties.putObject("AssumeRolePolicyDocument");
            trust.put("Version", "2012-10-17");
            ObjectNode statement = trust.putArray("Statement").addObject();
            statement.put("Effect", "Allow").put("Action", "sts:AssumeRole");
            statement.putObject("Principal").put("Service", "lambda.amazonaws.com");
        }
        if (policy != null) {
            properties.putArray("ManagedPolicyArns").add(policy);
        }
        return root.toString();
    }
}
