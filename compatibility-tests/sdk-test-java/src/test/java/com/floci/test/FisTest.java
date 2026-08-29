package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.fis.FisClient;
import software.amazon.awssdk.services.fis.model.AccountTargeting;
import software.amazon.awssdk.services.fis.model.ActionsMode;
import software.amazon.awssdk.services.fis.model.CreateExperimentTemplateActionInput;
import software.amazon.awssdk.services.fis.model.CreateExperimentTemplateStopConditionInput;
import software.amazon.awssdk.services.fis.model.CreateExperimentTemplateTargetInput;
import software.amazon.awssdk.services.fis.model.EmptyTargetResolutionMode;
import software.amazon.awssdk.services.fis.model.ExperimentStatus;
import software.amazon.awssdk.services.fis.model.ResourceNotFoundException;
import software.amazon.awssdk.services.fis.model.SafetyLeverStatus;
import software.amazon.awssdk.services.fis.model.SafetyLeverStatusInput;
import software.amazon.awssdk.services.fis.model.ServiceQuotaExceededException;
import software.amazon.awssdk.services.fis.model.ValidationException;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AWS Fault Injection Service")
class FisTest {

    private static final String ACCOUNT_ID = "111122223333";
    private static final String ROLE_ARN =
            "arn:aws:iam::000000000000:role/fis-compat-role";
    private static final String TARGET_ROLE_ARN =
            "arn:aws:iam::" + ACCOUNT_ID + ":role/fis-target-role";
    private static final String INSTANCE_ARN =
            "arn:aws:ec2:us-east-1:000000000000:instance/i-0123456789abcdef0";
    private static final String ACTION_ID = "aws:ec2:stop-instances";
    private static final String RESOURCE_TYPE = "aws:ec2:instance";

