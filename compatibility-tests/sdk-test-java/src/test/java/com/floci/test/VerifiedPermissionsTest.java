package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.verifiedpermissions.VerifiedPermissionsClient;
import software.amazon.awssdk.services.verifiedpermissions.model.ActionIdentifier;
import software.amazon.awssdk.services.verifiedpermissions.model.Decision;
import software.amazon.awssdk.services.verifiedpermissions.model.EntitiesDefinition;
import software.amazon.awssdk.services.verifiedpermissions.model.EntityIdentifier;
import software.amazon.awssdk.services.verifiedpermissions.model.PolicyDefinition;
import software.amazon.awssdk.services.verifiedpermissions.model.PolicyEffect;
import software.amazon.awssdk.services.verifiedpermissions.model.PolicyType;
import software.amazon.awssdk.services.verifiedpermissions.model.SchemaDefinition;
import software.amazon.awssdk.services.verifiedpermissions.model.StaticPolicyDefinition;
import software.amazon.awssdk.services.verifiedpermissions.model.ValidationException;
import software.amazon.awssdk.services.verifiedpermissions.model.ValidationMode;
import software.amazon.awssdk.services.verifiedpermissions.model.ValidationSettings;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("Amazon Verified Permissions")
class VerifiedPermissionsTest {

    @Test
    @DisplayName("policy store, schema, policies, aliases and Cedar authorization use AWS SDK v2")
    void lifecycleAndAuthorizationUseAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Creates emulator-only policy data");

        try (VerifiedPermissionsClient avp = TestFixtures.verifiedPermissionsClient()) {
            String policyStoreId = avp.createPolicyStore(request -> request
                            .validationSettings(ValidationSettings.builder().mode(ValidationMode.OFF).build())
                            .tags(Map.of("project", "floci")))
                    .policyStoreId();

            var store = avp.getPolicyStore(request -> request.policyStoreId(policyStoreId).tags(true));
            assertThat(store.policyStoreId()).isEqualTo(policyStoreId);
            assertThat(store.tags()).containsEntry("project", "floci");

            String schema = """
                    {"Demo":{"entityTypes":{
                      "User":{"shape":{"type":"Record","attributes":{"tenant":{"type":"String","required":true}}}},
                      "Document":{"shape":{"type":"Record","attributes":{"tenant":{"type":"String","required":true}}}}
                    },"actions":{"read":{"appliesTo":{"principalTypes":["User"],"resourceTypes":["Document"]}}}}}
                    """;
            avp.putSchema(request -> request.policyStoreId(policyStoreId)
                    .definition(SchemaDefinition.builder().cedarJson(schema).build()));

            String statement = "permit(principal, action == Demo::Action::\"read\", resource) "
                    + "when { principal.tenant == resource.tenant };";
            String policyId = avp.createPolicy(request -> request
                            .policyStoreId(policyStoreId)
                            .name("name/tenant-reader")
                            .definition(PolicyDefinition.builder().staticValue(StaticPolicyDefinition.builder()
                                    .statement(statement).description("tenant reader").build()).build()))
                    .policyId();

            assertThat(avp.getPolicy(request -> request.policyStoreId(policyStoreId).policyId("name/tenant-reader"))
                    .policyId()).isEqualTo(policyId);
            assertThat(avp.listPolicies(request -> request.policyStoreId(policyStoreId)).policies())
                    .anySatisfy(policy -> {
                        assertThat(policy.policyId()).isEqualTo(policyId);
                        assertThat(policy.policyType()).isEqualTo(PolicyType.STATIC);
                        assertThat(policy.effect()).isEqualTo(PolicyEffect.PERMIT);
                    });

            String entities = """
                    [
                      {"uid":{"type":"Demo::User","id":"alice"},"attrs":{"tenant":"t1"},"parents":[]},
                      {"uid":{"type":"Demo::Document","id":"doc1"},"attrs":{"tenant":"t1"},"parents":[]}
                    ]
                    """;
            var decision = avp.isAuthorized(request -> request
                    .policyStoreId(policyStoreId)
                    .principal(EntityIdentifier.builder().entityType("Demo::User").entityId("alice").build())
                    .action(ActionIdentifier.builder().actionType("Demo::Action").actionId("read").build())
                    .resource(EntityIdentifier.builder().entityType("Demo::Document").entityId("doc1").build())
                    .entities(EntitiesDefinition.builder().cedarJson(entities).build()));
            assertThat(decision.decision()).isEqualTo(Decision.ALLOW);
            assertThat(decision.determiningPolicies()).extracting(p -> p.policyId()).contains(policyId);

            String alias = "policy-store-alias/" + TestFixtures.uniqueName("avp");
            avp.createPolicyStoreAlias(request -> request.aliasName(alias).policyStoreId(policyStoreId));
            assertThat(avp.getPolicyStore(request -> request.policyStoreId(alias)).policyStoreId())
                    .isEqualTo(policyStoreId);
            avp.deletePolicyStoreAlias(request -> request.aliasName(alias).deletionMode(software.amazon.awssdk.services.verifiedpermissions.model.DeletionMode.HARD_DELETE));

            String token = "sdk-idempotency-123";
            String first = avp.createPolicyStore(request -> request.clientToken(token)
                            .validationSettings(ValidationSettings.builder().mode(ValidationMode.OFF).build()))
                    .policyStoreId();
            String replay = avp.createPolicyStore(request -> request.clientToken(token)
                            .validationSettings(ValidationSettings.builder().mode(ValidationMode.OFF).build()))
                    .policyStoreId();
            assertThat(replay).isEqualTo(first);
            assertThatThrownBy(() -> avp.createPolicyStore(request -> request.clientToken(token)
                            .validationSettings(ValidationSettings.builder().mode(ValidationMode.STRICT).build())))
                    .isInstanceOf(software.amazon.awssdk.services.verifiedpermissions.model.ConflictException.class);

            avp.deletePolicy(request -> request.policyStoreId(policyStoreId).policyId(policyId));
            avp.deletePolicyStore(request -> request.policyStoreId(policyStoreId));
        }
    }

    @Test
    @DisplayName("STRICT validation rejects policies that do not match the schema")
    void strictValidationUsesCedarSchema() {
        assumeFalse(TestFixtures.isRealAws(), "Creates emulator-only policy data");

        try (VerifiedPermissionsClient avp = TestFixtures.verifiedPermissionsClient()) {
            String store = avp.createPolicyStore(request -> request
                            .validationSettings(ValidationSettings.builder().mode(ValidationMode.STRICT).build()))
                    .policyStoreId();
            assertThatThrownBy(() -> avp.createPolicy(request -> request
                            .policyStoreId(store)
                            .definition(PolicyDefinition.builder().staticValue(StaticPolicyDefinition.builder()
                                    .statement("permit(principal, action, resource);").build()).build())))
                    .isInstanceOf(ValidationException.class);
        }
    }
}
