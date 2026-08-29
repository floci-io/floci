package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.AlreadyExistsException;
import software.amazon.awssdk.services.sesv2.model.BadRequestException;
import software.amazon.awssdk.services.sesv2.model.CreateTenantRequest;
import software.amazon.awssdk.services.sesv2.model.CreateTenantResponse;
import software.amazon.awssdk.services.sesv2.model.DeleteTenantRequest;
import software.amazon.awssdk.services.sesv2.model.GetTenantRequest;
import software.amazon.awssdk.services.sesv2.model.GetTenantResponse;
import software.amazon.awssdk.services.sesv2.model.ListTenantsRequest;
import software.amazon.awssdk.services.sesv2.model.ListTenantsResponse;
import software.amazon.awssdk.services.sesv2.model.NotFoundException;
import software.amazon.awssdk.services.sesv2.model.Tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SDK compatibility test for the SES v2 tenant APIs. Verifies the AWS Java SDK v2 marshalling of
 * CreateTenant / GetTenant / ListTenants / DeleteTenant — the RPC-style POST routes, the generated
 * TenantId (tn-) and TenantArn, the ENABLED sending status, and the
 * AlreadyExistsException / NotFoundException / BadRequestException errors — against a live Floci
 * instance. Tenants are reversible (create + delete), so cleanup restores the account.
 */
@DisplayName("SES v2 Tenants")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesTenantTest {

    private static final String TENANT = "compat-tenant-alpha";

    private static SesV2Client sesV2;

    @BeforeAll
    static void setup() {
        sesV2 = TestFixtures.sesV2Client();
    }

    @AfterAll
    static void cleanup() {
        if (sesV2 != null) {
            try {
                sesV2.deleteTenant(DeleteTenantRequest.builder().tenantName(TENANT).build());
            } catch (NotFoundException expected) {
                // Already removed by the delete test; anything else (auth, connectivity) must surface.
            } finally {
                sesV2.close();
            }
        }
    }

    @Test
    @Order(1)
    void createTenant_returnsGeneratedIdArnAndEnabledStatus() {
        CreateTenantResponse resp = sesV2.createTenant(CreateTenantRequest.builder()
                .tenantName(TENANT)
                .tags(Tag.builder().key("team").value("floci").build())
                .build());
        assertThat(resp.tenantName()).isEqualTo(TENANT);
        assertThat(resp.tenantId()).startsWith("tn-");
        assertThat(resp.tenantArn()).contains(":tenant/" + TENANT + "/");
        assertThat(resp.sendingStatusAsString()).isEqualTo("ENABLED");
        assertThat(resp.createdTimestamp()).isNotNull();
        assertThat(resp.tags()).extracting(Tag::key).contains("team");
    }

    @Test
    @Order(2)
    void getTenant_returnsTenant() {
        GetTenantResponse resp = sesV2.getTenant(GetTenantRequest.builder().tenantName(TENANT).build());
        assertThat(resp.tenant()).isNotNull();
        assertThat(resp.tenant().tenantName()).isEqualTo(TENANT);
        assertThat(resp.tenant().tenantId()).startsWith("tn-");
        assertThat(resp.tenant().sendingStatusAsString()).isEqualTo("ENABLED");
    }

    @Test
    @Order(3)
    void listTenants_includesCreatedTenant() {
        ListTenantsResponse resp = sesV2.listTenants(ListTenantsRequest.builder().build());
        assertThat(resp.tenants()).anyMatch(t -> TENANT.equals(t.tenantName()));
    }

    @Test
    @Order(4)
    void createTenant_duplicate_throwsAlreadyExists() {
        assertThatThrownBy(() -> sesV2.createTenant(CreateTenantRequest.builder()
                        .tenantName(TENANT).build()))
                .isInstanceOf(AlreadyExistsException.class);
    }

    @Test
    @Order(5)
    void createTenant_invalidName_throwsBadRequest() {
        assertThatThrownBy(() -> sesV2.createTenant(CreateTenantRequest.builder()
                        .tenantName("bad name!").build()))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @Order(6)
    void deleteTenant_removesIt_thenGetIsNotFound() {
        sesV2.deleteTenant(DeleteTenantRequest.builder().tenantName(TENANT).build());
        assertThatThrownBy(() -> sesV2.getTenant(GetTenantRequest.builder().tenantName(TENANT).build()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @Order(7)
    void deleteTenant_missing_throwsNotFound() {
        assertThatThrownBy(() -> sesV2.deleteTenant(DeleteTenantRequest.builder()
                        .tenantName("compat-tenant-does-not-exist").build()))
                .isInstanceOf(NotFoundException.class);
    }
}
