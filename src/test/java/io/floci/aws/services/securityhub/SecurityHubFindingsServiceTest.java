package io.floci.aws.services.securityhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.ServiceConfigAccess;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.securityhub.model.SecurityHubState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecurityHubFindingsServiceTest {
    private static final String ACCOUNT = "222222222222";
    private static final String OTHER_ACCOUNT = "333333333333";
    private static final String REGION = "us-east-1";
    private static final String OTHER_REGION = "us-west-2";
    private static final String CREATED_AT = "2026-11-06T10:00:00Z";

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AccountAwareStorageBackend<Map<String, ObjectNode>> storage;
    private SecurityHubFindingsService service;
    private SecurityHubService hub;

    @BeforeEach
    void setUp() {
        storage = AccountAwareStorageBackend.inMemory(ACCOUNT);
        hub = mock(SecurityHubService.class);
        SecurityHubState state = new SecurityHubState();
        state.setEnabled(true);
        when(hub.state(anyString())).thenReturn(state);
        service = new SecurityHubFindingsService(storage, hub, new RegionResolver(REGION, ACCOUNT));
    }

    @Test
    void importRoundTripsAsffAndAddsManagedDefaults() {
        ObjectNode finding = finding("one", ACCOUNT, REGION);
        finding.putObject("ProductFields").put("source", "test");
        finding.put("Confidence", 80);
        finding.put("Criticality", 70);
        finding.putArray("RelatedFindings").addObject().put("Id", "related")
                .put("ProductArn", productArn(ACCOUNT, REGION));
        finding.putObject("Severity").put("Label", "MEDIUM");

        SecurityHubFindingsService.ImportResult result = service.batchImportFindings(REGION, batch(finding));

        assertEquals(1, result.successCount());
        assertTrue(result.failedFindings().isEmpty());
        ObjectNode read = onlyFinding(service, REGION);
        assertEquals("FAILED", read.path("Compliance").path("Status").asText());
        assertEquals("IAM.1", read.path("Compliance").path("SecurityControlId").asText());
        assertEquals("MEDIUM", read.path("Severity").path("Label").asText());
        assertEquals("MEDIUM", read.path("FindingProviderFields").path("Severity").path("Label").asText());
        assertEquals(40, read.path("Severity").path("Normalized").asInt());
        assertEquals(40, read.path("FindingProviderFields").path("Severity").path("Normalized").asInt());
        assertEquals(read.path("Types"), read.path("FindingProviderFields").path("Types"));
        assertEquals(80, read.path("FindingProviderFields").path("Confidence").asInt());
        assertEquals(70, read.path("FindingProviderFields").path("Criticality").asInt());
        assertEquals(read.path("RelatedFindings"), read.path("FindingProviderFields").path("RelatedFindings"));
        assertEquals("test", read.path("ProductFields").path("source").asText());
        assertEquals("Default", read.path("ProductName").asText());
        assertEquals("Personal", read.path("CompanyName").asText());
        assertEquals(REGION, read.path("Region").asText());
        assertEquals("NEW", read.path("Workflow").path("Status").asText());
        assertEquals("ACTIVE", read.path("RecordState").asText());
        assertEquals("AwsIamRole", read.path("Resources").path(0).path("Type").asText());
    }

    @Test
    void importedFindingSurvivesPersistentStoreRestart() {
        StorageFactory firstFactory = persistentFactory();
        SecurityHubFindingsService first = new SecurityHubFindingsService(firstFactory, hub,
                new RegionResolver(REGION, ACCOUNT));
        assertEquals(1, first.batchImportFindings(REGION, batch(finding("persisted", ACCOUNT, REGION)))
                .successCount());
        firstFactory.shutdownAll();

        StorageFactory secondFactory = persistentFactory();
        SecurityHubFindingsService reopened = new SecurityHubFindingsService(secondFactory, hub,
                new RegionResolver(REGION, ACCOUNT));
        assertEquals("persisted", onlyFinding(reopened, REGION).path("Id").asText());
        secondFactory.shutdownAll();
    }

    @Test
    void batchImportReportsItemFailuresWithoutStoringThem() {
        ObjectNode wrongProduct = finding("wrong-product", ACCOUNT, REGION);
        wrongProduct.put("ProductArn", productArn(OTHER_ACCOUNT, REGION));
        ObjectNode wrongAccount = finding("wrong-account", ACCOUNT, REGION);
        wrongAccount.put("AwsAccountId", OTHER_ACCOUNT);
        ObjectNode missingTitle = finding("missing-title", ACCOUNT, REGION);
        missingTitle.remove("Title");
        ObjectNode oversized = finding("oversized", ACCOUNT, REGION);
        oversized.putObject("ProductFields").put("payload", "x".repeat(240 * 1024));

        SecurityHubFindingsService.ImportResult result = service.batchImportFindings(REGION,
                batch(finding("valid", ACCOUNT, REGION), wrongProduct, wrongAccount, missingTitle, oversized));

        assertEquals(1, result.successCount());
        assertEquals(4, result.failedFindings().size());
        assertEquals(List.of("InvalidAccess", "InvalidAccess", "InvalidInput", "InvalidInput"),
                result.failedFindings().stream().map(SecurityHubFindingsService.ImportFailure::errorCode).toList());
        assertEquals("valid", onlyFinding(service, REGION).path("Id").asText());
    }

    @Test
    void batchImportRejectsInvalidOptionalComplianceAndRecordStatePerItem() {
        ObjectNode noCompliance = finding("no-compliance", ACCOUNT, REGION);
        noCompliance.remove("Compliance");
        ObjectNode malformedCompliance = finding("malformed-compliance", ACCOUNT, REGION);
        malformedCompliance.put("Compliance", "bad");
        ObjectNode invalidStatus = finding("invalid-status", ACCOUNT, REGION);
        ((ObjectNode) invalidStatus.path("Compliance")).put("Status", "BOGUS");
        ObjectNode invalidRecordState = finding("invalid-record-state", ACCOUNT, REGION);
        invalidRecordState.put("RecordState", "BOGUS");

        SecurityHubFindingsService.ImportResult result = service.batchImportFindings(REGION,
                batch(finding("valid", ACCOUNT, REGION), noCompliance, malformedCompliance,
                        invalidStatus, invalidRecordState));

        assertEquals(2, result.successCount());
        assertEquals(List.of("malformed-compliance", "invalid-status", "invalid-record-state"),
                result.failedFindings().stream().map(SecurityHubFindingsService.ImportFailure::id).toList());
        assertTrue(result.failedFindings().stream().allMatch(failure -> "InvalidInput".equals(failure.errorCode())));
        assertEquals(Set.of("valid", "no-compliance"), Set.copyOf(service.getFindings(REGION,
                objectMapper.createObjectNode()).items().stream().map(item -> item.path("Id").asText()).toList()));
    }

    @Test
    void findingProviderFieldsMustBeAnObjectWhenPresent() {
        ObjectNode malformed = finding("malformed-provider", ACCOUNT, REGION);
        malformed.put("FindingProviderFields", "bad");
        ObjectNode nullProvider = finding("null-provider", ACCOUNT, REGION);
        nullProvider.putNull("FindingProviderFields");

        SecurityHubFindingsService.ImportResult result = service.batchImportFindings(REGION,
                batch(malformed, nullProvider));

        assertEquals(0, result.successCount());
        assertEquals(List.of("malformed-provider", "null-provider"),
                result.failedFindings().stream().map(SecurityHubFindingsService.ImportFailure::id).toList());
        assertTrue(result.failedFindings().stream().allMatch(failure -> "InvalidInput".equals(failure.errorCode())));
        assertTrue(service.getFindings(REGION, objectMapper.createObjectNode()).items().isEmpty());
    }

    @Test
    void customDefaultProductCanProvideAndUpdateNames() {
        ObjectNode first = finding("default-product-name", ACCOUNT, REGION);
        first.put("ProductName", "Caller product");
        first.put("CompanyName", "Caller company");
        assertEquals(1, service.batchImportFindings(REGION, batch(first)).successCount());
        assertEquals("Caller product", onlyFinding(service, REGION).path("ProductName").asText());
        assertEquals("Caller company", onlyFinding(service, REGION).path("CompanyName").asText());

        ObjectNode updated = first.deepCopy();
        updated.put("UpdatedAt", "2026-11-06T11:00:00Z");
        updated.put("ProductName", "Renamed product");
        updated.put("CompanyName", "Renamed company");
        assertEquals(1, service.batchImportFindings(REGION, batch(updated)).successCount());
        assertEquals("Renamed product", onlyFinding(service, REGION).path("ProductName").asText());
        assertEquals("Renamed company", onlyFinding(service, REGION).path("CompanyName").asText());

        ObjectNode withoutNames = first.deepCopy();
        withoutNames.put("UpdatedAt", "2026-11-06T12:00:00Z");
        withoutNames.remove("ProductName");
        withoutNames.remove("CompanyName");
        assertEquals(1, service.batchImportFindings(REGION, batch(withoutNames)).successCount());
        assertEquals("Renamed product", onlyFinding(service, REGION).path("ProductName").asText());
        assertEquals("Renamed company", onlyFinding(service, REGION).path("CompanyName").asText());
    }

    @Test
    void productAndCompanyNamesMustBeNonemptyAndAtMost128Characters() {
        ObjectNode emptyProduct = finding("empty-product-name", ACCOUNT, REGION);
        emptyProduct.put("ProductName", "");
        ObjectNode longProduct = finding("long-product-name", ACCOUNT, REGION);
        longProduct.put("ProductName", "x".repeat(129));
        ObjectNode emptyCompany = finding("empty-company-name", ACCOUNT, REGION);
        emptyCompany.put("CompanyName", " ");
        ObjectNode longCompany = finding("long-company-name", ACCOUNT, REGION);
        longCompany.put("CompanyName", "x".repeat(129));

        SecurityHubFindingsService.ImportResult result = service.batchImportFindings(REGION,
                batch(emptyProduct, longProduct, emptyCompany, longCompany));

        assertEquals(0, result.successCount());
        assertEquals(List.of("empty-product-name", "long-product-name", "empty-company-name", "long-company-name"),
                result.failedFindings().stream().map(SecurityHubFindingsService.ImportFailure::id).toList());
        assertTrue(result.failedFindings().stream().allMatch(failure -> "InvalidInput".equals(failure.errorCode())));
        assertTrue(service.getFindings(REGION, objectMapper.createObjectNode()).items().isEmpty());
    }

    @Test
    void batchShapeAndRequiredResourceFieldsAreValidated() {
        for (ObjectNode request : List.of(objectMapper.createObjectNode(), batch(),
                batchWithCount(101))) {
            AwsException error = assertThrows(AwsException.class,
                    () -> service.batchImportFindings(REGION, request));
            assertEquals("InvalidInputException", error.getErrorCode());
        }
        ObjectNode missingResourceId = finding("bad-resource", ACCOUNT, REGION);
        ((ObjectNode) missingResourceId.path("Resources").path(0)).remove("Id");
        SecurityHubFindingsService.ImportResult result = service.batchImportFindings(REGION,
                batch(missingResourceId));
        assertEquals(0, result.successCount());
        assertEquals("InvalidInput", result.failedFindings().getFirst().errorCode());
        assertTrue(service.getFindings(REGION, objectMapper.createObjectNode()).items().isEmpty());
    }

    @Test
    void vendoredShapeChecksRequiredFindingAndResourceMembers() {
        ObjectNode valid = finding("valid", ACCOUNT, REGION);
        for (String field : List.of("SchemaVersion", "Id", "ProductArn", "GeneratorId",
                "AwsAccountId", "CreatedAt", "UpdatedAt", "Title", "Description", "Resources")) {
            ObjectNode missing = valid.deepCopy();
            missing.remove(field);
            AwsException error = assertThrows(AwsException.class,
                    () -> SecurityHubRequestShapes.checkFinding(missing), field);
            assertTrue(error.getMessage().contains("." + field + " is required."), field);
        }

        ObjectNode blankTitle = valid.deepCopy();
        blankTitle.put("Title", " \t");
        assertTrue(assertThrows(AwsException.class,
                () -> SecurityHubRequestShapes.checkFinding(blankTitle)).getMessage().contains(".Title"));

        for (String field : List.of("Type", "Id")) {
            ObjectNode missing = valid.deepCopy();
            ((ObjectNode) missing.path("Resources").get(0)).remove(field);
            AwsException error = assertThrows(AwsException.class,
                    () -> SecurityHubRequestShapes.checkFinding(missing), field);
            assertTrue(error.getMessage().contains(".Resources[]." + field + " is required."), field);
        }
        ObjectNode blankResourceId = valid.deepCopy();
        ((ObjectNode) blankResourceId.path("Resources").get(0)).put("Id", " ");
        assertTrue(assertThrows(AwsException.class,
                () -> SecurityHubRequestShapes.checkFinding(blankResourceId))
                .getMessage().contains(".Resources[].Id"));
    }

    @Test
    void batchImportAcceptsMultilineTitleAndDescriptionButRejectsWhitespaceOnlyText() {
        ObjectNode multilineTitle = finding("multiline-title", ACCOUNT, REGION);
        multilineTitle.put("Title", "First line\nSecond line");
        ObjectNode multilineDescription = finding("multiline-description", ACCOUNT, REGION);
        multilineDescription.put("Description", "First line\nSecond line");
        ObjectNode oneCharacterTitle = finding("one-character-title", ACCOUNT, REGION);
        oneCharacterTitle.put("Title", "\nX\n");
        ObjectNode oneCharacterDescription = finding("one-character-description", ACCOUNT, REGION);
        oneCharacterDescription.put("Description", "\nY\n");
        ObjectNode blankDescription = finding("blank-description", ACCOUNT, REGION);
        blankDescription.put("Description", " \n\t ");

        SecurityHubFindingsService.ImportResult result = service.batchImportFindings(REGION,
                batch(multilineTitle, multilineDescription, oneCharacterTitle, oneCharacterDescription,
                        blankDescription));

        assertEquals(4, result.successCount());
        assertEquals(List.of("blank-description"),
                result.failedFindings().stream().map(SecurityHubFindingsService.ImportFailure::id).toList());
        assertEquals("InvalidInput", result.failedFindings().getFirst().errorCode());
        assertEquals(Set.of("multiline-title", "multiline-description", "one-character-title",
                "one-character-description"), Set.copyOf(service.getFindings(REGION,
                objectMapper.createObjectNode()).items().stream().map(item -> item.path("Id").asText()).toList()));
    }

    @Test
    void reimportPreservesInvestigationFieldsAndSameBatchDuplicateUsesFirst() {
        ObjectNode original = finding("one", ACCOUNT, REGION);
        original.put("Title", "first");
        original.putObject("Workflow").put("Status", "NOTIFIED");
        original.putObject("Note").put("Text", "investigating").put("UpdatedBy", "analyst")
                .put("UpdatedAt", CREATED_AT);
        original.putObject("UserDefinedFields").put("reviewedBy", "analyst");
        original.put("VerificationState", "UNKNOWN");
        service.batchImportFindings(REGION, batch(original));
        assertEquals("NEW", onlyFinding(service, REGION).path("Workflow").path("Status").asText());
        assertEquals("investigating", onlyFinding(service, REGION).path("Note").path("Text").asText());
        assertEquals("analyst", onlyFinding(service, REGION).path("UserDefinedFields").path("reviewedBy").asText());
        assertEquals("UNKNOWN", onlyFinding(service, REGION).path("VerificationState").asText());
        storage.getForAccount(ACCOUNT, REGION).orElseThrow().values().iterator().next()
                .putObject("Workflow").put("Status", "NOTIFIED");

        ObjectNode updated = finding("one", ACCOUNT, REGION);
        updated.put("Title", "updated");
        updated.put("UpdatedAt", "2026-11-06T11:00:00Z");
        updated.putObject("Workflow").put("Status", "RESOLVED");
        updated.putObject("Note").put("Text", "provider overwrite");
        updated.putObject("UserDefinedFields").put("reviewedBy", "provider overwrite");
        updated.put("VerificationState", "BOGUS");
        SecurityHubFindingsService.ImportResult result = service.batchImportFindings(REGION, batch(updated));

        assertEquals(1, result.successCount());
        ObjectNode read = onlyFinding(service, REGION);
        assertEquals("updated", read.path("Title").asText());
        assertEquals("NOTIFIED", read.path("Workflow").path("Status").asText());
        assertEquals("investigating", read.path("Note").path("Text").asText());
        assertEquals("analyst", read.path("UserDefinedFields").path("reviewedBy").asText());
        assertEquals("UNKNOWN", read.path("VerificationState").asText());

        ObjectNode duplicate = finding("one", ACCOUNT, REGION);
        duplicate.put("Title", "ignored second item");
        ObjectNode firstInBatch = finding("one", ACCOUNT, REGION);
        firstInBatch.put("Title", "first in batch");
        firstInBatch.put("UpdatedAt", "2026-11-06T12:00:00Z");
        duplicate.put("UpdatedAt", "2026-11-06T13:00:00Z");
        SecurityHubFindingsService.ImportResult duplicates = service.batchImportFindings(REGION,
                batch(firstInBatch, duplicate));
        assertEquals(2, duplicates.successCount());
        assertEquals("first in batch", onlyFinding(service, REGION).path("Title").asText());
    }

    @Test
    void firstImportRejectsInvalidVerificationState() {
        ObjectNode invalid = finding("invalid-verification", ACCOUNT, REGION);
        invalid.put("VerificationState", "BOGUS");
        ObjectNode nullValue = finding("null-verification", ACCOUNT, REGION);
        nullValue.putNull("VerificationState");

        SecurityHubFindingsService.ImportResult result = service.batchImportFindings(REGION, batch(invalid, nullValue));

        assertEquals(0, result.successCount());
        assertEquals(2, result.failedFindings().size());
        assertTrue(result.failedFindings().stream().allMatch(failure -> "InvalidInput".equals(failure.errorCode())));
        assertTrue(service.getFindings(REGION, objectMapper.createObjectNode()).items().isEmpty());
    }

    @Test
    void providerFieldsPopulateTopLevelValuesAndCustomNamesCanChange() {
        ObjectNode first = finding("one", ACCOUNT, REGION);
        first.remove("Severity");
        first.remove("Types");
        first.put("Region", OTHER_REGION);
        first.put("ProductName", "My Product");
        first.put("CompanyName", "My Company");
        ObjectNode providerFields = first.putObject("FindingProviderFields");
        providerFields.putObject("Severity").put("Label", "HIGH");
        providerFields.putArray("Types").add("Software and Configuration Checks/Vulnerabilities/CVE");

        assertEquals(1, service.batchImportFindings(REGION, batch(first)).successCount());
        ObjectNode read = onlyFinding(service, REGION);
        assertEquals("HIGH", read.path("Severity").path("Label").asText());
        assertEquals("Software and Configuration Checks/Vulnerabilities/CVE", read.path("Types").path(0).asText());
        assertEquals(REGION, read.path("Region").asText());

        ObjectNode updated = first.deepCopy();
        updated.put("UpdatedAt", "2026-11-06T11:00:00Z");
        updated.put("ProductName", "Renamed Product");
        updated.put("CompanyName", "Renamed Company");
        assertEquals(1, service.batchImportFindings(REGION, batch(updated)).successCount());
        read = onlyFinding(service, REGION);
        assertEquals("Renamed Product", read.path("ProductName").asText());
        assertEquals("Renamed Company", read.path("CompanyName").asText());
        assertEquals(REGION, read.path("Region").asText());
    }

    @Test
    void reimportTopLevelProviderFieldsUpdatesBothRepresentations() {
        ObjectNode first = finding("top-level-update", ACCOUNT, REGION);
        assertEquals(1, service.batchImportFindings(REGION, batch(first)).successCount());

        ObjectNode updated = first.deepCopy();
        updated.put("UpdatedAt", "2026-11-06T11:00:00Z");
        updated.putObject("Severity").put("Label", "HIGH");
        updated.putArray("Types").add("Software and Configuration Checks/Vulnerabilities/CVE");
        assertEquals(1, service.batchImportFindings(REGION, batch(updated)).successCount());

        ObjectNode read = onlyFinding(service, REGION);
        assertEquals("HIGH", read.path("Severity").path("Label").asText());
        assertEquals("HIGH", read.path("FindingProviderFields").path("Severity").path("Label").asText());
        assertEquals(read.path("Types"), read.path("FindingProviderFields").path("Types"));
    }

    @Test
    void firstImportUsesProviderValuesWhenBothRepresentationsAreSupplied() {
        ObjectNode first = finding("dual-first-import", ACCOUNT, REGION);
        first.putObject("Severity").put("Label", "LOW");
        first.putArray("Types").add("top-level/type");
        ObjectNode provider = first.putObject("FindingProviderFields");
        provider.putObject("Severity").put("Label", "HIGH");
        provider.putArray("Types").add("provider/type");

        assertEquals(1, service.batchImportFindings(REGION, batch(first)).successCount());

        ObjectNode read = onlyFinding(service, REGION);
        assertEquals("HIGH", read.path("Severity").path("Label").asText());
        assertEquals("HIGH", read.path("FindingProviderFields").path("Severity").path("Label").asText());
        assertEquals("provider/type", read.path("Types").path(0).asText());
        assertEquals("provider/type", read.path("FindingProviderFields").path("Types").path(0).asText());
    }

    @Test
    void reimportKeepsDistinctTopLevelAndProviderValuesWhenBothAreSupplied() {
        ObjectNode first = finding("dual-update", ACCOUNT, REGION);
        assertEquals(1, service.batchImportFindings(REGION, batch(first)).successCount());

        ObjectNode updated = first.deepCopy();
        updated.put("UpdatedAt", "2026-11-06T11:00:00Z");
        updated.putObject("Severity").put("Label", "LOW");
        updated.putObject("FindingProviderFields").putObject("Severity").put("Label", "CRITICAL");
        assertEquals(1, service.batchImportFindings(REGION, batch(updated)).successCount());

        ObjectNode read = onlyFinding(service, REGION);
        assertEquals("LOW", read.path("Severity").path("Label").asText());
        assertEquals("CRITICAL", read.path("FindingProviderFields").path("Severity").path("Label").asText());
    }

    @Test
    void distinctTopLevelAndProviderFieldsAreBothValidated() {
        ObjectNode invalidTopLevelSeverity = finding("invalid-top-level-severity", ACCOUNT, REGION);
        ((ObjectNode) invalidTopLevelSeverity.path("Severity")).put("Label", "BOGUS");
        invalidTopLevelSeverity.putObject("FindingProviderFields").putObject("Severity").put("Label", "LOW");
        ObjectNode invalidTopLevelTypes = finding("invalid-top-level-types", ACCOUNT, REGION);
        invalidTopLevelTypes.putArray("Types").add(" ");
        invalidTopLevelTypes.putObject("FindingProviderFields").putArray("Types").add("valid/type");

        SecurityHubFindingsService.ImportResult result = service.batchImportFindings(REGION,
                batch(invalidTopLevelSeverity, invalidTopLevelTypes));

        assertEquals(0, result.successCount());
        assertEquals(List.of("invalid-top-level-severity", "invalid-top-level-types"),
                result.failedFindings().stream().map(SecurityHubFindingsService.ImportFailure::id).toList());
        assertTrue(service.getFindings(REGION, objectMapper.createObjectNode()).items().isEmpty());
    }

    @Test
    void staleAndEqualTimestampUpdatesAreIgnored() {
        ObjectNode first = finding("one", ACCOUNT, REGION);
        first.put("Title", "original");
        service.batchImportFindings(REGION, batch(first));
        ObjectNode equal = first.deepCopy();
        equal.put("Title", "equal timestamp");
        ObjectNode older = first.deepCopy();
        older.put("Title", "older timestamp");
        older.put("UpdatedAt", "2026-11-06T09:00:00Z");

        assertEquals(1, service.batchImportFindings(REGION, batch(equal)).successCount());
        assertEquals(1, service.batchImportFindings(REGION, batch(older)).successCount());
        assertEquals("original", onlyFinding(service, REGION).path("Title").asText());
    }

    @Test
    void firstImportAcceptsFindingUpdatedAfterItWasCreated() {
        ObjectNode updatedBeforeImport = finding("updated-before-import", ACCOUNT, REGION);
        updatedBeforeImport.put("UpdatedAt", "2026-11-06T11:00:00Z");

        SecurityHubFindingsService.ImportResult result = service.batchImportFindings(REGION,
                batch(updatedBeforeImport));

        assertEquals(1, result.successCount());
        assertTrue(result.failedFindings().isEmpty());
        assertEquals("2026-11-06T11:00:00Z", onlyFinding(service, REGION).path("UpdatedAt").asText());
    }

    @Test
    void malformedPresentSeverityAndTypesAreRejected() {
        ObjectNode malformedSeverity = finding("malformed-severity", ACCOUNT, REGION);
        malformedSeverity.put("Severity", "bad");
        ObjectNode invalidSeverityLabel = finding("invalid-severity-label", ACCOUNT, REGION);
        ((ObjectNode) invalidSeverityLabel.path("Severity")).put("Label", "BOGUS");
        ObjectNode emptySeverity = finding("empty-severity", ACCOUNT, REGION);
        emptySeverity.putObject("Severity");
        ObjectNode outOfRangeSeverity = finding("out-of-range-severity", ACCOUNT, REGION);
        outOfRangeSeverity.putObject("Severity").put("Normalized", 101);
        ObjectNode overflowSeverity = finding("overflow-severity", ACCOUNT, REGION);
        overflowSeverity.putObject("Severity").put("Normalized", 4_294_967_336L);
        ObjectNode malformedTypes = finding("malformed-types", ACCOUNT, REGION);
        malformedTypes.put("Types", "bad");
        ObjectNode invalidType = finding("invalid-type", ACCOUNT, REGION);
        invalidType.putArray("Types").add(" ");

        SecurityHubFindingsService.ImportResult result = service.batchImportFindings(REGION,
                batch(malformedSeverity, invalidSeverityLabel, emptySeverity, outOfRangeSeverity,
                        overflowSeverity, malformedTypes, invalidType));

        assertEquals(0, result.successCount());
        assertEquals(7, result.failedFindings().size());
        assertTrue(result.failedFindings().stream().allMatch(error -> "InvalidInput".equals(error.errorCode())));
        assertTrue(service.getFindings(REGION, objectMapper.createObjectNode()).items().isEmpty());
    }

    @Test
    void providerConfidenceAndCriticalityUseModeledBoundsPerFinding() {
        ObjectNode highConfidence = finding("high-confidence", ACCOUNT, REGION);
        highConfidence.putObject("FindingProviderFields").put("Confidence", 101);
        ObjectNode negativeCriticality = finding("negative-criticality", ACCOUNT, REGION);
        negativeCriticality.putObject("FindingProviderFields").put("Criticality", -1);

        SecurityHubFindingsService.ImportResult result = service.batchImportFindings(REGION,
                batch(finding("valid", ACCOUNT, REGION), highConfidence, negativeCriticality));

        assertEquals(1, result.successCount());
        assertEquals(2, result.failedFindings().size());
        assertTrue(result.failedFindings().stream().allMatch(error -> "InvalidInput".equals(error.errorCode())));
        assertEquals("valid", onlyFinding(service, REGION).path("Id").asText());
    }

    @Test
    void malformedProductAndRelatedFieldsFailOnlyTheirFinding() {
        ObjectNode badProduct = finding("bad-product-fields", ACCOUNT, REGION);
        badProduct.put("ProductFields", "bad");
        ObjectNode badRelated = finding("bad-related-findings", ACCOUNT, REGION);
        badRelated.put("RelatedFindings", "bad");
        ObjectNode badProviderRelated = finding("bad-provider-related", ACCOUNT, REGION);
        badProviderRelated.putObject("FindingProviderFields").put("RelatedFindings", "bad");

        SecurityHubFindingsService.ImportResult result = service.batchImportFindings(REGION,
                batch(finding("valid", ACCOUNT, REGION), badProduct, badRelated, badProviderRelated));

        assertEquals(1, result.successCount());
        assertEquals(List.of("bad-product-fields", "bad-related-findings", "bad-provider-related"),
                result.failedFindings().stream().map(SecurityHubFindingsService.ImportFailure::id).toList());
        assertTrue(result.failedFindings().stream().allMatch(error -> "InvalidInput".equals(error.errorCode())));
        assertEquals("valid", onlyFinding(service, REGION).path("Id").asText());
    }

    @Test
    void optionalSeverityAndTypesCanBeOmittedWithoutLosingOtherFindingFields() {
        ObjectNode sparse = finding("sparse", ACCOUNT, REGION);
        sparse.remove("Severity");
        sparse.remove("Types");
        ObjectNode normalizedOnly = finding("normalized-only", ACCOUNT, REGION);
        ((ObjectNode) normalizedOnly.path("Severity")).remove("Label");
        ((ObjectNode) normalizedOnly.path("Severity")).put("Normalized", 50);
        normalizedOnly.remove("Types");
        ObjectNode providerOnly = finding("provider-only", ACCOUNT, REGION);
        providerOnly.remove("Severity");
        providerOnly.remove("Types");
        providerOnly.putObject("FindingProviderFields").putObject("Severity").put("Normalized", 60);

        SecurityHubFindingsService.ImportResult result = service.batchImportFindings(REGION,
                batch(sparse, normalizedOnly, providerOnly));

        assertEquals(3, result.successCount());
        assertTrue(result.failedFindings().isEmpty());
        List<ObjectNode> imported = service.getFindings(REGION, objectMapper.createObjectNode()).items();
        assertEquals(3, imported.size());
        assertTrue(imported.stream().filter(item -> "sparse".equals(item.path("Id").asText()))
                .allMatch(item -> !item.has("Severity") && !item.has("Types")));
        assertEquals(60, imported.stream().filter(item -> "provider-only".equals(item.path("Id").asText()))
                .findFirst().orElseThrow().path("Severity").path("Normalized").asInt());
        assertEquals("MEDIUM", imported.stream().filter(item -> "normalized-only".equals(item.path("Id").asText()))
                .findFirst().orElseThrow().path("Severity").path("Label").asText());
    }

    @Test
    void disabledHubReturnsPerFindingInvalidAccess() {
        when(hub.state(anyString())).thenReturn(new SecurityHubState());

        SecurityHubFindingsService.ImportResult result = service.batchImportFindings(REGION,
                batch(finding("one", ACCOUNT, REGION), finding("two", ACCOUNT, REGION)));

        assertEquals(0, result.successCount());
        assertEquals(2, result.failedFindings().size());
        assertTrue(result.failedFindings().stream().allMatch(error -> "InvalidAccess".equals(error.errorCode())));
        assertEquals("InvalidAccessException", assertThrows(AwsException.class,
                () -> service.getFindings(REGION, objectMapper.createObjectNode())).getErrorCode());
    }

    @Test
    void supportedFiltersReadNestedValuesAndCombineFieldsWithAnd() {
        ObjectNode first = finding("first", ACCOUNT, REGION);
        ObjectNode second = finding("second", ACCOUNT, REGION);
        second.put("RecordState", "ARCHIVED");
        ((ObjectNode) second.path("Compliance")).put("Status", "PASSED");
        ((ObjectNode) second.path("Compliance")).put("SecurityControlId", "EC2.1");
        ((ObjectNode) second.path("Compliance").path("AssociatedStandards").path(0))
                .put("StandardsId", "standards/other/v/1.0.0");
        service.batchImportFindings(REGION, batch(first, second));

        Map<String, String> singleMatch = Map.of(
                "ComplianceAssociatedStandardsId", "standards/cis-aws-foundations-benchmark/v/5.0.0",
                "RecordState", "ACTIVE", "ComplianceStatus", "FAILED",
                "ComplianceSecurityControlId", "IAM.1");
        for (Map.Entry<String, String> filter : singleMatch.entrySet()) {
            assertEquals("first", findWithFilter(filter.getKey(), filter.getValue()).getFirst().path("Id").asText(),
                    filter.getKey());
        }
        Map<String, String> bothMatch = Map.of("ProductArn", productArn(ACCOUNT, REGION),
                "AwsAccountId", ACCOUNT, "Region", REGION, "WorkflowStatus", "NEW");
        for (Map.Entry<String, String> filter : bothMatch.entrySet()) {
            assertEquals(2, findWithFilter(filter.getKey(), filter.getValue()).size(), filter.getKey());
        }

        ObjectNode filters = objectMapper.createObjectNode();
        filters.set("ComplianceStatus", predicates("FAILED", "EQUALS"));
        filters.set("RecordState", predicates("ARCHIVED", "EQUALS"));
        assertTrue(service.getFindings(REGION, query(filters, null, null)).items().isEmpty());
    }

    @Test
    void positivePredicatesAreOrAndNegativePredicatesAreAnd() {
        ObjectNode first = finding("first", ACCOUNT, REGION);
        ObjectNode second = finding("second", ACCOUNT, REGION);
        second.put("RecordState", "ARCHIVED");
        service.batchImportFindings(REGION, batch(first, second));

        ObjectNode filters = objectMapper.createObjectNode();
        ArrayNode states = filters.putArray("RecordState");
        states.add(predicate("ACTIVE", "EQUALS"));
        states.add(predicate("ARCHIVED", "EQUALS"));
        assertEquals(2, service.getFindings(REGION, query(filters, null, null)).items().size());

        states.removeAll();
        states.add(predicate("ACTIVE", "NOT_EQUALS"));
        states.add(predicate("ARCHIVED", "NOT_EQUALS"));
        assertTrue(service.getFindings(REGION, query(filters, null, null)).items().isEmpty());

        states.removeAll();
        states.add(predicate("A", "PREFIX"));
        states.add(predicate("ARCHIVED", "NOT_EQUALS"));
        assertEquals("first", service.getFindings(REGION, query(filters, null, null))
                .items().getFirst().path("Id").asText());

        states.removeAll();
        states.add(predicate("ACTIVE", "EQUALS"));
        states.add(predicate("ARCHIVED", "NOT_EQUALS"));
        assertEquals("InvalidInputException", assertThrows(AwsException.class,
                () -> service.getFindings(REGION, query(filters, null, null))).getErrorCode());
    }

    @Test
    void paginationTokenIsScopedToAccountRegionAndFilters() {
        service.batchImportFindings(REGION, batch(finding("one", ACCOUNT, REGION),
                finding("two", ACCOUNT, REGION), finding("three", ACCOUNT, REGION)));
        ObjectNode request = query(null, 1, null);
        PaginatedResult<ObjectNode> first = service.getFindings(REGION, request);
        assertEquals(1, first.items().size());
        assertNotNull(first.nextToken());
        Set<String> ids = new HashSet<>();
        ids.add(first.items().getFirst().path("Id").asText());
        String token = first.nextToken();
        while (token != null) {
            PaginatedResult<ObjectNode> page = service.getFindings(REGION, query(null, 1, token));
            ids.add(page.items().getFirst().path("Id").asText());
            token = page.nextToken();
        }
        assertEquals(Set.of("one", "two", "three"), ids);

        assertEquals("InvalidInputException", assertThrows(AwsException.class,
                () -> service.getFindings(OTHER_REGION, query(null, 1, first.nextToken()))).getErrorCode());
        ObjectNode differentFilter = objectMapper.createObjectNode();
        differentFilter.set("RecordState", predicates("ACTIVE", "EQUALS"));
        assertEquals("InvalidInputException", assertThrows(AwsException.class,
                () -> service.getFindings(REGION, query(differentFilter, 1, first.nextToken()))).getErrorCode());
        SecurityHubFindingsService other = new SecurityHubFindingsService(storage, hub,
                new RegionResolver(REGION, OTHER_ACCOUNT));
        assertTrue(other.getFindings(REGION, objectMapper.createObjectNode()).items().isEmpty());
        assertEquals("InvalidInputException", assertThrows(AwsException.class,
                () -> other.getFindings(REGION, query(null, 1, first.nextToken()))).getErrorCode());
    }

    @Test
    void invalidGetOptionsAndDisabledHubAreRejected() {
        for (int size : List.of(0, 101)) {
            assertEquals("InvalidInputException", assertThrows(AwsException.class,
                    () -> service.getFindings(REGION, query(null, size, null))).getErrorCode());
        }
        ObjectNode sort = objectMapper.createObjectNode();
        sort.putArray("SortCriteria");
        assertEquals("InvalidInputException", assertThrows(AwsException.class,
                () -> service.getFindings(REGION, sort)).getErrorCode());
        ObjectNode unsupported = objectMapper.createObjectNode();
        unsupported.set("Title", predicates("title", "EQUALS"));
        assertEquals("InvalidInputException", assertThrows(AwsException.class,
                () -> service.getFindings(REGION, query(unsupported, null, null))).getErrorCode());

        SecurityHubState disabled = new SecurityHubState();
        when(hub.state(anyString())).thenReturn(disabled);
        assertEquals("InvalidAccessException", assertThrows(AwsException.class,
                () -> service.getFindings(REGION, objectMapper.createObjectNode())).getErrorCode());
    }

    private List<ObjectNode> findWithFilter(String field, String value) {
        ObjectNode filters = objectMapper.createObjectNode();
        filters.set(field, predicates(value, "EQUALS"));
        return service.getFindings(REGION, query(filters, null, null)).items();
    }

    private ObjectNode finding(String id, String account, String region) {
        ObjectNode finding = objectMapper.createObjectNode();
        finding.put("SchemaVersion", "2018-10-08");
        finding.put("Id", id);
        finding.put("ProductArn", productArn(account, region));
        finding.put("GeneratorId", "securityhub-test");
        finding.put("AwsAccountId", account);
        finding.put("CreatedAt", CREATED_AT);
        finding.put("UpdatedAt", CREATED_AT);
        finding.put("Title", "Control finding " + id);
        finding.put("Description", "Finding imported by unit test");
        finding.putArray("Types").add("Software and Configuration Checks/Industry and Regulatory Standards");
        finding.putObject("Severity").put("Label", "MEDIUM");
        finding.putArray("Resources").addObject().put("Type", "AwsIamRole")
                .put("Id", "role/securityhub-test");
        ObjectNode compliance = finding.putObject("Compliance");
        compliance.put("Status", "FAILED");
        compliance.put("SecurityControlId", "IAM.1");
        compliance.putArray("AssociatedStandards").addObject()
                .put("StandardsId", "standards/cis-aws-foundations-benchmark/v/5.0.0");
        return finding;
    }

    private static String productArn(String account, String region) {
        return "arn:aws:securityhub:" + region + ":" + account + ":product/" + account + "/default";
    }

    private StorageFactory persistentFactory() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.storage().persistentPath()).thenReturn(tempDir.toString());
        ServiceConfigAccess serviceConfigAccess = mock(ServiceConfigAccess.class);
        when(serviceConfigAccess.storageMode(anyString())).thenReturn("persistent");
        return new StorageFactory(config, serviceConfigAccess);
    }

    private ObjectNode batch(ObjectNode... items) {
        ObjectNode request = objectMapper.createObjectNode();
        ArrayNode findings = request.putArray("Findings");
        for (ObjectNode item : items) {
            findings.add(item);
        }
        return request;
    }

    private ObjectNode batchWithCount(int count) {
        ObjectNode request = objectMapper.createObjectNode();
        ArrayNode findings = request.putArray("Findings");
        for (int index = 0; index < count; index++) {
            findings.add(finding("finding-" + index, ACCOUNT, REGION));
        }
        return request;
    }

    private ObjectNode query(ObjectNode filters, Integer maxResults, String nextToken) {
        ObjectNode request = objectMapper.createObjectNode();
        if (filters != null) {
            request.set("Filters", filters);
        }
        if (maxResults != null) {
            request.put("MaxResults", maxResults);
        }
        if (nextToken != null) {
            request.put("NextToken", nextToken);
        }
        return request;
    }

    private ArrayNode predicates(String value, String comparison) {
        ArrayNode predicates = objectMapper.createArrayNode();
        predicates.add(predicate(value, comparison));
        return predicates;
    }

    private ObjectNode predicate(String value, String comparison) {
        return objectMapper.createObjectNode().put("Value", value).put("Comparison", comparison);
    }

    private static ObjectNode onlyFinding(SecurityHubFindingsService service, String region) {
        List<ObjectNode> found = service.getFindings(region, new ObjectMapper().createObjectNode()).items();
        assertEquals(1, found.size());
        return found.getFirst();
    }
}
