package io.github.hectorvent.floci.services.guardduty;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.services.guardduty.model.AdminAccount;
import io.github.hectorvent.floci.services.guardduty.model.Detector;
import io.github.hectorvent.floci.services.guardduty.model.DetectorAdditionalConfiguration;
import io.github.hectorvent.floci.services.guardduty.model.DetectorFeature;
import io.github.hectorvent.floci.services.guardduty.model.OrganizationAdditionalConfiguration;
import io.github.hectorvent.floci.services.guardduty.model.OrganizationConfiguration;
import io.github.hectorvent.floci.services.guardduty.model.OrganizationFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardDutyServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GuardDutyService service =
            new GuardDutyService(new InMemoryStorage<>(), new InMemoryStorage<>());

    @Test
    void createDetectorAppliesDefaultsAndGeneratesIdentifiers() throws Exception {
        Detector detector = service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}"));

        assertEquals(32, detector.getId().length());
        assertEquals("ENABLED", detector.getStatus());
        assertEquals("SIX_HOURS", detector.getFindingPublishingFrequency());
        assertEquals(
                "arn:aws:iam::" + ACCOUNT
                        + ":role/aws-service-role/guardduty.amazonaws.com/AWSServiceRoleForAmazonGuardDuty",
                detector.getServiceRole());
        assertEquals(detector.getCreatedAt(), detector.getUpdatedAt());
        assertNull(detector.getFeatures());
    }

    @Test
    void createDetectorRejectsSecondDetectorInSameRegion() throws Exception {
        service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}"));

        AwsException error = assertThrows(
                AwsException.class,
                () -> service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}")));

        assertEquals("BadRequestException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals(
                "The request is rejected because a detector already exists for the current account.",
                error.getMessage());
    }

    @Test
    void detectorsAreRegionScoped() throws Exception {
        Detector detector = service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}"));
        service.createDetector("us-west-2", ACCOUNT, request("{\"enable\":true}"));

        AwsException error = assertThrows(
                AwsException.class, () -> service.getDetector("us-west-2", detector.getId()));

        assertEquals("BadRequestException", error.getErrorCode());
        assertEquals(GuardDutyService.DETECTOR_NOT_FOUND_MESSAGE, error.getMessage());
    }

    @Test
    void getDetectorRejectsMissingDetectorWithProviderMatchedMessage() {
        AwsException error = assertThrows(
                AwsException.class, () -> service.getDetector(REGION, "does-not-exist"));

        assertEquals("BadRequestException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals(GuardDutyService.DETECTOR_NOT_FOUND_MESSAGE, error.getMessage());
    }

    @Test
    void updateDetectorTogglesStatusAndFrequency() throws Exception {
        Detector detector = service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}"));

        service.updateDetector(REGION, detector.getId(), request(
                "{\"enable\":false,\"findingPublishingFrequency\":\"ONE_HOUR\"}"));

        Detector updated = service.getDetector(REGION, detector.getId());
        assertEquals("DISABLED", updated.getStatus());
        assertEquals("ONE_HOUR", updated.getFindingPublishingFrequency());
    }

    @Test
    void featureAdditionalConfigurationPreservesSubmittedOrder() throws Exception {
        Detector detector = service.createDetector(REGION, ACCOUNT, request("""
                {"enable":true,"features":[
                  {"name":"RUNTIME_MONITORING","status":"ENABLED","additionalConfiguration":[
                    {"name":"ECS_FARGATE_AGENT_MANAGEMENT","status":"ENABLED"},
                    {"name":"EC2_AGENT_MANAGEMENT","status":"ENABLED"},
                    {"name":"EKS_ADDON_MANAGEMENT","status":"DISABLED"}
                  ]},
                  {"name":"S3_DATA_EVENTS","status":"ENABLED"}
                ]}
                """));

        List<DetectorFeature> features = service.getDetector(REGION, detector.getId()).getFeatures();
        assertEquals(List.of("RUNTIME_MONITORING", "S3_DATA_EVENTS"),
                features.stream().map(DetectorFeature::getName).toList());
        assertEquals(
                List.of("ECS_FARGATE_AGENT_MANAGEMENT", "EC2_AGENT_MANAGEMENT", "EKS_ADDON_MANAGEMENT"),
                features.get(0).getAdditionalConfiguration().stream()
                        .map(DetectorAdditionalConfiguration::getName)
                        .toList());
    }

    @Test
    void updateDetectorMergesFeaturesByNameAndAppendsNewOnes() throws Exception {
        Detector detector = service.createDetector(REGION, ACCOUNT, request("""
                {"enable":true,"features":[
                  {"name":"S3_DATA_EVENTS","status":"ENABLED"},
                  {"name":"RDS_LOGIN_EVENTS","status":"ENABLED"}
                ]}
                """));

        service.updateDetector(REGION, detector.getId(), request("""
                {"features":[
                  {"name":"RDS_LOGIN_EVENTS","status":"DISABLED"},
                  {"name":"LAMBDA_NETWORK_LOGS","status":"ENABLED"}
                ]}
                """));

        List<DetectorFeature> features = service.getDetector(REGION, detector.getId()).getFeatures();
        assertEquals(List.of("S3_DATA_EVENTS", "RDS_LOGIN_EVENTS", "LAMBDA_NETWORK_LOGS"),
                features.stream().map(DetectorFeature::getName).toList());
        assertEquals("ENABLED", features.get(0).getStatus());
        assertEquals("DISABLED", features.get(1).getStatus());
        assertEquals("ENABLED", features.get(2).getStatus());
    }

    @Test
    void createDetectorRejectsUnknownFeatureName() {
        AwsException error = assertThrows(
                AwsException.class,
                () -> service.createDetector(REGION, ACCOUNT, request(
                        "{\"enable\":true,\"features\":[{\"name\":\"NOT_A_FEATURE\",\"status\":\"ENABLED\"}]}")));

        assertEquals("BadRequestException", error.getErrorCode());
    }

    @Test
    void createDetectorRejectsInvalidFrequencyAndMissingEnable() {
        AwsException frequencyError = assertThrows(
                AwsException.class,
                () -> service.createDetector(REGION, ACCOUNT, request(
                        "{\"enable\":true,\"findingPublishingFrequency\":\"NEVER\"}")));
        assertEquals("BadRequestException", frequencyError.getErrorCode());

        AwsException enableError = assertThrows(
                AwsException.class, () -> service.createDetector(REGION, ACCOUNT, request("{}")));
        assertEquals("BadRequestException", enableError.getErrorCode());
    }

    @Test
    void deleteDetectorRemovesItAndRejectsSecondDelete() throws Exception {
        Detector detector = service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}"));

        service.deleteDetector(REGION, detector.getId());

        AwsException error = assertThrows(
                AwsException.class, () -> service.deleteDetector(REGION, detector.getId()));
        assertEquals(GuardDutyService.DETECTOR_NOT_FOUND_MESSAGE, error.getMessage());
        assertTrue(service.listDetectorIds(REGION, null, null).items().isEmpty());
    }

    @Test
    void listDetectorIdsReturnsTheRegionalDetector() throws Exception {
        Detector detector = service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}"));

        GuardDutyService.Page<String> page = service.listDetectorIds(REGION, null, null);

        assertEquals(List.of(detector.getId()), page.items());
        assertNull(page.nextToken());
    }

    @Test
    void describeOrganizationConfigurationDefaultsToNone() throws Exception {
        Detector detector = service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}"));

        OrganizationConfiguration configuration =
                service.describeOrganizationConfiguration(REGION, detector.getId());

        assertEquals(false, configuration.getAutoEnable());
        assertEquals("NONE", configuration.getAutoEnableOrganizationMembers());
        assertTrue(configuration.getFeatures().isEmpty());
    }

    @Test
    void organizationConfigurationEchoesMembersAndDerivesAutoEnable() throws Exception {
        Detector detector = service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}"));

        service.updateOrganizationConfiguration(REGION, detector.getId(), request(
                "{\"autoEnableOrganizationMembers\":\"ALL\"}"));

        OrganizationConfiguration configuration =
                service.describeOrganizationConfiguration(REGION, detector.getId());
        assertEquals(true, configuration.getAutoEnable());
        assertEquals("ALL", configuration.getAutoEnableOrganizationMembers());
    }

    @Test
    void organizationFeatureUpdatesMergeWithoutClobberingOtherFeatures() throws Exception {
        Detector detector = service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}"));
        service.updateOrganizationConfiguration(REGION, detector.getId(), request(
                "{\"autoEnableOrganizationMembers\":\"ALL\"}"));

        service.updateOrganizationConfiguration(REGION, detector.getId(), request("""
                {"features":[
                  {"name":"RUNTIME_MONITORING","autoEnable":"ALL","additionalConfiguration":[
                    {"name":"ECS_FARGATE_AGENT_MANAGEMENT","autoEnable":"ALL"},
                    {"name":"EC2_AGENT_MANAGEMENT","autoEnable":"ALL"},
                    {"name":"EKS_ADDON_MANAGEMENT","autoEnable":"NONE"}
                  ]}
                ]}
                """));
        service.updateOrganizationConfiguration(REGION, detector.getId(), request(
                "{\"features\":[{\"name\":\"S3_DATA_EVENTS\",\"autoEnable\":\"NEW\"}]}"));

        OrganizationConfiguration configuration =
                service.describeOrganizationConfiguration(REGION, detector.getId());
        assertEquals("ALL", configuration.getAutoEnableOrganizationMembers());
        assertEquals(List.of("RUNTIME_MONITORING", "S3_DATA_EVENTS"),
                configuration.getFeatures().stream().map(OrganizationFeature::getName).toList());
        assertEquals(
                List.of("ECS_FARGATE_AGENT_MANAGEMENT", "EC2_AGENT_MANAGEMENT", "EKS_ADDON_MANAGEMENT"),
                configuration.getFeatures().get(0).getAdditionalConfiguration().stream()
                        .map(OrganizationAdditionalConfiguration::getName)
                        .toList());
    }

    @Test
    void updateOrganizationConfigurationRejectsMissingDetectorAndBadValues() throws Exception {
        AwsException notFound = assertThrows(
                AwsException.class,
                () -> service.updateOrganizationConfiguration(REGION, "missing", request(
                        "{\"autoEnableOrganizationMembers\":\"ALL\"}")));
        assertEquals(GuardDutyService.DETECTOR_NOT_FOUND_MESSAGE, notFound.getMessage());

        Detector detector = service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}"));
        AwsException badValue = assertThrows(
                AwsException.class,
                () -> service.updateOrganizationConfiguration(REGION, detector.getId(), request(
                        "{\"autoEnableOrganizationMembers\":\"SOME\"}")));
        assertEquals("BadRequestException", badValue.getErrorCode());
    }

    @Test
    void adminAccountLifecycle() throws Exception {
        service.enableOrganizationAdminAccount(REGION, request("{\"adminAccountId\":\"111111111111\"}"));

        GuardDutyService.Page<AdminAccount> accounts =
                service.listOrganizationAdminAccounts(REGION, null, null);
        assertEquals(1, accounts.items().size());
        assertEquals("111111111111", accounts.items().get(0).getAdminAccountId());
        assertEquals("ENABLED", accounts.items().get(0).getAdminStatus());

        AwsException conflict = assertThrows(
                AwsException.class,
                () -> service.enableOrganizationAdminAccount(REGION, request(
                        "{\"adminAccountId\":\"222222222222\"}")));
        assertEquals("BadRequestException", conflict.getErrorCode());

        service.disableOrganizationAdminAccount(REGION, request("{\"adminAccountId\":\"111111111111\"}"));
        assertTrue(service.listOrganizationAdminAccounts(REGION, null, null).items().isEmpty());

        AwsException alreadyDisabled = assertThrows(
                AwsException.class,
                () -> service.disableOrganizationAdminAccount(REGION, request(
                        "{\"adminAccountId\":\"111111111111\"}")));
        assertEquals(GuardDutyService.ADMIN_ALREADY_DISABLED_MESSAGE, alreadyDisabled.getMessage());
    }

    @Test
    void enableOrganizationAdminAccountRejectsMalformedAccountId() {
        AwsException error = assertThrows(
                AwsException.class,
                () -> service.enableOrganizationAdminAccount(REGION, request(
                        "{\"adminAccountId\":\"not-an-account\"}")));

        assertEquals("BadRequestException", error.getErrorCode());
    }

    @Test
    void tagOperationsRoundTripThroughTheDetectorArn() throws Exception {
        Detector detector = service.createDetector(REGION, ACCOUNT, request(
                "{\"enable\":true,\"tags\":{\"env\":\"test\"}}"));
        String arn = "arn:aws:guardduty:" + REGION + ":" + ACCOUNT + ":detector/" + detector.getId();

        service.tagResource(arn, Map.of("team", "security"));
        assertEquals(Map.of("env", "test", "team", "security"), service.listTags(arn));

        service.untagResource(arn, List.of("env"));
        assertEquals(Map.of("team", "security"), service.listTags(arn));

        AwsException error = assertThrows(
                AwsException.class,
                () -> service.listTags("arn:aws:guardduty:" + REGION + ":" + ACCOUNT + ":detector/missing"));
        assertEquals(GuardDutyService.DETECTOR_NOT_FOUND_MESSAGE, error.getMessage());
    }

    @Test
    void detectorSurvivesPersistentStorageReloadWithOrderIntact(@TempDir Path tempDir) throws Exception {
        Path detectorFile = tempDir.resolve("detectors.json");
        Path adminFile = tempDir.resolve("admins.json");
        GuardDutyService firstService = new GuardDutyService(
                loadedStore(detectorFile, new TypeReference<Map<String, Detector>>() {
                }),
                loadedStore(adminFile, new TypeReference<Map<String, AdminAccount>>() {
                }));
        Detector created = firstService.createDetector(REGION, ACCOUNT, request("""
                {"enable":true,"tags":{"env":"test"},"features":[
                  {"name":"RUNTIME_MONITORING","status":"ENABLED","additionalConfiguration":[
                    {"name":"ECS_FARGATE_AGENT_MANAGEMENT","status":"ENABLED"},
                    {"name":"EC2_AGENT_MANAGEMENT","status":"ENABLED"},
                    {"name":"EKS_ADDON_MANAGEMENT","status":"DISABLED"}
                  ]}
                ]}
                """));
        firstService.updateOrganizationConfiguration(REGION, created.getId(), request(
                "{\"autoEnableOrganizationMembers\":\"ALL\"}"));

        GuardDutyService reloadedService = new GuardDutyService(
                loadedStore(detectorFile, new TypeReference<Map<String, Detector>>() {
                }),
                loadedStore(adminFile, new TypeReference<Map<String, AdminAccount>>() {
                }));
        Detector reloaded = reloadedService.getDetector(REGION, created.getId());

        assertEquals(created.getId(), reloaded.getId());
        assertEquals(created.getCreatedAt(), reloaded.getCreatedAt());
        assertEquals("test", reloaded.getTags().get("env"));
        assertEquals(
                List.of("ECS_FARGATE_AGENT_MANAGEMENT", "EC2_AGENT_MANAGEMENT", "EKS_ADDON_MANAGEMENT"),
                reloaded.getFeatures().get(0).getAdditionalConfiguration().stream()
                        .map(DetectorAdditionalConfiguration::getName)
                        .toList());
        assertEquals("ALL",
                reloadedService.describeOrganizationConfiguration(REGION, created.getId())
                        .getAutoEnableOrganizationMembers());
    }

    private static <V> PersistentStorage<String, V> loadedStore(
            Path file, TypeReference<Map<String, V>> type) {
        PersistentStorage<String, V> store = new PersistentStorage<>(file, type);
        store.load();
        return store;
    }

    private JsonNode request(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
