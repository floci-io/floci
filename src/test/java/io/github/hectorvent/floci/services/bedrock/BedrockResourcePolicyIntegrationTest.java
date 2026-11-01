package io.github.hectorvent.floci.services.bedrock;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrock.BedrockClient;
import software.amazon.awssdk.services.bedrock.model.BedrockException;
import software.amazon.awssdk.services.bedrock.model.CreateGuardrailResponse;
import software.amazon.awssdk.services.bedrock.model.PutResourcePolicyResponse;
import software.amazon.awssdk.services.bedrock.model.ResourceNotFoundException;
import software.amazon.awssdk.services.bedrock.model.ValidationException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class BedrockResourcePolicyIntegrationTest {

    private static final String ACCOUNT = "741623800011";
    private static final String OTHER_ACCOUNT = "741623800012";

    @Test
    void putGetAndDeleteRoundTripOnAGuardrail() {
        try (BedrockClient client = client(ACCOUNT, Region.US_EAST_1)) {
            CreateGuardrailResponse created = create(client);
            String arn = created.guardrailArn();
            try {
                assertNull(client.getResourcePolicy(r -> r.resourceArn(arn)).resourcePolicy());

                String policy = rootPolicy(arn, "\"bedrock:ApplyGuardrail\",\"bedrock:GetGuardrail\"");
                PutResourcePolicyResponse put =
                        client.putResourcePolicy(r -> r.resourceArn(arn).resourcePolicy(policy));
                assertEquals(201, put.sdkHttpResponse().statusCode());
                assertEquals(arn, put.resourceArn());
                assertEquals(policy, client.getResourcePolicy(r -> r.resourceArn(arn)).resourcePolicy());

                String organizationPolicy = organizationPolicy(arn);
                client.putResourcePolicy(r -> r.resourceArn(arn).resourcePolicy(organizationPolicy));
                assertEquals(organizationPolicy, client.getResourcePolicy(r -> r.resourceArn(arn)).resourcePolicy());

                assertEquals(200, client.deleteResourcePolicy(r -> r.resourceArn(arn)).sdkHttpResponse().statusCode());
                assertNull(client.getResourcePolicy(r -> r.resourceArn(arn)).resourcePolicy());
            } finally {
                client.deleteGuardrail(r -> r.guardrailIdentifier(created.guardrailId()));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "empty", "too-long", "no-statement", "deny", "other-resource",
            "unsupported-action", "public-principal", "wildcard-organization", "other-account-principal",
            "unsupported-condition", "trailing-content", "extra-principal", "org-paths-object"})
    void aPolicyOutsideTheGuardrailPolicyLanguageIsRejected(String invalid) {
        try (BedrockClient client = client(ACCOUNT, Region.US_EAST_1)) {
            CreateGuardrailResponse created = create(client);
            String arn = created.guardrailArn();
            try {
                String valid = rootPolicy(arn, "\"bedrock:ApplyGuardrail\"");
                String policy = switch (invalid) {
                    case "not-json" -> "not json";
                    case "empty" -> "";
                    case "too-long" -> valid + " ".repeat(20481 - valid.length());
                    case "no-statement" -> "{\"Version\":\"2012-10-17\",\"Statement\":[]}";
                    case "deny" -> valid.replace("\"Allow\"", "\"Deny\"");
                    case "other-resource" -> valid.replace("\"Resource\":\"" + arn + "\"",
                            "\"Resource\":\"" + arn + "-other\"");
                    case "unsupported-action" -> rootPolicy(arn, "\"bedrock:InvokeModel\"");
                    case "public-principal" ->
                            valid.replace("{\"AWS\":\"arn:aws:iam::" + ACCOUNT + ":root\"}", "\"*\"");
                    case "wildcard-organization" -> organizationPolicy(arn).replace("o-exampleorgid", "o-*");
                    case "other-account-principal" -> valid.replace(ACCOUNT + ":root", OTHER_ACCOUNT + ":root");
                    case "trailing-content" -> valid + " {}";
                    case "extra-principal" -> valid.replace(ACCOUNT + ":root\"}",
                            ACCOUNT + ":root\",\"Service\":\"lambda.amazonaws.com\"}");
                    case "org-paths-object" -> organizationPolicy(arn).replace(
                            "[\"o-exampleorgid/*/ou-ab12-cdefgh34/*\"]", "{}");
                    default -> valid.replace("\"Resource\"",
                            "\"Condition\":{\"Bool\":{\"aws:SecureTransport\":\"true\"}},\"Resource\"");
                };
                ValidationException error = assertThrows(ValidationException.class,
                        () -> client.putResourcePolicy(r -> r.resourceArn(arn).resourcePolicy(policy)));
                assertEquals(400, error.statusCode());
                assertNull(client.getResourcePolicy(r -> r.resourceArn(arn)).resourcePolicy());
            } finally {
                client.deleteGuardrail(r -> r.guardrailIdentifier(created.guardrailId()));
            }
        }
    }

    @Test
    void anArnNamingNoGuardrailIsNotFound() {
        try (BedrockClient client = client(ACCOUNT, Region.US_EAST_1);
             BedrockClient otherAccount = client(OTHER_ACCOUNT, Region.US_EAST_1)) {
            String unknown = "arn:aws:bedrock:us-east-1:" + ACCOUNT + ":guardrail/0123456789ab";
            String policy = rootPolicy(unknown, "\"bedrock:ApplyGuardrail\"");
            assertEquals(404, assertThrows(ResourceNotFoundException.class,
                    () -> client.getResourcePolicy(r -> r.resourceArn(unknown))).statusCode());
            assertEquals(404, assertThrows(ResourceNotFoundException.class,
                    () -> client.deleteResourcePolicy(r -> r.resourceArn(unknown))).statusCode());
            BedrockException put = assertThrows(BedrockException.class,
                    () -> client.putResourcePolicy(r -> r.resourceArn(unknown).resourcePolicy(policy)));
            assertEquals(404, put.statusCode());
            assertEquals("ResourceNotFoundException", put.awsErrorDetails().errorCode());

            CreateGuardrailResponse created = create(client);
            try {
                assertThrows(ResourceNotFoundException.class,
                        () -> otherAccount.getResourcePolicy(r -> r.resourceArn(created.guardrailArn())));
            } finally {
                client.deleteGuardrail(r -> r.guardrailIdentifier(created.guardrailId()));
            }
        }
    }

    @Test
    void deletingTheGuardrailDeletesItsPolicy() {
        try (BedrockClient client = client(ACCOUNT, Region.US_EAST_1)) {
            String name = "rp-" + UUID.randomUUID();
            CreateGuardrailResponse created = create(client, name);
            String arn = created.guardrailArn();
            String policy = rootPolicy(arn, "\"bedrock:ApplyGuardrail\"");
            client.putResourcePolicy(r -> r.resourceArn(arn).resourcePolicy(policy));

            client.deleteGuardrail(r -> r.guardrailIdentifier(created.guardrailId()));

            assertThrows(ResourceNotFoundException.class, () -> client.getResourcePolicy(r -> r.resourceArn(arn)));
            CreateGuardrailResponse recreated = create(client, name);
            try {
                assertNull(client.getResourcePolicy(r -> r.resourceArn(recreated.guardrailArn())).resourcePolicy());
            } finally {
                client.deleteGuardrail(r -> r.guardrailIdentifier(recreated.guardrailId()));
            }
        }
    }

    @Test
    void aGuardrailProfilePolicyIsKeptUnderTheCallingRegion() {
        String profile = "arn:aws:bedrock:us-west-2:" + ACCOUNT + ":guardrail-profile/us.guardrail.v1:0";
        try (BedrockClient source = client(ACCOUNT, Region.US_EAST_1);
             BedrockClient destination = client(ACCOUNT, Region.US_WEST_2);
             BedrockClient otherAccount = client(OTHER_ACCOUNT, Region.US_EAST_1)) {
            String policy = rootPolicy(profile, "\"bedrock:ApplyGuardrail\"");
            try {
                assertEquals("{}", source.getResourcePolicy(r -> r.resourceArn(profile)).resourcePolicy());

                source.putResourcePolicy(r -> r.resourceArn(profile).resourcePolicy(policy));

                assertEquals(policy, source.getResourcePolicy(r -> r.resourceArn(profile)).resourcePolicy());
                assertEquals("{}", destination.getResourcePolicy(r -> r.resourceArn(profile)).resourcePolicy());
                assertThrows(ValidationException.class, () -> source.putResourcePolicy(r -> r.resourceArn(profile)
                        .resourcePolicy(rootPolicy(profile, "\"bedrock:GetGuardrail\""))));
                assertThrows(ResourceNotFoundException.class,
                        () -> otherAccount.getResourcePolicy(r -> r.resourceArn(profile)));

                source.deleteResourcePolicy(r -> r.resourceArn(profile));
                assertEquals("{}", source.getResourcePolicy(r -> r.resourceArn(profile)).resourcePolicy());
            } finally {
                source.deleteResourcePolicy(r -> r.resourceArn(profile));
            }
        }
    }

    private static CreateGuardrailResponse create(BedrockClient client) {
        return create(client, "rp-" + UUID.randomUUID());
    }

    private static CreateGuardrailResponse create(BedrockClient client, String name) {
        return client.createGuardrail(r -> r.name(name)
                .blockedInputMessaging("blocked").blockedOutputsMessaging("blocked"));
    }

    private static String rootPolicy(String resourceArn, String actions) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":"arn:aws:iam::%s:root"},\
                "Action":[%s],"Resource":"%s"}]}""".formatted(ACCOUNT, actions, resourceArn);
    }

    private static String organizationPolicy(String resourceArn) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":"*",\
                "Action":["bedrock:ApplyGuardrail","bedrock:GetGuardrail"],"Resource":"%s",\
                "Condition":{"StringEquals":{"aws:PrincipalOrgID":"o-exampleorgid"},\
                "ForAnyValue:StringLike":{"aws:PrincipalOrgPaths":["o-exampleorgid/*/ou-ab12-cdefgh34/*"]}}}]}"""
                .formatted(resourceArn);
    }

    private static BedrockClient client(String account, Region region) {
        return AwsTestClients.create(BedrockClient.builder(), account, region);
    }
}
