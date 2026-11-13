package io.floci.aws.services.ssm;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.DocumentDescription;
import software.amazon.awssdk.services.ssm.model.DocumentType;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SsmDescribeDocumentCreatedDateIntegrationTest {

    private static final String CONTENT = "{\"schemaVersion\":\"2.2\",\"description\":\"d\",\"mainSteps\":[{"
            + "\"action\":\"aws:runShellScript\",\"name\":\"s\",\"inputs\":{\"runCommand\":[\"echo hi\"]}}]}";

    @Test
    void theSdkReadsTheCreationDateOfADescribedDocument() {
        String name = "created-date-" + UUID.randomUUID();
        try (SsmClient ssm = AwsTestClients.create(SsmClient.builder(), "100000008138", Region.US_EAST_1)) {
            Instant before = Instant.now().minusSeconds(1);
            ssm.createDocument(r -> r.name(name).documentType(DocumentType.COMMAND).content(CONTENT));
            try {
                DocumentDescription document = ssm.describeDocument(r -> r.name(name)).document();

                Duration age = Duration.between(before, document.createdDate());
                assertTrue(!age.isNegative() && age.compareTo(Duration.ofMinutes(1)) < 0,
                        document.createdDate().toString());
            } finally {
                ssm.deleteDocument(r -> r.name(name));
            }
        }
    }
}
