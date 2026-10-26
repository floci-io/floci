package io.github.hectorvent.floci.services.bedrock;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrock.BedrockClient;
import software.amazon.awssdk.services.bedrock.model.CreateGuardrailResponse;
import software.amazon.awssdk.services.bedrock.model.ResourceNotFoundException;
import software.amazon.awssdk.services.bedrock.model.Tag;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class BedrockTagArnIntegrationTest {

    private static final String OWNER = "741623800001";
    private static final Tag ORIGINAL = Tag.builder().key("team").value("original").build();

    @ParameterizedTest
    @CsvSource({"ListTagsForResource,account", "TagResource,account", "UntagResource,account",
            "ListTagsForResource,region", "TagResource,region", "UntagResource,region",
            "ListTagsForResource,partition", "TagResource,partition", "UntagResource,partition",
            "ListTagsForResource,service", "TagResource,service", "UntagResource,service",
            "ListTagsForResource,resourceType", "TagResource,resourceType", "UntagResource,resourceType"})
    void sdkRejectsAnArnThatDoesNotIdentifyTheStoredGuardrail(String action, String changedPart) {
        try (BedrockClient client = client(OWNER, "us-east-1")) {
            CreateGuardrailResponse created = create(client);
            try {
                String arn = created.guardrailArn();
                String wrongArn = switch (changedPart) {
                    case "account" -> arn.replace(OWNER, "741623800002");
                    case "region" -> arn.replace("us-east-1", "eu-west-1");
                    case "partition" -> arn.replace("arn:aws:", "arn:aws-cn:");
                    case "service" -> arn.replace(":bedrock:", ":bedrock-runtime:");
                    default -> arn.replace(":guardrail/", ":custom-model/");
                };
                ResourceNotFoundException error = assertThrows(ResourceNotFoundException.class, () -> {
                    switch (action) {
                        case "ListTagsForResource" -> client.listTagsForResource(request -> request.resourceARN(wrongArn));
                        case "TagResource" -> client.tagResource(request -> request.resourceARN(wrongArn)
                                .tags(Tag.builder().key("team").value("changed").build()));
                        default -> client.untagResource(request -> request.resourceARN(wrongArn).tagKeys("team"));
                    }
                });
                assertEquals(404, error.statusCode());
                assertEquals("ResourceNotFoundException", error.awsErrorDetails().errorCode());
                assertEquals(List.of(ORIGINAL), client.listTagsForResource(request -> request.resourceARN(arn)).tags());
            } finally {
                client.deleteGuardrail(request -> request.guardrailIdentifier(created.guardrailId()));
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"us-east-1,arn:aws:", "cn-north-1,arn:aws-cn:", "us-gov-west-1,arn:aws-us-gov:"})
    void sdkAcceptsTheExactArnAndTheExistingBareIdForm(String region, String prefix) {
        try (BedrockClient client = client(OWNER, region)) {
            CreateGuardrailResponse created = create(client);
            try {
                assertEquals(prefix + "bedrock:" + region + ":" + OWNER + ":guardrail/" + created.guardrailId(),
                        created.guardrailArn());
                for (String identifier : List.of(created.guardrailArn(), created.guardrailId())) {
                    client.tagResource(request -> request.resourceARN(identifier)
                            .tags(Tag.builder().key("extra").value("accepted").build()));
                    assertEquals(2, client.listTagsForResource(request -> request.resourceARN(identifier)).tags().size());
                    client.untagResource(request -> request.resourceARN(identifier).tagKeys("extra"));
                    assertEquals(List.of(ORIGINAL),
                            client.listTagsForResource(request -> request.resourceARN(identifier)).tags());
                }
                try (BedrockClient otherAccount = client("741623800002", region);
                     BedrockClient otherRegion = client(OWNER, "eu-west-1")) {
                    for (BedrockClient other : List.of(otherAccount, otherRegion)) {
                        ResourceNotFoundException error = assertThrows(ResourceNotFoundException.class,
                                () -> other.listTagsForResource(request -> request.resourceARN(created.guardrailArn())));
                        assertEquals(404, error.statusCode());
                    }
                }
            } finally {
                client.deleteGuardrail(request -> request.guardrailIdentifier(created.guardrailId()));
            }
        }
    }

    private CreateGuardrailResponse create(BedrockClient client) {
        return client.createGuardrail(request -> request.name("tag-arn-" + UUID.randomUUID())
                .blockedInputMessaging("blocked").blockedOutputsMessaging("blocked").tags(ORIGINAL));
    }

    private BedrockClient client(String account, String region) {
        return BedrockClient.builder().endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test"))).build();
    }
}
