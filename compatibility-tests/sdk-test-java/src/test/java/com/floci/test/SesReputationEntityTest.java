package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.BadRequestException;
import software.amazon.awssdk.services.sesv2.model.ListReputationEntitiesResponse;
import software.amazon.awssdk.services.sesv2.model.NotFoundException;
import software.amazon.awssdk.services.sesv2.model.RecommendationImpact;
import software.amazon.awssdk.services.sesv2.model.ReputationEntity;
import software.amazon.awssdk.services.sesv2.model.ReputationEntityFilterKey;
import software.amazon.awssdk.services.sesv2.model.ReputationEntityType;
import software.amazon.awssdk.services.sesv2.model.SendingStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SDK compatibility test for the SES v2 reputation entity operations. Every tenant is an entity
 * referenced by its full ARN, which the SDK sends percent-encoded in the request path. SES reports
 * the impact of an entity without findings as {@code NONE}, a value the SDK enum does not know, so
 * the test reads it as a string.
 */
@DisplayName("SES v2 Reputation Entities")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesReputationEntityTest {

    private static final String TENANT = "compat-reputation-a";
    private static final String OTHER_TENANT = "compat-reputation-b";

    private static SesV2Client sesV2;
    private static String tenantArn;
    private static String otherTenantArn;

    @BeforeAll
    static void setup() {
        sesV2 = TestFixtures.sesV2Client();
        tenantArn = sesV2.createTenant(r -> r.tenantName(TENANT)).tenantArn();
        otherTenantArn = sesV2.createTenant(r -> r.tenantName(OTHER_TENANT)).tenantArn();
    }

    @AfterAll
    static void cleanup() {
        if (sesV2 == null) {
            return;
        }
        for (String name : List.of(TENANT, OTHER_TENANT)) {
            try {
                sesV2.deleteTenant(r -> r.tenantName(name));
            } catch (NotFoundException expected) {
                // Already removed by the delete test; anything else (auth, connectivity) must surface.
            }
        }
    }

    @Test
    @Order(1)
    @DisplayName("ListReputationEntities includes the tenant with its defaults")
    void listIncludesTenant() {
        ListReputationEntitiesResponse response = sesV2.listReputationEntities(r -> r
                .filter(Map.of(ReputationEntityFilterKey.ENTITY_REFERENCE_PREFIX, tenantArn)));

        assertThat(response.reputationEntities()).hasSize(1);
        ReputationEntity entity = response.reputationEntities().get(0);
        assertThat(entity.reputationEntityReference()).isEqualTo(tenantArn);
        assertThat(entity.reputationEntityType()).isEqualTo(ReputationEntityType.RESOURCE);
        assertThat(entity.reputationImpact()).isEqualTo(RecommendationImpact.UNKNOWN_TO_SDK_VERSION);
        assertThat(entity.reputationImpactAsString()).isEqualTo("NONE");
        assertThat(entity.reputationManagementPolicy()).endsWith(":aws:reputation-policy/standard");
        assertThat(entity.customerManagedStatus()).isNull();
        assertThat(entity.sendingStatusAggregate()).isEqualTo(SendingStatus.ENABLED);
        assertThat(response.nextToken()).isNull();
    }

    @Test
    @Order(2)
    @DisplayName("The paginator walks the entities a page at a time")
    void paginatesEntities() {
        String prefix = tenantArn.substring(0, tenantArn.indexOf(TENANT));

        List<String> references = sesV2.listReputationEntitiesPaginator(r -> r
                        .filter(Map.of(ReputationEntityFilterKey.ENTITY_REFERENCE_PREFIX, prefix + "compat-reputation-"))
                        .pageSize(1))
                .reputationEntities().stream()
                .map(ReputationEntity::reputationEntityReference)
                .toList();

        assertThat(references).containsExactly(tenantArn, otherTenantArn);
    }

    @Test
    @Order(3)
    @DisplayName("UpdateReputationEntityPolicy changes the policy GetReputationEntity reports")
    void updatePolicy() {
        String strict = sesV2.getReputationEntity(r -> r.reputationEntityType(ReputationEntityType.RESOURCE)
                        .reputationEntityReference(tenantArn))
                .reputationEntity().reputationManagementPolicy().replace("/standard", "/strict");

        sesV2.updateReputationEntityPolicy(r -> r.reputationEntityType(ReputationEntityType.RESOURCE)
                .reputationEntityReference(tenantArn).reputationEntityPolicy(strict));

        assertThat(sesV2.getReputationEntity(r -> r.reputationEntityType(ReputationEntityType.RESOURCE)
                        .reputationEntityReference(tenantArn))
                .reputationEntity().reputationManagementPolicy()).isEqualTo(strict);
    }

    @Test
    @Order(4)
    @DisplayName("A DISABLED customer-managed status becomes the aggregate and the tenant's status")
    void disableSending() {
        sesV2.updateReputationEntityCustomerManagedStatus(r -> r
                .reputationEntityType(ReputationEntityType.RESOURCE)
                .reputationEntityReference(tenantArn)
                .sendingStatus(SendingStatus.DISABLED));

        ReputationEntity entity = sesV2.getReputationEntity(r -> r
                .reputationEntityType(ReputationEntityType.RESOURCE)
                .reputationEntityReference(tenantArn)).reputationEntity();
        assertThat(entity.customerManagedStatus().status()).isEqualTo(SendingStatus.DISABLED);
        assertThat(entity.customerManagedStatus().cause()).isEqualTo("Status manually updated.");
        assertThat(entity.customerManagedStatus().lastUpdatedTimestamp()).isNotNull();
        assertThat(entity.awsSesManagedStatus()).isNull();
        assertThat(entity.sendingStatusAggregate()).isEqualTo(SendingStatus.DISABLED);
        assertThat(sesV2.getTenant(r -> r.tenantName(TENANT)).tenant().sendingStatus())
                .isEqualTo(SendingStatus.DISABLED);
        assertThat(sesV2.listReputationEntities(r -> r
                        .filter(Map.of(ReputationEntityFilterKey.SENDING_STATUS, SendingStatus.DISABLED.toString())))
                .reputationEntities())
                .extracting(ReputationEntity::reputationEntityReference)
                .contains(tenantArn)
                .doesNotContain(otherTenantArn);
    }

    @Test
    @Order(5)
    @DisplayName("An ARN that names no tenant is a BadRequestException")
    void unknownEntity() {
        String ghost = tenantArn.substring(0, tenantArn.lastIndexOf('/') + 1) + "tn-000000000000000000000000000000";

        assertThatThrownBy(() -> sesV2.getReputationEntity(r -> r
                .reputationEntityType(ReputationEntityType.RESOURCE)
                .reputationEntityReference(ghost)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("ReputationEntity <" + ghost + "> does not exist");
    }

    @Test
    @Order(6)
    @DisplayName("Deleting the tenant removes the entity")
    void deleteTenantRemovesEntity() {
        sesV2.deleteTenant(r -> r.tenantName(TENANT));

        assertThatThrownBy(() -> sesV2.getReputationEntity(r -> r
                .reputationEntityType(ReputationEntityType.RESOURCE)
                .reputationEntityReference(tenantArn)))
                .isInstanceOf(BadRequestException.class);
    }
}
