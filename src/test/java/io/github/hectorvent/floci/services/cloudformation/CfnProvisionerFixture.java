package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.services.acm.AcmService;
import io.github.hectorvent.floci.services.apigateway.ApiGatewayService;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import io.github.hectorvent.floci.services.autoscaling.AutoScalingService;
import io.github.hectorvent.floci.services.batch.BatchService;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnDynamicReferences;
import io.github.hectorvent.floci.services.cloudformation.provisioners.ConfigCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.Ec2FlowLogCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.LambdaMicrovmsCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.OrganizationsCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.SqsCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.WafV2CfnProvisioner;
import io.github.hectorvent.floci.services.configservice.AwsConfigService;
import io.github.hectorvent.floci.services.ec2.FlowLogService;
import io.github.hectorvent.floci.services.lambdamicrovms.LambdaMicrovmsService;
import io.github.hectorvent.floci.services.organizations.OrganizationsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.wafv2.WafV2Service;
import io.github.hectorvent.floci.services.cloudformation.provisioners.AcmCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.ApiGatewayAccountCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.AutoScalingLifecycleHookCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CdkMetadataCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CloudWatchCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.Ec2LaunchTemplateCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.Ec2NetworkAclCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.Ec2SecurityGroupRuleCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.Ec2VpcCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.Ec2VpcEndpointCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.Ec2VpcGatewayAttachmentCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.EcrCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.EcsCapacityCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.FirehoseCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.IamRoleCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.KinesisCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.KmsCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.LambdaAddressingCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.LambdaVersionAliasCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.LogsCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.PipesCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.Route53CfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.S3CfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.SchedulerScheduleGroupCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.SnsCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.SsmCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnResourceProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CloudFormationResourceRegistry;
import io.github.hectorvent.floci.services.cloudfront.CloudFrontService;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.github.hectorvent.floci.services.cognito.CognitoService;
import io.github.hectorvent.floci.services.docdb.DocDbService;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ecr.EcrService;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.eks.EksService;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.firehose.FirehoseService;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.lambda.LambdaLayerService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.pipes.PipesService;
import io.github.hectorvent.floci.services.rds.RdsService;
import io.github.hectorvent.floci.services.route53.Route53Service;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.scheduler.SchedulerService;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.ssm.SsmService;
import io.github.hectorvent.floci.services.stepfunctions.StepFunctionsService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Builds {@link CloudFormationResourceProvisioner} for tests without a wall of positional nulls.
 *
 * <p>The provisioner takes one constructor argument per service it still provisions, so every test
 * that built it directly had to pass 30-plus {@code null}s in the right order, and every argument
 * added or removed edited all of them. Naming only what a test actually uses keeps the intent
 * visible and, more importantly, makes the arity a one-file change: as types migrate to per-service
 * provisioners their arguments fall away, and only this class needs updating.
 *
 * <p>Tests exercising a type that has already migrated must register its provisioner with
 * {@link Builder#provisioners} rather than leaving the registry empty. With an empty registry a
 * migrated type reaches the provisioner's default arm and is stubbed with a fake ARN and
 * CREATE_COMPLETE, so the test passes while provisioning nothing.
 */
final class CfnProvisionerFixture {

    private CfnProvisionerFixture() {
    }

    static Builder builder() {
        return new Builder();
    }

    static final class Builder {

        private S3Service s3Service;
        private SnsService snsService;
        private DynamoDbService dynamoDbService;
        private LambdaService lambdaService;
        private IamService iamService;
        private SsmService ssmService;
        private KmsService kmsService;
        private SecretsManagerService secretsManagerService;
        private EventBridgeService eventBridgeService;
        private ApiGatewayService apiGatewayService;
        private ApiGatewayV2Service apiGatewayV2Service;
        private EcrService ecrService;
        private PipesService pipesService;
        private CognitoService cognitoService;
        private LambdaLayerService lambdaLayerService;
        private ObjectMapper objectMapper;
        private CustomResourceResponseStore customResourceResponseStore;
        private ContainerReachableEndpoint reachableEndpoint;
        private EcsService ecsService;
        private ElbV2Service elbV2Service;
        private StepFunctionsService stepFunctionsService;
        private BatchService batchService;
        private Ec2Service ec2Service;
        private RdsService rdsService;
        private EksService eksService;
        private CloudWatchLogsService logsService;
        private KinesisService kinesisService;
        private CloudWatchMetricsService cloudWatchMetricsService;
        private AutoScalingService autoScalingService;
        private AcmService acmService;
        private FirehoseService firehoseService;
        private DocDbService docDbService;
        private CloudFrontService cloudFrontService;
        private Route53Service route53Service;
        private SchedulerService schedulerService;
        // Services that back a provisioner without being a constructor argument of the
        // dispatcher. They exist only so inferredProvisioners() can wire their provisioner.
        private FlowLogService flowLogService;
        private LambdaMicrovmsService lambdaMicrovmsService;
        private AwsConfigService awsConfigService;
        private OrganizationsService organizationsService;
        private SqsService sqsService;
        private WafV2Service wafV2Service;
        private CloudFormationResourceRegistry resourceRegistry;
        private boolean registryChosenByTest;
        private CfnDynamicReferences dynamicReferences;
        private EmulatorConfig config;

        private Builder() {
            this.objectMapper = new ObjectMapper();
        }

        /**
         * Registers an explicit provisioner set, replacing the ones inferred from the named
         * services. Needed only for a provisioner this fixture cannot build itself, such as one
         * taking collaborators beyond a single service.
         */
        public Builder provisioners(CfnResourceProvisioner... provisioners) {
            this.resourceRegistry = new CloudFormationResourceRegistry(Arrays.asList(provisioners));
            this.registryChosenByTest = true;
            return this;
        }

        /**
         * The provisioners inferred from the services this test named, not everything CDI would
         * discover: a service the test did not name contributes nothing here.
         *
         * <p>Inferring these is what keeps a test honest across a migration. A test that names a
         * service and provisions one of its types used to keep passing when that type moved to a
         * provisioner: the empty registry sent it to the dispatcher's stub arm, which reports
         * CREATE_COMPLETE with a synthetic id, so the assertions ran against nothing. Wiring the
         * provisioner from the service means the test follows the type instead.
         *
         * <p>Covers every provisioner that takes a single service. The two that take more
         * ({@code CodeBuildCfnProvisioner}, {@code CodePipelineCfnProvisioner}) must be passed to
         * {@link Builder#provisioners} explicitly. {@code CfnProvisionerFixtureTest} fails if a new
         * provisioner is neither wired here nor listed as an exemption there, so this cannot fall
         * behind silently.
         */
        private List<CfnResourceProvisioner> inferredProvisioners() {
            List<CfnResourceProvisioner> discovered = new ArrayList<>();
            discovered.add(new CdkMetadataCfnProvisioner());
            if (s3Service != null) {
                discovered.add(new S3CfnProvisioner(s3Service));
            }
            if (snsService != null) {
                discovered.add(new SnsCfnProvisioner(snsService));
            }
            if (ssmService != null) {
                discovered.add(new SsmCfnProvisioner(ssmService));
            }
            if (kmsService != null) {
                discovered.add(new KmsCfnProvisioner(kmsService));
            }
            if (ecrService != null) {
                discovered.add(new EcrCfnProvisioner(ecrService));
            }
            if (pipesService != null) {
                discovered.add(new PipesCfnProvisioner(pipesService));
            }
            if (firehoseService != null) {
                discovered.add(new FirehoseCfnProvisioner(firehoseService));
            }
            if (logsService != null) {
                discovered.add(new LogsCfnProvisioner(logsService));
            }
            if (kinesisService != null) {
                discovered.add(new KinesisCfnProvisioner(kinesisService));
            }
            if (cloudWatchMetricsService != null) {
                discovered.add(new CloudWatchCfnProvisioner(cloudWatchMetricsService));
            }
            if (iamService != null) {
                discovered.add(new IamRoleCfnProvisioner(iamService));
            }
            if (ecsService != null) {
                discovered.add(new EcsCapacityCfnProvisioner(ecsService));
            }
            if (apiGatewayService != null) {
                discovered.add(new ApiGatewayAccountCfnProvisioner(apiGatewayService));
            }
            if (autoScalingService != null) {
                discovered.add(new AutoScalingLifecycleHookCfnProvisioner(autoScalingService));
            }
            if (acmService != null) {
                discovered.add(new AcmCfnProvisioner(acmService));
            }
            if (lambdaService != null) {
                discovered.add(new LambdaAddressingCfnProvisioner(lambdaService));
                discovered.add(new LambdaVersionAliasCfnProvisioner(lambdaService));
            }
            if (flowLogService != null) {
                discovered.add(new Ec2FlowLogCfnProvisioner(flowLogService));
            }
            if (lambdaMicrovmsService != null) {
                discovered.add(new LambdaMicrovmsCfnProvisioner(lambdaMicrovmsService));
            }
            if (awsConfigService != null) {
                discovered.add(new ConfigCfnProvisioner(awsConfigService));
            }
            if (organizationsService != null) {
                discovered.add(new OrganizationsCfnProvisioner(organizationsService));
            }
            if (sqsService != null) {
                discovered.add(new SqsCfnProvisioner(sqsService));
            }
            if (wafV2Service != null) {
                discovered.add(new WafV2CfnProvisioner(wafV2Service));
            }
            if (ec2Service != null) {
                discovered.add(new Ec2VpcCfnProvisioner(ec2Service));
                discovered.add(new Ec2VpcEndpointCfnProvisioner(ec2Service));
                discovered.add(new Ec2VpcGatewayAttachmentCfnProvisioner(ec2Service));
                discovered.add(new Ec2NetworkAclCfnProvisioner(ec2Service));
                discovered.add(new Ec2SecurityGroupRuleCfnProvisioner(ec2Service));
                discovered.add(new Ec2LaunchTemplateCfnProvisioner(ec2Service));
            }
            if (route53Service != null) {
                discovered.add(new Route53CfnProvisioner(route53Service));
            }
            if (schedulerService != null) {
                discovered.add(new SchedulerScheduleGroupCfnProvisioner(schedulerService));
            }
            return discovered;
        }

        public Builder s3(S3Service v) {
            this.s3Service = v;
            return this;
        }

        public Builder sns(SnsService v) {
            this.snsService = v;
            return this;
        }

        public Builder dynamoDb(DynamoDbService v) {
            this.dynamoDbService = v;
            return this;
        }

        public Builder lambda(LambdaService v) {
            this.lambdaService = v;
            return this;
        }

        public Builder iam(IamService v) {
            this.iamService = v;
            return this;
        }

        public Builder ssm(SsmService v) {
            this.ssmService = v;
            return this;
        }

        public Builder kms(KmsService v) {
            this.kmsService = v;
            return this;
        }

        public Builder secretsManager(SecretsManagerService v) {
            this.secretsManagerService = v;
            return this;
        }

        public Builder eventBridge(EventBridgeService v) {
            this.eventBridgeService = v;
            return this;
        }

        public Builder apiGateway(ApiGatewayService v) {
            this.apiGatewayService = v;
            return this;
        }

        public Builder apiGatewayV2(ApiGatewayV2Service v) {
            this.apiGatewayV2Service = v;
            return this;
        }

        public Builder ecr(EcrService v) {
            this.ecrService = v;
            return this;
        }

        public Builder pipes(PipesService v) {
            this.pipesService = v;
            return this;
        }

        public Builder cognito(CognitoService v) {
            this.cognitoService = v;
            return this;
        }

        public Builder lambdaLayer(LambdaLayerService v) {
            this.lambdaLayerService = v;
            return this;
        }

        public Builder objectMapper(ObjectMapper v) {
            this.objectMapper = v;
            return this;
        }

        public Builder customResourceResponseStore(CustomResourceResponseStore v) {
            this.customResourceResponseStore = v;
            return this;
        }

        public Builder reachableEndpoint(ContainerReachableEndpoint v) {
            this.reachableEndpoint = v;
            return this;
        }

        public Builder ecs(EcsService v) {
            this.ecsService = v;
            return this;
        }

        public Builder elbV2(ElbV2Service v) {
            this.elbV2Service = v;
            return this;
        }

        public Builder stepFunctions(StepFunctionsService v) {
            this.stepFunctionsService = v;
            return this;
        }

        public Builder batch(BatchService v) {
            this.batchService = v;
            return this;
        }

        public Builder ec2(Ec2Service v) {
            this.ec2Service = v;
            return this;
        }

        public Builder rds(RdsService v) {
            this.rdsService = v;
            return this;
        }

        public Builder route53(Route53Service v) {
            this.route53Service = v;
            return this;
        }

        public Builder scheduler(SchedulerService v) {
            this.schedulerService = v;
            return this;
        }

        public Builder eks(EksService v) {
            this.eksService = v;
            return this;
        }

        public Builder logs(CloudWatchLogsService v) {
            this.logsService = v;
            return this;
        }

        public Builder kinesis(KinesisService v) {
            this.kinesisService = v;
            return this;
        }

        public Builder cloudWatchMetrics(CloudWatchMetricsService v) {
            this.cloudWatchMetricsService = v;
            return this;
        }

        public Builder autoScaling(AutoScalingService v) {
            this.autoScalingService = v;
            return this;
        }

        public Builder acm(AcmService v) {
            this.acmService = v;
            return this;
        }

        public Builder firehose(FirehoseService v) {
            this.firehoseService = v;
            return this;
        }

        public Builder docDb(DocDbService v) {
            this.docDbService = v;
            return this;
        }

        public Builder cloudFront(CloudFrontService v) {
            this.cloudFrontService = v;
            return this;
        }

        public Builder flowLog(FlowLogService v) {
            this.flowLogService = v;
            return this;
        }

        public Builder lambdaMicrovms(LambdaMicrovmsService v) {
            this.lambdaMicrovmsService = v;
            return this;
        }

        public Builder awsConfig(AwsConfigService v) {
            this.awsConfigService = v;
            return this;
        }

        public Builder organizations(OrganizationsService v) {
            this.organizationsService = v;
            return this;
        }

        public Builder sqs(SqsService v) {
            this.sqsService = v;
            return this;
        }

        public Builder wafV2(WafV2Service v) {
            this.wafV2Service = v;
            return this;
        }

        public Builder registry(CloudFormationResourceRegistry v) {
            this.resourceRegistry = v;
            this.registryChosenByTest = true;
            return this;
        }

        public Builder dynamicReferences(CfnDynamicReferences v) {
            this.dynamicReferences = v;
            return this;
        }

        public Builder config(EmulatorConfig v) {
            this.config = v;
            return this;
        }

        /** The registry {@link #build()} would use: explicit if the test chose one, else inferred. */
        CloudFormationResourceRegistry buildRegistry() {
            return registryChosenByTest
                    ? resourceRegistry
                    : new CloudFormationResourceRegistry(inferredProvisioners());
        }

        public CloudFormationResourceProvisioner build() {
            if (!registryChosenByTest) {
                resourceRegistry = buildRegistry();
            }
            if (dynamicReferences == null) {
                // Wire it from the services already named, the way CDI does in production, so a
                // test resolving {{resolve:ssm:...}} or {{resolve:secretsmanager:...}} does not
                // have to know that resolution moved out of the provisioner.
                dynamicReferences = new CfnDynamicReferences(
                        secretsManagerService, ssmService, objectMapper);
            }
            return new CloudFormationResourceProvisioner(
                    s3Service,
                    snsService,
                    dynamoDbService,
                    lambdaService,
                    iamService,
                    ssmService,
                    kmsService,
                    secretsManagerService,
                    eventBridgeService,
                    apiGatewayService,
                    apiGatewayV2Service,
                    ecrService,
                    pipesService,
                    cognitoService,
                    lambdaLayerService,
                    objectMapper,
                    customResourceResponseStore,
                    reachableEndpoint,
                    ecsService,
                    elbV2Service,
                    stepFunctionsService,
                    batchService,
                    ec2Service,
                    rdsService,
                    eksService,
                    logsService,
                    kinesisService,
                    cloudWatchMetricsService,
                    autoScalingService,
                    firehoseService,
                    docDbService,
                    cloudFrontService,
                    resourceRegistry,
                    dynamicReferences,
                    config);
        }
    }
}
