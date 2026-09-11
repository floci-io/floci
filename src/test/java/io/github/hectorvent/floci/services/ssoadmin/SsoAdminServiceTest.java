package io.github.hectorvent.floci.services.ssoadmin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ssoadmin.model.Assignment;
import io.github.hectorvent.floci.services.ssoadmin.model.AssignmentOperation;
import io.github.hectorvent.floci.services.ssoadmin.model.AssignmentDeletionOperation;
import io.github.hectorvent.floci.services.ssoadmin.model.ApplicationAccessScope;
import io.github.hectorvent.floci.services.ssoadmin.model.ApplicationAssignment;
import io.github.hectorvent.floci.services.ssoadmin.model.ApplicationAuthenticationMethod;
import io.github.hectorvent.floci.services.ssoadmin.model.ApplicationGrant;
import io.github.hectorvent.floci.services.ssoadmin.model.InstanceAccessControlAttributeConfiguration;
import io.github.hectorvent.floci.services.ssoadmin.model.InstanceUpdateState;
import io.github.hectorvent.floci.services.ssoadmin.model.PermissionSet;
import io.github.hectorvent.floci.services.ssoadmin.model.PermissionSetProvisioning;
import io.github.hectorvent.floci.services.ssoadmin.model.PermissionSetProvisioningOperation;
import io.github.hectorvent.floci.services.ssoadmin.model.RegionMetadata;
import io.github.hectorvent.floci.services.ssoadmin.model.SsoApplication;
import io.github.hectorvent.floci.services.ssoadmin.model.SsoInstance;
import io.github.hectorvent.floci.services.ssoadmin.model.TrustedTokenIssuer;
import io.github.hectorvent.floci.services.identitystore.IdentityStoreService;
import io.github.hectorvent.floci.services.organizations.OrganizationsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SsoAdminServiceTest {
    private static final String ACCOUNT_ID = "123456789012";
    private static final String PRINCIPAL_ID = "11111111-2222-3333-4444-555555555555";

    private final ObjectMapper mapper = new ObjectMapper();
    private SsoAdminService service;
    private IdentityStoreService identityStoreService;
    private OrganizationsService organizationsService;
    private InMemoryStorage<String, ApplicationAccessScope> applicationAccessScopes;
    private InMemoryStorage<String, ApplicationAuthenticationMethod> applicationAuthenticationMethods;
    private InMemoryStorage<String, ApplicationGrant> applicationGrants;
    private InMemoryStorage<String, SsoInstance> instances;

    @BeforeEach
    void setUp() {
        identityStoreService = org.mockito.Mockito.mock(IdentityStoreService.class);
        organizationsService = org.mockito.Mockito.mock(OrganizationsService.class);
        applicationAccessScopes = new InMemoryStorage<>();
        applicationAuthenticationMethods = new InMemoryStorage<>();
        applicationGrants = new InMemoryStorage<>();
        instances = new InMemoryStorage<>();
        service = new SsoAdminService(
                new InMemoryStorage<String, PermissionSet>(),
                new InMemoryStorage<String, Assignment>(),
                new InMemoryStorage<String, AssignmentOperation>(),
                new InMemoryStorage<String, AssignmentDeletionOperation>(),
                new InMemoryStorage<String, PermissionSetProvisioning>(),
                new InMemoryStorage<String, PermissionSetProvisioningOperation>(),
                new InMemoryStorage<String, RegionMetadata>(),
                new InMemoryStorage<String, SsoApplication>(),
                new InMemoryStorage<String, SsoApplication>(),
                new InMemoryStorage<String, String>(),
                new InMemoryStorage<String, ApplicationAssignment>(),
                applicationAccessScopes,
                new InMemoryStorage<String, Boolean>(),
                applicationAuthenticationMethods,
                applicationGrants,
                new InMemoryStorage<String, String>(),
                new InMemoryStorage<String, Map<String, String>>(),
                instances,
                new InMemoryStorage<String, InstanceUpdateState>(),
                new InMemoryStorage<String, String>(),
                new InMemoryStorage<String, Boolean>(),
                new InMemoryStorage<String, InstanceAccessControlAttributeConfiguration>(),
                new InMemoryStorage<String, TrustedTokenIssuer>(),
                new InMemoryStorage<String, TrustedTokenIssuer>(),
                new InMemoryStorage<String, String>(),
                identityStoreService,
                organizationsService,
                ACCOUNT_ID,
                "us-east-1");
        service.ensureBootstrapInstance(ACCOUNT_ID, "us-east-1");
    }

    @Test
    void hasIdentityStoreSelfHealsAfterStorageFactoryClearsInstancesPostReset() {
        // Mirrors EmulatorInfoController.performReset(): every Resettable.clear() (including
        // this service's, which re-seeds the bootstrap instance) runs, then
        // StorageFactory.clearAll() wipes every backing store again, including `instances`.
        service.clear();
        instances.clear();
        assertTrue(instances.get(ACCOUNT_ID).isEmpty());

        assertTrue(service.hasIdentityStore(service.getIdentityStoreId()));
        assertTrue(instances.get(ACCOUNT_ID).isPresent());
    }

    @Test
    void createTrustedTokenIssuerPersistsOidcConfigurationAndSupportsIdempotency() {
        ObjectNode request = trustedTokenIssuerRequest("IssuerOne", "tti-token-one");

        TrustedTokenIssuer created = service.createTrustedTokenIssuer(request, ACCOUNT_ID);
        assertTrue(created.trustedTokenIssuerArn().matches(
                "arn:aws:sso::123456789012:trustedTokenIssuer/ssoins-[0-9a-f]{16}/tti-[0-9a-f-]{36}"));
        assertEquals("OIDC_JWT", created.trustedTokenIssuerType());
        assertEquals("https://issuer.example.com", created.oidcJwtConfiguration().issuerUrl());
        assertEquals(created, service.createTrustedTokenIssuer(request, ACCOUNT_ID));
        assertEquals(created, service.getTrustedTokenIssuer(created.trustedTokenIssuerArn()));
        ObjectNode describe = mapper.createObjectNode().put("TrustedTokenIssuerArn", created.trustedTokenIssuerArn());
        assertEquals(created, service.describeTrustedTokenIssuer(describe));

        ObjectNode list = mapper.createObjectNode().put("InstanceArn", service.getInstanceArn());
        assertTrue(service.listTrustedTokenIssuers(list).items().contains(created));

        ObjectNode mismatch = request.deepCopy();
        mismatch.put("Name", "IssuerTwo");
        assertError("IdempotentParameterMismatch",
                () -> service.createTrustedTokenIssuer(mismatch, ACCOUNT_ID));
    }

    @Test
    void updateTrustedTokenIssuerChangesMutableFieldsAndPreservesCreateIdempotency() {
        ObjectNode create = trustedTokenIssuerRequest("OriginalIssuer", "update-tti-token");
        TrustedTokenIssuer created = service.createTrustedTokenIssuer(create, ACCOUNT_ID);
        ObjectNode update = mapper.createObjectNode();
        update.put("TrustedTokenIssuerArn", created.trustedTokenIssuerArn());
        update.put("Name", "UpdatedIssuer");
        update.putObject("TrustedTokenIssuerConfiguration").putObject("OidcJwtConfiguration")
                .put("ClaimAttributePath", "email")
                .put("IdentityStoreAttributePath", "emails.value")
                .put("JwksRetrievalOption", "OPEN_ID_DISCOVERY");
        TrustedTokenIssuer updated = service.updateTrustedTokenIssuer(update);
        assertEquals("UpdatedIssuer", updated.name());
        assertEquals("email", updated.oidcJwtConfiguration().claimAttributePath());
        assertEquals(created.oidcJwtConfiguration().issuerUrl(), updated.oidcJwtConfiguration().issuerUrl());
        assertEquals(created.trustedTokenIssuerArn(), service.createTrustedTokenIssuer(create, ACCOUNT_ID).trustedTokenIssuerArn());
        ObjectNode invalid = update.deepCopy();
        invalid.withObject("TrustedTokenIssuerConfiguration").withObject("OidcJwtConfiguration")
                .put("IssuerUrl", "https://other.example.com");
        assertError("ValidationException", () -> service.updateTrustedTokenIssuer(invalid));
    }

    @Test
    void deleteTrustedTokenIssuerRemovesIssuerAndIdempotencyToken() {
        ObjectNode create = trustedTokenIssuerRequest("DeleteIssuer", "delete-tti-token");
        TrustedTokenIssuer issuer = service.createTrustedTokenIssuer(create, ACCOUNT_ID);

        ObjectNode request = mapper.createObjectNode();
        request.put("TrustedTokenIssuerArn", issuer.trustedTokenIssuerArn());
        service.deleteTrustedTokenIssuer(request);
        assertError("ResourceNotFoundException", () -> service.getTrustedTokenIssuer(issuer.trustedTokenIssuerArn()));
        assertError("ResourceNotFoundException", () -> service.deleteTrustedTokenIssuer(request));

        TrustedTokenIssuer recreated = service.createTrustedTokenIssuer(create, ACCOUNT_ID);
        assertFalse(recreated.trustedTokenIssuerArn().equals(issuer.trustedTokenIssuerArn()));

        ObjectNode malformed = mapper.createObjectNode();
        malformed.put("TrustedTokenIssuerArn", "not-an-arn");
        assertError("ValidationException", () -> service.deleteTrustedTokenIssuer(malformed));
    }

    @Test
    void createTrustedTokenIssuerValidatesUnionOidcFieldsAndQuota() {
        ObjectNode invalidType = trustedTokenIssuerRequest("InvalidType", null);
        invalidType.put("TrustedTokenIssuerType", "SAML");
        assertError("ValidationException", () -> service.createTrustedTokenIssuer(invalidType, ACCOUNT_ID));

        ObjectNode invalidIssuerUrl = trustedTokenIssuerRequest("InvalidUrl", null);
        invalidIssuerUrl.withObject("TrustedTokenIssuerConfiguration")
                .withObject("OidcJwtConfiguration").put("IssuerUrl", "ftp://issuer.example.com");
        assertError("ValidationException", () -> service.createTrustedTokenIssuer(invalidIssuerUrl, ACCOUNT_ID));

        for (int i = 0; i < 10; i++) {
            service.createTrustedTokenIssuer(trustedTokenIssuerRequest("Issuer" + i, null), ACCOUNT_ID);
        }
        assertError("ServiceQuotaExceededException",
                () -> service.createTrustedTokenIssuer(trustedTokenIssuerRequest("IssuerOverQuota", null), ACCOUNT_ID));
    }

    @Test
    void createInstanceAccessControlAttributeConfigurationPersistsAndValidatesAwsShape() {
        ObjectNode request = mapper.createObjectNode();
        request.put("InstanceArn", service.getInstanceArn());
        request.putObject("InstanceAccessControlAttributeConfiguration")
                .putArray("AccessControlAttributes")
                .addObject()
                .put("Key", "Department")
                .putObject("Value")
                .putArray("Source")
                .add("${path:enterprise.department}");

        InstanceAccessControlAttributeConfiguration created =
                service.createInstanceAccessControlAttributeConfiguration(request);
        assertEquals("ENABLED", created.status());
        assertEquals(1, created.accessControlAttributes().size());
        assertEquals("Department", created.accessControlAttributes().get(0).key());
        assertEquals("${path:enterprise.department}", created.accessControlAttributes().get(0).source());
        assertEquals(created, service.getInstanceAccessControlAttributeConfiguration(service.getInstanceArn()));
        ObjectNode describeRequest = mapper.createObjectNode().put("InstanceArn", service.getInstanceArn());
        assertEquals(created, service.describeInstanceAccessControlAttributeConfiguration(describeRequest));
        assertError("ConflictException", () -> service.createInstanceAccessControlAttributeConfiguration(request));
    }

    @Test
    void createInstanceAccessControlAttributeConfigurationRejectsInvalidAttributes() {
        ObjectNode malformedInstanceArn = mapper.createObjectNode();
        malformedInstanceArn.put("InstanceArn", "not-an-arn");
        malformedInstanceArn.putObject("InstanceAccessControlAttributeConfiguration")
                .putArray("AccessControlAttributes");
        assertError("ValidationException",
                () -> service.createInstanceAccessControlAttributeConfiguration(malformedInstanceArn));

        ObjectNode missingConfiguration = mapper.createObjectNode();
        missingConfiguration.put("InstanceArn", service.getInstanceArn());
        assertError("ValidationException",
                () -> service.createInstanceAccessControlAttributeConfiguration(missingConfiguration));

        ObjectNode tooMany = mapper.createObjectNode();
        tooMany.put("InstanceArn", service.getInstanceArn());
        var attributes = tooMany.putObject("InstanceAccessControlAttributeConfiguration")
                .putArray("AccessControlAttributes");
        for (int i = 0; i < 51; i++) {
            attributes.addObject().put("Key", "Key" + i).putObject("Value").putArray("Source").add("value");
        }
        assertError("ValidationException", () -> service.createInstanceAccessControlAttributeConfiguration(tooMany));

        ObjectNode invalidSourceCount = mapper.createObjectNode();
        invalidSourceCount.put("InstanceArn", service.getInstanceArn());
        invalidSourceCount.putObject("InstanceAccessControlAttributeConfiguration")
                .putArray("AccessControlAttributes")
                .addObject().put("Key", "Department").putObject("Value").putArray("Source")
                .add("one").add("two");
        assertError("ValidationException",
                () -> service.createInstanceAccessControlAttributeConfiguration(invalidSourceCount));
    }

    @Test
    void updateInstanceAccessControlAttributeConfigurationReplacesMappings() {
        ObjectNode create = mapper.createObjectNode();
        create.put("InstanceArn", service.getInstanceArn());
        create.putObject("InstanceAccessControlAttributeConfiguration")
                .putArray("AccessControlAttributes")
                .addObject().put("Key", "Department").putObject("Value").putArray("Source")
                .add("${path:enterprise.department}");
        service.createInstanceAccessControlAttributeConfiguration(create);

        ObjectNode update = mapper.createObjectNode();
        update.put("InstanceArn", service.getInstanceArn());
        update.putObject("InstanceAccessControlAttributeConfiguration")
                .putArray("AccessControlAttributes")
                .addObject().put("Key", "CostCenter").putObject("Value").putArray("Source")
                .add("${path:enterprise.costCenter}");
        InstanceAccessControlAttributeConfiguration updated =
                service.updateInstanceAccessControlAttributeConfiguration(update);
        assertEquals(1, updated.accessControlAttributes().size());
        assertEquals("CostCenter", updated.accessControlAttributes().get(0).key());
        assertEquals("ENABLED", updated.status());

        ObjectNode empty = mapper.createObjectNode();
        empty.put("InstanceArn", service.getInstanceArn());
        empty.putObject("InstanceAccessControlAttributeConfiguration").putArray("AccessControlAttributes");
        assertTrue(service.updateInstanceAccessControlAttributeConfiguration(empty).accessControlAttributes().isEmpty());

        service.deleteInstanceAccessControlAttributeConfiguration(mapper.createObjectNode().put("InstanceArn", service.getInstanceArn()));
        assertError("ResourceNotFoundException",
                () -> service.updateInstanceAccessControlAttributeConfiguration(update));
    }

    @Test
    void deleteInstanceAccessControlAttributeConfigurationRemovesAbacConfiguration() {
        ObjectNode create = mapper.createObjectNode();
        create.put("InstanceArn", service.getInstanceArn());
        create.putObject("InstanceAccessControlAttributeConfiguration")
                .putArray("AccessControlAttributes")
                .addObject()
                .put("Key", "Department")
                .putObject("Value")
                .putArray("Source")
                .add("${path:enterprise.department}");
        service.createInstanceAccessControlAttributeConfiguration(create);

        ObjectNode request = mapper.createObjectNode();
        request.put("InstanceArn", service.getInstanceArn());
        service.deleteInstanceAccessControlAttributeConfiguration(request);

        assertError("ResourceNotFoundException",
                () -> service.getInstanceAccessControlAttributeConfiguration(service.getInstanceArn()));
        assertError("ResourceNotFoundException",
                () -> service.deleteInstanceAccessControlAttributeConfiguration(request));

        InstanceAccessControlAttributeConfiguration recreated =
                service.createInstanceAccessControlAttributeConfiguration(create);
        assertEquals("ENABLED", recreated.status());
    }

    @Test
    void deleteInstanceRequiresOwnerAndAllowsRecreation() {
        SsoAdminService emptyService = emptyService();
        ObjectNode create = mapper.createObjectNode();
        create.put("Name", "DisposableInstance");
        create.put("ClientToken", "delete-instance-token");
        SsoInstance instance = emptyService.createInstance(create, ACCOUNT_ID, "us-west-2");

        ObjectNode request = mapper.createObjectNode();
        request.put("InstanceArn", instance.instanceArn());
        assertError("AccessDeniedException", () -> emptyService.deleteInstance(request, "210987654321"));

        emptyService.deleteInstance(request, ACCOUNT_ID);
        assertTrue(emptyService.listInstances(ACCOUNT_ID).isEmpty());
        assertError("AccessDeniedException", () -> emptyService.deleteInstance(request, ACCOUNT_ID));

        ObjectNode recreate = mapper.createObjectNode();
        recreate.put("Name", "ReplacementInstance");
        recreate.put("ClientToken", "replacement-instance-token");
        SsoInstance replacement = emptyService.createInstance(recreate, ACCOUNT_ID, "us-west-2");
        assertFalse(replacement.instanceArn().equals(instance.instanceArn()));
    }

    @Test
    void deleteInstancePreservesOtherAccountPermissionSets() {
        SsoAdminService emptyService = emptyService();
        String firstAccount = "111111111111";
        String secondAccount = "222222222222";

        ObjectNode firstCreate = mapper.createObjectNode();
        firstCreate.put("Name", "FirstInstance");
        firstCreate.put("ClientToken", "first-instance-token");
        SsoInstance first = emptyService.createInstance(firstCreate, firstAccount, "us-east-1");

        ObjectNode secondCreate = mapper.createObjectNode();
        secondCreate.put("Name", "SecondInstance");
        secondCreate.put("ClientToken", "second-instance-token");
        SsoInstance second = emptyService.createInstance(secondCreate, secondAccount, "us-west-2");

        ObjectNode firstPermissionSet = mapper.createObjectNode();
        firstPermissionSet.put("InstanceArn", first.instanceArn());
        firstPermissionSet.put("Name", "FirstAdmins");
        PermissionSet firstPs = emptyService.createPermissionSet(firstPermissionSet);

        ObjectNode secondPermissionSet = mapper.createObjectNode();
        secondPermissionSet.put("InstanceArn", second.instanceArn());
        secondPermissionSet.put("Name", "SecondAdmins");
        PermissionSet secondPs = emptyService.createPermissionSet(secondPermissionSet);

        emptyService.deleteInstance(mapper.createObjectNode().put("InstanceArn", first.instanceArn()), firstAccount);

        assertError("ResourceNotFoundException",
                () -> emptyService.getPermissionSet(first.instanceArn(), firstPs.arn()));
        assertEquals(secondPs, emptyService.getPermissionSet(second.instanceArn(), secondPs.arn()));
    }

    @Test
    void deleteInstanceRejectsMalformedArnAndCascadesOwnedResources() {
        SsoAdminService emptyService = emptyService();
        ObjectNode malformed = mapper.createObjectNode();
        malformed.put("InstanceArn", "not-an-arn");
        assertError("ValidationException", () -> emptyService.deleteInstance(malformed, ACCOUNT_ID));

        SsoInstance instance = emptyService.createInstance(mapper.createObjectNode(), ACCOUNT_ID, "us-east-1");
        ObjectNode application = mapper.createObjectNode();
        application.put("InstanceArn", instance.instanceArn());
        application.put("ApplicationProviderArn", "arn:aws:sso::aws:applicationProvider/custom");
        application.put("Name", "AttachedApplication");
        SsoApplication createdApplication = emptyService.createApplication(application, ACCOUNT_ID, "us-east-1");
        assertEquals(instance.instanceArn(), createdApplication.instanceArn());

        ObjectNode request = mapper.createObjectNode();
        request.put("InstanceArn", instance.instanceArn());
        emptyService.deleteInstance(request, ACCOUNT_ID);
        assertError("ResourceNotFoundException", () -> emptyService.getApplication(createdApplication.applicationArn()));
        org.mockito.Mockito.verify(identityStoreService).deleteIdentityStore(instance.identityStoreId());
    }

    @Test
    void createInstanceUsesThePartitionForItsPrimaryRegion() {
        SsoAdminService emptyService = emptyService();
        SsoInstance instance = emptyService.createInstance(mapper.createObjectNode(), ACCOUNT_ID, "cn-north-1");

        assertTrue(instance.instanceArn().startsWith("arn:aws-cn:sso:::instance/"));
    }

    @Test
    void createInstancePersistsMetadataAndSupportsIdempotentReplay() {
        SsoAdminService emptyService = emptyService();
        ObjectNode request = mapper.createObjectNode();
        request.put("Name", "AccountInstance");
        request.put("ClientToken", "create-instance-token");
        request.putArray("Tags").addObject().put("Key", "Environment").put("Value", "dev");

        assertTrue(emptyService.listInstances(ACCOUNT_ID).isEmpty());

        SsoInstance created = emptyService.createInstance(request, ACCOUNT_ID, "us-west-2");
        assertEquals(ACCOUNT_ID, created.ownerAccountId());
        assertEquals("us-west-2", created.primaryRegion());
        assertEquals("ACTIVE", created.status());
        assertTrue(created.accountInstance());
        assertTrue(created.instanceArn().matches("arn:aws:sso:::instance/ssoins-[0-9a-f]{16}"));
        assertTrue(created.identityStoreId().matches("d-[0-9a-f]{10}"));
        ObjectNode describeRequest = mapper.createObjectNode().put("InstanceArn", created.instanceArn());
        assertEquals(created, emptyService.describeInstance(describeRequest));
        assertEquals(created.instanceArn(), emptyService.createInstance(request, ACCOUNT_ID, "us-west-2").instanceArn());
        assertEquals(1, emptyService.listInstances(ACCOUNT_ID).size());

        ObjectNode rename = mapper.createObjectNode();
        rename.put("InstanceArn", created.instanceArn());
        rename.put("Name", "RenamedInstance");
        InstanceUpdateState renamed = emptyService.updateInstance(rename, ACCOUNT_ID);
        assertEquals("RenamedInstance", renamed.name());
        assertFalse(renamed.permissionSetsEnabled());

        ObjectNode enablePermissionSets = mapper.createObjectNode();
        enablePermissionSets.put("InstanceArn", created.instanceArn());
        enablePermissionSets.put("PermissionSetsEnabled", true);
        assertTrue(emptyService.updateInstance(enablePermissionSets, ACCOUNT_ID).permissionSetsEnabled());
        assertEquals(created.instanceArn(), emptyService.createInstance(request, ACCOUNT_ID, "us-west-2").instanceArn());

        ObjectNode disablePermissionSets = enablePermissionSets.deepCopy().put("PermissionSetsEnabled", false);
        assertError("ValidationException", () -> emptyService.updateInstance(disablePermissionSets, ACCOUNT_ID));

        ObjectNode mismatch = request.deepCopy();
        mismatch.put("Name", "DifferentName");
        assertError("IdempotentParameterMismatch",
                () -> emptyService.createInstance(mismatch, ACCOUNT_ID, "us-west-2"));
    }

    @Test
    void updateInstanceValidatesEncryptionConfiguration() {
        ObjectNode customerManaged = mapper.createObjectNode();
        customerManaged.put("InstanceArn", service.getInstanceArn());
        customerManaged.putObject("EncryptionConfiguration")
                .put("KeyType", "CUSTOMER_MANAGED_KEY")
                .put("KmsKeyArn", "arn:aws:kms:us-east-1:123456789012:key/12345678-1234-1234-1234-1234567890ab");
        InstanceUpdateState encrypted = service.updateInstance(customerManaged, ACCOUNT_ID);
        assertEquals("CUSTOMER_MANAGED_KEY", encrypted.keyType());
        assertEquals("ENABLED", encrypted.encryptionStatus());

        ObjectNode both = customerManaged.deepCopy().put("PermissionSetsEnabled", true);
        assertError("ValidationException", () -> service.updateInstance(both, ACCOUNT_ID));

        ObjectNode missingKmsArn = mapper.createObjectNode();
        missingKmsArn.put("InstanceArn", service.getInstanceArn());
        missingKmsArn.putObject("EncryptionConfiguration").put("KeyType", "CUSTOMER_MANAGED_KEY");
        assertError("ValidationException", () -> service.updateInstance(missingKmsArn, ACCOUNT_ID));

        ObjectNode wrongRegion = customerManaged.deepCopy();
        wrongRegion.withObject("EncryptionConfiguration")
                .put("KmsKeyArn", "arn:aws:kms:us-west-2:123456789012:key/12345678-1234-1234-1234-1234567890ab");
        assertError("ValidationException", () -> service.updateInstance(wrongRegion, ACCOUNT_ID));

        ObjectNode wrongAccount = customerManaged.deepCopy();
        wrongAccount.withObject("EncryptionConfiguration")
                .put("KmsKeyArn", "arn:aws:kms:us-east-1:999999999999:key/12345678-1234-1234-1234-1234567890ab");
        assertError("ValidationException", () -> service.updateInstance(wrongAccount, ACCOUNT_ID));

        assertError("AccessDeniedException", () -> service.updateInstance(customerManaged, "999999999999"));

        ObjectNode awsOwnedWithArn = mapper.createObjectNode();
        awsOwnedWithArn.put("InstanceArn", service.getInstanceArn());
        awsOwnedWithArn.putObject("EncryptionConfiguration")
                .put("KeyType", "AWS_OWNED_KMS_KEY")
                .put("KmsKeyArn", "arn:aws:kms:us-east-1:123456789012:key/12345678-1234-1234-1234-1234567890ab");
        assertError("ValidationException", () -> service.updateInstance(awsOwnedWithArn, ACCOUNT_ID));
    }

    @Test
    void createInstanceEnforcesSingletonAndValidatesInputs() {
        ObjectNode duplicate = mapper.createObjectNode();
        duplicate.put("Name", "SecondInstance");
        assertError("ServiceQuotaExceededException",
                () -> service.createInstance(duplicate, ACCOUNT_ID, "us-east-1"));

        SsoAdminService emptyService = emptyService();
        ObjectNode invalidName = mapper.createObjectNode();
        invalidName.put("Name", "bad name");
        assertError("ValidationException",
                () -> emptyService.createInstance(invalidName, ACCOUNT_ID, "us-east-1"));

        ObjectNode invalidToken = mapper.createObjectNode();
        invalidToken.put("ClientToken", "bad token");
        assertError("ValidationException",
                () -> emptyService.createInstance(invalidToken, ACCOUNT_ID, "us-east-1"));

        ObjectNode nonStringToken = mapper.createObjectNode();
        nonStringToken.put("ClientToken", 123);
        assertError("ValidationException",
                () -> emptyService.createInstance(nonStringToken, ACCOUNT_ID, "us-east-1"));

        ObjectNode reservedTag = mapper.createObjectNode();
        reservedTag.putArray("Tags").addObject().put("Key", "aws:reserved").put("Value", "x");
        assertError("ValidationException",
                () -> emptyService.createInstance(reservedTag, ACCOUNT_ID, "us-east-1"));
    }

    @Test
    void createInstanceRejectsOrganizationsManagementAccounts() {
        org.mockito.Mockito.when(organizationsService.isManagementAccount(ACCOUNT_ID)).thenReturn(true);
        SsoAdminService emptyService = emptyService();

        assertError("AccessDeniedException",
                () -> emptyService.createInstance(mapper.createObjectNode(), ACCOUNT_ID, "us-east-1"));
    }

    @Test
    void describeApplicationReturnsPersistedApplicationAndValidatesArn() {
        SsoApplication application = createApplication("Describe App", "describe-app-token");
        ObjectNode request = mapper.createObjectNode();
        request.put("ApplicationArn", application.applicationArn());
        assertEquals(application, service.describeApplication(request));

        ObjectNode missing = mapper.createObjectNode();
        missing.put("ApplicationArn", "arn:aws:sso::123456789012:application/ssoins-7223b02a5d9f7c8e/apl-1111111111111111");
        assertError("ResourceNotFoundException", () -> service.describeApplication(missing));

        ObjectNode malformed = mapper.createObjectNode();
        malformed.put("ApplicationArn", "not-an-arn");
        assertError("ValidationException", () -> service.describeApplication(malformed));
    }

    @Test
    void listApplicationsSupportsFiltersPaginationAndMemberAccountIsolation() {
        SsoApplication first = createApplication("List Apps One", "list-apps-one-token");
        SsoApplication second = createApplication("List Apps Two", "list-apps-two-token");
        ObjectNode request = mapper.createObjectNode();
        request.put("InstanceArn", service.getInstanceArn());
        request.put("MaxResults", 1);
        var firstPage = service.listApplications(request, ACCOUNT_ID);
        assertEquals(1, firstPage.items().size());
        assertNotNull(firstPage.nextToken());
        request.put("NextToken", firstPage.nextToken());
        assertEquals(1, service.listApplications(request, ACCOUNT_ID).items().size());

        ObjectNode providerFilter = mapper.createObjectNode();
        providerFilter.put("InstanceArn", service.getInstanceArn());
        providerFilter.putObject("Filter")
                .put("ApplicationProvider", "arn:aws:sso::aws:applicationProvider/custom")
                .put("ApplicationAccount", ACCOUNT_ID);
        assertTrue(service.listApplications(providerFilter, ACCOUNT_ID).items().containsAll(java.util.List.of(first, second)));

        ObjectNode memberWithoutFilter = mapper.createObjectNode().put("InstanceArn", service.getInstanceArn());
        assertError("AccessDeniedException",
                () -> service.listApplications(memberWithoutFilter, "222233334444"));
        memberWithoutFilter.putObject("Filter").put("ApplicationAccount", "222233334444");
        assertTrue(service.listApplications(memberWithoutFilter, "222233334444").items().isEmpty());
    }

    @Test
    void deleteApplicationRemovesApplicationAssignmentsAndIdempotencyReferences() {
        ObjectNode createRequest = mapper.createObjectNode();
        createRequest.put("InstanceArn", service.getInstanceArn());
        createRequest.put("ApplicationProviderArn", "arn:aws:sso::aws:applicationProvider/custom");
        createRequest.put("Name", "DeleteMeApplication");
        createRequest.put("ClientToken", "delete-app-token");
        SsoApplication application = service.createApplication(createRequest, ACCOUNT_ID, "us-east-1");

        ObjectNode assignment = mapper.createObjectNode();
        assignment.put("ApplicationArn", application.applicationArn());
        assignment.put("PrincipalId", PRINCIPAL_ID);
        assignment.put("PrincipalType", "GROUP");
        service.createApplicationAssignment(assignment);

        service.deleteApplication(application.applicationArn());
        assertError("ResourceNotFoundException", () -> service.getApplication(application.applicationArn()));
        assertError("ResourceNotFoundException", () -> service.createApplicationAssignment(assignment));

        SsoApplication recreated = service.createApplication(createRequest, ACCOUNT_ID, "us-east-1");
        assertFalse(recreated.applicationArn().equals(application.applicationArn()));
        assertError("ResourceNotFoundException", () -> service.deleteApplication(application.applicationArn()));
    }

    @Test
    void getApplicationAssignmentConfigurationDefaultsToAssignmentsRequired() {
        SsoApplication application = createApplication("Assignment Config App", "assignment-config-app-token");
        ObjectNode request = mapper.createObjectNode();
        request.put("ApplicationArn", application.applicationArn());

        assertTrue(service.getApplicationAssignmentConfiguration(request));

        ObjectNode malformed = mapper.createObjectNode().put("ApplicationArn", "not-an-arn");
        assertError("ValidationException", () -> service.getApplicationAssignmentConfiguration(malformed));

        ObjectNode missing = mapper.createObjectNode();
        missing.put("ApplicationArn",
                "arn:aws:sso::123456789012:application/ssoins-7223b02a5d9f7c8e/apl-1111111111111111");
        assertError("ResourceNotFoundException", () -> service.getApplicationAssignmentConfiguration(missing));
    }

    @Test
    void putApplicationAssignmentConfigurationPersistsExplicitAccessRequirement() {
        SsoApplication application = createApplication("Put Assignment Config App", "put-assignment-config-token");
        ObjectNode request = mapper.createObjectNode();
        request.put("ApplicationArn", application.applicationArn());
        request.put("AssignmentRequired", false);

        service.putApplicationAssignmentConfiguration(request);
        assertFalse(service.getApplicationAssignmentConfiguration(request));

        request.put("AssignmentRequired", true);
        service.putApplicationAssignmentConfiguration(request);
        assertTrue(service.getApplicationAssignmentConfiguration(request));

        ObjectNode missingValue = mapper.createObjectNode().put("ApplicationArn", application.applicationArn());
        assertError("ValidationException", () -> service.putApplicationAssignmentConfiguration(missingValue));

        ObjectNode wrongType = mapper.createObjectNode();
        wrongType.put("ApplicationArn", application.applicationArn());
        wrongType.put("AssignmentRequired", "false");
        assertError("ValidationException", () -> service.putApplicationAssignmentConfiguration(wrongType));
    }

    @Test
    void putApplicationAccessScopeCreatesAndUpdatesAuthorizedTargets() {
        SsoApplication application = createApplication("Put Scope App", "put-scope-app-token");
        ObjectNode request = mapper.createObjectNode();
        request.put("ApplicationArn", application.applicationArn());
        request.put("Scope", "api:read");
        request.putArray("AuthorizedTargets").add(service.getInstanceArn());

        ApplicationAccessScope created = service.putApplicationAccessScope(request);
        assertEquals("api:read", created.scope());
        assertEquals(java.util.List.of(service.getInstanceArn()), created.authorizedTargets());
        assertEquals(created, service.getApplicationAccessScope(request));

        request.putArray("AuthorizedTargets").add(application.applicationArn());
        ApplicationAccessScope updated = service.putApplicationAccessScope(request);
        assertEquals(java.util.List.of(application.applicationArn()), updated.authorizedTargets());

        ObjectNode invalid = request.deepCopy();
        invalid.putArray("AuthorizedTargets").add("not-an-arn");
        assertError("ValidationException", () -> service.putApplicationAccessScope(invalid));
    }

    @Test
    void listApplicationAccessScopesPaginatesScopesForTheRequestedApplication() {
        SsoApplication application = createApplication("List Scope App", "list-scope-app-token");
        SsoApplication otherApplication = createApplication("Other Scope App", "other-scope-app-token");
        for (String scope : java.util.List.of("api:read", "api:write")) {
            ObjectNode put = mapper.createObjectNode();
            put.put("ApplicationArn", application.applicationArn());
            put.put("Scope", scope);
            put.putArray("AuthorizedTargets").add(service.getInstanceArn());
            service.putApplicationAccessScope(put);
        }
        ObjectNode other = mapper.createObjectNode();
        other.put("ApplicationArn", otherApplication.applicationArn());
        other.put("Scope", "other:read");
        other.putArray("AuthorizedTargets").add(service.getInstanceArn());
        service.putApplicationAccessScope(other);

        ObjectNode request = mapper.createObjectNode();
        request.put("ApplicationArn", application.applicationArn());
        request.put("MaxResults", 1);
        var first = service.listApplicationAccessScopes(request);
        assertEquals(1, first.items().size());
        assertEquals("api:read", first.items().get(0).scope());
        assertEquals(java.util.List.of(service.getInstanceArn()), first.items().get(0).authorizedTargets());
        assertNotNull(first.nextToken());

        request.put("NextToken", first.nextToken());
        var second = service.listApplicationAccessScopes(request);
        assertEquals(1, second.items().size());
        assertEquals("api:write", second.items().get(0).scope());
        assertNull(second.nextToken());

        ObjectNode invalidMaxResults = mapper.createObjectNode();
        invalidMaxResults.put("ApplicationArn", application.applicationArn());
        invalidMaxResults.put("MaxResults", 11);
        assertError("ValidationException", () -> service.listApplicationAccessScopes(invalidMaxResults));

        ObjectNode missing = mapper.createObjectNode();
        missing.put("ApplicationArn",
                "arn:aws:sso::123456789012:application/ssoins-7223b02a5d9f7c8e/apl-1111111111111111");
        assertError("ResourceNotFoundException", () -> service.listApplicationAccessScopes(missing));
    }

    @Test
    void deleteApplicationAccessScopeDeletesStoredScopeAndValidatesRequest() {
        SsoApplication application = createApplication("Scope App", "scope-app-token");
        String scope = "api:read";
        String key = SsoAdminService.applicationAccessScopeKey(application.applicationArn(), scope);
        applicationAccessScopes.put(key, new ApplicationAccessScope(
                application.applicationArn(), scope, java.util.List.of(service.getInstanceArn())));

        ObjectNode request = mapper.createObjectNode();
        request.put("ApplicationArn", application.applicationArn());
        request.put("Scope", scope);
        service.deleteApplicationAccessScope(request);
        assertTrue(applicationAccessScopes.get(key).isEmpty());
        assertError("ResourceNotFoundException", () -> service.deleteApplicationAccessScope(request));

        ObjectNode invalid = request.deepCopy();
        invalid.put("Scope", "bad scope");
        assertError("ValidationException", () -> service.deleteApplicationAccessScope(invalid));
    }

    @Test
    void getApplicationAuthenticationMethodReturnsStoredIamMethod() {
        SsoApplication application = createApplication("Get Authentication App", "get-authentication-app-token");
        String key = SsoAdminService.applicationAuthenticationMethodKey(application.applicationArn(), "IAM");
        ObjectNode method = mapper.createObjectNode();
        method.putObject("Iam").putObject("ActorPolicy").put("Version", "2012-10-17");
        applicationAuthenticationMethods.put(key, new ApplicationAuthenticationMethod(
                application.applicationArn(), "IAM", method));

        ObjectNode request = mapper.createObjectNode();
        request.put("ApplicationArn", application.applicationArn());
        request.put("AuthenticationMethodType", "IAM");
        assertEquals(method, service.getApplicationAuthenticationMethod(request).authenticationMethod());

        ObjectNode invalidType = request.deepCopy();
        invalidType.put("AuthenticationMethodType", "SAML");
        assertError("ValidationException", () -> service.getApplicationAuthenticationMethod(invalidType));

        applicationAuthenticationMethods.delete(key);
        assertError("ResourceNotFoundException", () -> service.getApplicationAuthenticationMethod(request));
    }

    @Test
    void listApplicationAuthenticationMethodsReturnsOnlyMethodsForRequestedApplication() {
        SsoApplication application = createApplication("List Authentication App", "list-authentication-app-token");
        SsoApplication otherApplication = createApplication("Other Authentication App", "other-authentication-app-token");
        ObjectNode method = mapper.createObjectNode();
        method.putObject("Iam").putObject("ActorPolicy").put("Version", "2012-10-17");
        applicationAuthenticationMethods.put(
                SsoAdminService.applicationAuthenticationMethodKey(application.applicationArn(), "IAM"),
                new ApplicationAuthenticationMethod(application.applicationArn(), "IAM", method));
        applicationAuthenticationMethods.put(
                SsoAdminService.applicationAuthenticationMethodKey(otherApplication.applicationArn(), "IAM"),
                new ApplicationAuthenticationMethod(otherApplication.applicationArn(), "IAM", method.deepCopy()));

        ObjectNode request = mapper.createObjectNode().put("ApplicationArn", application.applicationArn());
        var result = service.listApplicationAuthenticationMethods(request);
        assertEquals(1, result.items().size());
        assertEquals("IAM", result.items().get(0).authenticationMethodType());
        assertEquals(method, result.items().get(0).authenticationMethod());
        assertNull(result.nextToken());

        ObjectNode badToken = request.deepCopy().put("NextToken", "***");
        assertError("ValidationException", () -> service.listApplicationAuthenticationMethods(badToken));
    }

    @Test
    void putApplicationAuthenticationMethodCreatesAndUpdatesIamActorPolicy() {
        SsoApplication application = createApplication("Put Authentication App", "put-authentication-app-token");
        ObjectNode request = mapper.createObjectNode();
        request.put("ApplicationArn", application.applicationArn());
        request.put("AuthenticationMethodType", "IAM");
        ObjectNode method = request.putObject("AuthenticationMethod");
        method.putObject("Iam").putObject("ActorPolicy").put("Version", "2012-10-17");

        service.putApplicationAuthenticationMethod(request);
        var stored = service.getApplicationAuthenticationMethod(request);
        assertEquals(method, stored.authenticationMethod());

        method.withObject("Iam").withObject("ActorPolicy").put("Id", "updated");
        service.putApplicationAuthenticationMethod(request);
        assertEquals("updated", service.getApplicationAuthenticationMethod(request)
                .authenticationMethod().path("Iam").path("ActorPolicy").path("Id").asText());

        ObjectNode missingActorPolicy = request.deepCopy();
        ((ObjectNode) missingActorPolicy.path("AuthenticationMethod").path("Iam")).remove("ActorPolicy");
        assertError("ValidationException", () -> service.putApplicationAuthenticationMethod(missingActorPolicy));

        ObjectNode wrongUnion = request.deepCopy();
        ((ObjectNode) wrongUnion.path("AuthenticationMethod")).set("Other", mapper.createObjectNode());
        assertError("ValidationException", () -> service.putApplicationAuthenticationMethod(wrongUnion));
    }

    @Test
    void iamActorPolicyAllowsWildcardAndAccountRootWithExplicitDenyPrecedence() {
        SsoApplication application = createApplication("Actor Policy App", "actor-policy-app-token");
        String key = SsoAdminService.applicationAuthenticationMethodKey(application.applicationArn(), "IAM");
        ObjectNode method = mapper.createObjectNode();
        ObjectNode policy = method.putObject("Iam").putObject("ActorPolicy");
        policy.put("Version", "2012-10-17");
        ObjectNode allow = policy.putArray("Statement").addObject();
        allow.put("Effect", "Allow");
        allow.put("Principal", "*");
        allow.put("Action", "sso-oauth:CreateTokenWithIAM");
        allow.put("Resource", "*");
        applicationAuthenticationMethods.put(key,
                new ApplicationAuthenticationMethod(application.applicationArn(), "IAM", method));
        assertTrue(service.iamActorPolicyAllows(application.applicationArn(), ACCOUNT_ID));

        ObjectNode deny = policy.withArray("Statement").addObject();
        deny.put("Effect", "Deny");
        deny.putObject("Principal").put("AWS", "arn:aws:iam::" + ACCOUNT_ID + ":root");
        deny.put("Action", "sso-oauth:CreateTokenWithIAM");
        deny.put("Resource", "*");
        applicationAuthenticationMethods.put(key,
                new ApplicationAuthenticationMethod(application.applicationArn(), "IAM", method.deepCopy()));
        assertFalse(service.iamActorPolicyAllows(application.applicationArn(), ACCOUNT_ID));
        assertTrue(service.iamActorPolicyAllows(application.applicationArn(), "999999999999"));
    }
    @Test
    void deleteApplicationAuthenticationMethodDeletesIamMethodAndValidatesType() {
        SsoApplication application = createApplication("Authentication App", "authentication-app-token");
        String key = SsoAdminService.applicationAuthenticationMethodKey(application.applicationArn(), "IAM");
        ObjectNode method = mapper.createObjectNode();
        method.putObject("Iam").putObject("ActorPolicy").put("Version", "2012-10-17");
        applicationAuthenticationMethods.put(key, new ApplicationAuthenticationMethod(
                application.applicationArn(), "IAM", method));

        ObjectNode request = mapper.createObjectNode();
        request.put("ApplicationArn", application.applicationArn());
        request.put("AuthenticationMethodType", "IAM");
        service.deleteApplicationAuthenticationMethod(request);
        assertTrue(applicationAuthenticationMethods.get(key).isEmpty());
        assertError("ResourceNotFoundException", () -> service.deleteApplicationAuthenticationMethod(request));

        ObjectNode invalidType = request.deepCopy();
        invalidType.put("AuthenticationMethodType", "SAML");
        assertError("ValidationException", () -> service.deleteApplicationAuthenticationMethod(invalidType));
    }

    @Test
    void putApplicationSessionConfigurationUpdatesBackgroundSessionStatus() {
        SsoApplication application = createApplication("Put Session Config App", "put-session-config-token");
        ObjectNode request = mapper.createObjectNode().put("ApplicationArn", application.applicationArn());
        request.put("UserBackgroundSessionApplicationStatus", "ENABLED");

        service.putApplicationSessionConfiguration(request);
        assertEquals("ENABLED", service.getApplicationSessionConfiguration(request));

        request.put("UserBackgroundSessionApplicationStatus", "DISABLED");
        service.putApplicationSessionConfiguration(request);
        assertEquals("DISABLED", service.getApplicationSessionConfiguration(request));

        ObjectNode omitted = mapper.createObjectNode().put("ApplicationArn", application.applicationArn());
        service.putApplicationSessionConfiguration(omitted);
        assertEquals("DISABLED", service.getApplicationSessionConfiguration(omitted));

        ObjectNode invalid = request.deepCopy().put("UserBackgroundSessionApplicationStatus", "UNKNOWN");
        assertError("ValidationException", () -> service.putApplicationSessionConfiguration(invalid));
    }

    @Test
    void getApplicationSessionConfigurationDefaultsCustomApplicationsToDisabled() {
        SsoApplication application = createApplication("Session Config App", "session-config-app-token");
        ObjectNode request = mapper.createObjectNode().put("ApplicationArn", application.applicationArn());

        assertEquals("DISABLED", service.getApplicationSessionConfiguration(request));

        ObjectNode malformed = mapper.createObjectNode().put("ApplicationArn", "not-an-arn");
        assertError("ValidationException", () -> service.getApplicationSessionConfiguration(malformed));

        ObjectNode missing = mapper.createObjectNode();
        missing.put("ApplicationArn",
                "arn:aws:sso::123456789012:application/ssoins-7223b02a5d9f7c8e/apl-1111111111111111");
        assertError("ResourceNotFoundException", () -> service.getApplicationSessionConfiguration(missing));
    }

    @Test
    void listApplicationGrantsReturnsOnlyGrantsForRequestedApplication() {
        SsoApplication application = createApplication("List Grant App", "list-grant-app-token");
        SsoApplication otherApplication = createApplication("Other Grant App", "other-grant-app-token");
        ObjectNode authorizationCode = mapper.createObjectNode();
        authorizationCode.putObject("AuthorizationCode").putArray("RedirectUris").add("https://example.com/callback");
        applicationGrants.put(SsoAdminService.applicationGrantKey(application.applicationArn(), "authorization_code"),
                new ApplicationGrant(application.applicationArn(), "authorization_code", authorizationCode));
        ObjectNode refreshToken = mapper.createObjectNode();
        refreshToken.putObject("RefreshToken");
        applicationGrants.put(SsoAdminService.applicationGrantKey(application.applicationArn(), "refresh_token"),
                new ApplicationGrant(application.applicationArn(), "refresh_token", refreshToken));
        applicationGrants.put(SsoAdminService.applicationGrantKey(otherApplication.applicationArn(), "refresh_token"),
                new ApplicationGrant(otherApplication.applicationArn(), "refresh_token", refreshToken.deepCopy()));

        ObjectNode request = mapper.createObjectNode().put("ApplicationArn", application.applicationArn());
        var result = service.listApplicationGrants(request);
        assertEquals(2, result.items().size());
        assertEquals("authorization_code", result.items().get(0).grantType());
        assertEquals("refresh_token", result.items().get(1).grantType());
        assertNull(result.nextToken());

        ObjectNode badToken = request.deepCopy().put("NextToken", "***");
        assertError("ValidationException", () -> service.listApplicationGrants(badToken));
    }

    @Test
    void putApplicationGrantValidatesUnionAndPersistsSupportedGrantTypes() {
        SsoApplication application = createApplication("Put Grant App", "put-grant-app-token");
        ObjectNode request = mapper.createObjectNode();
        request.put("ApplicationArn", application.applicationArn());
        request.put("GrantType", "authorization_code");
        request.putObject("Grant").putObject("AuthorizationCode")
                .putArray("RedirectUris").add("https://example.com/callback");

        service.putApplicationGrant(request);
        assertEquals("https://example.com/callback", service.getApplicationGrant(request)
                .grant().path("AuthorizationCode").path("RedirectUris").get(0).asText());

        ObjectNode refresh = mapper.createObjectNode();
        refresh.put("ApplicationArn", application.applicationArn());
        refresh.put("GrantType", "refresh_token");
        refresh.putObject("Grant").putObject("RefreshToken");
        service.putApplicationGrant(refresh);
        assertTrue(service.getApplicationGrant(refresh).grant().has("RefreshToken"));

        ObjectNode tokenExchange = mapper.createObjectNode();
        tokenExchange.put("ApplicationArn", application.applicationArn());
        tokenExchange.put("GrantType", "urn:ietf:params:oauth:grant-type:token-exchange");
        tokenExchange.putObject("Grant").putObject("TokenExchange");
        service.putApplicationGrant(tokenExchange);
        assertTrue(service.getApplicationGrant(tokenExchange).grant().has("TokenExchange"));

        ObjectNode jwt = mapper.createObjectNode();
        jwt.put("ApplicationArn", application.applicationArn());
        jwt.put("GrantType", "urn:ietf:params:oauth:grant-type:jwt-bearer");
        ObjectNode issuer = jwt.putObject("Grant").putObject("JwtBearer")
                .putArray("AuthorizedTokenIssuers").addObject();
        issuer.put("TrustedTokenIssuerArn",
                "arn:aws:sso::123456789012:trustedTokenIssuer/ssoins-7223b02a5d9f7c8e/tti-11111111-2222-3333-4444-555555555555");
        issuer.putArray("AuthorizedAudiences").add("api://example");
        service.putApplicationGrant(jwt);
        assertTrue(service.getApplicationGrant(jwt).grant().has("JwtBearer"));

        ObjectNode mismatched = request.deepCopy();
        mismatched.set("Grant", mapper.createObjectNode().set("RefreshToken", mapper.createObjectNode()));
        assertError("ValidationException", () -> service.putApplicationGrant(mismatched));

        ObjectNode missingRedirects = request.deepCopy();
        missingRedirects.set("Grant", mapper.createObjectNode().set("AuthorizationCode", mapper.createObjectNode()));
        assertError("ValidationException", () -> service.putApplicationGrant(missingRedirects));
    }

    @Test
    void getApplicationGrantReturnsStoredGrantAndValidatesType() {
        SsoApplication application = createApplication("Get Grant App", "get-grant-app-token");
        String grantType = "authorization_code";
        String key = SsoAdminService.applicationGrantKey(application.applicationArn(), grantType);
        ObjectNode grant = mapper.createObjectNode();
        grant.putObject("AuthorizationCode").putArray("RedirectUris").add("https://example.com/callback");
        applicationGrants.put(key, new ApplicationGrant(application.applicationArn(), grantType, grant));

        ObjectNode request = mapper.createObjectNode();
        request.put("ApplicationArn", application.applicationArn());
        request.put("GrantType", grantType);
        assertEquals(grant, service.getApplicationGrant(request).grant());

        ObjectNode invalidType = request.deepCopy();
        invalidType.put("GrantType", "client_credentials");
        assertError("ValidationException", () -> service.getApplicationGrant(invalidType));

        applicationGrants.delete(key);
        assertError("ResourceNotFoundException", () -> service.getApplicationGrant(request));
    }

    @Test
    void deleteApplicationGrantDeletesConfiguredGrantAndValidatesGrantType() {
        SsoApplication application = createApplication("Grant App", "grant-app-token");
        String grantType = "authorization_code";
        String key = SsoAdminService.applicationGrantKey(application.applicationArn(), grantType);
        ObjectNode grant = mapper.createObjectNode();
        grant.putObject("AuthorizationCode");
        applicationGrants.put(key, new ApplicationGrant(application.applicationArn(), grantType, grant));

        ObjectNode request = mapper.createObjectNode();
        request.put("ApplicationArn", application.applicationArn());
        request.put("GrantType", grantType);
        service.deleteApplicationGrant(request);
        assertTrue(applicationGrants.get(key).isEmpty());
        assertError("ResourceNotFoundException", () -> service.deleteApplicationGrant(request));

        ObjectNode invalidType = request.deepCopy();
        invalidType.put("GrantType", "client_credentials");
        assertError("ValidationException", () -> service.deleteApplicationGrant(invalidType));
    }

    @Test
    void createApplicationAssignmentValidatesApplicationPrincipalAndDuplicates() {
        SsoApplication application = createApplication("Assignment App", "assignment-app-token");
        ObjectNode request = mapper.createObjectNode();
        request.put("ApplicationArn", application.applicationArn());
        request.put("PrincipalId", PRINCIPAL_ID);
        request.put("PrincipalType", "GROUP");

        ApplicationAssignment assignment = service.createApplicationAssignment(request);
        assertEquals(application.applicationArn(), assignment.applicationArn());
        assertEquals(PRINCIPAL_ID, assignment.principalId());
        assertEquals("GROUP", assignment.principalType());
        assertError("ConflictException", () -> service.createApplicationAssignment(request));

        ObjectNode invalidPrincipalType = request.deepCopy();
        invalidPrincipalType.put("PrincipalType", "ROLE");
        invalidPrincipalType.put("PrincipalId", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        assertError("ValidationException", () -> service.createApplicationAssignment(invalidPrincipalType));

        ObjectNode missingApplication = request.deepCopy();
        missingApplication.put("ApplicationArn", "arn:aws:sso::123456789012:application/ssoins-7223b02a5d9f7c8e/apl-1111111111111111");
        assertError("ResourceNotFoundException", () -> service.createApplicationAssignment(missingApplication));
    }

    @Test
    void describeApplicationAssignmentReturnsDirectAssignmentAndValidatesRequest() {
        SsoApplication application = createApplication("Describe Assignment App", "describe-assignment-app-token");
        ObjectNode request = mapper.createObjectNode();
        request.put("ApplicationArn", application.applicationArn());
        request.put("PrincipalId", PRINCIPAL_ID);
        request.put("PrincipalType", "GROUP");
        ApplicationAssignment created = service.createApplicationAssignment(request);

        assertEquals(created, service.describeApplicationAssignment(request));

        ObjectNode wrongType = request.deepCopy();
        wrongType.put("PrincipalType", "USER");
        assertError("ResourceNotFoundException", () -> service.describeApplicationAssignment(wrongType));

        ObjectNode invalidType = request.deepCopy();
        invalidType.put("PrincipalType", "ROLE");
        assertError("ValidationException", () -> service.describeApplicationAssignment(invalidType));

        ObjectNode invalidPrincipal = request.deepCopy();
        invalidPrincipal.put("PrincipalId", "not-a-guid");
        assertError("ValidationException", () -> service.describeApplicationAssignment(invalidPrincipal));
    }

    @Test
    void describeApplicationProviderReturnsCustomOauthProviderAndValidatesArn() {
        ObjectNode request = mapper.createObjectNode();
        request.put("ApplicationProviderArn", "arn:aws:sso::aws:applicationProvider/custom");
        assertEquals("arn:aws:sso::aws:applicationProvider/custom", service.describeApplicationProvider(request, "us-east-1"));

        ObjectNode missing = mapper.createObjectNode();
        missing.put("ApplicationProviderArn", "arn:aws:sso::aws:applicationProvider/example");
        assertError("ResourceNotFoundException", () -> service.describeApplicationProvider(missing, "us-east-1"));

        ObjectNode malformed = mapper.createObjectNode();
        malformed.put("ApplicationProviderArn", "not-an-arn");
        assertError("ValidationException", () -> service.describeApplicationProvider(malformed, "us-east-1"));
    }

    @Test
    void listApplicationProvidersReturnsCustomProviderAndValidatesPagination() {
        ObjectNode request = mapper.createObjectNode();
        var page = service.listApplicationProviders(request, "us-east-1");
        assertEquals(java.util.List.of("arn:aws:sso::aws:applicationProvider/custom"), page.items());
        assertEquals(null, page.nextToken());

        ObjectNode invalidMaxResults = mapper.createObjectNode().put("MaxResults", 101);
        assertError("ValidationException", () -> service.listApplicationProviders(invalidMaxResults, "us-east-1"));
    }

    @Test
    void listApplicationAssignmentsPaginatesAndScopesToApplication() {
        SsoApplication application = createApplication("List Assignment App", "list-assignment-app-token");
        ObjectNode user = mapper.createObjectNode();
        user.put("ApplicationArn", application.applicationArn());
        user.put("PrincipalId", "11111111-2222-3333-4444-555555555555");
        user.put("PrincipalType", "USER");
        service.createApplicationAssignment(user);
        ObjectNode group = user.deepCopy();
        group.put("PrincipalId", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        group.put("PrincipalType", "GROUP");
        service.createApplicationAssignment(group);

        ObjectNode request = mapper.createObjectNode();
        request.put("ApplicationArn", application.applicationArn());
        request.put("MaxResults", 1);
        var first = service.listApplicationAssignments(request);
        assertEquals(1, first.items().size());
        assertNotNull(first.nextToken());

        request.put("NextToken", first.nextToken());
        var second = service.listApplicationAssignments(request);
        assertEquals(1, second.items().size());
        assertNull(second.nextToken());

        ObjectNode missing = mapper.createObjectNode();
        missing.put("ApplicationArn", "arn:aws:sso::123456789012:application/ssoins-7223b02a5d9f7c8e/apl-1111111111111111");
        assertError("ResourceNotFoundException", () -> service.listApplicationAssignments(missing));
    }

    @Test
    void listApplicationAssignmentsForPrincipalIncludesGroupBasedUserAccessAndRequiresMemberFilter() {
        SsoApplication application = createApplication("Principal Assignment App", "principal-assignment-app-token");
        String groupId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        ObjectNode groupAssignment = mapper.createObjectNode();
        groupAssignment.put("ApplicationArn", application.applicationArn());
        groupAssignment.put("PrincipalId", groupId);
        groupAssignment.put("PrincipalType", "GROUP");
        service.createApplicationAssignment(groupAssignment);
        org.mockito.Mockito.when(identityStoreService.groupIdsForUser(service.getIdentityStoreId(), PRINCIPAL_ID))
                .thenReturn(Set.of(groupId));

        ObjectNode request = mapper.createObjectNode();
        request.put("InstanceArn", service.getInstanceArn());
        request.put("PrincipalId", PRINCIPAL_ID);
        request.put("PrincipalType", "USER");
        var page = service.listApplicationAssignmentsForPrincipal(request, ACCOUNT_ID);
        assertEquals(1, page.items().size());
        assertEquals(application.applicationArn(), page.items().get(0).applicationArn());
        assertEquals(PRINCIPAL_ID, page.items().get(0).principalId());
        assertEquals("USER", page.items().get(0).principalType());

        ObjectNode memberRequest = request.deepCopy();
        assertError("AccessDeniedException",
                () -> service.listApplicationAssignmentsForPrincipal(memberRequest, "222233334444"));
        memberRequest.putObject("Filter").put("ApplicationArn", application.applicationArn());
        assertEquals(1, service.listApplicationAssignmentsForPrincipal(memberRequest, "222233334444").items().size());
    }

    @Test
    void deleteApplicationAssignmentRevokesDirectAssignmentAndValidatesPrincipal() {
        SsoApplication application = createApplication("Delete Assignment App", "delete-assignment-app-token");
        ObjectNode request = mapper.createObjectNode();
        request.put("ApplicationArn", application.applicationArn());
        request.put("PrincipalId", PRINCIPAL_ID);
        request.put("PrincipalType", "USER");
        service.createApplicationAssignment(request);

        service.deleteApplicationAssignment(request);
        assertError("ResourceNotFoundException", () -> service.deleteApplicationAssignment(request));

        ObjectNode invalidType = request.deepCopy();
        invalidType.put("PrincipalType", "ROLE");
        assertError("ValidationException", () -> service.deleteApplicationAssignment(invalidType));

        ObjectNode invalidPrincipal = request.deepCopy();
        invalidPrincipal.put("PrincipalId", "not-a-guid");
        assertError("ValidationException", () -> service.deleteApplicationAssignment(invalidPrincipal));
    }

    @Test
    void createApplicationAssignmentEnforcesTheDocumentedGroupQuota() {
        SsoApplication application = createApplication("Group Quota App", "group-quota-app-token");
        for (int i = 0; i < 100; i++) {
            ObjectNode request = mapper.createObjectNode();
            request.put("ApplicationArn", application.applicationArn());
            request.put("PrincipalId", "00000000-0000-0000-0000-" + String.format("%012x", i));
            request.put("PrincipalType", "GROUP");
            service.createApplicationAssignment(request);
        }
        ObjectNode overQuota = mapper.createObjectNode();
        overQuota.put("ApplicationArn", application.applicationArn());
        overQuota.put("PrincipalId", "00000000-0000-0000-0000-000000000100");
        overQuota.put("PrincipalType", "GROUP");
        assertError("ServiceQuotaExceededException", () -> service.createApplicationAssignment(overQuota));
    }

    @Test
    void createApplicationSupportsOAuthProviderPortalOptionsTagsAndIdempotency() {
        ObjectNode request = mapper.createObjectNode();
        request.put("InstanceArn", service.getInstanceArn());
        request.put("ApplicationProviderArn", "arn:aws:sso::aws:applicationProvider/custom");
        request.put("Name", "Platform Portal");
        request.put("Description", "Platform OAuth application");
        request.put("ClientToken", "token-123456");
        request.put("Status", "DISABLED");
        request.putObject("PortalOptions").put("Visibility", "ENABLED")
                .putObject("SignInOptions").put("Origin", "APPLICATION").put("ApplicationUrl", "https://example.com/login");
        request.putArray("Tags").addObject().put("Key", "Environment").put("Value", "dev");

        SsoApplication created = service.createApplication(request, ACCOUNT_ID, "us-east-1");
        assertTrue(created.applicationArn().matches("arn:aws:sso::123456789012:application/ssoins-7223b02a5d9f7c8e/apl-[0-9a-f]{16}"));
        assertEquals("arn:aws:identitystore::123456789012:identitystore/d-9067f2a3c1", created.identityStoreArn());
        assertEquals("DISABLED", created.status());
        assertEquals("APPLICATION", created.portalOptions().signInOptions().origin());
        assertEquals(created.applicationArn(), service.createApplication(request, ACCOUNT_ID, "us-east-1").applicationArn());

        ObjectNode mismatch = request.deepCopy();
        mismatch.put("Name", "Different Name");
        assertError("IdempotentParameterMismatch", () -> service.createApplication(mismatch, ACCOUNT_ID, "us-east-1"));
    }

    @Test
    void updateApplicationChangesOnlyDocumentedMutableFieldsAndPreservesCreateIdempotency() {
        ObjectNode create = mapper.createObjectNode();
        create.put("InstanceArn", service.getInstanceArn());
        create.put("ApplicationProviderArn", "arn:aws:sso::aws:applicationProvider/custom");
        create.put("Name", "Original App");
        create.put("Description", "Original description");
        create.put("ClientToken", "update-app-token");
        create.put("Status", "ENABLED");
        create.putObject("PortalOptions").put("Visibility", "ENABLED")
                .putObject("SignInOptions").put("Origin", "IDENTITY_CENTER");
        SsoApplication created = service.createApplication(create, ACCOUNT_ID, "us-east-1");

        ObjectNode update = mapper.createObjectNode();
        update.put("ApplicationArn", created.applicationArn());
        update.put("Name", "Updated App");
        update.put("Description", "Updated description");
        update.put("Status", "DISABLED");
        update.putObject("PortalOptions").putObject("SignInOptions")
                .put("Origin", "APPLICATION").put("ApplicationUrl", "https://example.com/new-login");
        SsoApplication updated = service.updateApplication(update);
        assertEquals("Updated App", updated.name());
        assertEquals("Updated description", updated.description());
        assertEquals("DISABLED", updated.status());
        assertEquals("ENABLED", updated.portalOptions().visibility());
        assertEquals("APPLICATION", updated.portalOptions().signInOptions().origin());
        assertEquals("https://example.com/new-login", updated.portalOptions().signInOptions().applicationUrl());
        assertEquals("Updated App", service.describeApplication(update).name());
        assertEquals(created.applicationArn(), service.createApplication(create, ACCOUNT_ID, "us-east-1").applicationArn());

        ObjectNode invalidVisibility = mapper.createObjectNode().put("ApplicationArn", created.applicationArn());
        invalidVisibility.putObject("PortalOptions").put("Visibility", "DISABLED");
        assertError("ValidationException", () -> service.updateApplication(invalidVisibility));
    }

    @Test
    void createApplicationRejectsUnsupportedProvidersAndInvalidPortalOptions() {
        ObjectNode request = mapper.createObjectNode();
        request.put("InstanceArn", service.getInstanceArn());
        request.put("ApplicationProviderArn", "arn:aws:sso::aws:applicationProvider/custom");
        request.put("Name", "Portal");
        request.putObject("PortalOptions").putObject("SignInOptions").put("Origin", "APPLICATION");
        assertError("ValidationException", () -> service.createApplication(request, ACCOUNT_ID, "us-east-1"));

        request.remove("PortalOptions");
        request.putArray("Tags").addObject().put("Key", "bad*").put("Value", "value");
        assertError("ValidationException", () -> service.createApplication(request, ACCOUNT_ID, "us-east-1"));

        request.remove("Tags");
        request.put("ApplicationProviderArn", "arn:aws:sso::aws:applicationProvider/aws-managed");
        assertError("ResourceNotFoundException", () -> service.createApplication(request, ACCOUNT_ID, "us-east-1"));
    }

    @Test
    void addRegionValidatesAndRejectsDuplicates() {
        ObjectNode request = mapper.createObjectNode();
        request.put("InstanceArn", service.getInstanceArn());
        request.put("RegionName", "us-west-2");

        RegionMetadata created = service.addRegion(request);
        assertEquals("us-west-2", created.regionName());
        assertEquals("ADDING", created.status());
        assertFalse(created.primaryRegion());
        assertNotNull(created.addedDate());

        RegionMetadata described = service.describeRegion(request);
        assertEquals("us-west-2", described.regionName());
        assertEquals("ACTIVE", described.status());
        assertFalse(described.primaryRegion());

        ObjectNode listRequest = mapper.createObjectNode();
        listRequest.put("InstanceArn", service.getInstanceArn());
        listRequest.put("MaxResults", 1);
        var firstPage = service.listRegions(listRequest);
        assertEquals(1, firstPage.items().size());
        assertNotNull(firstPage.nextToken());
        listRequest.put("NextToken", firstPage.nextToken());
        assertEquals(1, service.listRegions(listRequest).items().size());

        ObjectNode primaryDescribe = mapper.createObjectNode();
        primaryDescribe.put("InstanceArn", service.getInstanceArn());
        primaryDescribe.put("RegionName", "us-east-1");
        assertTrue(service.describeRegion(primaryDescribe).primaryRegion());

        ObjectNode missingDescribe = request.deepCopy();
        missingDescribe.put("RegionName", "eu-west-3");
        assertError("ResourceNotFoundException", () -> service.describeRegion(missingDescribe));

        assertError("ConflictException", () -> service.addRegion(request));

        ObjectNode primaryRegion = request.deepCopy();
        primaryRegion.put("RegionName", "us-east-1");
        assertError("ConflictException", () -> service.addRegion(primaryRegion));

        ObjectNode invalidRegion = request.deepCopy();
        invalidRegion.put("RegionName", "invalid");
        assertError("ValidationException", () -> service.addRegion(invalidRegion));
    }

    @Test
    void removeRegionReturnsRemovingAndDeletesAdditionalRegion() {
        ObjectNode request = mapper.createObjectNode();
        request.put("InstanceArn", service.getInstanceArn());
        request.put("RegionName", "us-west-2");
        service.addRegion(request);

        RegionMetadata removing = service.removeRegion(request, "us-east-1");
        assertEquals("us-west-2", removing.regionName());
        assertEquals("REMOVING", removing.status());
        assertFalse(removing.primaryRegion());
        assertError("ResourceNotFoundException", () -> service.describeRegion(request));

        ObjectNode primary = request.deepCopy().put("RegionName", "us-east-1");
        assertError("ConflictException", () -> service.removeRegion(primary, "us-east-1"));

        ObjectNode missing = request.deepCopy().put("RegionName", "eu-west-3");
        assertError("ResourceNotFoundException", () -> service.removeRegion(missing, "us-east-1"));

        ObjectNode wrongRegion = request.deepCopy().put("RegionName", "eu-west-1");
        service.addRegion(wrongRegion);
        assertError("AccessDeniedException", () -> service.removeRegion(wrongRegion, "us-west-2"));
    }

    @Test
    void addRegionEnforcesTheDocumentedSixRegionQuotaIncludingPrimary() {
        for (String region : java.util.List.of("us-west-1", "us-west-2", "eu-west-1", "eu-central-1", "ap-south-1")) {
            ObjectNode request = mapper.createObjectNode();
            request.put("InstanceArn", service.getInstanceArn());
            request.put("RegionName", region);
            service.addRegion(request);
        }

        ObjectNode overQuota = mapper.createObjectNode();
        overQuota.put("InstanceArn", service.getInstanceArn());
        overQuota.put("RegionName", "ap-northeast-1");
        assertError("ServiceQuotaExceededException", () -> service.addRegion(overQuota));
    }

    @Test
    void listTagsForResourceReturnsPermissionSetTagsAndValidatesContext() {
        ObjectNode create = mapper.createObjectNode();
        create.put("InstanceArn", service.getInstanceArn());
        create.put("Name", "TaggedAdmins");
        var tags = create.putArray("Tags");
        tags.addObject().put("Key", "Environment").put("Value", "test");
        tags.addObject().put("Key", "Owner").put("Value", "platform");
        PermissionSet permissionSet = service.createPermissionSet(create);

        ObjectNode request = mapper.createObjectNode();
        request.put("ResourceArn", permissionSet.arn());
        request.put("InstanceArn", service.getInstanceArn());
        var page = service.listTagsForResource(request);
        assertEquals(2, page.items().size());
        assertEquals("Environment", page.items().get(0).getKey());
        assertEquals("test", page.items().get(0).getValue());
        assertEquals("Owner", page.items().get(1).getKey());
        assertNull(page.nextToken());

        ObjectNode tag = mapper.createObjectNode();
        tag.put("ResourceArn", permissionSet.arn());
        tag.put("InstanceArn", service.getInstanceArn());
        tag.putArray("Tags")
                .addObject().put("Key", "Environment").put("Value", "prod");
        service.tagResource(tag);
        var updated = service.listTagsForResource(request);
        assertEquals(2, updated.items().size());
        assertEquals("prod", updated.items().get(0).getValue());

        ObjectNode fillToQuota = mapper.createObjectNode();
        fillToQuota.put("ResourceArn", permissionSet.arn());
        var quotaTags = fillToQuota.putArray("Tags");
        for (int i = 0; i < 73; i++) {
            quotaTags.addObject().put("Key", "K" + i).put("Value", "v");
        }
        service.tagResource(fillToQuota);
        ObjectNode exceedQuota = mapper.createObjectNode();
        exceedQuota.put("ResourceArn", permissionSet.arn());
        exceedQuota.putArray("Tags").addObject().put("Key", "K73").put("Value", "v");
        assertError("ServiceQuotaExceededException", () -> service.tagResource(exceedQuota));

        ObjectNode missingTags = mapper.createObjectNode().put("ResourceArn", permissionSet.arn());
        assertError("ValidationException", () -> service.tagResource(missingTags));

        ObjectNode untag = mapper.createObjectNode();
        untag.put("ResourceArn", permissionSet.arn());
        untag.putArray("TagKeys").add("Environment").add("does-not-exist");
        service.untagResource(untag);
        assertTrue(service.listTagsForResource(request).items().stream()
                .noneMatch(entry -> "Environment".equals(entry.getKey())));

        ObjectNode missingTagKeys = mapper.createObjectNode().put("ResourceArn", permissionSet.arn());
        assertError("ValidationException", () -> service.untagResource(missingTagKeys));

        ObjectNode invalidToken = request.deepCopy();
        invalidToken.put("NextToken", "bad%token");
        assertError("ValidationException", () -> service.listTagsForResource(invalidToken));

        ObjectNode wrongInstance = request.deepCopy();
        wrongInstance.put("InstanceArn", "arn:aws:sso:::instance/ssoins-1111111111111111");
        assertError("ResourceNotFoundException", () -> service.listTagsForResource(wrongInstance));

        ObjectNode invalidResource = mapper.createObjectNode().put("ResourceArn", "arn:aws:s3:::bucket");
        assertError("ValidationException", () -> service.listTagsForResource(invalidResource));
    }

    @Test
    void attachesCustomerManagedPolicyReferencesWithAwsValidationRules() {
        PermissionSet permissionSet = createPermissionSet("CustomerPolicyAdmins");
        ObjectNode request = mapper.createObjectNode();
        request.put("InstanceArn", service.getInstanceArn());
        request.put("PermissionSetArn", permissionSet.arn());
        request.putObject("CustomerManagedPolicyReference").put("Name", "PlatformPolicy");

        service.attachCustomerManagedPolicyReference(request);

        ObjectNode list = mapper.createObjectNode();
        list.put("InstanceArn", service.getInstanceArn());
        list.put("PermissionSetArn", permissionSet.arn());
        assertEquals(1, service.listCustomerManagedPolicyReferences(list).items().size());
        assertEquals("PlatformPolicy", service.listCustomerManagedPolicyReferences(list).items().get(0).name());

        PermissionSet stored = service.getPermissionSet(service.getInstanceArn(), permissionSet.arn());
        assertEquals(1, stored.customerManagedPolicies().size());
        assertEquals("/", stored.customerManagedPolicies().values().iterator().next().path());

        ObjectNode caseInsensitiveDuplicate = request.deepCopy();
        caseInsensitiveDuplicate.withObject("CustomerManagedPolicyReference").put("Name", "platformpolicy");
        assertError("ConflictException", () -> service.attachCustomerManagedPolicyReference(caseInsensitiveDuplicate));

        ObjectNode invalidPath = request.deepCopy();
        invalidPath.withObject("CustomerManagedPolicyReference").put("Name", "OtherPolicy").put("Path", "missing-slash");
        assertError("ValidationException", () -> service.attachCustomerManagedPolicyReference(invalidPath));

        ObjectNode detach = request.deepCopy();
        detach.withObject("CustomerManagedPolicyReference").put("Name", "platformpolicy");
        service.detachCustomerManagedPolicyReference(detach);
        assertTrue(service.getPermissionSet(service.getInstanceArn(), permissionSet.arn()).customerManagedPolicies().isEmpty());
        assertError("ResourceNotFoundException", () -> service.detachCustomerManagedPolicyReference(detach));
    }

    @Test
    void customerManagedPoliciesShareTheDocumentedManagedPolicyQuota() {
        PermissionSet permissionSet = createPermissionSet("QuotaPolicyAdmins");
        for (int i = 0; i < 24; i++) {
            service.attachPolicy(service.getInstanceArn(), permissionSet.arn(),
                    "arn:aws:iam::aws:policy/TestPolicy" + i);
        }
        ObjectNode customer = mapper.createObjectNode();
        customer.put("InstanceArn", service.getInstanceArn());
        customer.put("PermissionSetArn", permissionSet.arn());
        customer.putObject("CustomerManagedPolicyReference").put("Name", "CustomerPolicy");
        service.attachCustomerManagedPolicyReference(customer);

        assertError("ServiceQuotaExceededException", () -> service.attachPolicy(service.getInstanceArn(), permissionSet.arn(),
                "arn:aws:iam::aws:policy/OverQuotaPolicy"));
    }

    @Test
    void managedPolicyPaginationHonorsMaxResultsAndNextToken() {
        PermissionSet permissionSet = createPermissionSet("PlatformAdmins");
        service.attachPolicy(service.getInstanceArn(), permissionSet.arn(), "arn:aws:iam::aws:policy/ReadOnlyAccess");
        service.attachPolicy(service.getInstanceArn(), permissionSet.arn(), "arn:aws:iam::aws:policy/SecurityAudit");
        service.attachPolicy(service.getInstanceArn(), permissionSet.arn(), "arn:aws:iam::aws:policy/ViewOnlyAccess");

        ObjectNode firstRequest = managedPoliciesRequest(permissionSet.arn());
        firstRequest.put("MaxResults", 1);
        var first = service.listManagedPolicies(firstRequest);

        assertEquals(1, first.items().size());
        assertNotNull(first.nextToken());

        ObjectNode secondRequest = managedPoliciesRequest(permissionSet.arn());
        secondRequest.put("MaxResults", 1);
        secondRequest.put("NextToken", first.nextToken());
        var second = service.listManagedPolicies(secondRequest);

        assertEquals(1, second.items().size());
        assertNotNull(second.nextToken());
        assertFalse(first.items().get(0).getKey().equals(second.items().get(0).getKey()));
    }

    @Test
    void managedPolicyPaginationRejectsInvalidInputs() {
        PermissionSet permissionSet = createPermissionSet("AuditAdmins");

        ObjectNode badLimit = managedPoliciesRequest(permissionSet.arn());
        badLimit.put("MaxResults", 101);
        assertError("ValidationException", () -> service.listManagedPolicies(badLimit));

        ObjectNode badToken = managedPoliciesRequest(permissionSet.arn());
        badToken.put("NextToken", "not%base64");
        assertError("ValidationException", () -> service.listManagedPolicies(badToken));
    }

    @Test
    void duplicateManagedPolicyReturnsConflict() {
        PermissionSet permissionSet = createPermissionSet("SecurityAdmins");
        String policyArn = "arn:aws:iam::aws:policy/SecurityAudit";
        service.attachPolicy(service.getInstanceArn(), permissionSet.arn(), policyArn);

        assertError("ConflictException",
                () -> service.attachPolicy(service.getInstanceArn(), permissionSet.arn(), policyArn));
    }

    @Test
    void deletePermissionSetRemovesAssignmentsAndProvisioningState() {
        PermissionSet permissionSet = createPermissionSet("DeletePermissionSetAdmins");
        service.createAssignment(assignmentRequest(permissionSet.arn()));
        assertFalse(service.listAssignments(service.getInstanceArn(), ACCOUNT_ID, permissionSet.arn()).isEmpty());

        service.deletePermissionSet(service.getInstanceArn(), permissionSet.arn());

        assertError("ResourceNotFoundException",
                () -> service.getPermissionSet(service.getInstanceArn(), permissionSet.arn()));
        assertTrue(service.listPermissionSetsProvisionedToAccount(mapper.createObjectNode()
                .put("InstanceArn", service.getInstanceArn())
                .put("AccountId", ACCOUNT_ID)).items().stream().noneMatch(permissionSet.arn()::equals));
        assertError("ResourceNotFoundException",
                () -> service.deletePermissionSet(service.getInstanceArn(), permissionSet.arn()));
    }

    @Test
    void listAccountAssignmentsForPrincipalFiltersAndPaginatesUserOrGroupAccess() {
        PermissionSet first = createPermissionSet("PrincipalListOne");
        PermissionSet second = createPermissionSet("PrincipalListTwo");
        service.createAssignment(assignmentRequest(first.arn()));
        service.createAssignment(assignmentRequest(second.arn()));

        ObjectNode request = mapper.createObjectNode();
        request.put("InstanceArn", service.getInstanceArn());
        request.put("PrincipalId", PRINCIPAL_ID);
        request.put("PrincipalType", "GROUP");
        request.put("MaxResults", 1);

        var firstPage = service.listAssignmentsForPrincipal(request, ACCOUNT_ID);
        assertEquals(1, firstPage.items().size());
        assertNotNull(firstPage.nextToken());

        request.put("NextToken", firstPage.nextToken());
        var secondPage = service.listAssignmentsForPrincipal(request, ACCOUNT_ID);
        assertEquals(1, secondPage.items().size());
        assertTrue(secondPage.nextToken() == null);

        request.remove("NextToken");
        request.remove("MaxResults");
        request.putObject("Filter").put("AccountId", ACCOUNT_ID);
        assertEquals(2, service.listAssignmentsForPrincipal(request, ACCOUNT_ID).items().size());

        ObjectNode invalidType = request.deepCopy();
        invalidType.put("PrincipalType", "ROLE");
        assertError("ValidationException", () -> service.listAssignmentsForPrincipal(invalidType, ACCOUNT_ID));

        SsoAdminService accountInstanceService = emptyService();
        SsoInstance accountInstance = accountInstanceService.createInstance(mapper.createObjectNode(), ACCOUNT_ID, "us-east-1");
        ObjectNode accountInstanceRequest = mapper.createObjectNode();
        accountInstanceRequest.put("InstanceArn", accountInstance.instanceArn());
        accountInstanceRequest.put("PrincipalId", PRINCIPAL_ID);
        accountInstanceRequest.put("PrincipalType", "USER");
        assertError("AccessDeniedException",
                () -> accountInstanceService.listAssignmentsForPrincipal(accountInstanceRequest, ACCOUNT_ID));
    }

    @Test
    void provisionPermissionSetSupportsSingleAndAllProvisionedAccounts() {
        PermissionSet permissionSet = createPermissionSet("ProvisionAdmins");

        ObjectNode single = mapper.createObjectNode();
        single.put("InstanceArn", service.getInstanceArn());
        single.put("PermissionSetArn", permissionSet.arn());
        single.put("TargetType", "AWS_ACCOUNT");
        single.put("TargetId", ACCOUNT_ID);
        PermissionSetProvisioningOperation operation = service.provisionPermissionSet(single);
        assertEquals("SUCCEEDED", operation.status());
        assertEquals(ACCOUNT_ID, operation.accountId());
        assertEquals(permissionSet.arn(), operation.permissionSetArn());
        assertTrue(operation.createdDateEpochMillis() > 0);
        assertEquals(operation, service.getPermissionSetProvisioningOperation(service.getInstanceArn(), operation.requestId()));
        assertError("ValidationException",
                () -> service.getPermissionSetProvisioningOperation(service.getInstanceArn(), "not-a-uuid"));

        ObjectNode assignment = assignmentRequest(permissionSet.arn());
        service.createAssignment(assignment);
        ObjectNode all = mapper.createObjectNode();
        all.put("InstanceArn", service.getInstanceArn());
        all.put("PermissionSetArn", permissionSet.arn());
        all.put("TargetType", "ALL_PROVISIONED_ACCOUNTS");
        PermissionSetProvisioningOperation allOperation = service.provisionPermissionSet(all);
        assertEquals("SUCCEEDED", allOperation.status());
        assertTrue(allOperation.accountId() == null);

        ObjectNode listStatus = mapper.createObjectNode();
        listStatus.put("InstanceArn", service.getInstanceArn());
        listStatus.put("MaxResults", 1);
        listStatus.putObject("Filter").put("Status", "SUCCEEDED");
        var statusPage = service.listPermissionSetProvisioningStatus(listStatus);
        assertEquals(1, statusPage.items().size());
        assertNotNull(statusPage.nextToken());
        listStatus.putObject("Filter").put("Status", "INVALID");
        assertError("ValidationException", () -> service.listPermissionSetProvisioningStatus(listStatus));

        ObjectNode invalid = single.deepCopy();
        invalid.put("TargetType", "ORGANIZATION");
        assertError("ValidationException", () -> service.provisionPermissionSet(invalid));

        ObjectNode invalidAll = all.deepCopy();
        invalidAll.put("TargetId", ACCOUNT_ID);
        assertError("ValidationException", () -> service.provisionPermissionSet(invalidAll));

        SsoAdminService accountInstanceService = emptyService();
        SsoInstance accountInstance = accountInstanceService.createInstance(mapper.createObjectNode(), ACCOUNT_ID, "us-east-1");
        ObjectNode accountInstanceRequest = single.deepCopy();
        accountInstanceRequest.put("InstanceArn", accountInstance.instanceArn());
        assertError("AccessDeniedException", () -> accountInstanceService.provisionPermissionSet(accountInstanceRequest));
    }

    @Test
    void listPermissionSetsProvisionedToAccountFiltersCurrentAndStaleProvisioning() {
        PermissionSet first = createPermissionSet("ProvisionedListOne");
        PermissionSet second = createPermissionSet("ProvisionedListTwo");
        for (PermissionSet permissionSet : java.util.List.of(first, second)) {
            ObjectNode provision = mapper.createObjectNode();
            provision.put("InstanceArn", service.getInstanceArn());
            provision.put("PermissionSetArn", permissionSet.arn());
            provision.put("TargetType", "AWS_ACCOUNT");
            provision.put("TargetId", ACCOUNT_ID);
            service.provisionPermissionSet(provision);
        }

        ObjectNode request = mapper.createObjectNode();
        request.put("InstanceArn", service.getInstanceArn());
        request.put("AccountId", ACCOUNT_ID);
        request.put("MaxResults", 1);
        var firstPage = service.listPermissionSetsProvisionedToAccount(request);
        assertEquals(1, firstPage.items().size());
        assertNotNull(firstPage.nextToken());

        request.remove("MaxResults");
        request.remove("NextToken");
        request.put("ProvisioningStatus", "LATEST_PERMISSION_SET_PROVISIONED");
        assertEquals(2, service.listPermissionSetsProvisionedToAccount(request).items().size());

        ObjectNode update = mapper.createObjectNode();
        update.put("InstanceArn", service.getInstanceArn());
        update.put("PermissionSetArn", first.arn());
        update.put("Description", "Changed after provisioning");
        service.updatePermissionSet(update);
        assertEquals(1, service.listPermissionSetsProvisionedToAccount(request).items().size());

        request.put("ProvisioningStatus", "LATEST_PERMISSION_SET_NOT_PROVISIONED");
        assertEquals(java.util.List.of(first.arn()), service.listPermissionSetsProvisionedToAccount(request).items());

        request.put("ProvisioningStatus", "FAILED");
        assertError("ValidationException", () -> service.listPermissionSetsProvisionedToAccount(request));
    }

    @Test
    void listAccountsForProvisionedPermissionSetFiltersCurrentAndStaleAccounts() {
        PermissionSet permissionSet = createPermissionSet("ProvisionedAccountsList");
        for (String accountId : java.util.List.of(ACCOUNT_ID, "210987654321")) {
            ObjectNode provision = mapper.createObjectNode();
            provision.put("InstanceArn", service.getInstanceArn());
            provision.put("PermissionSetArn", permissionSet.arn());
            provision.put("TargetType", "AWS_ACCOUNT");
            provision.put("TargetId", accountId);
            service.provisionPermissionSet(provision);
        }

        ObjectNode request = mapper.createObjectNode();
        request.put("InstanceArn", service.getInstanceArn());
        request.put("PermissionSetArn", permissionSet.arn());
        request.put("MaxResults", 1);
        var firstPage = service.listAccountsForProvisionedPermissionSet(request);
        assertEquals(1, firstPage.items().size());
        assertNotNull(firstPage.nextToken());

        request.remove("MaxResults");
        request.remove("NextToken");
        request.put("ProvisioningStatus", "LATEST_PERMISSION_SET_PROVISIONED");
        assertEquals(2, service.listAccountsForProvisionedPermissionSet(request).items().size());

        ObjectNode update = mapper.createObjectNode();
        update.put("InstanceArn", service.getInstanceArn());
        update.put("PermissionSetArn", permissionSet.arn());
        update.put("Description", "Changed after provisioning");
        service.updatePermissionSet(update);
        assertEquals(0, service.listAccountsForProvisionedPermissionSet(request).items().size());

        request.put("ProvisioningStatus", "LATEST_PERMISSION_SET_NOT_PROVISIONED");
        assertEquals(2, service.listAccountsForProvisionedPermissionSet(request).items().size());

        request.put("ProvisioningStatus", "FAILED");
        assertError("ValidationException", () -> service.listAccountsForProvisionedPermissionSet(request));
    }

    @Test
    void assignmentValidationAndDuplicateDetectionAreModeled() {
        PermissionSet permissionSet = createPermissionSet("AssignmentAdmins");
        ObjectNode request = assignmentRequest(permissionSet.arn());
        AssignmentOperation created = service.createAssignment(request);
        assertEquals("SUCCEEDED", created.status());
        assertTrue(created.createdDateEpochMillis() > 0);

        ObjectNode listStatus = mapper.createObjectNode();
        listStatus.put("InstanceArn", service.getInstanceArn());
        listStatus.putObject("Filter").put("Status", "SUCCEEDED");
        var statusPage = service.listAccountAssignmentCreationStatus(listStatus);
        assertEquals(1, statusPage.items().size());
        assertEquals(created.requestId(), statusPage.items().get(0).requestId());
        listStatus.putObject("Filter");
        assertEquals(1, service.listAccountAssignmentCreationStatus(listStatus).items().size());
        listStatus.putObject("Filter").put("Status", "INVALID");
        assertError("ValidationException", () -> service.listAccountAssignmentCreationStatus(listStatus));

        assertError("ConflictException", () -> service.createAssignment(request));

        ObjectNode invalidPrincipalType = assignmentRequest(permissionSet.arn());
        invalidPrincipalType.put("PrincipalType", "ROLE");
        invalidPrincipalType.put("PrincipalId", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        assertError("ValidationException", () -> service.createAssignment(invalidPrincipalType));
    }

    @Test
    void deleteAccountAssignmentRemovesAssignmentAndCreatesDeletionOperation() {
        PermissionSet permissionSet = createPermissionSet("DeleteAssignmentAdmins");
        ObjectNode request = assignmentRequest(permissionSet.arn());
        service.createAssignment(request);

        AssignmentDeletionOperation deleted = service.deleteAssignment(request);
        assertEquals("SUCCEEDED", deleted.status());
        assertTrue(deleted.createdDateEpochMillis() > 0);
        assertTrue(service.listAssignments(service.getInstanceArn(), ACCOUNT_ID, permissionSet.arn()).isEmpty());
        assertEquals(deleted, service.getAssignmentDeletionOperation(service.getInstanceArn(), deleted.requestId()));

        ObjectNode listStatus = mapper.createObjectNode();
        listStatus.put("InstanceArn", service.getInstanceArn());
        listStatus.putObject("Filter").put("Status", "SUCCEEDED");
        var statusPage = service.listAccountAssignmentDeletionStatus(listStatus);
        assertEquals(1, statusPage.items().size());
        assertEquals(deleted.requestId(), statusPage.items().get(0).requestId());
        listStatus.putObject("Filter").put("Status", "INVALID");
        assertError("ValidationException", () -> service.listAccountAssignmentDeletionStatus(listStatus));

        assertError("ResourceNotFoundException", () -> service.deleteAssignment(request));
    }

    @Test
    void deleteAccountAssignmentValidatesPrincipalTypeAndTargetType() {
        PermissionSet permissionSet = createPermissionSet("DeleteValidationAdmins");
        ObjectNode request = assignmentRequest(permissionSet.arn());
        service.createAssignment(request);

        ObjectNode invalidTargetType = request.deepCopy();
        invalidTargetType.put("TargetType", "APPLICATION");
        assertError("ValidationException", () -> service.deleteAssignment(invalidTargetType));

        ObjectNode wrongPrincipalType = request.deepCopy();
        wrongPrincipalType.put("PrincipalType", "USER");
        assertError("ResourceNotFoundException", () -> service.deleteAssignment(wrongPrincipalType));
    }

    @Test
    void putPermissionsBoundarySupportsAwsManagedAndCustomerManagedPolicies() {
        PermissionSet permissionSet = createPermissionSet("BoundaryAdmins");
        assertError("ResourceNotFoundException",
                () -> service.getPermissionsBoundary(service.getInstanceArn(), permissionSet.arn()));

        ObjectNode managed = mapper.createObjectNode();
        managed.put("InstanceArn", service.getInstanceArn());
        managed.put("PermissionSetArn", permissionSet.arn());
        managed.putObject("PermissionsBoundary")
                .put("ManagedPolicyArn", "arn:aws:iam::aws:policy/PowerUserAccess");
        service.putPermissionsBoundary(managed);
        assertEquals("arn:aws:iam::aws:policy/PowerUserAccess",
                service.getPermissionsBoundary(service.getInstanceArn(), permissionSet.arn()).managedPolicyArn());

        ObjectNode customer = mapper.createObjectNode();
        customer.put("InstanceArn", service.getInstanceArn());
        customer.put("PermissionSetArn", permissionSet.arn());
        customer.putObject("PermissionsBoundary")
                .putObject("CustomerManagedPolicyReference")
                .put("Name", "BoundaryPolicy")
                .put("Path", "/platform/");
        service.putPermissionsBoundary(customer);
        assertEquals("BoundaryPolicy", service.getPermissionSet(service.getInstanceArn(), permissionSet.arn())
                .permissionsBoundary().customerManagedPolicyReference().name());
        service.deletePermissionsBoundary(service.getInstanceArn(), permissionSet.arn());
        assertError("ResourceNotFoundException",
                () -> service.getPermissionsBoundary(service.getInstanceArn(), permissionSet.arn()));
        assertError("ResourceNotFoundException",
                () -> service.deletePermissionsBoundary(service.getInstanceArn(), permissionSet.arn()));

        ObjectNode invalid = managed.deepCopy();
        invalid.withObject("PermissionsBoundary")
                .putObject("CustomerManagedPolicyReference")
                .put("Name", "BoundaryPolicy");
        assertError("ValidationException", () -> service.putPermissionsBoundary(invalid));
    }

    @Test
    void clearRemovesPersistedServiceState() {
        PermissionSet permissionSet = createPermissionSet("ResetAdmins");
        AssignmentOperation operation = service.createAssignment(assignmentRequest(permissionSet.arn()));
        assertFalse(service.listPermissionSets(service.getInstanceArn()).isEmpty());
        assertNotNull(service.getAssignmentOperation(service.getInstanceArn(), operation.requestId()));

        service.clear();

        assertTrue(service.listPermissionSets(service.getInstanceArn()).isEmpty());
        assertError("ResourceNotFoundException",
                () -> service.getAssignmentOperation(service.getInstanceArn(), operation.requestId()));
    }

    private SsoAdminService emptyService() {
        return new SsoAdminService(
                new InMemoryStorage<String, PermissionSet>(),
                new InMemoryStorage<String, Assignment>(),
                new InMemoryStorage<String, AssignmentOperation>(),
                new InMemoryStorage<String, AssignmentDeletionOperation>(),
                new InMemoryStorage<String, PermissionSetProvisioning>(),
                new InMemoryStorage<String, PermissionSetProvisioningOperation>(),
                new InMemoryStorage<String, RegionMetadata>(),
                new InMemoryStorage<String, SsoApplication>(),
                new InMemoryStorage<String, SsoApplication>(),
                new InMemoryStorage<String, String>(),
                new InMemoryStorage<String, ApplicationAssignment>(),
                new InMemoryStorage<String, ApplicationAccessScope>(),
                new InMemoryStorage<String, Boolean>(),
                new InMemoryStorage<String, ApplicationAuthenticationMethod>(),
                new InMemoryStorage<String, ApplicationGrant>(),
                new InMemoryStorage<String, String>(),
                new InMemoryStorage<String, Map<String, String>>(),
                new InMemoryStorage<String, SsoInstance>(),
                new InMemoryStorage<String, InstanceUpdateState>(),
                new InMemoryStorage<String, String>(),
                new InMemoryStorage<String, Boolean>(),
                new InMemoryStorage<String, InstanceAccessControlAttributeConfiguration>(),
                new InMemoryStorage<String, TrustedTokenIssuer>(),
                new InMemoryStorage<String, TrustedTokenIssuer>(),
                new InMemoryStorage<String, String>(),
                identityStoreService,
                organizationsService,
                "999999999999",
                "us-east-1");
    }

    private ObjectNode trustedTokenIssuerRequest(String name, String clientToken) {
        ObjectNode request = mapper.createObjectNode();
        request.put("InstanceArn", service.getInstanceArn());
        request.put("Name", name);
        request.put("TrustedTokenIssuerType", "OIDC_JWT");
        if (clientToken != null) {
            request.put("ClientToken", clientToken);
        }
        request.putObject("TrustedTokenIssuerConfiguration")
                .putObject("OidcJwtConfiguration")
                .put("ClaimAttributePath", "sub")
                .put("IdentityStoreAttributePath", "userName")
                .put("IssuerUrl", "https://issuer.example.com")
                .put("JwksRetrievalOption", "OPEN_ID_DISCOVERY");
        return request;
    }

    private SsoApplication createApplication(String name, String clientToken) {
        ObjectNode request = mapper.createObjectNode();
        request.put("InstanceArn", service.getInstanceArn());
        request.put("ApplicationProviderArn", "arn:aws:sso::aws:applicationProvider/custom");
        request.put("Name", name);
        request.put("ClientToken", clientToken);
        return service.createApplication(request, ACCOUNT_ID, "us-east-1");
    }

    private PermissionSet createPermissionSet(String name) {
        ObjectNode request = mapper.createObjectNode();
        request.put("InstanceArn", service.getInstanceArn());
        request.put("Name", name);
        return service.createPermissionSet(request);
    }

    private ObjectNode managedPoliciesRequest(String permissionSetArn) {
        ObjectNode request = mapper.createObjectNode();
        request.put("InstanceArn", service.getInstanceArn());
        request.put("PermissionSetArn", permissionSetArn);
        return request;
    }

    private ObjectNode assignmentRequest(String permissionSetArn) {
        ObjectNode request = mapper.createObjectNode();
        request.put("InstanceArn", service.getInstanceArn());
        request.put("TargetId", ACCOUNT_ID);
        request.put("TargetType", "AWS_ACCOUNT");
        request.put("PermissionSetArn", permissionSetArn);
        request.put("PrincipalType", "GROUP");
        request.put("PrincipalId", PRINCIPAL_ID);
        return request;
    }

    private static void assertError(String expectedCode, Runnable operation) {
        AwsException error = assertThrows(AwsException.class, operation::run);
        assertEquals(expectedCode, error.getErrorCode());
    }
}
