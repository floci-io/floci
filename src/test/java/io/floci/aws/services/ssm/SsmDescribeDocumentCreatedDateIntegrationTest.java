package io.floci.aws.services.ssm;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.DocumentDescription;
import software.amazon.awssdk.services.ssm.model.DocumentType;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SsmDescribeDocumentCreatedDateIntegrationTest {

    private static final String CONTENT = "{\"schemaVersion\":\"2.2\",\"description\":\"d\",\"mainSteps\":[{"
            + "\"action\":\"aws:runShellScript\",\"name\":\"s\",\"inputs\":{\"runCommand\":[\"echo hi\"]}}]}";

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void theSdkReadsCreationDatesForLatestAndRetainedVersions(String region) {
        String name = "created-date-" + UUID.randomUUID();
        try (SsmClient ssm = AwsTestClients.create(SsmClient.builder(), "100000008138", Region.of(region))) {
            Instant before = Instant.now().minusSeconds(1);
            ssm.createDocument(r -> r.name(name).documentType(DocumentType.COMMAND).content(CONTENT));
            try {
                DocumentDescription created = ssm.describeDocument(r -> r.name(name)).document();
                assertEquals("1", created.documentVersion());
                assertCreationDate(created, before);

                ssm.updateDocument(r -> r.name(name).documentVersion("$LATEST")
                        .content(CONTENT.replace("echo hi", "echo updated")));
                DocumentDescription latest = ssm.describeDocument(r -> r.name(name).documentVersion("$LATEST"))
                        .document();
                DocumentDescription retained = ssm.describeDocument(r -> r.name(name).documentVersion("1"))
                        .document();
                assertEquals("2", latest.documentVersion());
                assertEquals("1", retained.documentVersion());
                assertCreationDate(latest, before);
                assertEquals(created.createdDate(), retained.createdDate());
            } finally {
                ssm.deleteDocument(r -> r.name(name));
            }
        }
    }

    private static void assertCreationDate(DocumentDescription document, Instant before) {
        Duration age = Duration.between(before, document.createdDate());
        assertTrue(!age.isNegative() && age.compareTo(Duration.ofMinutes(1)) < 0,
                document.createdDate().toString());
    }
}
