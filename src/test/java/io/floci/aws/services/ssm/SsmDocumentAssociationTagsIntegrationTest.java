package io.floci.aws.services.ssm;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.DocumentIdentifier;
import software.amazon.awssdk.services.ssm.model.DocumentType;
import software.amazon.awssdk.services.ssm.model.InvalidResourceIdException;
import software.amazon.awssdk.services.ssm.model.ResourceTypeForTagging;
import software.amazon.awssdk.services.ssm.model.Tag;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class SsmDocumentAssociationTagsIntegrationTest {

    private static final String ACCOUNT = "100000007959";
    private static final String CONTENT = "{\"schemaVersion\":\"2.2\",\"description\":\"d\",\"mainSteps\":[{"
            + "\"action\":\"aws:runShellScript\",\"name\":\"s\",\"inputs\":{\"runCommand\":[\"echo hi\"]}}]}";

    private SsmClient ssm;
    private String documentName;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void createClient() {
        ssm = AwsTestClients.create(SsmClient.builder(), ACCOUNT, Region.US_EAST_1);
        documentName = "tag-doc-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void closeClient() {
        ssm.close();
    }

    @Test
    void tagsGivenToCreateDocumentAreListedAndReturnedWithTheDocument() {
        List<Tag> created = ssm.createDocument(r -> r.name(documentName).documentType(DocumentType.COMMAND)
                .content(CONTENT).tags(tag("team", "red"))).documentDescription().tags();

        assertEquals(List.of(tag("team", "red")), created);
        assertEquals(List.of(tag("team", "red")), listTags(ResourceTypeForTagging.DOCUMENT, documentName));
        given()
                .contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AmazonSSM.DescribeDocument")
                .header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260101/us-east-1/ssm/aws4_request")
                .body("{\"Name\":\"" + documentName + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Document.Tags[0].Key", equalTo("team"))
                .body("Document.Tags[0].Value", equalTo("red"));
        DocumentIdentifier listed = ssm.listDocuments(r -> r.filters(f -> f.key("Name").values(documentName)))
                .documentIdentifiers().get(0);
        assertEquals(List.of(tag("team", "red")), listed.tags());
    }

    @Test
    void aDocumentIsTaggedAndUntaggedByName() {
        createDocument();

        ssm.addTagsToResource(r -> r.resourceType(ResourceTypeForTagging.DOCUMENT).resourceId(documentName)
                .tags(tag("env", "dev"), tag("team", "blue")));
        ssm.removeTagsFromResource(r -> r.resourceType(ResourceTypeForTagging.DOCUMENT).resourceId(documentName)
                .tagKeys("env"));

        assertEquals(List.of(tag("team", "blue")), listTags(ResourceTypeForTagging.DOCUMENT, documentName));
    }

    @Test
    void tagsGivenToCreateAssociationAreListedAndTheAssociationCanBeRetagged() {
        createDocument();
        String associationId = ssm.createAssociation(r -> r.name(documentName)
                        .targets(t -> t.key("InstanceIds").values("i-0123456789abcdef0"))
                        .tags(tag("team", "red")))
                .associationDescription().associationId();

        assertEquals(List.of(tag("team", "red")), listTags(ResourceTypeForTagging.ASSOCIATION, associationId));

        ssm.addTagsToResource(r -> r.resourceType(ResourceTypeForTagging.ASSOCIATION).resourceId(associationId)
                .tags(tag("team", "blue"), tag("env", "dev")));
        ssm.removeTagsFromResource(r -> r.resourceType(ResourceTypeForTagging.ASSOCIATION)
                .resourceId(associationId).tagKeys("env"));

        assertEquals(List.of(tag("team", "blue")), listTags(ResourceTypeForTagging.ASSOCIATION, associationId));
    }

    @Test
    void aMissingDocumentOrAssociationAnswersInvalidResourceId() {
        assertThrows(InvalidResourceIdException.class,
                () -> listTags(ResourceTypeForTagging.DOCUMENT, documentName + "-missing"));
        assertThrows(InvalidResourceIdException.class,
                () -> listTags(ResourceTypeForTagging.ASSOCIATION, UUID.randomUUID().toString()));
    }

    @Test
    void aParameterIsStillTaggedByName() {
        String parameter = "/tags/" + documentName;
        ssm.putParameter(r -> r.name(parameter).value("v").type("String").tags(tag("team", "red")));

        assertEquals(List.of(tag("team", "red")), listTags(ResourceTypeForTagging.PARAMETER, parameter));
    }

    private void createDocument() {
        ssm.createDocument(r -> r.name(documentName).documentType(DocumentType.COMMAND).content(CONTENT));
    }

    private List<Tag> listTags(ResourceTypeForTagging type, String id) {
        return ssm.listTagsForResource(r -> r.resourceType(type).resourceId(id)).tagList();
    }

    private static Tag tag(String key, String value) {
        return Tag.builder().key(key).value(value).build();
    }
}
