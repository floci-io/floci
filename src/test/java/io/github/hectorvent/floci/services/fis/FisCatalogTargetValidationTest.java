package io.github.hectorvent.floci.services.fis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FisCatalogTargetValidationTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String ROLE_ARN = "arn:aws:iam::" + ACCOUNT_ID + ":role/fis-role";

    private ObjectMapper mapper;
    private FisCatalog catalog;
    private FisService service;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        RegionResolver resolver = new RegionResolver(REGION, ACCOUNT_ID);
        catalog = new FisCatalog(mapper, resolver);
        service = new FisService(FisStores.inMemory(), mapper, resolver, catalog);
    }

    @Test
    void exposesOfficialActionSlotsAndRequiredParameterMetadata() {
        assertEquals(50, catalog.actionIds().size());
        catalog.actionIds().forEach(id -> {
            ObjectNode action = catalog.action(REGION, id);
            assertEquals(id, action.path("id").asText());
            assertFalse(action.path("description").asText().isBlank());
            assertTrue(action.path("targets").isObject());
            assertTrue(action.path("parameters").isObject());
        });

        ObjectNode customResource = catalog.action(REGION, "aws:eks:inject-kubernetes-custom-resource");
        assertTrue(customResource.path("targets").has("Cluster"));
        assertFalse(customResource.path("targets").has("Clusters"));
        assertRequired(customResource, "kubernetesApiVersion", true);
        assertRequired(customResource, "kubernetesKind", true);
        assertRequired(customResource, "kubernetesNamespace", true);
        assertRequired(customResource, "kubernetesSpec", true);
        assertRequired(customResource, "maxDuration", true);

        ObjectNode vpcEndpoint = catalog.action(REGION, "aws:network:disrupt-vpc-endpoint");
        assertTrue(vpcEndpoint.path("targets").has("VPCEndpoints"));
        assertFalse(vpcEndpoint.path("targets").has("VpcEndpoints"));

        ObjectNode injectApi = catalog.action(REGION, "aws:fis:inject-api-internal-error");
        assertRequired(injectApi, "duration", true);
        assertRequired(injectApi, "service", true);
        assertRequired(injectApi, "percentage", true);
        assertRequired(injectApi, "operations", true);

        ObjectNode zonalAutoshift = catalog.action(REGION, "aws:arc:start-zonal-autoshift");
        assertRequired(zonalAutoshift, "duration", true);
        assertRequired(zonalAutoshift, "availabilityZoneIdentifier", true);
        assertRequired(zonalAutoshift, "managedResourceTypes", false);
        assertRequired(zonalAutoshift, "zonalAutoshiftStatus", false);

        ObjectNode podCpu = catalog.action(REGION, "aws:eks:pod-cpu-stress");
        assertRequired(podCpu, "duration", true);
        assertRequired(podCpu, "kubernetesServiceAccount", true);
        assertRequired(podCpu, "percent", false);
        assertRequired(podCpu, "workers", false);
        assertRequired(podCpu, "fisPodSecurityPolicy", false);

        ObjectNode pod = catalog.targetResourceType("aws:eks:pod");
        assertRequired(pod, "availabilityZoneIdentifier", false);
        assertRequired(pod, "clusterIdentifier", true);
        assertRequired(pod, "namespace", true);
        assertRequired(pod, "selectorType", true);
        assertRequired(pod, "selectorValue", true);
        assertRequired(pod, "targetContainerName", false);
    }

    @Test
    void acceptsParameterOnlyTargetsAndHonorsEmptyTargetSkip() throws Exception {
        ObjectNode request = eksPodTemplate();

        String templateId = service.createExperimentTemplate(REGION, request)
                .path("experimentTemplate").path("id").asText();
        ObjectNode started = service.startExperiment(REGION, mapper.readTree("""
                {
                  "clientToken":"%s",
                  "experimentTemplateId":"%s",
                  "experimentOptions":{"actionsMode":"skip-all"}
                }
                """.formatted(UUID.randomUUID(), templateId)))
                .path("experiment").deepCopy();
        String experimentId = started.path("id").asText();

        JsonNode resolved = service.listExperimentResolvedTargets(REGION, experimentId, 100, null, null)
                .path("resolvedTargets");
        assertTrue(resolved.isEmpty());
        assertEquals("completed", started.path("state").path("status").asText());
        assertEquals("skipped", started.path("actions").path("delete")
                .path("state").path("status").asText());
    }

    @Test
    void failsExperimentWhenSelectorTargetDoesNotResolveByDefault() throws Exception {
        ObjectNode request = ec2Template("ALL");
        ObjectNode target = (ObjectNode) request.path("targets").path("instances");
        target.remove("resourceArns");
        target.putObject("resourceTags").put("environment", "missing");

        String templateId = service.createExperimentTemplate(REGION, request)
                .path("experimentTemplate").path("id").asText();
        ObjectNode experiment = service.startExperiment(REGION, mapper.readTree("""
                {"clientToken":"%s","experimentTemplateId":"%s"}
                """.formatted(UUID.randomUUID(), templateId)))
                .path("experiment").deepCopy();

        assertEquals("failed", experiment.path("state").path("status").asText());
        assertEquals("failed", experiment.path("actions").path("stop")
                .path("state").path("status").asText());
        assertTrue(service.listExperimentResolvedTargets(REGION, experiment.path("id").asText(),
                100, null, null).path("resolvedTargets").isEmpty());
    }

    @Test
    void validatesFiltersAndRejectsUnsupportedParameters() throws Exception {
        ObjectNode filterOnly = ec2Template("ALL");
        ObjectNode filterOnlyTarget = (ObjectNode) filterOnly.path("targets").path("instances");
        filterOnlyTarget.remove("resourceArns");
        filterOnlyTarget.set("filters", mapper.readTree("""
                [{"path":"State.Name","values":["running"]}]
                """));
        String filterOnlyId = service.createExperimentTemplate(REGION, filterOnly)
                .path("experimentTemplate").path("id").asText();
        assertEquals(1, service.getExperimentTemplate(REGION, filterOnlyId)
                .path("experimentTemplate").path("targets").path("instances").path("filters").size());

        ObjectNode valid = ec2Template("PERCENT(50)");
        ObjectNode validTarget = (ObjectNode) valid.path("targets").path("instances");
        validTarget.remove("resourceArns");
        validTarget.putObject("resourceTags").put("environment", "test");
        validTarget.set("filters", mapper.readTree("""
                [{"path":"State.Name","values":["running","stopped"]}]
                """));
        service.createExperimentTemplate(REGION, valid);

        ObjectNode missingFilterPath = ec2Template("ALL");
        ObjectNode missingPathTarget = (ObjectNode) missingFilterPath.path("targets").path("instances");
        missingPathTarget.remove("resourceArns");
        missingPathTarget.putObject("resourceTags").put("environment", "test");
        missingPathTarget.set("filters", mapper.readTree("""
                [{"values":["running"]}]
                """));
        assertValidation(() -> service.createExperimentTemplate(REGION, missingFilterPath));

        ObjectNode unknownActionParameter = ec2Template("ALL");
        ((ObjectNode) unknownActionParameter.path("actions").path("stop"))
                .putObject("parameters").put("notSupported", "value");
        assertValidation(() -> service.createExperimentTemplate(REGION, unknownActionParameter));

        ObjectNode unknownTargetParameter = ec2Template("ALL");
        ((ObjectNode) unknownTargetParameter.path("targets").path("instances"))
                .putObject("parameters").put("notSupported", "value");
        assertValidation(() -> service.createExperimentTemplate(REGION, unknownTargetParameter));

        ObjectNode arnsAndFilters = ec2Template("ALL");
        ((ObjectNode) arnsAndFilters.path("targets").path("instances")).set("filters", mapper.readTree("""
                [{"path":"State.Name","values":["running"]}]
                """));
        assertValidation(() -> service.createExperimentTemplate(REGION, arnsAndFilters));
    }

    @Test
    void percentSelectionRoundsDown() throws Exception {
        ObjectNode request = ec2Template("PERCENT(50)");
        String templateId = service.createExperimentTemplate(REGION, request)
                .path("experimentTemplate").path("id").asText();
        String experimentId = service.startExperiment(REGION, mapper.readTree("""
                {"clientToken":"%s","experimentTemplateId":"%s","experimentOptions":{"actionsMode":"skip-all"}}
                """.formatted(UUID.randomUUID(), templateId)))
                .path("experiment").path("id").asText();

        assertEquals(2, service.listExperimentResolvedTargets(REGION, experimentId, 100, null, null)
                .path("resolvedTargets").size());
    }

    @Test
    void countSelectionSamplesArnsInsteadOfTakingRequestOrder() throws Exception {
        RegionResolver resolver = new RegionResolver(REGION, ACCOUNT_ID);
        service = new FisService(
                FisStores.inMemory(), mapper, resolver, catalog, new Random(12345L));
        ObjectNode request = ec2Template("COUNT(1)");
        String firstArn = request.path("targets").path("instances")
                .path("resourceArns").path(0).asText();
        String templateId = service.createExperimentTemplate(REGION, request)
                .path("experimentTemplate").path("id").asText();
        String experimentId = service.startExperiment(REGION, mapper.readTree("""
                {"clientToken":"%s","experimentTemplateId":"%s","experimentOptions":{"actionsMode":"skip-all"}}
                """.formatted(UUID.randomUUID(), templateId)))
                .path("experiment").path("id").asText();

        JsonNode resolved = service.listExperimentResolvedTargets(
                REGION, experimentId, 100, null, "instances").path("resolvedTargets");
        assertEquals(1, resolved.size());
        assertNotEquals(firstArn, resolved.path(0).path("targetInformation").path("arn").asText());
    }

    @Test
    void rejectsOversizedCountAndFailsUnresolvedArnTargetsEvenInSkipMode() throws Exception {
        ObjectNode oversizedCount = ec2Template("COUNT(99999999999999999999)");
        assertValidation(() -> service.createExperimentTemplate(REGION, oversizedCount));

        ObjectNode unresolvedArn = ec2Template("PERCENT(5)");
        unresolvedArn.putObject("experimentOptions").put("emptyTargetResolutionMode", "skip");
        String templateId = service.createExperimentTemplate(REGION, unresolvedArn)
                .path("experimentTemplate").path("id").asText();
        ObjectNode experiment = service.startExperiment(REGION, mapper.readTree("""
                {"clientToken":"%s","experimentTemplateId":"%s"}
                """.formatted(UUID.randomUUID(), templateId)))
                .path("experiment").deepCopy();

        assertEquals("failed", experiment.path("state").path("status").asText());
        assertEquals("failed", experiment.path("actions").path("stop")
                .path("state").path("status").asText());
        assertTrue(service.listExperimentResolvedTargets(REGION, experiment.path("id").asText(),
                100, null, null).path("resolvedTargets").isEmpty());
    }

    @Test
    void enforcesActionSpecificTargetSelectionMethods() throws Exception {
        ObjectNode podArns = eksPodTemplate();
        ((ObjectNode) podArns.path("targets").path("pods"))
                .putArray("resourceArns")
                .add("arn:aws:eks:us-east-1:000000000000:pod/test");
        assertValidation(() -> service.createExperimentTemplate(REGION, podArns));

        ObjectNode podTags = eksPodTemplate();
        ((ObjectNode) podTags.path("targets").path("pods"))
                .putObject("resourceTags").put("environment", "test");
        assertValidation(() -> service.createExperimentTemplate(REGION, podTags));

        ObjectNode podFilters = eksPodTemplate();
        ((ObjectNode) podFilters.path("targets").path("pods")).set("filters", mapper.readTree("""
                [{"path":"Status.Phase","values":["Running"]}]
                """));
        assertValidation(() -> service.createExperimentTemplate(REGION, podFilters));

        service.createExperimentTemplate(REGION, eksCustomResourceTemplate(false));

        ObjectNode multipleClusters = eksCustomResourceTemplate(false);
        ((ArrayNode) multipleClusters.path("targets").path("cluster").path("resourceArns"))
                .add("arn:aws:eks:us-east-1:000000000000:cluster/second");
        assertValidation(() -> service.createExperimentTemplate(REGION, multipleClusters));

        ObjectNode clusterTags = eksCustomResourceTemplate(true);
        assertValidation(() -> service.createExperimentTemplate(REGION, clusterTags));

        service.createExperimentTemplate(REGION, s3PauseReplicationTemplate(true));
        ObjectNode bucketArn = s3PauseReplicationTemplate(false);
        assertValidation(() -> service.createExperimentTemplate(REGION, bucketArn));
        ObjectNode bucketFilters = s3PauseReplicationTemplate(true);
        ((ObjectNode) bucketFilters.path("targets").path("buckets")).set("filters", mapper.readTree("""
                [{"path":"Name","values":["source-bucket"]}]
                """));
        assertValidation(() -> service.createExperimentTemplate(REGION, bucketFilters));

        service.createExperimentTemplate(REGION, ec2InsufficientCapacityTemplate(false));
        ObjectNode roleTags = ec2InsufficientCapacityTemplate(true);
        assertValidation(() -> service.createExperimentTemplate(REGION, roleTags));
    }

    @Test
    void validatesLogConfigurationSmithyShapes() throws Exception {
        ObjectNode missingVersion = waitTemplate(false);
        missingVersion.putObject("logConfiguration");
        assertValidation(() -> service.createExperimentTemplate(REGION, missingVersion));

        ObjectNode fractionalVersion = waitTemplate(false);
        fractionalVersion.putObject("logConfiguration").put("logSchemaVersion", 1.5);
        assertValidation(() -> service.createExperimentTemplate(REGION, fractionalVersion));

        ObjectNode valid = waitTemplate(false);
        ObjectNode validLogConfiguration = valid.putObject("logConfiguration");
        validLogConfiguration.put("logSchemaVersion", 2);
        validLogConfiguration.putObject("cloudWatchLogsConfiguration")
                .put("logGroupArn", "arn:aws:logs:us-east-1:000000000000:log-group:fis-logs");
        validLogConfiguration.putObject("s3Configuration")
                .put("bucketName", "fis-log-bucket")
                .put("prefix", "experiment logs/");
        String templateId = service.createExperimentTemplate(REGION, valid)
                .path("experimentTemplate").path("id").asText();

        ObjectNode emptyUpdate = mapper.createObjectNode();
        emptyUpdate.putObject("logConfiguration");
        assertTrue(service.updateExperimentTemplate(REGION, templateId, emptyUpdate)
                .path("experimentTemplate").path("logConfiguration").isEmpty());

        ObjectNode missingLogGroupArn = mapper.createObjectNode();
        missingLogGroupArn.putObject("logConfiguration").putObject("cloudWatchLogsConfiguration");
        assertValidation(() -> service.updateExperimentTemplate(REGION, templateId, missingLogGroupArn));

        ObjectNode emptyPrefix = mapper.createObjectNode();
        emptyPrefix.putObject("logConfiguration").putObject("s3Configuration")
                .put("bucketName", "fis-log-bucket")
                .put("prefix", "");
        assertValidation(() -> service.updateExperimentTemplate(REGION, templateId, emptyPrefix));
    }

    @Test
    void validatesExperimentReportConfigurationSmithyShapes() throws Exception {
        ObjectNode valid = waitTemplate(false);
        ObjectNode report = valid.putObject("experimentReportConfiguration");
        report.put("preExperimentDuration", "PT5M");
        report.put("postExperimentDuration", "PT10M");
        report.putObject("outputs").putObject("s3Configuration")
                .put("bucketName", "fis-report-bucket")
                .put("prefix", "reports/fis");
        report.putObject("dataSources").putArray("cloudWatchDashboards")
                .addObject().put("dashboardIdentifier",
                        "arn:aws:cloudwatch::000000000000:dashboard/fis-dashboard");
        String templateId = service.createExperimentTemplate(REGION, valid)
                .path("experimentTemplate").path("id").asText();
        assertEquals("PT5M", service.getExperimentTemplate(REGION, templateId)
                .path("experimentTemplate").path("experimentReportConfiguration")
                .path("preExperimentDuration").asText());

        ObjectNode validUpdate = mapper.createObjectNode();
        validUpdate.putObject("experimentReportConfiguration")
                .put("postExperimentDuration", "PT15M")
                .putObject("dataSources").putArray("cloudWatchDashboards").addObject();
        assertEquals("PT15M", service.updateExperimentTemplate(REGION, templateId, validUpdate)
                .path("experimentTemplate").path("experimentReportConfiguration")
                .path("postExperimentDuration").asText());

        ObjectNode spacedDuration = waitTemplate(false);
        spacedDuration.putObject("experimentReportConfiguration")
                .put("preExperimentDuration", "PT1 M");
        assertValidation(() -> service.createExperimentTemplate(REGION, spacedDuration));

        ObjectNode longDuration = waitTemplate(false);
        longDuration.putObject("experimentReportConfiguration")
                .put("postExperimentDuration", "x".repeat(33));
        assertValidation(() -> service.createExperimentTemplate(REGION, longDuration));

        ObjectNode shortBucket = waitTemplate(false);
        shortBucket.putObject("experimentReportConfiguration")
                .putObject("outputs").putObject("s3Configuration").put("bucketName", "ab");
        assertValidation(() -> service.createExperimentTemplate(REGION, shortBucket));

        ObjectNode emptyPrefix = waitTemplate(false);
        emptyPrefix.putObject("experimentReportConfiguration")
                .putObject("outputs").putObject("s3Configuration").put("prefix", "");
        assertValidation(() -> service.createExperimentTemplate(REGION, emptyPrefix));

        ObjectNode dashboardsObject = waitTemplate(false);
        dashboardsObject.putObject("experimentReportConfiguration")
                .putObject("dataSources").putObject("cloudWatchDashboards");
        assertValidation(() -> service.createExperimentTemplate(REGION, dashboardsObject));

        ObjectNode dashboardString = waitTemplate(false);
        dashboardString.putObject("experimentReportConfiguration")
                .putObject("dataSources").putArray("cloudWatchDashboards").add("not-an-object");
        assertValidation(() -> service.createExperimentTemplate(REGION, dashboardString));
    }

    @Test
    void validatesModeledActionNameArnAndStopConditionStrings() throws Exception {
        ObjectNode spacedParameter = waitTemplate(false);
        ((ObjectNode) spacedParameter.path("actions").path("wait"))
                .putObject("parameters").put("duration", "PT1 M");
        assertValidation(() -> service.createExperimentTemplate(REGION, spacedParameter));

        ObjectNode oversizedParameter = waitTemplate(false);
        ((ObjectNode) oversizedParameter.path("actions").path("wait"))
                .putObject("parameters").put("duration", "x".repeat(1025));
        assertValidation(() -> service.createExperimentTemplate(REGION, oversizedParameter));

        ObjectNode emptyDescription = waitTemplate(false);
        ((ObjectNode) emptyDescription.path("actions").path("wait")).put("description", "");
        assertValidation(() -> service.createExperimentTemplate(REGION, emptyDescription));

        ObjectNode spacedActionName = waitTemplate(false);
        ObjectNode spacedActions = (ObjectNode) spacedActionName.path("actions");
        spacedActions.set("wait action", spacedActions.remove("wait"));
        assertValidation(() -> service.createExperimentTemplate(REGION, spacedActionName));

        ObjectNode spacedTargetName = ec2Template("ALL");
        ObjectNode spacedTargets = (ObjectNode) spacedTargetName.path("targets");
        spacedTargets.set("target one", spacedTargets.remove("instances"));
        ((ObjectNode) spacedTargetName.path("actions").path("stop").path("targets"))
                .put("Instances", "target one");
        assertValidation(() -> service.createExperimentTemplate(REGION, spacedTargetName));

        ObjectNode shortArn = ec2Template("ALL");
        ((ArrayNode) shortArn.path("targets").path("instances").path("resourceArns"))
                .removeAll().add("x".repeat(19));
        assertValidation(() -> service.createExperimentTemplate(REGION, shortArn));

        ObjectNode spacedArn = ec2Template("ALL");
        ((ArrayNode) spacedArn.path("targets").path("instances").path("resourceArns"))
                .removeAll().add("arn:aws:ec2:us-east-1:000000000000:instance/i bad");
        assertValidation(() -> service.createExperimentTemplate(REGION, spacedArn));

        ObjectNode shortStopValue = waitTemplate(false);
        ((ObjectNode) shortStopValue.path("stopConditions").path(0)).put("value", "x".repeat(19));
        assertValidation(() -> service.createExperimentTemplate(REGION, shortStopValue));
    }

    @Test
    void validatesRoleArnAndUsesModeledSafetyReasonShape() throws Exception {
        ObjectNode spacedRoleArn = waitTemplate(false);
        spacedRoleArn.put("roleArn", "arn:aws:iam::000000000000:role/fis role");
        assertValidation(() -> service.createExperimentTemplate(REGION, spacedRoleArn));

        ObjectNode emptyReason = service.updateSafetyLeverState(REGION, "default", mapper.readTree("""
                {"state":{"status":"engaged","reason":""}}
                """));
        assertEquals("", emptyReason.path("safetyLever").path("state").path("reason").asText());

        String longReason = "x".repeat(1024);
        ObjectNode longReasonResponse = service.updateSafetyLeverState(REGION, "default", mapper.readTree("""
                {"state":{"status":"disengaged","reason":"%s"}}
                """.formatted(longReason)));
        assertEquals(longReason, longReasonResponse.path("safetyLever").path("state").path("reason").asText());

        assertValidation(() -> service.updateSafetyLeverState(
                REGION, "default", mapper.readTree("""
                        {"state":{"status":"engaged"}}
                        """)));
        assertValidation(() -> service.updateSafetyLeverState(
                REGION, "default", mapper.readTree("""
                        {"state":{"status":"engaged","reason":1}}
                        """)));
    }

    @Test
    void validatesModeledTokensAndListFilters() throws Exception {
        ObjectNode spacedToken = waitTemplate(false);
        spacedToken.put("clientToken", "token with spaces");
        assertValidation(() -> service.createExperimentTemplate(REGION, spacedToken));

        assertValidation(() -> service.listActions(REGION, 100, ""));
        assertValidation(() -> service.listExperiments(REGION, 100, null, "template id"));

        String templateId = service.createExperimentTemplate(REGION, waitTemplate(false))
                .path("experimentTemplate").path("id").asText();
        String experimentId = service.startExperiment(REGION, mapper.readTree("""
                {"clientToken":"%s","experimentTemplateId":"%s","experimentOptions":{"actionsMode":"skip-all"}}
                """.formatted(UUID.randomUUID(), templateId)))
                .path("experiment").path("id").asText();
        assertValidation(() -> service.listExperimentResolvedTargets(
                REGION, experimentId, 100, null, "target name"));
    }

    @Test
    void enforcesTemplateAndTargetAccountQuotasAndMultiAccountStartRequirement() throws Exception {
        ObjectNode tooManyActions = waitTemplate(false);
        ObjectNode actions = (ObjectNode) tooManyActions.path("actions");
        actions.removeAll();
        for (int index = 0; index < 21; index++) {
            ObjectNode action = actions.putObject("wait" + index);
            action.put("actionId", "aws:fis:wait");
            action.putObject("parameters").put("duration", "PT1M");
        }
        assertQuota(() -> service.createExperimentTemplate(REGION, tooManyActions));

        ObjectNode tooManyStopConditions = waitTemplate(false);
        ArrayNode stopConditions = (ArrayNode) tooManyStopConditions.path("stopConditions");
        stopConditions.removeAll();
        for (int index = 0; index < 6; index++) {
            stopConditions.addObject().put("source", "none");
        }
        assertQuota(() -> service.createExperimentTemplate(REGION, tooManyStopConditions));

        ObjectNode noTargetAccount = waitTemplate(true);
        String noTargetAccountId = service.createExperimentTemplate(REGION, noTargetAccount)
                .path("experimentTemplate").path("id").asText();
        assertValidation(() -> service.startExperiment(REGION, mapper.readTree("""
                {"clientToken":"%s","experimentTemplateId":"%s"}
                """.formatted(UUID.randomUUID(), noTargetAccountId))));

        ObjectNode targetAccountQuota = waitTemplate(true);
        String templateId = service.createExperimentTemplate(REGION, targetAccountQuota)
                .path("experimentTemplate").path("id").asText();
        for (int index = 0; index < 40; index++) {
            String accountId = "%012d".formatted(index + 1L);
            service.createTargetAccountConfiguration(REGION, templateId, accountId, mapper.readTree("""
                    {"roleArn":"arn:aws:iam::%s:role/fis-target-role"}
                    """.formatted(accountId)));
        }
        AwsException quota = assertThrows(AwsException.class,
                () -> service.createTargetAccountConfiguration(
                        REGION, templateId, "000000000041", mapper.readTree("""
                                {"roleArn":"arn:aws:iam::000000000041:role/fis-target-role"}
                                """)));
        assertEquals("ServiceQuotaExceededException", quota.getErrorCode());
        assertEquals(402, quota.getHttpStatus());
    }

    @Test
    void enforcesExperimentTemplateQuotaWithoutBreakingIdempotency() throws Exception {
        ObjectNode firstRequest = waitTemplate(false);
        ObjectNode firstResponse = service.createExperimentTemplate(REGION, firstRequest);
        String firstId = firstResponse.path("experimentTemplate").path("id").asText();
        for (int index = 1; index < 500; index++) {
            service.createExperimentTemplate(REGION, waitTemplate(false));
        }

        assertEquals(firstId, service.createExperimentTemplate(REGION, firstRequest)
                .path("experimentTemplate").path("id").asText());
        ObjectNode rejected = waitTemplate(false);
        assertQuota(() -> service.createExperimentTemplate(REGION, rejected));

        service.deleteExperimentTemplate(REGION, firstId);
        service.createExperimentTemplate(REGION, rejected);
    }

    @Test
    void enforcesActiveExperimentQuotaAndReleasesStoppedCapacity() throws Exception {
        String templateId = service.createExperimentTemplate(REGION, ec2Template("ALL"))
                .path("experimentTemplate").path("id").asText();
        List<String> experimentIds = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            ObjectNode experiment = service.startExperiment(REGION, mapper.readTree("""
                    {"clientToken":"%s","experimentTemplateId":"%s"}
                    """.formatted(UUID.randomUUID(), templateId))).path("experiment").deepCopy();
            assertEquals("running", experiment.path("state").path("status").asText());
            experimentIds.add(experiment.path("id").asText());
        }

        ObjectNode rejected = (ObjectNode) mapper.readTree("""
                {"clientToken":"%s","experimentTemplateId":"%s"}
                """.formatted(UUID.randomUUID(), templateId));
        assertQuota(() -> service.startExperiment(REGION, rejected));

        service.stopExperiment(REGION, experimentIds.getFirst());
        assertEquals("running", service.startExperiment(REGION, rejected)
                .path("experiment").path("state").path("status").asText());
    }

    @Test
    void enforcesMaximumAntichainForParallelActionQuota() throws Exception {
        ObjectNode tenIndependent = waitTemplate(false);
        setWaitActions(tenIndependent, 10, false);
        String validTemplateId = service.createExperimentTemplate(REGION, tenIndependent)
                .path("experimentTemplate").path("id").asText();

        ObjectNode elevenIndependent = waitTemplate(false);
        setWaitActions(elevenIndependent, 11, false);
        assertQuota(() -> service.createExperimentTemplate(REGION, elevenIndependent));
        ObjectNode overwideUpdate = mapper.createObjectNode();
        overwideUpdate.set("actions", elevenIndependent.path("actions").deepCopy());
        assertQuota(() -> service.updateExperimentTemplate(REGION, validTemplateId, overwideUpdate));
        assertEquals(10, service.getExperimentTemplate(REGION, validTemplateId)
                .path("experimentTemplate").path("actions").size());

        ObjectNode twentySequential = waitTemplate(false);
        setWaitActions(twentySequential, 20, true);
        service.createExperimentTemplate(REGION, twentySequential);

        ObjectNode staggered = waitTemplate(false);
        ObjectNode staggeredActions = (ObjectNode) staggered.path("actions");
        staggeredActions.removeAll();
        addWaitAction(staggeredActions, "a");
        for (int index = 1; index <= 9; index++) {
            addWaitAction(staggeredActions, "d" + index);
        }
        addWaitAction(staggeredActions, "b", "a");
        addWaitAction(staggeredActions, "c", "a");
        assertQuota(() -> service.createExperimentTemplate(REGION, staggered));
    }

    private ObjectNode ec2Template(String selectionMode) throws Exception {
        return (ObjectNode) mapper.readTree("""
                {
                  "clientToken":"%s",
                  "description":"EC2 target template",
                  "roleArn":"%s",
                  "stopConditions":[{"source":"none"}],
                  "targets":{
                    "instances":{
                      "resourceType":"aws:ec2:instance",
                      "resourceArns":[
                        "arn:aws:ec2:us-east-1:000000000000:instance/i-00000000000000001",
                        "arn:aws:ec2:us-east-1:000000000000:instance/i-00000000000000002",
                        "arn:aws:ec2:us-east-1:000000000000:instance/i-00000000000000003",
                        "arn:aws:ec2:us-east-1:000000000000:instance/i-00000000000000004",
                        "arn:aws:ec2:us-east-1:000000000000:instance/i-00000000000000005"
                      ],
                      "selectionMode":"%s"
                    }
                  },
                  "actions":{
                    "stop":{
                      "actionId":"aws:ec2:stop-instances",
                      "targets":{"Instances":"instances"}
                    }
                  }
                }
                """.formatted(UUID.randomUUID(), ROLE_ARN, selectionMode));
    }

    private ObjectNode eksPodTemplate() throws Exception {
        return (ObjectNode) mapper.readTree("""
                {
                  "clientToken":"%s",
                  "description":"parameter target",
                  "roleArn":"%s",
                  "stopConditions":[{"source":"none"}],
                  "targets":{
                    "pods":{
                      "resourceType":"aws:eks:pod",
                      "parameters":{
                        "clusterIdentifier":"cluster-one",
                        "namespace":"default",
                        "selectorType":"labelSelector",
                        "selectorValue":"app=test"
                      },
                      "selectionMode":"COUNT(1)"
                    }
                  },
                  "actions":{
                    "delete":{
                      "actionId":"aws:eks:pod-delete",
                      "parameters":{"kubernetesServiceAccount":"fis-service-account"},
                      "targets":{"Pods":"pods"}
                    }
                  },
                  "experimentOptions":{"emptyTargetResolutionMode":"skip"}
                }
                """.formatted(UUID.randomUUID(), ROLE_ARN));
    }

    private ObjectNode waitTemplate(boolean multiAccount) throws Exception {
        String options = multiAccount
                ? ",\"experimentOptions\":{\"accountTargeting\":\"multi-account\"}"
                : "";
        return (ObjectNode) mapper.readTree("""
                {
                  "clientToken":"%s",
                  "description":"wait template",
                  "roleArn":"%s",
                  "stopConditions":[{"source":"none"}],
                  "targets":{},
                  "actions":{"wait":{"actionId":"aws:fis:wait","parameters":{"duration":"PT1M"}}}%s
                }
                """.formatted(UUID.randomUUID(), ROLE_ARN, options));
    }

    private ObjectNode eksCustomResourceTemplate(boolean tags) throws Exception {
        ObjectNode request = (ObjectNode) mapper.readTree("""
                {
                  "clientToken":"%s",
                  "description":"EKS custom resource template",
                  "roleArn":"%s",
                  "stopConditions":[{"source":"none"}],
                  "targets":{
                    "cluster":{
                      "resourceType":"aws:eks:cluster",
                      "resourceArns":["arn:aws:eks:us-east-1:000000000000:cluster/test"],
                      "selectionMode":"ALL"
                    }
                  },
                  "actions":{
                    "custom":{
                      "actionId":"aws:eks:inject-kubernetes-custom-resource",
                      "parameters":{
                        "kubernetesApiVersion":"chaos-mesh.org/v1alpha1",
                        "kubernetesKind":"AWSChaos",
                        "kubernetesNamespace":"default",
                        "kubernetesSpec":"{}",
                        "maxDuration":"PT1M"
                      },
                      "targets":{"Cluster":"cluster"}
                    }
                  }
                }
                """.formatted(UUID.randomUUID(), ROLE_ARN));
        if (tags) {
            ObjectNode target = (ObjectNode) request.path("targets").path("cluster");
            target.remove("resourceArns");
            target.putObject("resourceTags").put("environment", "test");
        }
        return request;
    }

    private ObjectNode s3PauseReplicationTemplate(boolean tags) throws Exception {
        ObjectNode request = (ObjectNode) mapper.readTree("""
                {
                  "clientToken":"%s",
                  "description":"S3 replication template",
                  "roleArn":"%s",
                  "stopConditions":[{"source":"none"}],
                  "targets":{
                    "buckets":{
                      "resourceType":"aws:s3:bucket",
                      "resourceArns":["arn:aws:s3:::source-bucket"],
                      "selectionMode":"ALL"
                    }
                  },
                  "actions":{
                    "pause":{
                      "actionId":"aws:s3:bucket-pause-replication",
                      "parameters":{"duration":"PT1M","region":"us-east-1"},
                      "targets":{"Buckets":"buckets"}
                    }
                  }
                }
                """.formatted(UUID.randomUUID(), ROLE_ARN));
        if (tags) {
            ObjectNode target = (ObjectNode) request.path("targets").path("buckets");
            target.remove("resourceArns");
            target.putObject("resourceTags").put("environment", "test");
        }
        return request;
    }

    private ObjectNode ec2InsufficientCapacityTemplate(boolean tags) throws Exception {
        ObjectNode request = (ObjectNode) mapper.readTree("""
                {
                  "clientToken":"%s",
                  "description":"EC2 insufficient capacity template",
                  "roleArn":"%s",
                  "stopConditions":[{"source":"none"}],
                  "targets":{
                    "roles":{
                      "resourceType":"aws:iam:role",
                      "resourceArns":["%s"],
                      "selectionMode":"ALL"
                    }
                  },
                  "actions":{
                    "inject":{
                      "actionId":"aws:ec2:api-insufficient-instance-capacity-error",
                      "parameters":{
                        "duration":"PT1M",
                        "availabilityZoneIdentifiers":"us-east-1a",
                        "percentage":"100"
                      },
                      "targets":{"Roles":"roles"}
                    }
                  }
                }
                """.formatted(UUID.randomUUID(), ROLE_ARN, ROLE_ARN));
        if (tags) {
            ObjectNode target = (ObjectNode) request.path("targets").path("roles");
            target.remove("resourceArns");
            target.putObject("resourceTags").put("environment", "test");
        }
        return request;
    }

    private void setWaitActions(ObjectNode request, int count, boolean sequential) {
        ObjectNode actions = (ObjectNode) request.path("actions");
        actions.removeAll();
        for (int index = 0; index < count; index++) {
            if (sequential && index > 0) {
                addWaitAction(actions, "wait" + index, "wait" + (index - 1));
            } else {
                addWaitAction(actions, "wait" + index);
            }
        }
    }

    private void addWaitAction(ObjectNode actions, String name, String... startAfter) {
        ObjectNode action = actions.putObject(name);
        action.put("actionId", "aws:fis:wait");
        action.putObject("parameters").put("duration", "PT1M");
        if (startAfter.length > 0) {
            ArrayNode dependencies = action.putArray("startAfter");
            for (String dependency : startAfter) {
                dependencies.add(dependency);
            }
        }
    }

    private void assertRequired(ObjectNode catalogEntry, String parameter, boolean expected) {
        assertEquals(expected,
                catalogEntry.path("parameters").path(parameter).path("required").asBoolean(),
                parameter);
        assertFalse(catalogEntry.path("parameters").path(parameter).path("description").asText().isBlank());
    }

    private void assertValidation(ThrowingOperation operation) {
        AwsException exception = assertThrows(AwsException.class, operation::run);
        assertEquals("ValidationException", exception.getErrorCode());
        assertEquals(400, exception.getHttpStatus());
    }

    private void assertQuota(ThrowingOperation operation) {
        AwsException exception = assertThrows(AwsException.class, operation::run);
        assertEquals("ServiceQuotaExceededException", exception.getErrorCode());
        assertEquals(402, exception.getHttpStatus());
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws Exception;
    }
}
