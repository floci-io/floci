package io.floci.aws.services.bedrock;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrock.BedrockClient;
import software.amazon.awssdk.services.bedrock.model.AccessDeniedException;
import software.amazon.awssdk.services.bedrock.model.CreateInferenceProfileResponse;
import software.amazon.awssdk.services.bedrock.model.GetInferenceProfileResponse;
import software.amazon.awssdk.services.bedrock.model.InferenceProfileModelSource;
import software.amazon.awssdk.services.bedrock.model.InferenceProfileStatus;
import software.amazon.awssdk.services.bedrock.model.InferenceProfileSummary;
import software.amazon.awssdk.services.bedrock.model.InferenceProfileType;
import software.amazon.awssdk.services.bedrock.model.ResourceNotFoundException;
import software.amazon.awssdk.services.bedrock.model.Tag;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class BedrockApplicationInferenceProfileIntegrationTest {

    private static final String ACCOUNT = "741623800008";
    private static final String REGION = "us-east-1";
    private static final String MODEL_ARN =
            "arn:aws:bedrock:us-east-1::foundation-model/amazon.nova-micro-v1:0";

    @Test
    void directCreateRejectsMalformedSuppliedTags() {
        String tooLongKey = "k".repeat(129);
        String tooLongValue = "v".repeat(257);
        String tooManyTags = "[" + String.join(",", Collections.nCopies(201,
                "{\"key\":\"team\",\"value\":\"ml\"}")) + "]";
        for (String tags : List.of("{}", "[{}]", "[{\"key\":\"team\"}]",
                "[{\"key\":12,\"value\":\"ml\"}]", "[{\"key\":\"team\",\"value\":true}]",
                "[{\"key\":\"\",\"value\":\"ml\"}]", "[{\"key\":\"bad?key\",\"value\":\"ml\"}]",
                "[{\"key\":\"team\",\"value\":\"bad?value\"}]",
                "[{\"key\":\"" + tooLongKey + "\",\"value\":\"ml\"}]",
                "[{\"key\":\"team\",\"value\":\"" + tooLongValue + "\"}]", tooManyTags)) {
            String name = "malformed-tags-" + UUID.randomUUID();
            given().contentType("application/json")
                    .body("""
                            {"inferenceProfileName":"%s",
                             "modelSource":{"copyFrom":"%s"},"tags":%s}
                            """.formatted(name, MODEL_ARN, tags))
                    .when().post("/inference-profiles")
                    .then().statusCode(400).body("__type", equalTo("ValidationException"));
            List<String> names = given().when().get("/inference-profiles?type=APPLICATION&maxResults=1000")
                    .then().statusCode(200).extract().path("inferenceProfileSummaries.inferenceProfileName");
            assertFalse(names.contains(name), tags);
        }
    }

    @Test
    void directCreateReportsItemCapBeforeMalformedItems() {
        String tags = "[{\"key\":\"bad?key\",\"value\":\"ml\"},"
                + String.join(",", Collections.nCopies(200, "{\"key\":\"team\",\"value\":\"ml\"}")) + "]";
        given().contentType("application/json")
                .body("""
                        {"inferenceProfileName":"oversized-tags",
                         "modelSource":{"copyFrom":"%s"},"tags":%s}
                        """.formatted(MODEL_ARN, tags))
                .when().post("/inference-profiles")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("tags must have at most 200 items."));
    }

    @Test
    void tagResourceRequiresAndValidatesTagsWithoutChangingApplicationProfileTags() {
        String name = "tag-resource-validation-" + UUID.randomUUID();
        String arn = given().contentType("application/json").body("""
                        {"inferenceProfileName":"%s","modelSource":{"copyFrom":"%s"},
                         "tags":[{"key":"team","value":"original"}]}
                        """.formatted(name, MODEL_ARN))
                .when().post("/inference-profiles")
                .then().statusCode(201).extract().path("inferenceProfileArn");

        try (BedrockClient client = client("000000000000", REGION)) {
            try {
                given().contentType("application/json").body("""
                                {"resourceARN":"%s"}
                                """.formatted(arn))
                        .when().post("/tagResource")
                        .then().statusCode(400).body("__type", equalTo("ValidationException"));
                given().contentType("application/json").body("""
                                {"resourceARN":"%s","tags":[{"key":"bad?key","value":"changed"}]}
                                """.formatted(arn))
                        .when().post("/tagResource")
                        .then().statusCode(400).body("__type", equalTo("ValidationException"));
                assertEquals(List.of(Tag.builder().key("team").value("original").build()),
                        client.listTagsForResource(request -> request.resourceARN(arn)).tags());
            } finally {
                client.deleteInferenceProfile(request -> request.inferenceProfileIdentifier(arn));
            }
        }
    }

    @Test
    void directCreateAllowsWhitespaceKeyAndMaximumTagLengths() {
        String name = "boundary-tags-" + UUID.randomUUID();
        String maxKey = "k".repeat(128);
        String maxValue = "v".repeat(256);
        String body = """
                {"inferenceProfileName":"%s","modelSource":{"copyFrom":"%s"},
                 "tags":[{"key":" ","value":""},{"key":"%s","value":"%s"}]}
                """.formatted(name, MODEL_ARN, maxKey, maxValue);
        String arn = given().contentType("application/json").body(body)
                .when().post("/inference-profiles")
                .then().statusCode(201).extract().path("inferenceProfileArn");

        try (BedrockClient client = client("000000000000", REGION)) {
            try {
                List<Tag> tags = client.listTagsForResource(request -> request.resourceARN(arn)).tags();
                assertTrue(tags.contains(Tag.builder().key(" ").value("").build()));
                assertTrue(tags.contains(Tag.builder().key(maxKey).value(maxValue).build()));
            } finally {
                client.deleteInferenceProfile(request -> request.inferenceProfileIdentifier(arn));
            }
        }
    }

    @Test
    void directCreateStillAllowsOmittedTags() {
        String name = "untagged-profile-" + UUID.randomUUID();
        try (BedrockClient client = client(ACCOUNT, REGION)) {
            String arn = client.createInferenceProfile(request -> request.inferenceProfileName(name)
                    .modelSource(InferenceProfileModelSource.builder().copyFrom(MODEL_ARN).build()))
                    .inferenceProfileArn();
            try {
                assertTrue(client.listTagsForResource(request -> request.resourceARN(arn)).tags().isEmpty());
            } finally {
                client.deleteInferenceProfile(request -> request.inferenceProfileIdentifier(arn));
            }
        }
    }

    @Test
    void sdkCreateGetListTagAndDeleteUseTheApplicationProfileWireContract() {
        String name = "application-profile-" + UUID.randomUUID();
        String token = UUID.randomUUID().toString();
        try (BedrockClient client = client(ACCOUNT, REGION)) {
            CreateInferenceProfileResponse created = client.createInferenceProfile(request -> request
                    .inferenceProfileName(name).description("tracks cost")
                    .modelSource(InferenceProfileModelSource.builder().copyFrom(MODEL_ARN).build())
                    .clientRequestToken(token).tags(Tag.builder().key("team").value("ml").build()));
            String arn = created.inferenceProfileArn();
            String id = arn.substring(arn.lastIndexOf('/') + 1);
            try {
                assertEquals("arn:aws:bedrock:" + REGION + ":" + ACCOUNT
                        + ":application-inference-profile/" + id, arn);
                assertEquals(InferenceProfileStatus.ACTIVE, created.status());

                GetInferenceProfileResponse fetched = client.getInferenceProfile(request -> request
                        .inferenceProfileIdentifier(id));
                assertEquals(arn, fetched.inferenceProfileArn());
                assertEquals(name, fetched.inferenceProfileName());
                assertEquals("tracks cost", fetched.description());
                assertEquals(InferenceProfileType.APPLICATION, fetched.type());
                assertEquals(List.of(MODEL_ARN), fetched.models().stream().map(model -> model.modelArn()).toList());
                assertEquals(id, client.getInferenceProfile(request -> request
                        .inferenceProfileIdentifier(arn)).inferenceProfileId());

                List<InferenceProfileSummary> application = client.listInferenceProfiles(request -> request
                                .typeEquals(InferenceProfileType.APPLICATION)).inferenceProfileSummaries();
                assertTrue(application.stream().anyMatch(profile -> id.equals(profile.inferenceProfileId())
                        && arn.equals(profile.inferenceProfileArn())));
                assertTrue(client.listInferenceProfiles(request -> request
                                .typeEquals(InferenceProfileType.SYSTEM_DEFINED)).inferenceProfileSummaries().stream()
                        .noneMatch(profile -> id.equals(profile.inferenceProfileId())));

                CreateInferenceProfileResponse replay = client.createInferenceProfile(request -> request
                        .inferenceProfileName(name + " changed")
                        .modelSource(InferenceProfileModelSource.builder().copyFrom("invalid").build())
                        .clientRequestToken(token));
                assertEquals(arn, replay.inferenceProfileArn());

                client.tagResource(request -> request.resourceARN(arn)
                        .tags(Tag.builder().key("owner").value("platform").build()));
                assertEquals(2, client.listTagsForResource(request -> request.resourceARN(arn)).tags().size());
                client.untagResource(request -> request.resourceARN(arn).tagKeys("owner"));
                assertEquals(List.of(Tag.builder().key("team").value("ml").build()),
                        client.listTagsForResource(request -> request.resourceARN(arn)).tags());

                try (BedrockClient otherAccount = client("741623800009", REGION);
                     BedrockClient otherRegion = client(ACCOUNT, "us-west-2")) {
                    assertThrows(AccessDeniedException.class, () -> otherAccount.getInferenceProfile(
                            request -> request.inferenceProfileIdentifier(arn)));
                    assertThrows(ResourceNotFoundException.class, () -> otherRegion.getInferenceProfile(
                            request -> request.inferenceProfileIdentifier(arn)));
                }
            } finally {
                client.deleteInferenceProfile(request -> request.inferenceProfileIdentifier(arn));
            }
            assertThrows(ResourceNotFoundException.class, () -> client.getInferenceProfile(
                    request -> request.inferenceProfileIdentifier(id)));
        }
    }

    private BedrockClient client(String account, String region) {
        return BedrockClient.builder().endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .build();
    }
}
