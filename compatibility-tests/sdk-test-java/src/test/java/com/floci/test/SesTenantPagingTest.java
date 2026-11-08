package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.CreateEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.CreateTenantRequest;
import software.amazon.awssdk.services.sesv2.model.CreateTenantResourceAssociationRequest;
import software.amazon.awssdk.services.sesv2.model.CreateTenantResponse;
import software.amazon.awssdk.services.sesv2.model.DeleteEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.DeleteTenantRequest;
import software.amazon.awssdk.services.sesv2.model.ListResourceTenantsRequest;
import software.amazon.awssdk.services.sesv2.model.ListResourceTenantsResponse;
import software.amazon.awssdk.services.sesv2.model.ListTenantResourcesRequest;
import software.amazon.awssdk.services.sesv2.model.ListTenantResourcesResponse;
import software.amazon.awssdk.services.sesv2.model.ListTenantsRequest;
import software.amazon.awssdk.services.sesv2.model.ListTenantsResponse;
import software.amazon.awssdk.services.sesv2.model.ResourceTenantMetadata;
import software.amazon.awssdk.services.sesv2.model.TenantInfo;
import software.amazon.awssdk.services.sesv2.model.TenantResource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Paging of the SES v2 tenant lists through the SDK paginators: every tenant, tenant resource and
 * resource tenant created here is listed exactly once across small pages. Tenants are reversible
 * (create + delete), so cleanup restores the account.
 */
@DisplayName("SES tenant list paging")
class SesTenantPagingTest {

    private static SesV2Client sesV2;
    private static List<String> tenants;
    private static List<String> identities;
    private static List<String> identityArns;

    @BeforeAll
    static void setup() {
        sesV2 = TestFixtures.sesV2Client();
        String prefix = "sdk-page-" + TestFixtures.uniqueName();
        tenants = List.of(prefix + "-b", prefix + "-c", prefix + "-a");
        identities = List.of(prefix + "-b.test", prefix + "-c.test", prefix + "-a.test");
        identityArns = new ArrayList<>();
        String arnPrefix = null;
        for (String tenant : tenants) {
            CreateTenantResponse created = sesV2.createTenant(CreateTenantRequest.builder().tenantName(tenant).build());
            arnPrefix = created.tenantArn().substring(0, created.tenantArn().indexOf(":tenant/") + 1);
        }
        for (String identity : identities) {
            sesV2.createEmailIdentity(CreateEmailIdentityRequest.builder().emailIdentity(identity).build());
            identityArns.add(arnPrefix + "identity/" + identity);
        }
        // Every identity is associated with the first tenant, and the first identity with every tenant.
        for (String arn : identityArns) {
            associate(tenants.get(0), arn);
        }
        for (String tenant : tenants.subList(1, tenants.size())) {
            associate(tenant, identityArns.get(0));
        }
    }

    @AfterAll
    static void cleanup() {
        if (sesV2 != null) {
            // DeleteTenant cascades the associations, so the identities can go afterwards.
            for (String tenant : tenants) {
                try {
                    sesV2.deleteTenant(DeleteTenantRequest.builder().tenantName(tenant).build());
                } catch (Exception ignored) {
                    // Best-effort cleanup; a leftover tenant does not affect other tests.
                }
            }
            for (String identity : identities) {
                try {
                    sesV2.deleteEmailIdentity(DeleteEmailIdentityRequest.builder().emailIdentity(identity).build());
                } catch (Exception ignored) {
                    // Best-effort cleanup; a leftover identity does not affect other tests.
                }
            }
            sesV2.close();
        }
    }

    @Test
    @DisplayName("ListTenants paginator walks small pages over every tenant once")
    void listTenantsPaginatorWalksEveryTenantOnce() {
        List<String> listed = new ArrayList<>();
        for (ListTenantsResponse page : sesV2.listTenantsPaginator(ListTenantsRequest.builder().pageSize(2).build())) {
            assertThat(page.tenants()).hasSizeLessThanOrEqualTo(2);
            page.tenants().stream().map(TenantInfo::tenantName).forEach(listed::add);
        }

        assertThat(listed).doesNotHaveDuplicates();
        assertThat(listed.stream().filter(tenants::contains)).containsExactlyInAnyOrderElementsOf(tenants);
    }

    @Test
    @DisplayName("ListTenantResources paginator walks small pages over every resource once")
    void listTenantResourcesPaginatorWalksEveryResourceOnce() {
        List<String> listed = new ArrayList<>();
        for (ListTenantResourcesResponse page : sesV2.listTenantResourcesPaginator(
                ListTenantResourcesRequest.builder().tenantName(tenants.get(0)).pageSize(2).build())) {
            assertThat(page.tenantResources()).hasSizeLessThanOrEqualTo(2);
            page.tenantResources().stream().map(TenantResource::resourceArn).forEach(listed::add);
        }

        assertThat(listed).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(identityArns);
    }

    @Test
    @DisplayName("ListResourceTenants paginator walks small pages over every tenant once")
    void listResourceTenantsPaginatorWalksEveryTenantOnce() {
        List<String> listed = new ArrayList<>();
        for (ListResourceTenantsResponse page : sesV2.listResourceTenantsPaginator(
                ListResourceTenantsRequest.builder().resourceArn(identityArns.get(0)).pageSize(2).build())) {
            assertThat(page.resourceTenants()).hasSizeLessThanOrEqualTo(2);
            page.resourceTenants().stream().map(ResourceTenantMetadata::tenantName).forEach(listed::add);
        }

        assertThat(listed).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(tenants);
    }

    private static void associate(String tenant, String arn) {
        sesV2.createTenantResourceAssociation(CreateTenantResourceAssociationRequest.builder()
                .tenantName(tenant).resourceArn(arn).build());
    }
}
