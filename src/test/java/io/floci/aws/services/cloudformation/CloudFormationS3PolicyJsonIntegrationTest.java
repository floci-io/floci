package io.floci.aws.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.S3IamEnforcementProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(S3IamEnforcementProfile.class)
class CloudFormationS3PolicyJsonIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "000000000000";

    @ParameterizedTest
    @ValueSource(strings = {"object", "string", "substitution"})
    void aPolicyRemainsAJsonObjectAfterItsTemplateRepresentationIsResolved(String form) throws Exception {
        String name = "policy-json-" + UUID.randomUUID().toString().replace("-", "");
        String resourceArn = "arn:aws:s3:::" + name + "/*";
        ObjectNode template = MAPPER.createObjectNode();
        ObjectNode resources = template.putObject("Resources");
        resources.putObject("Bucket").put("Type", "AWS::S3::Bucket")
                .putObject("Properties").put("BucketName", name);
        ObjectNode properties = resources.putObject("Policy").put("Type", "AWS::S3::BucketPolicy")
                .putObject("Properties");
        properties.putObject("Bucket").put("Ref", "Bucket");
        String policy = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Deny",
                "Principal":{"AWS":"arn:aws:iam::%s:root"},
                "Action":"s3:GetObject","Resource":"%s"}]}
                """.formatted(ACCOUNT, resourceArn);
        switch (form) {
            case "object" -> properties.set("PolicyDocument", MAPPER.readTree(policy));
            case "string" -> properties.put("PolicyDocument", policy);
            case "substitution" -> properties.putObject("PolicyDocument")
                    .put("Fn::Sub", policy.replace(resourceArn, "${Bucket.Arn}/*"));
            default -> throw new IllegalArgumentException(form);
        }
        try (CloudFormationClient cfn = AwsTestClients.create(
                     CloudFormationClient.builder(), "test", "test", Region.US_EAST_1);
             S3Client s3 = AwsTestClients.create(S3Client.builder(), "test", "test", Region.US_EAST_1)) {
            cfn.createStack(request -> request.stackName(name).templateBody(template.toString()));
            boolean objectCreated = false;
            try {
                assertEquals("CREATE_COMPLETE",
                        CfnStackWaits.awaitStatus(cfn, name, "CREATE_COMPLETE").stackStatusAsString());
                JsonNode stored = MAPPER.readTree(s3.getBucketPolicy(request -> request.bucket(name)).policy());
                assertTrue(stored.isObject());
                assertEquals(resourceArn, stored.path("Statement").get(0).path("Resource").asText());
                s3.putObject(request -> request.bucket(name).key("owned"), RequestBody.fromString("owned"));
                objectCreated = true;
                S3Exception denied = assertThrows(S3Exception.class,
                        () -> s3.getObjectAsBytes(request -> request.bucket(name).key("owned")));
                assertEquals("AccessDenied", denied.awsErrorDetails().errorCode());
            } finally {
                if (objectCreated) {
                    s3.deleteObject(request -> request.bucket(name).key("owned"));
                }
                CfnStackWaits.deleteStack(cfn, name);
            }
        }
    }
}
