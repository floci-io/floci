package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.securityhub.SecurityHubClient;
import software.amazon.awssdk.services.securityhub.model.AwsSecurityFinding;
import software.amazon.awssdk.services.securityhub.model.BatchImportFindingsResponse;
import software.amazon.awssdk.services.securityhub.model.GetFindingsResponse;
import software.amazon.awssdk.services.securityhub.model.StringFilter;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("Security Hub findings via AWS SDK")
class SecurityHubFindingsCompatibilityTest {
    private static final String READ_ACCOUNT = "123456789128";
    private static final String IMPORT_ACCOUNT = "123456789129";

    @Test
    void getFindingsReturnsEmptyListForEnabledHub() {
        assumeFalse(TestFixtures.isRealAws(), "Uses emulator-only accounts");

        try (SecurityHubClient client = TestFixtures.securityHubClient(READ_ACCOUNT)) {
            client.enableSecurityHub(request -> request.enableDefaultStandards(false));

            assertThat(client.getFindings(request -> request.maxResults(5)).findings()).isEmpty();
        }
    }

    @Test
    void batchImportFindingsCanBeReadBack() {
        assumeFalse(TestFixtures.isRealAws(), "Uses emulator-only accounts");

        String findingId = TestFixtures.uniqueName("securityhub-finding");
        String productArn = TestFixtures.arn("securityhub", IMPORT_ACCOUNT,
                "product/" + IMPORT_ACCOUNT + "/default");
        String resourceArn = TestFixtures.globalArn("iam", IMPORT_ACCOUNT, "role/securityhub-test");
        String timestamp = Instant.now().toString();
        AwsSecurityFinding finding = AwsSecurityFinding.builder()
                .schemaVersion("2018-10-08")
                .id(findingId)
                .productArn(productArn)
                .generatorId("securityhub-findings-compatibility")
                .awsAccountId(IMPORT_ACCOUNT)
                .types("Software and Configuration Checks/Industry and Regulatory Standards")
                .createdAt(timestamp)
                .updatedAt(timestamp)
                .severity(severity -> severity.label("MEDIUM"))
                .title("Test\ncontrol finding")
                .description("Imported by\nthe Security Hub compatibility test")
                .resources(resource -> resource.type("AwsIamRole").id(resourceArn)
                        .partition(TestFixtures.partition()).region(TestFixtures.region().id()))
                .compliance(compliance -> compliance.status("FAILED").securityControlId("IAM.1"))
                .recordState("ACTIVE")
                .build();

        try (SecurityHubClient client = TestFixtures.securityHubClient(IMPORT_ACCOUNT)) {
            client.enableSecurityHub(request -> request.enableDefaultStandards(false));

            BatchImportFindingsResponse imported = client.batchImportFindings(request -> request.findings(finding));
            assertThat(imported.successCount()).isEqualTo(1);
            assertThat(imported.failedCount()).isZero();
            assertThat(imported.failedFindings()).isEmpty();

            assertThat(client.getFindings(request -> {}).findings())
                    .filteredOn(candidate -> findingId.equals(candidate.id()))
                    .singleElement()
                    .satisfies(candidate -> {
                        assertThat(candidate.productArn()).isEqualTo(productArn);
                        assertThat(candidate.awsAccountId()).isEqualTo(IMPORT_ACCOUNT);
                        assertThat(candidate.title()).isEqualTo(finding.title());
                        assertThat(candidate.description()).isEqualTo(finding.description());
                        assertThat(candidate.compliance().statusAsString()).isEqualTo("FAILED");
                        assertThat(candidate.severity().labelAsString()).isEqualTo("MEDIUM");
                        assertThat(candidate.findingProviderFields().severity().labelAsString()).isEqualTo("MEDIUM");
                        assertThat(candidate.findingProviderFields().types()).containsExactlyElementsOf(finding.types());
                        assertThat(candidate.recordStateAsString()).isEqualTo("ACTIVE");
                        assertThat(candidate.resources()).singleElement()
                                .satisfies(resource -> assertThat(resource.id()).isEqualTo(resourceArn));
                    });
        }
    }

