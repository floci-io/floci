package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.services.apigateway.ApiGatewayService;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import io.github.hectorvent.floci.services.autoscaling.AutoScalingService;
import io.github.hectorvent.floci.services.batch.BatchService;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnDynamicReferences;
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
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.ssm.SsmService;
import io.github.hectorvent.floci.services.stepfunctions.StepFunctionsService;

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
        private FirehoseService firehoseService;
        private DocDbService docDbService;
        private CloudFrontService cloudFrontService;
        private CloudFormationResourceRegistry resourceRegistry;
        private CfnDynamicReferences dynamicReferences;
        private EmulatorConfig config;

        private Builder() {
            this.objectMapper = new ObjectMapper();
            this.resourceRegistry = new CloudFormationResourceRegistry(List.of());
        }

        /** Registers extracted provisioners, the way production CDI discovery would. */
        public Builder provisioners(CfnResourceProvisioner... provisioners) {
            this.resourceRegistry = new CloudFormationResourceRegistry(Arrays.asList(provisioners));
            return this;
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

        public Builder registry(CloudFormationResourceRegistry v) {
            this.resourceRegistry = v;
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

        public CloudFormationResourceProvisioner build() {
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
