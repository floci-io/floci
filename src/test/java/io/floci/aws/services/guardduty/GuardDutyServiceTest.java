package io.floci.aws.services.guardduty;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.PersistentStorage;
import io.floci.aws.services.guardduty.model.AdminAccount;
import io.floci.aws.services.guardduty.model.Detector;
import io.floci.aws.services.guardduty.model.DetectorAdditionalConfiguration;
import io.floci.aws.services.guardduty.model.DetectorFeature;
import io.floci.aws.services.guardduty.model.MemberAccount;
import io.floci.aws.services.guardduty.model.OrganizationAdditionalConfiguration;
import io.floci.aws.services.guardduty.model.OrganizationConfiguration;
import io.floci.aws.services.guardduty.model.OrganizationFeature;
import io.floci.aws.services.guardduty.model.PublishingDestination;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardDutyServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GuardDutyService service =
            new GuardDutyService(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>());

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
        assertTrue(service.listDetectorIds(REGION, ACCOUNT, null, null).items().isEmpty());
    }

    @Test
    void listDetectorIdsReturnsTheRegionalDetector() throws Exception {
        Detector detector = service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}"));

        GuardDutyService.Page<String> page = service.listDetectorIds(REGION, ACCOUNT, null, null);

        assertEquals(List.of(detector.getId()), page.items());
        assertNull(page.nextToken());
    }

    @Test
    void detectorsAreScopedByAccountWithinTheSameRegion() throws Exception {
        String otherAccount = "222222222222";
        Detector first = service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}"));
        Detector second = service.createDetector(REGION, otherAccount, request("{\"enable\":true}"));

        assertEquals(List.of(first.getId()), service.listDetectorIds(REGION, ACCOUNT, null, null).items());
        assertEquals(List.of(second.getId()), service.listDetectorIds(REGION, otherAccount, null, null).items());
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
    void delegatedAdministratorCreatesEnabledOrganizationMembers() throws Exception {
        String adminAccount = "111111111111";
        String managementAccount = "222222222222";
        service.enableOrganizationAdminAccount(REGION, request("{\"adminAccountId\":\"" + adminAccount + "\"}"));
        Detector adminDetector = service.createDetector(REGION, adminAccount, request("{\"enable\":true}"));
        service.createDetector(REGION, managementAccount, request("{\"enable\":true}"));

        service.createMembers(REGION, adminDetector.getId(), request(
                "{\"accountDetails\":[{\"accountId\":\"" + managementAccount
                        + "\",\"email\":\"management@example.com\"}]}"));

        List<MemberAccount> members = service.listMembers(REGION, adminDetector.getId(), null, null, "true").items();
        assertEquals(1, members.size());
        assertEquals(managementAccount, members.get(0).accountId());
        assertEquals("Enabled", members.get(0).relationshipStatus());
    }

    @Test
    void delegatedAdministratorIsVisibleAcrossAccountPartitions() throws Exception {
        String adminAccount = "111111111111";
        String managementAccount = "222222222222";
        AccountAwareStorageBackend<Detector> detectors = AccountAwareStorageBackend.inMemory(adminAccount);
        AccountAwareStorageBackend<AdminAccount> admins = AccountAwareStorageBackend.inMemory(adminAccount);
        AccountAwareStorageBackend<MemberAccount> members = AccountAwareStorageBackend.inMemory(adminAccount);
        admins.putForAccount(managementAccount, REGION + "::" + adminAccount,
                new AdminAccount(adminAccount, "ENABLED"));
        GuardDutyService partitioned = new GuardDutyService(detectors, admins, members);
        Detector adminDetector = partitioned.createDetector(REGION, adminAccount, request("{\"enable\":true}"));

        partitioned.createMembers(REGION, adminDetector.getId(), request(
                "{\"accountDetails\":[{\"accountId\":\"" + managementAccount
                        + "\",\"email\":\"management@example.com\"}]}"));

        List<MemberAccount> listed = partitioned.listMembers(
                REGION, adminDetector.getId(), null, null, "true").items();
        assertEquals(1, listed.size());
        assertEquals("Enabled", listed.get(0).relationshipStatus());
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
        Path memberFile = tempDir.resolve("members.json");
        GuardDutyService firstService = new GuardDutyService(
                loadedStore(detectorFile, new TypeReference<Map<String, Detector>>() {
                }),
                loadedStore(adminFile, new TypeReference<Map<String, AdminAccount>>() {
                }),
                loadedStore(memberFile, new TypeReference<Map<String, MemberAccount>>() {
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
                }),
                loadedStore(memberFile, new TypeReference<Map<String, MemberAccount>>() {
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

    private static final String DESTINATION_NOT_FOUND =
            "The request is rejected because the one or more input parameters have invalid values.";

    @Test
    void createPublishingDestinationWithSameClientTokenReturnsOriginal() throws Exception {
        String detectorId = service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}")).getId();
        String body = "{\"destinationType\":\"S3\",\"clientToken\":\"tok-1\","
                + "\"destinationProperties\":{\"destinationArn\":\"arn:aws:s3:::a\"}}";
        String first = service.createPublishingDestination(REGION, detectorId, request(body)).destinationId();
        String retry = service.createPublishingDestination(REGION, detectorId, request(body)).destinationId();
        String other = service.createPublishingDestination(REGION, detectorId,
                request(body.replace("tok-1", "tok-2"))).destinationId();

        assertEquals(first, retry);
        assertNotEquals(first, other);
        assertEquals(2, service.listPublishingDestinations(REGION, detectorId, null, null).items().size());
    }

    @Test
    void createPublishingDestinationRejectsClientTokenOverSixtyFourCharacters() throws Exception {
        String detectorId = service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}")).getId();
        String body = "{\"destinationType\":\"S3\",\"clientToken\":\"%s\","
                + "\"destinationProperties\":{\"destinationArn\":\"arn:aws:s3:::a\"}}";

        AwsException error = assertThrows(AwsException.class,
                () -> service.createPublishingDestination(REGION, detectorId, request(body.formatted("t".repeat(65)))));
        assertEquals("BadRequestException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals("clientToken must be at most 64 characters.", error.getMessage());
        assertTrue(service.listPublishingDestinations(REGION, detectorId, null, null).items().isEmpty());

        service.createPublishingDestination(REGION, detectorId, request(body.formatted("t".repeat(64))));
        assertEquals(1, service.listPublishingDestinations(REGION, detectorId, null, null).items().size());
    }

    /** The model's length constraint counts Unicode code points, not UTF-16 code units. */
    @Test
    void createPublishingDestinationMeasuresClientTokenInCodePoints() throws Exception {
        String detectorId = service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}")).getId();
        String body = "{\"destinationType\":\"S3\",\"clientToken\":\"%s\","
                + "\"destinationProperties\":{\"destinationArn\":\"arn:aws:s3:::a\"}}";
        String emoji = "😀";

        service.createPublishingDestination(REGION, detectorId, request(body.formatted(emoji.repeat(64))));
        assertEquals(1, service.listPublishingDestinations(REGION, detectorId, null, null).items().size());

        AwsException error = assertThrows(AwsException.class,
                () -> service.createPublishingDestination(REGION, detectorId, request(body.formatted(emoji.repeat(65)))));
        assertEquals("clientToken must be at most 64 characters.", error.getMessage());
        assertEquals(1, service.listPublishingDestinations(REGION, detectorId, null, null).items().size());
    }

    @Test
    void publishingDestinationSurvivesPersistentStorageReloadWithClientToken(@TempDir Path tempDir)
            throws Exception {
        Path detectorFile = tempDir.resolve("detectors.json");
        Path adminFile = tempDir.resolve("admins.json");
        Path memberFile = tempDir.resolve("members.json");
        Path destinationFile = tempDir.resolve("destinations.json");
        GuardDutyService firstService = new GuardDutyService(
                loadedStore(detectorFile, new TypeReference<Map<String, Detector>>() {
                }),
                loadedStore(adminFile, new TypeReference<Map<String, AdminAccount>>() {
                }),
                loadedStore(memberFile, new TypeReference<Map<String, MemberAccount>>() {
                }),
                new InMemoryStorage<>(),
                loadedStore(destinationFile, new TypeReference<Map<String, PublishingDestination>>() {
                }));
        String detectorId = firstService.createDetector(REGION, ACCOUNT, request("{\"enable\":true}")).getId();
        String body = "{\"destinationType\":\"S3\",\"clientToken\":\"tok-reload\","
                + "\"destinationProperties\":{\"destinationArn\":\"arn:aws:s3:::a\","
                + "\"kmsKeyArn\":\"arn:aws:kms:us-east-1:000000000000:key/k\"}}";
        PublishingDestination created = firstService.createPublishingDestination(REGION, detectorId, request(body));

        GuardDutyService reloadedService = new GuardDutyService(
                loadedStore(detectorFile, new TypeReference<Map<String, Detector>>() {
                }),
                loadedStore(adminFile, new TypeReference<Map<String, AdminAccount>>() {
                }),
                loadedStore(memberFile, new TypeReference<Map<String, MemberAccount>>() {
                }),
                new InMemoryStorage<>(),
                loadedStore(destinationFile, new TypeReference<Map<String, PublishingDestination>>() {
                }));

        assertEquals(created, reloadedService.describePublishingDestination(
                REGION, detectorId, created.destinationId()));
        assertEquals("tok-reload", reloadedService.describePublishingDestination(
                REGION, detectorId, created.destinationId()).clientToken());
        PublishingDestination replay = reloadedService.createPublishingDestination(REGION, detectorId, request(body));
        assertEquals(created.destinationId(), replay.destinationId());
        assertEquals(1, reloadedService.listPublishingDestinations(REGION, detectorId, null, null).items().size());
    }

    @Test
    void deleteDetectorRemovesItsPublishingDestinations() throws Exception {
        InMemoryStorage<String, PublishingDestination> destinations = new InMemoryStorage<>();
        GuardDutyService svc = new GuardDutyService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), destinations);
        String keep = svc.createDetector(REGION, ACCOUNT, request("{\"enable\":true}")).getId();
        String dropRegion = "us-west-2";
        String drop = svc.createDetector(dropRegion, ACCOUNT, request("{\"enable\":true}")).getId();
        String body = "{\"destinationType\":\"S3\",\"destinationProperties\":{\"destinationArn\":\"arn:aws:s3:::a\"}}";
        svc.createPublishingDestination(REGION, keep, request(body));
        svc.createPublishingDestination(dropRegion, drop, request(body));

        svc.deleteDetector(dropRegion, drop);

        assertEquals(1, destinations.scan(k -> true).size());
        assertEquals(1, svc.listPublishingDestinations(REGION, keep, null, null).items().size());
    }

    @Test
    void publishingDestinationsArePartitionedByAccountEvenForTheSameDetector() throws Exception {
        // Both services see one detector (plain store), so only the destination store's account
        // partitioning can keep the second account away from the first account's destination.
        InMemoryStorage<String, Detector> sharedDetectors = new InMemoryStorage<>();
        InMemoryStorage<String, PublishingDestination> sharedDestinations = new InMemoryStorage<>();
        GuardDutyService first = new GuardDutyService(sharedDetectors, new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new AccountAwareStorageBackend<>(sharedDestinations, null, "111111111111"));
        GuardDutyService second = new GuardDutyService(sharedDetectors, new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new AccountAwareStorageBackend<>(sharedDestinations, null, "222222222222"));
        String detectorId = first.createDetector(REGION, ACCOUNT, request("{\"enable\":true}")).getId();
        String destinationId = first.createPublishingDestination(REGION, detectorId, request(
                "{\"destinationType\":\"S3\",\"destinationProperties\":{\"destinationArn\":\"arn:aws:s3:::a\"}}"))
                .destinationId();

        AwsException hidden = assertThrows(AwsException.class,
                () -> second.describePublishingDestination(REGION, detectorId, destinationId));
        assertEquals(DESTINATION_NOT_FOUND, hidden.getMessage());
        assertTrue(second.listPublishingDestinations(REGION, detectorId, null, null).items().isEmpty());
        assertEquals(destinationId, first.describePublishingDestination(REGION, detectorId, destinationId)
                .destinationId());
    }

    @Test
    void listPublishingDestinationsPagesWithMaxResultsAndRejectsBadTokens() throws Exception {
        String detectorId = service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}")).getId();
        List<String> created = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            created.add(createDestination(detectorId, "arn:aws:s3:::bucket-" + i).destinationId());
        }
        created.sort(null);

        GuardDutyService.Page<PublishingDestination> firstPage =
                service.listPublishingDestinations(REGION, detectorId, "2", null);
        assertEquals(created.subList(0, 2),
                firstPage.items().stream().map(PublishingDestination::destinationId).toList());
        GuardDutyService.Page<PublishingDestination> lastPage =
                service.listPublishingDestinations(REGION, detectorId, "2", firstPage.nextToken());
        assertEquals(created.subList(2, 3),
                lastPage.items().stream().map(PublishingDestination::destinationId).toList());
        assertNull(lastPage.nextToken());

        service.deletePublishingDestination(REGION, detectorId, created.get(2));
        AwsException stale = assertThrows(AwsException.class,
                () -> service.listPublishingDestinations(REGION, detectorId, "2", firstPage.nextToken()));
        assertEquals("nextToken is invalid.", stale.getMessage());
        AwsException garbage = assertThrows(AwsException.class,
                () -> service.listPublishingDestinations(REGION, detectorId, null, "not-a-token"));
        assertEquals("nextToken is invalid.", garbage.getMessage());
        AwsException tooMany = assertThrows(AwsException.class,
                () -> service.listPublishingDestinations(REGION, detectorId, "0", null));
        assertEquals("BadRequestException", tooMany.getErrorCode());
    }

    @Test
    void updatePublishingDestinationKeepsOmittedFieldsAndValidatesInput() throws Exception {
        String detectorId = service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}")).getId();
        String destinationId = service.createPublishingDestination(REGION, detectorId, request(
                "{\"destinationType\":\"S3\",\"destinationProperties\":"
                        + "{\"destinationArn\":\"arn:aws:s3:::a\",\"kmsKeyArn\":\"arn:aws:kms:k1\"}}"))
                .destinationId();

        service.updatePublishingDestination(REGION, detectorId, destinationId,
                request("{\"destinationProperties\":{\"kmsKeyArn\":\"arn:aws:kms:k2\"}}"));
        PublishingDestination afterKms = service.describePublishingDestination(REGION, detectorId, destinationId);
        assertEquals("arn:aws:s3:::a", afterKms.destinationArn());
        assertEquals("arn:aws:kms:k2", afterKms.kmsKeyArn());

        service.updatePublishingDestination(REGION, detectorId, destinationId,
                request("{\"destinationProperties\":{\"destinationArn\":\"arn:aws:s3:::b\"}}"));
        PublishingDestination afterArn = service.describePublishingDestination(REGION, detectorId, destinationId);
        assertEquals("arn:aws:s3:::b", afterArn.destinationArn());
        assertEquals("arn:aws:kms:k2", afterArn.kmsKeyArn());

        service.updatePublishingDestination(REGION, detectorId, destinationId, request("{}"));
        assertEquals(afterArn, service.describePublishingDestination(REGION, detectorId, destinationId));

        AwsException notObject = assertThrows(AwsException.class,
                () -> service.updatePublishingDestination(REGION, detectorId, destinationId,
                        request("{\"destinationProperties\":\"arn:aws:s3:::c\"}")));
        assertEquals("destinationProperties must be a JSON object.", notObject.getMessage());
        AwsException notText = assertThrows(AwsException.class,
                () -> service.updatePublishingDestination(REGION, detectorId, destinationId,
                        request("{\"destinationProperties\":{\"destinationArn\":5}}")));
        assertEquals("destinationArn must be a string.", notText.getMessage());
        assertEquals(afterArn, service.describePublishingDestination(REGION, detectorId, destinationId));
    }

    @Test
    void unknownPublishingDestinationIsRejectedWithProviderMatchedMessage() throws Exception {
        String detectorId = service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}")).getId();
        for (Executable call : List.<Executable>of(
                () -> service.describePublishingDestination(REGION, detectorId, "missing"),
                () -> service.updatePublishingDestination(REGION, detectorId, "missing",
                        request("{\"destinationProperties\":{\"destinationArn\":\"arn:aws:s3:::a\"}}")),
                () -> service.deletePublishingDestination(REGION, detectorId, "missing"))) {
            AwsException error = assertThrows(AwsException.class, call);
            assertEquals("BadRequestException", error.getErrorCode());
            assertEquals(400, error.getHttpStatus());
            assertEquals(DESTINATION_NOT_FOUND, error.getMessage());
        }
    }

    @Test
    void createPublishingDestinationRequiresDestinationProperties() throws Exception {
        String detectorId = service.createDetector(REGION, ACCOUNT, request("{\"enable\":true}")).getId();
        AwsException error = assertThrows(AwsException.class,
                () -> service.createPublishingDestination(REGION, detectorId,
                        request("{\"destinationType\":\"S3\"}")));
        assertEquals("BadRequestException", error.getErrorCode());
        assertEquals("destinationProperties must be a JSON object.", error.getMessage());
        assertTrue(service.listPublishingDestinations(REGION, detectorId, null, null).items().isEmpty());
    }

    private PublishingDestination createDestination(String detectorId, String bucketArn) {
        return service.createPublishingDestination(REGION, detectorId, request(
                "{\"destinationType\":\"S3\",\"destinationProperties\":{\"destinationArn\":\"" + bucketArn + "\"}}"));
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
