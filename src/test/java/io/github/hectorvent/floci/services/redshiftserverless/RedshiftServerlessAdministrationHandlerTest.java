package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.acm.AcmService;
import io.github.hectorvent.floci.services.acm.model.Certificate;
import io.github.hectorvent.floci.services.redshift.RedshiftIamDbUserResolver;
import io.github.hectorvent.floci.services.redshift.model.Endpoint;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the metadata-only Redshift Serverless actions through the handler, so both the stored
 * behavior and the response shape the SDKs deserialize are checked.
 */
class RedshiftServerlessAdministrationHandlerTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "123456789012";
    private static final String CERTIFICATE_ARN =
            "arn:aws:acm:us-east-1:123456789012:certificate/11111111-2222-3333-4444-555555555555";
    private static final String REPLACEMENT_CERTIFICATE_ARN =
            "arn:aws:acm:us-east-1:123456789012:certificate/99999999-2222-3333-4444-555555555555";

    private final ObjectMapper mapper = new ObjectMapper();
    private RedshiftServerlessJsonHandler handler;
    private RedshiftServerlessService serverlessService;
    private Certificate testCert;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        for (String file : List.of("namespaces", "workgroups", "snapshots", "usagelimits", "resourcepolicies",
                "endpointaccess", "customdomains", "snapshotcopyconfigurations")) {
            when(storageFactory.create(eq("redshiftserverless"), eq("redshiftserverless-" + file + ".json"),
                any(TypeReference.class))).thenReturn((AccountAwareStorageBackend)
                AccountAwareStorageBackend.inMemory(ACCOUNT_ID));
        }
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT_ID);
        when(regionResolver.buildArn(eq("redshift-serverless"), any(String.class), any(String.class)))
                .thenAnswer(invocation -> "arn:aws:redshift-serverless:"
                        + invocation.getArgument(1, String.class) + ":" + ACCOUNT_ID + ":"
                        + invocation.getArgument(2, String.class));
        RedshiftServerlessEndpoints endpoints = mock(RedshiftServerlessEndpoints.class);
        when(endpoints.allocate()).thenReturn(new Endpoint("localhost", 7100));
        RedshiftServerlessRuntime runtime = mock(RedshiftServerlessRuntime.class);
        when(runtime.generatePassword()).thenReturn("Generated123");
        when(runtime.start(any(String.class), any(String.class), any(String.class), any(String.class),
                any(String.class), any(String.class), any(Endpoint.class), anyBoolean(), any()))
                .thenReturn(new RedshiftServerlessRuntime.Backend("127.0.0.1", 55432));
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.storage().persistentPath()).thenReturn(System.getProperty("java.io.tmpdir"));
        serverlessService =
                new RedshiftServerlessService(storageFactory, regionResolver, endpoints, runtime, config);

        AcmService acmService = mock(AcmService.class);
        testCert = new Certificate();
        testCert.setArn(CERTIFICATE_ARN);
        testCert.setNotAfter(Instant.parse("2028-06-15T12:00:00Z"));
        when(acmService.describeCertificate(eq(CERTIFICATE_ARN), any())).thenReturn(testCert);
        Certificate replacementCert = new Certificate();
        replacementCert.setArn(REPLACEMENT_CERTIFICATE_ARN);
        replacementCert.setNotAfter(Instant.parse("2029-01-01T00:00:00Z"));
        when(acmService.describeCertificate(eq(REPLACEMENT_CERTIFICATE_ARN), any())).thenReturn(replacementCert);

        RedshiftServerlessCustomDomainService customDomains =
                new RedshiftServerlessCustomDomainService(storageFactory, serverlessService, acmService);
        handler = new RedshiftServerlessJsonHandler(serverlessService, mapper, mock(RedshiftIamDbUserResolver.class),
                new RedshiftServerlessUsageLimitService(storageFactory, regionResolver, serverlessService),
                new RedshiftServerlessAdminJsonHandler(mapper,
                        new RedshiftServerlessResourcePolicyService(storageFactory, serverlessService, mapper),
                        new RedshiftServerlessEndpointAccessService(storageFactory, regionResolver, serverlessService),
                        customDomains,
                        new RedshiftServerlessSnapshotCopyService(storageFactory, regionResolver, serverlessService)),
                customDomains);
    }

    @Test
    void resourcePolicyIsStoredPerSnapshotAndDroppedWithIt() {
        String snapshotArn = snapshot("policy-ns", "policy-snap");
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";

        JsonNode put = ok("PutResourcePolicy", "{\"resourceArn\":\"" + snapshotArn + "\",\"policy\":"
                + mapper.valueToTree(policy) + "}").get("resourcePolicy");
        assertEquals(snapshotArn, put.get("resourceArn").textValue());
        assertEquals(policy, put.get("policy").textValue());
        assertEquals(policy, ok("GetResourcePolicy", "{\"resourceArn\":\"" + snapshotArn + "\"}")
                .get("resourcePolicy").get("policy").textValue());

        assertEquals("ValidationException", errorType("PutResourcePolicy",
                "{\"resourceArn\":\"" + snapshotArn + "\",\"policy\":\"not json\"}"));
        assertEquals("ValidationException", errorType("PutResourcePolicy",
                "{\"resourceArn\":\"" + snapshotArn + "\",\"policy\":\"{}\\ngarbage\"}"));
        assertEquals("ValidationException", errorType("PutResourcePolicy",
                "{\"resourceArn\":\"" + snapshotArn + "\",\"policy\":\"{}{}\"}"));
        assertEquals("ResourceNotFoundException", errorType("PutResourcePolicy",
                "{\"resourceArn\":\"arn:aws:redshift-serverless:us-east-1:" + ACCOUNT_ID
                        + ":snapshot/missing\",\"policy\":" + mapper.valueToTree(policy) + "}"));
        assertEquals("ValidationException", errorType("PutResourcePolicy",
                "{\"resourceArn\":\"arn:aws:redshift-serverless:us-east-1:999999999999:snapshot/x\",\"policy\":"
                        + mapper.valueToTree(policy) + "}"));
        assertEquals("resourceArn does not identify a snapshot in this account and Region.",
                errorMessage("PutResourcePolicy",
                        "{\"resourceArn\":\"arn:aws:redshift-serverless:us-east-1:999999999999:snapshot/x\",\"policy\":"
                                + mapper.valueToTree(policy) + "}"));
        assertEquals("ValidationException", errorType("PutResourcePolicy",
                "{\"policy\":" + mapper.valueToTree(policy) + "}"));
        assertEquals("ValidationException", errorType("PutResourcePolicy",
                "{\"resourceArn\":\"" + snapshotArn + "\"}"));
        assertEquals("ValidationException", errorType("GetResourcePolicy",
                "{\"resourceArn\":\"arn:aws:redshift-serverless:us-east-1:999999999999:snapshot/x\"}"));
        assertEquals("resourceArn does not identify a snapshot in this account and Region.",
                errorMessage("GetResourcePolicy",
                        "{\"resourceArn\":\"arn:aws:redshift-serverless:us-east-1:999999999999:snapshot/x\"}"));
        assertEquals("ValidationException", errorType("DeleteResourcePolicy",
                "{\"resourceArn\":\"arn:aws:redshift-serverless:us-east-1:999999999999:snapshot/x\"}"));
        assertEquals("resourceArn does not identify a snapshot in this account and Region.",
                errorMessage("DeleteResourcePolicy",
                        "{\"resourceArn\":\"arn:aws:redshift-serverless:us-east-1:999999999999:snapshot/x\"}"));
        assertEquals("ValidationException", errorType("GetResourcePolicy",
                "{\"resourceArn\":\"arn:aws:redshift-serverless:us-east-1:" + ACCOUNT_ID + ":workgroup/x\"}"));
        assertEquals("resourceArn does not identify a snapshot in this account and Region.",
                errorMessage("GetResourcePolicy",
                        "{\"resourceArn\":\"arn:aws:redshift-serverless:us-east-1:" + ACCOUNT_ID + ":workgroup/x\"}"));
        assertEquals("ValidationException", errorType("DeleteResourcePolicy",
                "{\"resourceArn\":\"arn:aws:redshift-serverless:us-east-1:" + ACCOUNT_ID + ":workgroup/x\"}"));
        assertEquals("resourceArn does not identify a snapshot in this account and Region.",
                errorMessage("DeleteResourcePolicy",
                        "{\"resourceArn\":\"arn:aws:redshift-serverless:us-east-1:" + ACCOUNT_ID + ":workgroup/x\"}"));
        assertEquals("ValidationException", errorType("GetResourcePolicy", "{}"));
        assertEquals("ValidationException", errorType("DeleteResourcePolicy", "{}"));

        assertEquals(0, ok("DeleteResourcePolicy", "{\"resourceArn\":\"" + snapshotArn + "\"}").size());
        assertEquals("ResourceNotFoundException",
                errorType("GetResourcePolicy", "{\"resourceArn\":\"" + snapshotArn + "\"}"));

        ok("PutResourcePolicy", "{\"resourceArn\":\"" + snapshotArn + "\",\"policy\":"
                + mapper.valueToTree(policy) + "}");
        ok("DeleteSnapshot", "{\"snapshotName\":\"policy-snap\"}");
        assertEquals("ResourceNotFoundException",
                errorType("GetResourcePolicy", "{\"resourceArn\":\"" + snapshotArn + "\"}"));
    }

    @Test
    void endpointAccessFollowsTheApiModelAndTheWorkgroupLifecycle() {
        workgroup("ep-ns", "ep-wg");

        JsonNode created = ok("CreateEndpointAccess", "{\"endpointName\":\"ep-one\",\"workgroupName\":\"ep-wg\","
                + "\"subnetIds\":[\"subnet-a\",\"subnet-b\"],\"vpcSecurityGroupIds\":[\"sg-1\"]}").get("endpoint");
        assertEquals("ep-one", created.get("endpointName").textValue());
        assertEquals("ep-wg", created.get("workgroupName").textValue());
        assertEquals("ACTIVE", created.get("endpointStatus").textValue());
        assertEquals("localhost", created.get("address").textValue());
        assertEquals(7100, created.get("port").intValue());
        assertTrue(created.get("endpointCreateTime").textValue().matches("\\d{4}-\\d{2}-\\d{2}T.*Z"));
        assertTrue(created.get("endpointArn").textValue().contains(":managedvpcendpoint/"));
        assertEquals(2, created.get("subnetIds").size());
        assertEquals(2, created.get("vpcEndpoint").get("networkInterfaces").size());
        assertEquals("subnet-a", created.get("vpcEndpoint").get("networkInterfaces").get(0).get("subnetId").textValue());
        assertEquals("sg-1", created.get("vpcSecurityGroups").get(0).get("vpcSecurityGroupId").textValue());
        assertEquals("active", created.get("vpcSecurityGroups").get(0).get("status").textValue());

        assertEquals("ConflictException", errorType("CreateEndpointAccess",
                "{\"endpointName\":\"ep-one\",\"workgroupName\":\"ep-wg\",\"subnetIds\":[\"subnet-a\"],"
                        + "\"vpcSecurityGroupIds\":[\"sg-1\"]}"));
        assertEquals("ValidationException", errorType("CreateEndpointAccess",
                "{\"endpointName\":\"ep-two\",\"workgroupName\":\"ep-wg\"}"));
        assertEquals("ValidationException", errorType("CreateEndpointAccess",
                "{\"endpointName\":\"ep-two\",\"workgroupName\":\"ep-wg\",\"subnetIds\":[]}"));
        assertEquals("ValidationException", errorType("CreateEndpointAccess",
                "{\"endpointName\":\"ep-two\",\"workgroupName\":\"ep-wg\",\"subnetIds\":null}"));
        assertEquals("ValidationException", errorType("CreateEndpointAccess",
                "{\"endpointName\":\"ep-two\",\"workgroupName\":\"\",\"subnetIds\":[\"subnet-a\"]}"));
        assertEquals("ResourceNotFoundException", errorType("CreateEndpointAccess",
                "{\"endpointName\":\"ep-two\",\"workgroupName\":\"nope\",\"subnetIds\":[\"subnet-a\"],"
                        + "\"vpcSecurityGroupIds\":[\"sg-1\"]}"));

        JsonNode noSgUpdate = ok("UpdateEndpointAccess",
                "{\"endpointName\":\"ep-one\"}").get("endpoint");
        assertEquals(1, noSgUpdate.get("vpcSecurityGroups").size());
        assertEquals("sg-1", noSgUpdate.get("vpcSecurityGroups").get(0).get("vpcSecurityGroupId").textValue());

        JsonNode nullSgUpdate = ok("UpdateEndpointAccess",
                "{\"endpointName\":\"ep-one\",\"vpcSecurityGroupIds\":null}").get("endpoint");
        assertEquals(1, nullSgUpdate.get("vpcSecurityGroups").size());
        assertEquals("sg-1", nullSgUpdate.get("vpcSecurityGroups").get(0).get("vpcSecurityGroupId").textValue());

        JsonNode updated = ok("UpdateEndpointAccess",
                "{\"endpointName\":\"ep-one\",\"vpcSecurityGroupIds\":[\"sg-2\",\"sg-3\"]}").get("endpoint");
        assertEquals(2, updated.get("vpcSecurityGroups").size());
        assertEquals(1, ok("ListEndpointAccess", "{\"workgroupName\":\"ep-wg\"}").get("endpoints").size());
        assertEquals(0, ok("ListEndpointAccess", "{\"workgroupName\":\"other\"}").get("endpoints").size());
        assertEquals(0, ok("ListEndpointAccess", "{\"vpcId\":\"vpc-1\"}").get("endpoints").size());
        assertEquals("ValidationException", errorType("ListEndpointAccess", "{\"maxResults\":0}"));
        assertEquals("ValidationException", errorType("ListEndpointAccess", "{\"maxResults\":101}"));
        assertEquals("ValidationException", errorType("ListEndpointAccess", "{\"nextToken\":\"bad-token\"}"));
        JsonNode trackTokenPage = ok("ListTracks", "{\"maxResults\":1}");
        assertEquals("ValidationException", errorType("ListEndpointAccess",
                "{\"nextToken\":\"" + trackTokenPage.get("nextToken").textValue() + "\"}"));

        assertEquals("DELETING", ok("DeleteEndpointAccess", "{\"endpointName\":\"ep-one\"}")
                .get("endpoint").get("endpointStatus").textValue());
        assertEquals("ResourceNotFoundException", errorType("GetEndpointAccess", "{\"endpointName\":\"ep-one\"}"));

        ok("CreateEndpointAccess", "{\"endpointName\":\"ep-gone\",\"workgroupName\":\"ep-wg\","
                + "\"subnetIds\":[\"subnet-a\"],\"vpcSecurityGroupIds\":[\"sg-1\"]}");
        serverlessService.findWorkgroup("ep-wg", REGION).orElseThrow()
                .setEndpoint(new Endpoint("192.168.1.10", 8200));
        JsonNode refreshed = ok("GetEndpointAccess", "{\"endpointName\":\"ep-gone\"}").get("endpoint");
        assertEquals("192.168.1.10", refreshed.get("address").textValue());
        assertEquals(8200, refreshed.get("port").intValue());
        JsonNode refreshedList = ok("ListEndpointAccess", "{\"workgroupName\":\"ep-wg\"}").get("endpoints");
        assertEquals("192.168.1.10", refreshedList.get(0).get("address").textValue());
        assertEquals(8200, refreshedList.get(0).get("port").intValue());

        ok("DeleteWorkgroup", "{\"workgroupName\":\"ep-wg\"}");
        assertEquals("ResourceNotFoundException", errorType("GetEndpointAccess", "{\"endpointName\":\"ep-gone\"}"));
    }

    @Test
    void endpointSecurityGroupsAreOptional() {
        workgroup("optional-ns", "optional-wg");
        JsonNode omitted = ok("CreateEndpointAccess",
                "{\"endpointName\":\"optional\",\"workgroupName\":\"optional-wg\",\"subnetIds\":[\"subnet-a\"]}")
                .get("endpoint");
        assertEquals(0, omitted.get("vpcSecurityGroups").size());
        JsonNode empty = ok("CreateEndpointAccess",
                "{\"endpointName\":\"empty\",\"workgroupName\":\"optional-wg\",\"subnetIds\":[\"subnet-a\"],"
                        + "\"vpcSecurityGroupIds\":[]}").get("endpoint");
        assertEquals(0, empty.get("vpcSecurityGroups").size());
    }

    @Test
    void endpointNamesFollowAwsConstraints() {
        workgroup("names-ns", "names-wg");
        for (String name : List.of("", "1endpoint", "-endpoint", "end--point", "endpoint-", "end_point",
                "a".repeat(31))) {
            assertEquals("ValidationException", errorType("CreateEndpointAccess",
                    "{\"endpointName\":\"" + name + "\",\"workgroupName\":\"names-wg\","
                            + "\"subnetIds\":[\"subnet-a\"],\"vpcSecurityGroupIds\":[\"sg-1\"]}"), name);
        }
        for (String name : List.of("A", "Endpoint-123", "a".repeat(30))) {
            assertEquals(name, ok("CreateEndpointAccess",
                    "{\"endpointName\":\"" + name + "\",\"workgroupName\":\"names-wg\","
                            + "\"subnetIds\":[\"subnet-a\"],\"vpcSecurityGroupIds\":[\"sg-1\"]}")
                    .get("endpoint").get("endpointName").textValue());
        }
    }

    @Test
    void endpointAccessPagingWalksPagesAndRefusesForeignTokens() {
        workgroup("page-ns", "page-wg");
        for (String name : List.of("ep-alpha", "ep-beta", "ep-gamma")) {
            ok("CreateEndpointAccess", "{\"endpointName\":\"" + name + "\",\"workgroupName\":\"page-wg\","
                    + "\"subnetIds\":[\"subnet-a\"]}");
        }

        JsonNode firstPage = ok("ListEndpointAccess", "{\"workgroupName\":\"page-wg\",\"maxResults\":2}");
        assertEquals(2, firstPage.get("endpoints").size());
        assertEquals("ep-alpha", firstPage.get("endpoints").get(0).get("endpointName").textValue());
        assertEquals("ep-beta", firstPage.get("endpoints").get(1).get("endpointName").textValue());
        assertTrue(firstPage.has("nextToken"));

        String token = firstPage.get("nextToken").textValue();
        JsonNode secondPage = ok("ListEndpointAccess", "{\"workgroupName\":\"page-wg\",\"nextToken\":\"" + token + "\"}");
        assertEquals(1, secondPage.get("endpoints").size());
        assertEquals("ep-gamma", secondPage.get("endpoints").get(0).get("endpointName").textValue());
        assertFalse(secondPage.has("nextToken"));
    }

    @Test
    void customDomainAssociationIsKeyedByWorkgroupAndDomain() {
        workgroup("domain-ns", "domain-wg");

        JsonNode wgBefore = ok("GetWorkgroup", "{\"workgroupName\":\"domain-wg\"}").get("workgroup");
        assertFalse(wgBefore.has("customDomainName"));
        assertFalse(wgBefore.has("customDomainCertificateArn"));
        assertFalse(wgBefore.has("customDomainCertificateExpiryTime"));

        String request = "{\"workgroupName\":\"domain-wg\",\"customDomainName\":\"db.example.com\","
                + "\"customDomainCertificateArn\":\"" + CERTIFICATE_ARN + "\"}";

        JsonNode created = ok("CreateCustomDomainAssociation", request);
        assertEquals("db.example.com", created.get("customDomainName").textValue());
        assertEquals("domain-wg", created.get("workgroupName").textValue());
        assertEquals(CERTIFICATE_ARN, created.get("customDomainCertificateArn").textValue());
        assertEquals("2028-06-15T12:00:00.000Z", created.get("customDomainCertificateExpiryTime").textValue());

        JsonNode wgWithDomain = ok("GetWorkgroup", "{\"workgroupName\":\"domain-wg\"}").get("workgroup");
        assertEquals("db.example.com", wgWithDomain.get("customDomainName").textValue());
        assertEquals(CERTIFICATE_ARN, wgWithDomain.get("customDomainCertificateArn").textValue());
        assertEquals("2028-06-15T12:00:00.000Z", wgWithDomain.get("customDomainCertificateExpiryTime").textValue());

        JsonNode listWg = ok("ListWorkgroups", "{}").get("workgroups");
        boolean foundInList = false;
        for (JsonNode wg : listWg) {
            if ("domain-wg".equals(wg.get("workgroupName").textValue())) {
                assertEquals("db.example.com", wg.get("customDomainName").textValue());
                assertEquals(CERTIFICATE_ARN, wg.get("customDomainCertificateArn").textValue());
                assertEquals("2028-06-15T12:00:00.000Z", wg.get("customDomainCertificateExpiryTime").textValue());
                foundInList = true;
            }
        }
        assertTrue(foundInList);

        testCert.setNotAfter(Instant.parse("2030-01-01T00:00:00Z"));
        JsonNode renewed = ok("GetCustomDomainAssociation",
                "{\"workgroupName\":\"domain-wg\",\"customDomainName\":\"db.example.com\"}");
        assertEquals("2030-01-01T00:00:00.000Z", renewed.get("customDomainCertificateExpiryTime").textValue());
        testCert.setNotAfter(Instant.parse("2028-06-15T12:00:00Z"));

        assertEquals("ConflictException", errorType("CreateCustomDomainAssociation", request));
        assertEquals("ConflictException", errorType("CreateCustomDomainAssociation",
                "{\"workgroupName\":\"domain-wg\",\"customDomainName\":\"other.example.com\","
                        + "\"customDomainCertificateArn\":\"" + CERTIFICATE_ARN + "\"}"));
        assertTrue(errorMessage("CreateCustomDomainAssociation",
                "{\"workgroupName\":\"domain-wg\",\"customDomainName\":\"other.example.com\","
                        + "\"customDomainCertificateArn\":\"" + CERTIFICATE_ARN + "\"}")
                .contains("already has a custom domain"));

        assertEquals("ValidationException", errorType("CreateCustomDomainAssociation",
                "{\"workgroupName\":\"domain-wg\",\"customDomainName\":\"-bad\","
                        + "\"customDomainCertificateArn\":\"" + CERTIFICATE_ARN + "\"}"));
        assertEquals("ValidationException", errorType("CreateCustomDomainAssociation",
                "{\"workgroupName\":\"domain-wg\",\"customDomainName\":\"third.example.com\","
                        + "\"customDomainCertificateArn\":\"not-an-arn\"}"));
        assertEquals("ResourceNotFoundException", errorType("CreateCustomDomainAssociation",
                "{\"workgroupName\":\"nope\",\"customDomainName\":\"x.example.com\","
                        + "\"customDomainCertificateArn\":\"" + CERTIFICATE_ARN + "\"}"));

        String replacement = REPLACEMENT_CERTIFICATE_ARN;
        JsonNode updated = ok("UpdateCustomDomainAssociation", "{\"workgroupName\":\"domain-wg\","
                + "\"customDomainName\":\"db.example.com\",\"customDomainCertificateArn\":\"" + replacement + "\"}");
        assertEquals(replacement, updated.get("customDomainCertificateArn").textValue());
        assertEquals("2029-01-01T00:00:00.000Z", updated.get("customDomainCertificateExpiryTime").textValue());

        JsonNode got = ok("GetCustomDomainAssociation",
                "{\"workgroupName\":\"domain-wg\",\"customDomainName\":\"db.example.com\"}");
        assertEquals(replacement, got.get("customDomainCertificateArn").textValue());
        assertEquals("2029-01-01T00:00:00.000Z", got.get("customDomainCertificateExpiryTime").textValue());

        JsonNode wgUpdated = ok("GetWorkgroup", "{\"workgroupName\":\"domain-wg\"}").get("workgroup");
        assertEquals("db.example.com", wgUpdated.get("customDomainName").textValue());
        assertEquals(replacement, wgUpdated.get("customDomainCertificateArn").textValue());
        assertEquals("2029-01-01T00:00:00.000Z", wgUpdated.get("customDomainCertificateExpiryTime").textValue());

        JsonNode associationsList = ok("ListCustomDomainAssociations", "{\"customDomainName\":\"db.example.com\"}")
                .get("associations");
        assertEquals(1, associationsList.size());
        assertEquals("2029-01-01T00:00:00.000Z", associationsList.get(0).get("customDomainCertificateExpiryTime").textValue());
        assertEquals(0, ok("ListCustomDomainAssociations", "{\"customDomainName\":\"x.example.com\"}")
                .get("associations").size());
        assertEquals("ValidationException", errorType("UpdateCustomDomainAssociation",
                "{\"workgroupName\":\"domain-wg\",\"customDomainName\":\"db.example.com\"}"));
        assertEquals("ValidationException", errorType("ListCustomDomainAssociations", "{\"maxResults\":0}"));
        assertEquals("ValidationException", errorType("ListCustomDomainAssociations", "{\"maxResults\":101}"));
        assertEquals("InvalidPaginationException", errorType("ListCustomDomainAssociations", "{\"nextToken\":\"bad-token\"}"));
        JsonNode trackToken = ok("ListTracks", "{\"maxResults\":1}");
        assertEquals("InvalidPaginationException", errorType("ListCustomDomainAssociations",
                "{\"nextToken\":\"" + trackToken.get("nextToken").textValue() + "\"}"));

        assertEquals(0, ok("DeleteCustomDomainAssociation",
                "{\"workgroupName\":\"domain-wg\",\"customDomainName\":\"db.example.com\"}").size());
        assertEquals("ResourceNotFoundException", errorType("GetCustomDomainAssociation",
                "{\"workgroupName\":\"domain-wg\",\"customDomainName\":\"db.example.com\"}"));

        JsonNode wgAfterDelete = ok("GetWorkgroup", "{\"workgroupName\":\"domain-wg\"}").get("workgroup");
        assertFalse(wgAfterDelete.has("customDomainName"));
        assertFalse(wgAfterDelete.has("customDomainCertificateArn"));
        assertFalse(wgAfterDelete.has("customDomainCertificateExpiryTime"));
    }

    @Test
    void customDomainNameCannotBeClaimedByMultipleWorkgroups() {
        workgroup("domain-ns-a", "domain-wg-a");
        workgroup("domain-ns-b", "domain-wg-b");
        ok("CreateCustomDomainAssociation", "{\"workgroupName\":\"domain-wg-a\",\"customDomainName\":\"shared.example.com\","
                + "\"customDomainCertificateArn\":\"" + CERTIFICATE_ARN + "\"}");

        assertEquals("ConflictException", errorType("CreateCustomDomainAssociation",
                "{\"workgroupName\":\"domain-wg-b\",\"customDomainName\":\"shared.example.com\","
                        + "\"customDomainCertificateArn\":\"" + CERTIFICATE_ARN + "\"}"));
        assertTrue(errorMessage("CreateCustomDomainAssociation",
                "{\"workgroupName\":\"domain-wg-b\",\"customDomainName\":\"shared.example.com\","
                        + "\"customDomainCertificateArn\":\"" + CERTIFICATE_ARN + "\"}")
                .contains("domain-wg-a"));
    }

    @Test
    void customDomainOperationsValidateInputs() {
        workgroup("val-ns", "val-wg");
        assertEquals("ValidationException", errorType("GetCustomDomainAssociation",
                "{\"workgroupName\":\"\",\"customDomainName\":\"db.example.com\"}"));
        assertEquals("ValidationException", errorType("GetCustomDomainAssociation",
                "{\"workgroupName\":\"val-wg\",\"customDomainName\":\"\"}"));
        assertEquals("ValidationException", errorType("GetCustomDomainAssociation",
                "{\"workgroupName\":\"val-wg\"}"));
        assertEquals("ValidationException", errorType("GetCustomDomainAssociation",
                "{\"customDomainName\":\"db.example.com\"}"));

        assertEquals("ValidationException", errorType("DeleteCustomDomainAssociation",
                "{\"workgroupName\":\"\",\"customDomainName\":\"db.example.com\"}"));
        assertEquals("ValidationException", errorType("DeleteCustomDomainAssociation",
                "{\"workgroupName\":\"val-wg\",\"customDomainName\":\"\"}"));
        assertEquals("ValidationException", errorType("DeleteCustomDomainAssociation",
                "{\"workgroupName\":\"val-wg\"}"));
        assertEquals("ValidationException", errorType("DeleteCustomDomainAssociation",
                "{\"customDomainName\":\"db.example.com\"}"));

        assertEquals("ValidationException", errorType("UpdateCustomDomainAssociation",
                "{\"workgroupName\":\"\",\"customDomainName\":\"db.example.com\",\"customDomainCertificateArn\":\""
                        + CERTIFICATE_ARN + "\"}"));
        assertEquals("ValidationException", errorType("UpdateCustomDomainAssociation",
                "{\"workgroupName\":\"val-wg\",\"customDomainName\":\"\",\"customDomainCertificateArn\":\""
                        + CERTIFICATE_ARN + "\"}"));
        assertEquals("ValidationException", errorType("UpdateCustomDomainAssociation",
                "{\"workgroupName\":\"val-wg\",\"customDomainName\":\"db.example.com\"}"));
        assertEquals("ValidationException", errorType("UpdateCustomDomainAssociation",
                "{\"workgroupName\":\"val-wg\",\"customDomainName\":\"db.example.com\",\"customDomainCertificateArn\":\"\"}"));
        assertEquals("ValidationException", errorType("UpdateCustomDomainAssociation",
                "{\"workgroupName\":\"val-wg\",\"customDomainName\":\"-bad\",\"customDomainCertificateArn\":\""
                        + CERTIFICATE_ARN + "\"}"));
        assertEquals("ValidationException", errorType("UpdateCustomDomainAssociation",
                "{\"workgroupName\":\"val-wg\",\"customDomainName\":\"db.example.com\",\"customDomainCertificateArn\":\"not-an-arn\"}"));

        for (String badDomain : List.of("", "-bad.com", "bad-.com", "bad_domain.com", "a".repeat(254) + ".com")) {
            assertEquals("ValidationException", errorType("CreateCustomDomainAssociation",
                    "{\"workgroupName\":\"val-wg\",\"customDomainName\":\"" + badDomain
                            + "\",\"customDomainCertificateArn\":\"" + CERTIFICATE_ARN + "\"}"), badDomain);
        }
        for (String badArn : List.of("", "short", "arn:aws:s3:::mybucket", "arn:aws:acm:bad")) {
            assertEquals("ValidationException", errorType("CreateCustomDomainAssociation",
                    "{\"workgroupName\":\"val-wg\",\"customDomainName\":\"db.example.com\","
                            + "\"customDomainCertificateArn\":\"" + badArn + "\"}"), badArn);
        }
    }

    @Test
    void snapshotCopyConfigurationHasOneDestinationPerNamespace() {
        namespace("copy-ns");

        JsonNode created = ok("CreateSnapshotCopyConfiguration", "{\"namespaceName\":\"copy-ns\","
                + "\"destinationRegion\":\"us-west-2\",\"snapshotRetentionPeriod\":14,"
                + "\"destinationKmsKeyId\":\"key-1\"}").get("snapshotCopyConfiguration");
        String id = created.get("snapshotCopyConfigurationId").textValue();
        assertEquals("copy-ns", created.get("namespaceName").textValue());
        assertEquals("us-west-2", created.get("destinationRegion").textValue());
        assertEquals("key-1", created.get("destinationKmsKeyId").textValue());
        assertEquals(14, created.get("snapshotRetentionPeriod").intValue());
        assertTrue(created.get("snapshotCopyConfigurationArn").textValue().contains(":snapshotcopyconfiguration/"));

        assertEquals("ConflictException", errorType("CreateSnapshotCopyConfiguration",
                "{\"namespaceName\":\"copy-ns\",\"destinationRegion\":\"us-west-2\"}"));
        assertEquals("ConflictException", errorType("CreateSnapshotCopyConfiguration",
                "{\"namespaceName\":\"copy-ns\",\"destinationRegion\":\"eu-west-1\"}"));
        assertEquals("ValidationException", errorType("CreateSnapshotCopyConfiguration",
                "{\"namespaceName\":\"copy-ns\",\"destinationRegion\":\"us-east-1\"}"));
        assertEquals("ValidationException", errorType("CreateSnapshotCopyConfiguration",
                "{\"namespaceName\":\"copy-ns\",\"destinationRegion\":\"US-WEST-2\"}"));
        assertEquals("ValidationException", errorType("CreateSnapshotCopyConfiguration",
                "{\"namespaceName\":\"copy-ns\",\"destinationRegion\":\"invalid_region!\"}"));
        assertEquals("ResourceNotFoundException", errorType("CreateSnapshotCopyConfiguration",
                "{\"namespaceName\":\"nope-ns\",\"destinationRegion\":\"us-west-2\"}"));

        assertEquals(30, ok("UpdateSnapshotCopyConfiguration", "{\"snapshotCopyConfigurationId\":\"" + id
                + "\",\"snapshotRetentionPeriod\":30}").get("snapshotCopyConfiguration")
                .get("snapshotRetentionPeriod").intValue());
        assertEquals("ValidationException", errorType("UpdateSnapshotCopyConfiguration",
                "{\"snapshotCopyConfigurationId\":\"" + id + "\",\"snapshotRetentionPeriod\":0}"));
        assertEquals(1, ok("ListSnapshotCopyConfigurations", "{\"namespaceName\":\"copy-ns\"}")
                .get("snapshotCopyConfigurations").size());

        assertEquals(id, ok("DeleteSnapshotCopyConfiguration", "{\"snapshotCopyConfigurationId\":\"" + id + "\"}")
                .get("snapshotCopyConfiguration").get("snapshotCopyConfigurationId").textValue());
        assertEquals(0, ok("ListSnapshotCopyConfigurations", "{}").get("snapshotCopyConfigurations").size());
        assertEquals("eu-west-1", ok("CreateSnapshotCopyConfiguration",
                "{\"namespaceName\":\"copy-ns\",\"destinationRegion\":\"eu-west-1\"}")
                .get("snapshotCopyConfiguration").get("destinationRegion").textValue());
    }

    @Test
    void snapshotCopyPagingWalksPagesAndValidatesInputs() {
        for (String name : List.of("page-copy-a", "page-copy-b", "page-copy-c")) {
            namespace(name);
            ok("CreateSnapshotCopyConfiguration", "{\"namespaceName\":\"" + name
                    + "\",\"destinationRegion\":\"us-west-2\"}");
        }

        JsonNode firstPage = ok("ListSnapshotCopyConfigurations", "{\"maxResults\":2}");
        assertEquals(2, firstPage.get("snapshotCopyConfigurations").size());
        assertTrue(firstPage.has("nextToken"));

        String token = firstPage.get("nextToken").textValue();
        JsonNode secondPage = ok("ListSnapshotCopyConfigurations", "{\"nextToken\":\"" + token + "\"}");
        assertEquals(1, secondPage.get("snapshotCopyConfigurations").size());
        assertFalse(secondPage.has("nextToken"));

        assertEquals("ValidationException", errorType("ListSnapshotCopyConfigurations", "{\"maxResults\":0}"));
        assertEquals("ValidationException", errorType("ListSnapshotCopyConfigurations", "{\"maxResults\":101}"));
        assertEquals("InvalidPaginationException",
                errorType("ListSnapshotCopyConfigurations", "{\"nextToken\":\"bad-token\"}"));
    }

    @Test
    void tracksAreTheTwoAwsReleaseTracks() {
        JsonNode track = ok("GetTrack", "{\"trackName\":\"trailing\"}").get("track");
        assertEquals("trailing", track.get("trackName").textValue());
        assertEquals(0, track.get("updateTargets").size());
        assertEquals("ResourceNotFoundException", errorType("GetTrack", "{\"trackName\":\"nightly\"}"));
        assertEquals("ValidationException", errorType("GetTrack", "{\"trackName\":\"not valid!\"}"));

        assertEquals("ResourceNotFoundException", errorType("GetTrack", "{\"trackName\":\"preview\"}"));
        assertEquals(2, ok("ListTracks", "{}").get("tracks").size());
        JsonNode page = ok("ListTracks", "{\"maxResults\":1}");
        assertEquals("current", page.get("tracks").get(0).get("trackName").textValue());
        String next = page.get("nextToken").textValue();
        JsonNode rest = ok("ListTracks", "{\"nextToken\":\"" + next + "\"}");
        assertEquals(1, rest.get("tracks").size());
        assertEquals("trailing", rest.get("tracks").get(0).get("trackName").textValue());
        assertFalse(rest.has("nextToken"));
        assertEquals("ValidationException", errorType("ListTracks", "{\"maxResults\":\"x\"}"));
        assertEquals("ValidationException", errorType("ListTracks", "{\"maxResults\":0}"));
        assertEquals("ValidationException", errorType("ListTracks", "{\"maxResults\":101}"));
        assertEquals("ValidationException", errorType("ListTracks", "{\"nextToken\":\"bad-token\"}"));
    }

    @Test
    void managedWorkgroupsAndReservationsAreEmptyBecauseFlociModelsNone() {
        assertEquals(0, ok("ListManagedWorkgroups", "{}").get("managedWorkgroups").size());
        assertEquals("ValidationException", errorType("ListManagedWorkgroups", "{\"sourceArn\":\"nope\"}"));
        assertEquals(0, ok("ListManagedWorkgroups", "{\"sourceArn\":\"arn:aws:glue:us-east-1:123456789012:"
                + "database/db\"}").get("managedWorkgroups").size());
        assertEquals("ValidationException", errorType("ListManagedWorkgroups", "{\"maxResults\":0}"));
        assertEquals("ValidationException", errorType("ListManagedWorkgroups", "{\"maxResults\":101}"));

        assertEquals(0, ok("ListReservations", "{}").get("reservationsList").size());
        assertEquals("ValidationException", errorType("ListReservations", "{\"maxResults\":0}"));
        assertEquals("ValidationException", errorType("ListReservations", "{\"maxResults\":101}"));

        assertEquals(0, ok("ListReservationOfferings", "{}").get("reservationOfferingsList").size());
        assertEquals("ValidationException", errorType("ListReservationOfferings", "{\"maxResults\":0}"));
        assertEquals("ValidationException", errorType("ListReservationOfferings", "{\"maxResults\":101}"));

        assertEquals("ValidationException",
                errorType("ListManagedWorkgroups", "{\"nextToken\":\"bad-token\"}"));
        assertEquals("ValidationException",
                errorType("ListReservations", "{\"nextToken\":\"bad-token\"}"));
        assertEquals("ValidationException",
                errorType("ListReservationOfferings", "{\"nextToken\":\"bad-token\"}"));

        assertEquals("ResourceNotFoundException", errorType("GetReservation", "{\"reservationId\":\"r-1\"}"));
        assertEquals("ValidationException", errorType("GetReservation", "{}"));
        assertEquals("ValidationException", errorType("GetReservation", "{\"reservationId\":\"\"}"));
        assertEquals("ResourceNotFoundException", errorType("GetReservationOffering", "{\"offeringId\":\"o-1\"}"));
        assertEquals("ValidationException", errorType("GetReservationOffering", "{}"));
        assertEquals("ValidationException", errorType("GetReservationOffering", "{\"offeringId\":\"\"}"));
        assertEquals("ResourceNotFoundException",
                errorType("CreateReservation", "{\"capacity\":128,\"offeringId\":\"o-1\"}"));
        assertEquals("ValidationException", errorType("CreateReservation", "{\"offeringId\":\"o-1\"}"));
        assertEquals("ValidationException",
                errorType("CreateReservation", "{\"capacity\":\"nope\",\"offeringId\":\"o-1\"}"));
        assertEquals("ValidationException",
                errorType("CreateReservation", "{\"capacity\":128,\"offeringId\":\"o-1\",\"clientToken\":123}"));
    }

    @Test
    void aWorkgroupRecreatedUnderTheSameNameDoesNotInheritEndpointsOrDomains() {
        workgroup("recreate-wg-ns", "recreate-wg");
        ok("CreateEndpointAccess", "{\"endpointName\":\"old-ep\",\"workgroupName\":\"recreate-wg\","
                + "\"subnetIds\":[\"subnet-a\"],\"vpcSecurityGroupIds\":[\"sg-1\"]}");
        ok("CreateCustomDomainAssociation", "{\"workgroupName\":\"recreate-wg\",\"customDomainName\":\"old.example.com\","
                + "\"customDomainCertificateArn\":\"" + CERTIFICATE_ARN + "\"}");

        ok("DeleteWorkgroup", "{\"workgroupName\":\"recreate-wg\"}");
        ok("CreateWorkgroup", "{\"workgroupName\":\"recreate-wg\",\"namespaceName\":\"recreate-wg-ns\"}");

        assertEquals("ResourceNotFoundException", errorType("GetEndpointAccess", "{\"endpointName\":\"old-ep\"}"));
        assertEquals("ResourceNotFoundException", errorType("GetCustomDomainAssociation",
                "{\"workgroupName\":\"recreate-wg\",\"customDomainName\":\"old.example.com\"}"));
        assertEquals(0, ok("ListEndpointAccess", "{\"workgroupName\":\"recreate-wg\"}").get("endpoints").size());
        ok("CreateEndpointAccess", "{\"endpointName\":\"old-ep\",\"workgroupName\":\"recreate-wg\","
                + "\"subnetIds\":[\"subnet-a\"],\"vpcSecurityGroupIds\":[\"sg-1\"]}");
        ok("CreateCustomDomainAssociation", "{\"workgroupName\":\"recreate-wg\",\"customDomainName\":\"old.example.com\","
                + "\"customDomainCertificateArn\":\"" + CERTIFICATE_ARN + "\"}");
    }

    @Test
    void aNamespaceRecreatedUnderTheSameNameDoesNotInheritCopyConfigurations() {
        namespace("recreate-ns");
        ok("CreateSnapshotCopyConfiguration", "{\"namespaceName\":\"recreate-ns\",\"destinationRegion\":\"us-west-2\"}");

        ok("DeleteNamespace", "{\"namespaceName\":\"recreate-ns\"}");
        namespace("recreate-ns");

        assertEquals(0, ok("ListSnapshotCopyConfigurations", "{\"namespaceName\":\"recreate-ns\"}")
                .get("snapshotCopyConfigurations").size());
        ok("CreateSnapshotCopyConfiguration", "{\"namespaceName\":\"recreate-ns\",\"destinationRegion\":\"us-west-2\"}");
    }

    @Test
    void aSnapshotRecreatedUnderTheSameNameDoesNotInheritItsPolicy() {
        String arn = snapshot("recreate-snap-ns", "recreate-snap");
        ok("PutResourcePolicy", "{\"resourceArn\":\"" + arn + "\",\"policy\":\"{}\"}");

        ok("DeleteSnapshot", "{\"snapshotName\":\"recreate-snap\"}");
        ok("CreateSnapshot", "{\"snapshotName\":\"recreate-snap\",\"namespaceName\":\"recreate-snap-ns\"}");

        assertEquals("ResourceNotFoundException", errorType("GetResourcePolicy", "{\"resourceArn\":\"" + arn + "\"}"));
    }

    @Test
    void anUnknownActionIsStillRejected() {
        assertEquals("UnknownOperationException", errorType("NoSuchOperation", "{}"));
    }

    private void namespace(String name) {
        ok("CreateNamespace", "{\"namespaceName\":\"" + name + "\",\"adminUsername\":\"admin\"}");
    }

    private void workgroup(String namespaceName, String workgroupName) {
        namespace(namespaceName);
        ok("CreateWorkgroup", "{\"workgroupName\":\"" + workgroupName + "\",\"namespaceName\":\""
                + namespaceName + "\"}");
    }

    private String snapshot(String namespaceName, String snapshotName) {
        namespace(namespaceName);
        return ok("CreateSnapshot", "{\"snapshotName\":\"" + snapshotName + "\",\"namespaceName\":\""
                + namespaceName + "\"}").get("snapshot").get("snapshotArn").textValue();
    }

    private JsonNode ok(String action, String json) {
        Response response = invoke(action, json);
        assertEquals(200, response.getStatus(), action + " failed: " + response.getEntity());
        return mapper.valueToTree(response.getEntity());
    }

    private String errorType(String action, String json) {
        Response response = invoke(action, json);
        assertEquals(400, response.getStatus(), action + " should be rejected: " + response.getEntity());
        return mapper.valueToTree(response.getEntity()).path("__type").asText(null);
    }

    private String errorMessage(String action, String json) {
        Response response = invoke(action, json);
        assertEquals(400, response.getStatus(), action + " should be rejected: " + response.getEntity());
        return mapper.valueToTree(response.getEntity()).path("message").asText(null);
    }

    private Response invoke(String action, String json) {
        try {
            return handler.handle(action, mapper.readTree(json), REGION);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
