package io.floci.aws.services.ssm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.ValidateSignaturesProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.DocumentDescription;
import software.amazon.awssdk.services.ssm.model.DocumentIdentifier;
import software.amazon.awssdk.services.ssm.model.DocumentKeyValuesFilter;
import software.amazon.awssdk.services.ssm.model.GetDocumentResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(ValidateSignaturesProfile.class)
class SsmSessionDocumentIntegrationTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private SsmClient ssm;

    @BeforeEach
    void connect() {
        ssm = AwsTestClients.create(SsmClient.builder(), "test", Region.US_EAST_1);
    }

    @AfterEach
    void disconnect() {
        ssm.close();
    }

    @ParameterizedTest
    @CsvSource({
            "AWS-StartSSHSession, 22",
            "AWS-StartPortForwardingSession, 80",
            "AWS-StartPortForwardingSessionToRemoteHost, 80"
    })
    void builtinSessionDocumentsAreReadableWithAwsMetadata(String name, String defaultPort) throws Exception {
        DocumentDescription description = ssm.describeDocument(r -> r.name(name)).document();
        assertEquals(name, description.name());
        assertEquals("Amazon", description.owner());
        assertEquals("Session", description.documentTypeAsString());
        assertEquals("1.0", description.schemaVersion());
        assertEquals("1", description.defaultVersion());

        GetDocumentResponse result = ssm.getDocument(r -> r.name(name).documentVersion(description.defaultVersion()));
        JsonNode content = objectMapper.readTree(result.content());
        assertEquals("Port", content.path("sessionType").asText());
        assertEquals(defaultPort, content.at("/parameters/portNumber/default").asText());
    }

    @Test
    void listsAllBuiltinSessionDocumentsWithAwsMetadata() {
        List<DocumentIdentifier> documents = ssm.listDocuments(r -> r.filters(
                DocumentKeyValuesFilter.builder().key("Owner").values("Amazon").build(),
                DocumentKeyValuesFilter.builder().key("DocumentType").values("Session").build(),
                DocumentKeyValuesFilter.builder().key("PlatformTypes").values("Linux").build()))
                .documentIdentifiers();

        assertEquals(List.of("AWS-StartPortForwardingSession", "AWS-StartPortForwardingSessionToRemoteHost",
                        "AWS-StartSSHSession"),
                documents.stream().map(DocumentIdentifier::name).sorted().toList());
        for (DocumentIdentifier document : documents) {
            assertEquals("Amazon", document.owner());
            assertEquals("Session", document.documentTypeAsString());
        }
    }

    @Test
    void filtersBuiltinDocumentsByNamePrefix() {
        List<DocumentIdentifier> documents = ssm.listDocuments(r -> r.filters(
                f -> f.key("Name").values("AWS-StartPortForwarding"))).documentIdentifiers();

        assertEquals(List.of("AWS-StartPortForwardingSession", "AWS-StartPortForwardingSessionToRemoteHost"),
                documents.stream().map(DocumentIdentifier::name).sorted().toList());
    }

    @ParameterizedTest
    @CsvSource({"Owner, Self", "Owner, Private", "Owner, 000000000000", "DocumentType, Command", "PlatformTypes, AIX"})
    void excludesBuiltinDocumentsWhenAFilterDoesNotMatch(String key, String value) {
        List<DocumentIdentifier> documents = ssm.listDocuments(r -> r.filters(
                DocumentKeyValuesFilter.builder().key("Name").values("AWS-Start").build(),
                DocumentKeyValuesFilter.builder().key(key).values(value).build())).documentIdentifiers();

        assertTrue(documents.isEmpty());
    }
}
