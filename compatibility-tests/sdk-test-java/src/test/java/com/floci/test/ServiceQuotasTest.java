package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.servicequotas.ServiceQuotasClient;
import software.amazon.awssdk.services.servicequotas.model.InvalidPaginationTokenException;
import software.amazon.awssdk.services.servicequotas.model.ServiceInfo;
import software.amazon.awssdk.services.servicequotas.model.ServiceQuota;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class ServiceQuotasTest {

    private static final String STANDARD_CODE = "L-1216C47A";

    @Test
    void standardEc2DefaultQuotaUsesCanonicalMetadata() {
        assumeFalse(TestFixtures.isRealAws(), "Tests the emulated static quota catalog");
        try (ServiceQuotasClient client = TestFixtures.serviceQuotasClient()) {
            ServiceQuota quota = client.getAWSDefaultServiceQuota(request -> request
                    .serviceCode("ec2").quotaCode(STANDARD_CODE)).quota();
            assertStandardMetadata(quota);
            assertThat(quota.value()).isEqualTo(5.0);
            List<List<ServiceQuota>> pages = client.listAWSDefaultServiceQuotasPaginator(request -> request
                    .serviceCode("ec2").maxResults(1)).stream().map(page -> page.quotas()).toList();
            assertCatalogPages(pages, quota);
        }
    }

    @Test
    @DisplayName("Applied EC2 quota reads and pagination use the configured value")
    void emulatedAppliedQuotaReportsConfiguredValue() {
        assumeFalse(TestFixtures.isRealAws(), "Real applied quotas can differ from catalog defaults");
        try (ServiceQuotasClient client = TestFixtures.serviceQuotasClient()) {
            ServiceQuota quota = client.getServiceQuota(request -> request
                    .serviceCode("ec2").quotaCode(STANDARD_CODE)).quota();
            assertStandardMetadata(quota);
            assertThat(quota.value()).isNotNull().isGreaterThanOrEqualTo(0.0);
            String expectedValue = System.getenv().getOrDefault(
                    "FLOCI_TEST_EXPECTED_STANDARD_ON_DEMAND_VCPUS", "5");
            assertThat(quota.value()).isEqualTo(Double.parseDouble(expectedValue));
            List<List<ServiceQuota>> pages = client.listServiceQuotasPaginator(request -> request
                    .serviceCode("ec2").maxResults(1)).stream().map(page -> page.quotas()).toList();
            assertCatalogPages(pages, quota);
        }
    }

    private static void assertCatalogPages(List<List<ServiceQuota>> pages, ServiceQuota standardQuota) {
        assertThat(pages).hasSize(4).allSatisfy(page -> assertThat(page).hasSize(1));
        List<ServiceQuota> quotas = pages.stream().flatMap(List::stream).toList();
        assertThat(quotas).extracting(ServiceQuota::quotaCode).doesNotHaveDuplicates();
        assertThat(quotas.stream().filter(item -> STANDARD_CODE.equals(item.quotaCode())).toList())
                .containsExactly(standardQuota);
    }

    private static void assertStandardMetadata(ServiceQuota quota) {
        assertThat(quota).isNotNull();
        assertThat(quota.serviceCode()).isEqualTo("ec2");
        assertThat(quota.quotaCode()).isEqualTo(STANDARD_CODE);
        assertThat(quota.quotaName())
                .isEqualTo("Running On-Demand Standard (A, C, D, H, I, M, R, T, Z) instances");
        assertThat(quota.adjustable()).isTrue();
        assertThat(quota.globalQuota()).isFalse();
    }

    @Test
    @DisplayName("ListServices rejects empty NextToken")
    void listServicesRejectsEmptyNextToken() {
        assumeFalse(TestFixtures.isRealAws(), "Tests the emulated service catalog");
        try (ServiceQuotasClient client = TestFixtures.serviceQuotasClient()) {
            assertThatThrownBy(
                    () -> client.listServices(request -> request.nextToken("")))
                    .isInstanceOf(InvalidPaginationTokenException.class);
        }
    }

    @Test
    @DisplayName("ListServices rejects fabricated NextToken")
    void listServicesRejectsFabricatedNextToken() {
        assumeFalse(TestFixtures.isRealAws(), "Tests the emulated service catalog");
        try (ServiceQuotasClient client = TestFixtures.serviceQuotasClient()) {
            assertThatThrownBy(
                    () -> client.listServices(request -> request.nextToken("!!!")))
                    .isInstanceOf(InvalidPaginationTokenException.class);
        }
    }

    @Test
    @DisplayName("ListServices returns services with code and name, supports pagination")
    void listServicesReturnsServicesAndPaginates() {
        assumeFalse(TestFixtures.isRealAws(), "Tests the emulated service catalog");
        try (ServiceQuotasClient client = TestFixtures.serviceQuotasClient()) {
            // List all services
            List<ServiceInfo> allServices = client.listServicesPaginator(request -> request.maxResults(100))
                    .stream().flatMap(page -> page.services().stream()).toList();
            assertThat(allServices).hasSizeGreaterThanOrEqualTo(10);
            assertThat(allServices).anyMatch(s -> s.serviceCode().equals("ec2") && s.serviceName().equals("Amazon Elastic Compute Cloud (Amazon EC2)"));
            assertThat(allServices).anyMatch(s -> s.serviceCode().equals("lambda") && s.serviceName().equals("AWS Lambda"));
            assertThat(allServices).anyMatch(s -> s.serviceCode().equals("s3") && s.serviceName().equals("Amazon Simple Storage Service (Amazon S3)"));

            // Pagination
            List<ServiceInfo> firstPage = client.listServices(request -> request.maxResults(2)).services();
            assertThat(firstPage).hasSize(2);
            String nextToken = client.listServices(request -> request.maxResults(2)).nextToken();
            assertThat(nextToken).isNotNull();
            List<ServiceInfo> secondPage = client.listServices(request -> request.nextToken(nextToken)).services();
            assertThat(secondPage.size()).isGreaterThanOrEqualTo(1);

            // Services sorted by ServiceCode (case-insensitive)
            List<String> codes = allServices.stream().map(ServiceInfo::serviceCode).toList();
            List<String> sorted = new ArrayList<>(codes);
            sorted.sort(String::compareToIgnoreCase);
            assertThat(codes).isEqualTo(sorted);
        }
    }
}