    @Test
    void mixedImportDefaultsFiltersAndContinuationTokens() {
        assumeFalse(TestFixtures.isRealAws(), "Uses emulator-only accounts");

        String account = "123456789130";
        String idPrefix = TestFixtures.uniqueName("securityhub-page");
        String productArn = TestFixtures.arn("securityhub", account, "product/" + account + "/default");
        String timestamp = Instant.now().toString();
        AwsSecurityFinding first = AwsSecurityFinding.builder()
                .schemaVersion("2018-10-08")
                .id(idPrefix + "-one")
                .productArn(productArn)
                .generatorId("securityhub-findings-compatibility")
                .awsAccountId(account)
                .createdAt(timestamp)
                .updatedAt(timestamp)
                .title("First finding")
                .description("Sparse finding imported by the SDK")
                .resources(resource -> resource.type("AwsIamRole").id("role/first"))
                .compliance(compliance -> compliance.securityControlId("IAM.1"))
                .build();
        AwsSecurityFinding second = first.toBuilder().id(idPrefix + "-two")
                .title("Second finding")
                .compliance(compliance -> compliance.securityControlId("IAM.2"))
                .build();
        AwsSecurityFinding third = first.toBuilder().id(idPrefix + "-three")
                .title("Third finding")
                .compliance(compliance -> compliance.securityControlId("EC2.1"))
                .build();
        AwsSecurityFinding invalid = first.toBuilder().id(idPrefix + "-invalid").title("").build();

        try (SecurityHubClient client = TestFixtures.securityHubClient(account)) {
            client.enableSecurityHub(request -> request.enableDefaultStandards(false));

            BatchImportFindingsResponse imported = client.batchImportFindings(
                    request -> request.findings(first, invalid, second, third));
            assertThat(imported.successCount()).isEqualTo(3);
            assertThat(imported.failedCount()).isEqualTo(1);
            assertThat(imported.failedFindings()).singleElement().satisfies(failure -> {
                assertThat(failure.id()).isEqualTo(idPrefix + "-invalid");
                assertThat(failure.errorCode()).isEqualTo("InvalidInput");
            });

            Set<String> ids = new HashSet<>();
            String nextToken = null;
            for (int pageNumber = 0; pageNumber < 3; pageNumber++) {
                String token = nextToken;
                GetFindingsResponse page = client.getFindings(request -> {
                    request.maxResults(1).filters(filters -> filters.productArn(
                            stringFilter(productArn, "EQUALS")));
                    if (token != null) {
                        request.nextToken(token);
                    }
                });
                assertThat(page.findings()).singleElement().satisfies(candidate -> {
                    ids.add(candidate.id());
                    assertThat(candidate.productName()).isEqualTo("Default");
                    assertThat(candidate.companyName()).isEqualTo("Personal");
                    assertThat(candidate.recordStateAsString()).isEqualTo("ACTIVE");
                    assertThat(candidate.workflow().statusAsString()).isEqualTo("NEW");
                    assertThat(candidate.createdAt()).isEqualTo(timestamp);
                    assertThat(candidate.resources()).hasSize(1);
                });
                nextToken = page.nextToken();
                if (pageNumber < 2) {
                    assertThat(nextToken).isNotBlank();
                }
            }
            assertThat(nextToken).isNull();
            assertThat(ids).containsExactlyInAnyOrder(first.id(), second.id(), third.id());

            assertThat(client.getFindings(request -> request.filters(filters -> filters
                    .complianceSecurityControlId(stringFilter("IAM.1", "EQUALS"),
                            stringFilter("IAM.2", "EQUALS")))).findings())
                    .extracting(AwsSecurityFinding::id)
                    .containsExactlyInAnyOrder(first.id(), second.id());
            assertThat(client.getFindings(request -> request.filters(filters -> filters
                    .complianceSecurityControlId(stringFilter("IAM.1", "EQUALS"),
                            stringFilter("EC2", "PREFIX")))).findings())
                    .extracting(AwsSecurityFinding::id)
                    .containsExactlyInAnyOrder(first.id(), third.id());
            assertThat(client.getFindings(request -> request.filters(filters -> filters
                    .complianceSecurityControlId(stringFilter("IAM.1", "NOT_EQUALS"),
                            stringFilter("IAM.2", "NOT_EQUALS")))).findings())
                    .extracting(AwsSecurityFinding::id)
                    .containsExactly(third.id());
            assertThat(client.getFindings(request -> request.filters(filters -> filters
                    .complianceSecurityControlId(stringFilter("IAM", "PREFIX"),
                            stringFilter("IAM.1", "NOT_EQUALS")))).findings())
                    .extracting(AwsSecurityFinding::id)
                    .containsExactly(second.id());
            assertThatThrownBy(() -> client.getFindings(request -> request.filters(filters -> filters
                    .complianceSecurityControlId(stringFilter("IAM.1", "EQUALS"),
                            stringFilter("IAM.2", "NOT_EQUALS")))))
                    .hasMessageContaining("cannot combine EQUALS with NOT_EQUALS");
        }
    }

    private static StringFilter stringFilter(String value, String comparison) {
        return StringFilter.builder().value(value).comparison(comparison).build();
    }
}
