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
import software.amazon.awssdk.services.sesv2.model.CreateConfigurationSetRequest;
import software.amazon.awssdk.services.sesv2.model.CreateEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.CreateTenantRequest;
import software.amazon.awssdk.services.sesv2.model.CreateTenantResponse;
import software.amazon.awssdk.services.sesv2.model.DeleteConfigurationSetRequest;
import software.amazon.awssdk.services.sesv2.model.DeleteEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.DeleteTenantRequest;
import software.amazon.awssdk.services.sesv2.model.DeleteTenantResourceAssociationRequest;
import software.amazon.awssdk.services.sesv2.model.GetSuppressedDestinationResponse;
import software.amazon.awssdk.services.sesv2.model.GetTenantRequest;
import software.amazon.awssdk.services.sesv2.model.GetTenantResponse;
import software.amazon.awssdk.services.sesv2.model.ListResourceTenantsRequest;
import software.amazon.awssdk.services.sesv2.model.ListResourceTenantsResponse;
import software.amazon.awssdk.services.sesv2.model.ListSuppressedDestinationsResponse;
import software.amazon.awssdk.services.sesv2.model.ListTenantResourcesFilterKey;
import software.amazon.awssdk.services.sesv2.model.ListTenantResourcesRequest;
import software.amazon.awssdk.services.sesv2.model.ListTenantResourcesResponse;
import software.amazon.awssdk.services.sesv2.model.ListTenantsRequest;
import software.amazon.awssdk.services.sesv2.model.ListTenantsResponse;
import software.amazon.awssdk.services.sesv2.model.NotFoundException;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;
import software.amazon.awssdk.services.sesv2.model.SuppressionListReason;
import software.amazon.awssdk.services.sesv2.model.Tag;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SDK compatibility test for the SES v2 tenant APIs, one ordered flow against a live Floci
 * instance: the CRUD (CreateTenant / GetTenant / ListTenants / DeleteTenant), the resource
 * associations (CreateTenantResourceAssociation / DeleteTenantResourceAssociation /
 * ListTenantResources / ListResourceTenants), and tenant suppression (PutTenantSuppressionAttributes
 * plus the TenantName-scoped suppression-list operations). One AWS quirk drives the association
 * assertions: the service's wire values for ResourceType — in responses and as the RESOURCE_TYPE
 * filter value — are the ARN segments (identity / configuration-set / template), not the SDK's
 * EMAIL_IDENTITY-style enum constants. Real AWS rejects the enum spelling as a filter and returns
 * values the SDK maps to UNKNOWN_TO_SDK_VERSION, so the test asserts resourceTypeAsString. The
 * suppression block asserts the all-or-nothing attribute pair (a bare TenantName clears it), the
 * per-tenant list fully separate from the account list, the non-idempotent tenant-scoped delete,
 * and the DeleteTenant cascade. Tenants are reversible (create + delete), so cleanup restores the
 * account.
 */