    @Test
    void controlPlaneLifecycleUsesAwsSdkWireShapes() {
        String templateId = null;
        boolean targetAccountConfigurationExists = false;

        try (FisClient fis = TestFixtures.fisClient()) {
            var action = fis.getAction(request -> request.id(ACTION_ID)).action();
            assertThat(action.id()).isEqualTo(ACTION_ID);
            assertThat(action.arn()).endsWith("action/" + ACTION_ID);
            assertThat(action.targets()).containsKey("Instances");

            var firstActionsPage = fis.listActions(request -> request.maxResults(1));
            assertThat(firstActionsPage.actions()).hasSize(1);
            assertThat(firstActionsPage.nextToken()).isNotBlank();
            assertThat(fis.listActions(request -> request
                    .maxResults(1)
                    .nextToken(firstActionsPage.nextToken())).actions()).hasSize(1);

            Map<String, CreateExperimentTemplateActionInput> overwideActions = new LinkedHashMap<>();
            for (int index = 0; index < 11; index++) {
                overwideActions.put("wait" + index, CreateExperimentTemplateActionInput.builder()
                        .actionId("aws:fis:wait")
                        .parameters(Map.of("duration", "PT1M"))
                        .build());
            }
            assertThatThrownBy(() -> fis.createExperimentTemplate(request -> request
                    .clientToken(TestFixtures.uniqueName("fis-quota"))
                    .description("FIS parallel action quota")
                    .roleArn(ROLE_ARN)
                    .stopConditions(CreateExperimentTemplateStopConditionInput.builder()
                            .source("none")
                            .build())
                    .actions(overwideActions)))
                    .isInstanceOfSatisfying(ServiceQuotaExceededException.class,
                            exception -> assertThat(exception.statusCode()).isEqualTo(402));

            assertThatThrownBy(() -> fis.createExperimentTemplate(request -> request
                    .clientToken(TestFixtures.uniqueName("fis-report-validation"))
                    .description("FIS report validation")
                    .roleArn(ROLE_ARN)
                    .stopConditions(CreateExperimentTemplateStopConditionInput.builder()
                            .source("none")
                            .build())
                    .actions(Map.of("wait", CreateExperimentTemplateActionInput.builder()
                            .actionId("aws:fis:wait")
                            .parameters(Map.of("duration", "PT1M"))
                            .build()))
                    .experimentReportConfiguration(report -> report.outputs(outputs -> outputs
                            .s3Configuration(s3 -> s3.bucketName("ab"))))))
                    .isInstanceOfSatisfying(ValidationException.class,
                            exception -> assertThat(exception.statusCode()).isEqualTo(400));

            var targetResourceType = fis.getTargetResourceType(request -> request
                    .resourceType(RESOURCE_TYPE)).targetResourceType();
            assertThat(targetResourceType.resourceType()).isEqualTo(RESOURCE_TYPE);

            var firstResourceTypesPage = fis.listTargetResourceTypes(request -> request.maxResults(1));
            assertThat(firstResourceTypesPage.targetResourceTypes()).hasSize(1);
            assertThat(firstResourceTypesPage.nextToken()).isNotBlank();
            assertThat(fis.listTargetResourceTypes(request -> request
                    .maxResults(1)
                    .nextToken(firstResourceTypesPage.nextToken())).targetResourceTypes()).hasSize(1);

            var initialLever = fis.getSafetyLever(request -> request.id("default")).safetyLever();
            assertThat(initialLever.id()).isEqualTo("default");
            assertThat(initialLever.arn()).endsWith("safety-lever/default");

            var engagedLever = fis.updateSafetyLeverState(request -> request
                    .id("default")
                    .state(state -> state
                            .status(SafetyLeverStatusInput.ENGAGED)
                            .reason("SDK compatibility test"))).safetyLever();
            assertThat(engagedLever.state().status()).isEqualTo(SafetyLeverStatus.ENGAGED);

            var disengagedLever = fis.updateSafetyLeverState(request -> request
                    .id("default")
                    .state(state -> state
                            .status(SafetyLeverStatusInput.DISENGAGED)
                            .reason("SDK compatibility test complete"))).safetyLever();
            assertThat(disengagedLever.state().status()).isEqualTo(SafetyLeverStatus.DISENGAGED);

            String clientToken = TestFixtures.uniqueName("fis-template");
            var createRequest = software.amazon.awssdk.services.fis.model.CreateExperimentTemplateRequest
                    .builder()
                    .clientToken(clientToken)
                    .description("FIS SDK compatibility template")
                    .roleArn(ROLE_ARN)
                    .stopConditions(CreateExperimentTemplateStopConditionInput.builder()
                            .source("none")
                            .build())
                    .targets(Map.of("instances", CreateExperimentTemplateTargetInput.builder()
                            .resourceType(RESOURCE_TYPE)
                            .resourceArns(INSTANCE_ARN)
                            .selectionMode("ALL")
                            .build()))
                    .actions(Map.of("stopInstances", CreateExperimentTemplateActionInput.builder()
                            .actionId(ACTION_ID)
                            .targets(Map.of("Instances", "instances"))
                            .build()))
                    .tags(Map.of("suite", "sdk-compat"))
                    .logConfiguration(log -> log
                            .logSchemaVersion(2)
                            .s3Configuration(s3 -> s3
                                    .bucketName("fis-sdk-log-bucket")
                                    .prefix("logs/fis")))
                    .experimentOptions(options -> options
                            .accountTargeting(AccountTargeting.MULTI_ACCOUNT)
                            .emptyTargetResolutionMode(EmptyTargetResolutionMode.SKIP))
                    .experimentReportConfiguration(report -> report
                            .preExperimentDuration("PT5M")
                            .postExperimentDuration("PT10M")
                            .outputs(outputs -> outputs.s3Configuration(s3 -> s3
                                    .bucketName("fis-sdk-report-bucket")
                                    .prefix("reports/fis")))
                            .dataSources(dataSources -> dataSources.cloudWatchDashboards(dashboard -> dashboard
                                    .dashboardIdentifier(
                                            "arn:aws:cloudwatch::000000000000:dashboard/fis-sdk"))))
                    .build();

            var createdTemplate = fis.createExperimentTemplate(createRequest).experimentTemplate();
            templateId = createdTemplate.id();
            String activeTemplateId = templateId;
            assertThat(activeTemplateId).isNotBlank();
            assertThat(createdTemplate.arn()).endsWith("experiment-template/" + activeTemplateId);
            assertThat(createdTemplate.actions()).containsKey("stopInstances");
            assertThat(createdTemplate.targets()).containsKey("instances");
            assertThat(createdTemplate.tags()).containsEntry("suite", "sdk-compat");
            assertThat(createdTemplate.experimentOptions().accountTargeting())
                    .isEqualTo(AccountTargeting.MULTI_ACCOUNT);
            assertThat(createdTemplate.logConfiguration().logSchemaVersion()).isEqualTo(2);
            assertThat(createdTemplate.experimentReportConfiguration().preExperimentDuration())
                    .isEqualTo("PT5M");
            assertThat(createdTemplate.experimentReportConfiguration().outputs()
                    .s3Configuration().bucketName()).isEqualTo("fis-sdk-report-bucket");

            assertThat(fis.createExperimentTemplate(createRequest).experimentTemplate().id())
                    .isEqualTo(activeTemplateId);

            var fetchedTemplate = fis.getExperimentTemplate(request -> request.id(activeTemplateId))
                    .experimentTemplate();
            assertThat(fetchedTemplate.id()).isEqualTo(activeTemplateId);
            assertThat(fetchedTemplate.creationTime()).isNotNull();

            var updatedTemplate = fis.updateExperimentTemplate(request -> request
                    .id(activeTemplateId)
                    .description("Updated through the AWS SDK")).experimentTemplate();
            assertThat(updatedTemplate.description()).isEqualTo("Updated through the AWS SDK");

            assertThat(fis.listExperimentTemplates(request -> request.maxResults(100))
                    .experimentTemplates())
                    .anyMatch(template -> activeTemplateId.equals(template.id()));

            var createdTargetAccount = fis.createTargetAccountConfiguration(request -> request
                    .clientToken(TestFixtures.uniqueName("fis-account"))
                    .experimentTemplateId(activeTemplateId)
                    .accountId(ACCOUNT_ID)
                    .roleArn(TARGET_ROLE_ARN)
                    .description("compatibility target account"))
                    .targetAccountConfiguration();
            targetAccountConfigurationExists = true;
            assertThat(createdTargetAccount.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(createdTargetAccount.roleArn()).isEqualTo(TARGET_ROLE_ARN);

            var fetchedTargetAccount = fis.getTargetAccountConfiguration(request -> request
                    .experimentTemplateId(activeTemplateId)
                    .accountId(ACCOUNT_ID)).targetAccountConfiguration();
            assertThat(fetchedTargetAccount.description()).isEqualTo("compatibility target account");

            var updatedTargetAccount = fis.updateTargetAccountConfiguration(request -> request
                    .experimentTemplateId(activeTemplateId)
                    .accountId(ACCOUNT_ID)
                    .roleArn(TARGET_ROLE_ARN)
                    .description("updated compatibility target account"))
                    .targetAccountConfiguration();
            assertThat(updatedTargetAccount.description())
                    .isEqualTo("updated compatibility target account");

            assertThat(fis.listTargetAccountConfigurations(request -> request
                    .experimentTemplateId(activeTemplateId)
                    .maxResults(100)).targetAccountConfigurations())
                    .anyMatch(configuration -> ACCOUNT_ID.equals(configuration.accountId()));

            String templateArn = createdTemplate.arn();
            fis.tagResource(request -> request
                    .resourceArn(templateArn)
                    .tags(Map.of("owner", "compatibility")));
            assertThat(fis.listTagsForResource(request -> request.resourceArn(templateArn)).tags())
                    .containsEntry("suite", "sdk-compat")
                    .containsEntry("owner", "compatibility");
            fis.untagResource(request -> request.resourceArn(templateArn));
            assertThat(fis.listTagsForResource(request -> request.resourceArn(templateArn)).tags())
                    .containsEntry("owner", "compatibility");
            fis.untagResource(request -> request.resourceArn(templateArn).tagKeys("owner"));
            assertThat(fis.listTagsForResource(request -> request.resourceArn(templateArn)).tags())
                    .containsEntry("suite", "sdk-compat")
                    .doesNotContainKey("owner");

            var startedExperiment = fis.startExperiment(request -> request
                    .clientToken(TestFixtures.uniqueName("fis-experiment"))
                    .experimentTemplateId(activeTemplateId)
                    .experimentOptions(options -> options.actionsMode(ActionsMode.SKIP_ALL))
                    .tags(Map.of("mode", "safe-preview"))).experiment();
            String experimentId = startedExperiment.id();
            assertThat(experimentId).isNotBlank();
            assertThat(startedExperiment.experimentTemplateId()).isEqualTo(activeTemplateId);
            assertThat(startedExperiment.experimentOptions().actionsMode())
                    .isEqualTo(ActionsMode.SKIP_ALL);
            assertThat(startedExperiment.state().status())
                    .isIn(ExperimentStatus.RUNNING, ExperimentStatus.COMPLETED);

            var fetchedExperiment = fis.getExperiment(request -> request.id(experimentId)).experiment();
            assertThat(fetchedExperiment.id()).isEqualTo(experimentId);
            assertThat(fetchedExperiment.tags()).containsEntry("mode", "safe-preview");

            assertThat(fis.listExperiments(request -> request
                    .experimentTemplateId(activeTemplateId)
                    .maxResults(100)).experiments())
                    .anyMatch(experiment -> experimentId.equals(experiment.id()));

            var resolvedTargets = fis.listExperimentResolvedTargets(request -> request
                    .experimentId(experimentId)
                    .targetName("instances")
                    .maxResults(100)).resolvedTargets();
            assertThat(resolvedTargets)
                    .anyMatch(target -> "instances".equals(target.targetName())
                            && RESOURCE_TYPE.equals(target.resourceType()));

            var experimentTargetAccount = fis.getExperimentTargetAccountConfiguration(request -> request
                    .experimentId(experimentId)
                    .accountId(ACCOUNT_ID)).targetAccountConfiguration();
            assertThat(experimentTargetAccount.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(experimentTargetAccount.roleArn()).isEqualTo(TARGET_ROLE_ARN);

            assertThat(fis.listExperimentTargetAccountConfigurations(request -> request
                    .experimentId(experimentId)).targetAccountConfigurations())
                    .anyMatch(configuration -> ACCOUNT_ID.equals(configuration.accountId()));

            var stoppedExperiment = fis.stopExperiment(request -> request.id(experimentId)).experiment();
            assertThat(stoppedExperiment.id()).isEqualTo(experimentId);
            assertThat(stoppedExperiment.state().status())
                    .isIn(ExperimentStatus.STOPPED, ExperimentStatus.COMPLETED);

            fis.deleteTargetAccountConfiguration(request -> request
                    .experimentTemplateId(activeTemplateId)
                    .accountId(ACCOUNT_ID));
            targetAccountConfigurationExists = false;

            String deletedTemplateId = activeTemplateId;
            assertThat(fis.deleteExperimentTemplate(request -> request.id(deletedTemplateId))
                    .experimentTemplate().id()).isEqualTo(deletedTemplateId);
            templateId = null;

            assertThatThrownBy(() -> fis.getExperimentTemplate(request -> request.id(deletedTemplateId)))
                    .isInstanceOf(ResourceNotFoundException.class);
        } finally {
            cleanup(templateId, targetAccountConfigurationExists);
        }
    }

    private static void cleanup(String templateId, boolean targetAccountConfigurationExists) {
        try (FisClient fis = TestFixtures.fisClient()) {
            try {
                fis.updateSafetyLeverState(request -> request
                        .id("default")
                        .state(state -> state
                                .status(SafetyLeverStatusInput.DISENGAGED)
                                .reason("SDK compatibility cleanup")));
            } catch (RuntimeException e) {
                System.err.println("Unable to disengage the FIS safety lever during cleanup: " + e);
            }
            if (templateId == null) {
                return;
            }
            if (targetAccountConfigurationExists) {
                try {
                    fis.deleteTargetAccountConfiguration(request -> request
                            .experimentTemplateId(templateId)
                            .accountId(ACCOUNT_ID));
                } catch (RuntimeException e) {
                    System.err.println("Unable to delete the FIS target account configuration during cleanup: " + e);
                }
            }
            try {
                fis.deleteExperimentTemplate(request -> request.id(templateId));
            } catch (RuntimeException e) {
                System.err.println("Unable to delete the FIS experiment template during cleanup: " + e);
            }
        }
    }
}