@DisplayName("SES v2 Tenants")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesTenantTest {

    private static final String TENANT = "compat-tenant-alpha";
    private static final String IDENTITY = "compat-assoc.example.com";
    private static final String CONFIG_SET = "compat-assoc-cs";
    private static final String TENANT_ADDR = "compat-sup-tenant@example.com";
    private static final String ACCOUNT_ADDR = "compat-sup-acct@example.com";

    private static SesV2Client sesV2;
    private static String identityArn;
    private static String configSetArn;

    @BeforeAll
    static void setup() {
        sesV2 = TestFixtures.sesV2Client();
        sesV2.createEmailIdentity(CreateEmailIdentityRequest.builder().emailIdentity(IDENTITY).build());
        sesV2.createConfigurationSet(CreateConfigurationSetRequest.builder()
                .configurationSetName(CONFIG_SET).build());
        sesV2.putSuppressedDestination(r -> r.emailAddress(ACCOUNT_ADDR)
                .reason(SuppressionListReason.BOUNCE));
    }

    @AfterAll
    static void cleanup() {
        if (sesV2 != null) {
            try {
                // DeleteTenant cascades any remaining associations, freeing the backing resources.
                sesV2.deleteTenant(DeleteTenantRequest.builder().tenantName(TENANT).build());
            } catch (NotFoundException expected) {
                // Already removed by the delete test; anything else (auth, connectivity) must surface.
            }
            sesV2.deleteConfigurationSet(DeleteConfigurationSetRequest.builder()
                    .configurationSetName(CONFIG_SET).build());
            sesV2.deleteEmailIdentity(DeleteEmailIdentityRequest.builder().emailIdentity(IDENTITY).build());
            sesV2.deleteSuppressedDestination(r -> r.emailAddress(ACCOUNT_ADDR));
            sesV2.close();
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

        String arnPrefix = resp.tenantArn().substring(0, resp.tenantArn().indexOf(":tenant/") + 1);
        identityArn = arnPrefix + "identity/" + IDENTITY;
        configSetArn = arnPrefix + "configuration-set/" + CONFIG_SET;
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
    void createAssociations_succeed() {
        sesV2.createTenantResourceAssociation(r -> r.tenantName(TENANT).resourceArn(identityArn));
        sesV2.createTenantResourceAssociation(r -> r.tenantName(TENANT).resourceArn(configSetArn));
    }

    @Test
    @Order(7)
    void createAssociation_duplicate_throwsAlreadyExists() {
        assertThatThrownBy(() -> sesV2.createTenantResourceAssociation(
                r -> r.tenantName(TENANT).resourceArn(identityArn)))
                .isInstanceOf(AlreadyExistsException.class)
                .hasMessageContaining("has already been associated with tenant " + TENANT);
    }

    @Test
    @Order(8)
    void createAssociation_missingTenantOrResource_throwsNotFound() {
        assertThatThrownBy(() -> sesV2.createTenantResourceAssociation(
                r -> r.tenantName("compat-assoc-ghost").resourceArn(identityArn)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> sesV2.createTenantResourceAssociation(
                r -> r.tenantName(TENANT).resourceArn(configSetArn.replace(CONFIG_SET, "ghost-cs"))))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Configuration set <ghost-cs> does not exist:");
    }

    @Test
    @Order(9)
    void createAssociation_invalidArn_throwsBadRequest() {
        assertThatThrownBy(() -> sesV2.createTenantResourceAssociation(
                r -> r.tenantName(TENANT).resourceArn("not-an-arn")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Provided resource identifier is not an SES resource");
    }

    @Test
    @Order(10)
    void listTenantResources_returnsArnSegmentTypeValues() {
        ListTenantResourcesResponse resp = sesV2.listTenantResources(
                ListTenantResourcesRequest.builder().tenantName(TENANT).build());
        assertThat(resp.tenantResources()).hasSize(2);
        // Sorted by ARN; the wire type values do not map onto the SDK enum.
        assertThat(resp.tenantResources().get(0).resourceTypeAsString()).isEqualTo("configuration-set");
        assertThat(resp.tenantResources().get(1).resourceTypeAsString()).isEqualTo("identity");
        assertThat(resp.tenantResources().get(1).resourceArn()).isEqualTo(identityArn);
    }

    @Test
    @Order(11)
    void listTenantResources_filterTakesWireValues_rejectsEnumSpelling() {
        ListTenantResourcesResponse filtered = sesV2.listTenantResources(
                ListTenantResourcesRequest.builder().tenantName(TENANT)
                        .filter(Map.of(ListTenantResourcesFilterKey.RESOURCE_TYPE, "identity"))
                        .build());
        assertThat(filtered.tenantResources()).hasSize(1);
        assertThat(filtered.tenantResources().get(0).resourceArn()).isEqualTo(identityArn);

        assertThatThrownBy(() -> sesV2.listTenantResources(
                ListTenantResourcesRequest.builder().tenantName(TENANT)
                        .filter(Map.of(ListTenantResourcesFilterKey.RESOURCE_TYPE, "EMAIL_IDENTITY"))
                        .build()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Invalid resource type EMAIL_IDENTITY specified.");
    }

    @Test
    @Order(12)
    void listResourceTenants_returnsAssociationMetadata() {
        ListResourceTenantsResponse resp = sesV2.listResourceTenants(
                ListResourceTenantsRequest.builder().resourceArn(identityArn).build());
        assertThat(resp.resourceTenants()).hasSize(1);
        assertThat(resp.resourceTenants().get(0).tenantName()).isEqualTo(TENANT);
        assertThat(resp.resourceTenants().get(0).tenantId()).startsWith("tn-");
        assertThat(resp.resourceTenants().get(0).resourceArn()).isEqualTo(identityArn);
        assertThat(resp.resourceTenants().get(0).associatedTimestamp()).isNotNull();
    }

    @Test
    @Order(13)
    void deleteBackingResource_whileAssociated_isBlocked() {
        assertThatThrownBy(() -> sesV2.deleteConfigurationSet(DeleteConfigurationSetRequest.builder()
                        .configurationSetName(CONFIG_SET).build()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("because it has tenant associations");
    }

    @Test
    @Order(14)
    void deleteAssociation_removesIt_andIsIdempotent() {
        sesV2.deleteTenantResourceAssociation(DeleteTenantResourceAssociationRequest.builder()
                .tenantName(TENANT).resourceArn(configSetArn).build());
        // Removing an association that no longer exists is a silent success on AWS.
        sesV2.deleteTenantResourceAssociation(DeleteTenantResourceAssociationRequest.builder()
                .tenantName(TENANT).resourceArn(configSetArn).build());
        ListTenantResourcesResponse resp = sesV2.listTenantResources(
                ListTenantResourcesRequest.builder().tenantName(TENANT).build());
        assertThat(resp.tenantResources()).hasSize(1);
    }

    @Test
    @Order(15)
    void putTenantSuppressionAttributes_setsPair_getTenantReturnsThem() {
        sesV2.putTenantSuppressionAttributes(r -> r.tenantName(TENANT)
                .suppressedReasonsWithStrings("BOUNCE", "COMPLAINT")
                .suppressionScope("TENANT"));
        GetTenantResponse resp = sesV2.getTenant(GetTenantRequest.builder().tenantName(TENANT).build());
        assertThat(resp.tenant().suppressionAttributes().suppressedReasonsAsStrings())
                .containsExactly("BOUNCE", "COMPLAINT");
        assertThat(resp.tenant().suppressionAttributes().suppressionScopeAsString()).isEqualTo("TENANT");
    }

    @Test
    @Order(16)
    void putTenantSuppressionAttributes_halfPair_throwsBadRequest() {
        assertThatThrownBy(() -> sesV2.putTenantSuppressionAttributes(r -> r.tenantName(TENANT)
                        .suppressedReasonsWithStrings("BOUNCE")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("SuppressedReasons cannot be specified without SuppressionScope.");
    }

    @Test
    @Order(17)
    void putTenantSuppressionAttributes_bareTenantName_clearsTheBlock() {
        sesV2.putTenantSuppressionAttributes(r -> r.tenantName(TENANT));
        GetTenantResponse resp = sesV2.getTenant(GetTenantRequest.builder().tenantName(TENANT).build());
        assertThat(resp.tenant().suppressionAttributes()).isNull();
    }

    @Test
    @Order(18)
    void tenantSuppressionList_isSeparateFromAccountList() {
        sesV2.putSuppressedDestination(r -> r.emailAddress(TENANT_ADDR)
                .reason(SuppressionListReason.BOUNCE).tenantName(TENANT));

        GetSuppressedDestinationResponse got = sesV2.getSuppressedDestination(
                r -> r.emailAddress(TENANT_ADDR).tenantName(TENANT));
        assertThat(got.suppressedDestination().tenantName()).isEqualTo(TENANT);

        assertThatThrownBy(() -> sesV2.getSuppressedDestination(r -> r.emailAddress(TENANT_ADDR)))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("on your suppression list");
        assertThatThrownBy(() -> sesV2.getSuppressedDestination(
                r -> r.emailAddress(ACCOUNT_ADDR).tenantName(TENANT)))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("on your tenant suppression list");
    }

    @Test
    @Order(19)
    void tenantSuppressionList_listsOnlyTenantEntries() {
        ListSuppressedDestinationsResponse resp =
                sesV2.listSuppressedDestinations(r -> r.tenantName(TENANT));
        assertThat(resp.suppressedDestinationSummaries()).hasSize(1);
        assertThat(resp.suppressedDestinationSummaries().get(0).emailAddress()).isEqualTo(TENANT_ADDR);
    }

    @Test
    @Order(20)
    void tenantSuppressionOps_missingTenant_throwNotFound() {
        assertThatThrownBy(() -> sesV2.putSuppressedDestination(r -> r.emailAddress(TENANT_ADDR)
                        .reason(SuppressionListReason.BOUNCE).tenantName("compat-sup-ghost")))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("The requested tenant <compat-sup-ghost> does not exist.");
    }

    @Test
    @Order(21)
    void tenantSuppressionDelete_isNotIdempotent() {
        sesV2.deleteSuppressedDestination(r -> r.emailAddress(TENANT_ADDR).tenantName(TENANT));
        // Unlike the resource associations, a second tenant-scoped delete is a NotFound.
        assertThatThrownBy(() -> sesV2.deleteSuppressedDestination(
                r -> r.emailAddress(TENANT_ADDR).tenantName(TENANT)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @Order(22)
    void tenantSend_gatesOnAssociations() {
        // The domain identity is still associated (order 6); the config set's association was
        // removed in order 14, so using it in a tenant send must be refused with the 403.
        SendEmailRequest.Builder send = SendEmailRequest.builder()
                .fromEmailAddress("probe@" + IDENTITY)
                .destination(d -> d.toAddresses("success@simulator.amazonses.com"))
                .content(c -> c.simple(s -> s
                        .subject(sub -> sub.data("s"))
                        .body(b -> b.text(t -> t.data("t")))));

        assertThat(sesV2.sendEmail(send.tenantName(TENANT).build()).messageId()).isNotNull();

        assertThatThrownBy(() -> sesV2.sendEmail(send.tenantName("compat-send-ghost").build()))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Tenant compat-send-ghost for AwsAccountId")
                .hasMessageContaining("not found.");

        assertThatThrownBy(() -> sesV2.sendEmail(
                send.tenantName(TENANT).configurationSetName(CONFIG_SET).build()))
                .isInstanceOfSatisfying(SesV2Exception.class, e -> {
                    assertThat(e.statusCode()).isEqualTo(403);
                    assertThat(e.getMessage()).contains("Tenant not associated with resources [");
                    assertThat(e.getMessage()).contains("configuration-set/" + CONFIG_SET);
                });
    }

    @Test
    @Order(23)
    void deleteTenant_cascadesAssociationsAndSuppressionEntries_thenGetIsNotFound() {
        sesV2.putSuppressedDestination(r -> r.emailAddress(TENANT_ADDR)
                .reason(SuppressionListReason.COMPLAINT).tenantName(TENANT));
        sesV2.deleteTenant(DeleteTenantRequest.builder().tenantName(TENANT).build());
        ListResourceTenantsResponse resp = sesV2.listResourceTenants(
                ListResourceTenantsRequest.builder().resourceArn(identityArn).build());
        assertThat(resp.resourceTenants()).isEmpty();
        assertThatThrownBy(() -> sesV2.listSuppressedDestinations(r -> r.tenantName(TENANT)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> sesV2.getTenant(GetTenantRequest.builder().tenantName(TENANT).build()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @Order(24)
    void deleteTenant_missing_throwsNotFound() {
        assertThatThrownBy(() -> sesV2.deleteTenant(DeleteTenantRequest.builder()
                        .tenantName("compat-tenant-does-not-exist").build()))
                .isInstanceOf(NotFoundException.class);
    }
}
