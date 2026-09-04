package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.appconfig.AppConfigController;
import io.github.hectorvent.floci.services.backup.BackupController;
import io.github.hectorvent.floci.services.resourceexplorer2.ResourceExplorer2Controller;
import io.github.hectorvent.floci.services.appconfig.AppConfigDataController;
import io.github.hectorvent.floci.services.batch.BatchController;
import io.github.hectorvent.floci.services.bedrockruntime.BedrockRuntimeController;
import io.github.hectorvent.floci.services.cognito.CognitoOAuthController;
import io.github.hectorvent.floci.services.cognito.CognitoWellKnownController;
import io.github.hectorvent.floci.services.eks.EksController;
import io.github.hectorvent.floci.services.fis.FisController;
import io.github.hectorvent.floci.services.mwaa.MwaaController;
import io.github.hectorvent.floci.services.iot.IotController;
import io.github.hectorvent.floci.services.iot.IotDomainConfigurationController;
import io.github.hectorvent.floci.services.iot.IotDataController;
import io.github.hectorvent.floci.services.bedrockagentcore.BedrockAgentCoreController;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreControlController;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreGatewayController;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreIdentityController;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreMemoryController;
import io.github.hectorvent.floci.services.pipes.PipesController;
import io.github.hectorvent.floci.services.lambda.LambdaController;
import io.github.hectorvent.floci.services.lambdamicrovms.LambdaMicrovmsController;
import io.github.hectorvent.floci.services.lambdamicrovms.LambdaNetworkConnectorsController;
import io.github.hectorvent.floci.services.opensearch.OpenSearchController;
import io.github.hectorvent.floci.services.cloudfront.CloudFrontController;
import io.github.hectorvent.floci.services.cloudfront.CloudFrontServingController;
import io.github.hectorvent.floci.services.route53.Route53Controller;
import io.github.hectorvent.floci.services.ses.SesController;
import io.github.hectorvent.floci.services.appsync.AppSyncController;
import io.github.hectorvent.floci.services.rdsdata.RdsDataController;
import io.github.hectorvent.floci.services.guardduty.GuardDutyController;
import io.github.hectorvent.floci.services.aps.ApsController;
import io.github.hectorvent.floci.services.controltower.ControlTowerController;
import io.github.hectorvent.floci.services.rum.RumController;
import io.github.hectorvent.floci.services.s3vectors.S3VectorsController;
import io.github.hectorvent.floci.services.s3tables.S3TablesController;
import io.github.hectorvent.floci.services.efs.EfsController;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.EnumSet;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class ResolvedServiceCatalog {

    /**
     * Signing scopes that share another service's IAM namespace. S3 Express One Zone clients sign
     * directory-bucket requests as {@code s3express} while the actions, ARNs and condition keys
     * remain {@code s3}; the IoT Jobs Data Plane signs as {@code iot-jobs-data} while its actions
     * are {@code iot:} ({@code iot:DescribeJobExecution} and peers in the Service Authorization
     * Reference). Keep this minimal: every entry suppresses a distinct IAM namespace.
     */
    private static final java.util.Map<String, String> CREDENTIAL_SCOPE_ALIASES =
            java.util.Map.of(
                    "s3express", "s3",
                    "iot-jobs-data", "iot");

    private final ServiceCatalog catalog;

    @Inject
    public ResolvedServiceCatalog(EmulatorConfig config) {
        this.catalog = new ServiceCatalog(List.of(
                descriptor("ssm", "ssm", config.services().ssm().enabled(), true,
                        "ssm", storageMode(config.storage().services().ssm().mode(), config.storage().mode()),
                        config.storage().services().ssm().flushIntervalMs(), null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("AmazonSSM."), Set.of("ssm"), Set.of(), Set.of()),
                descriptor("sqs", "sqs", config.services().sqs().enabled(), true,
                        "sqs", storageMode(config.storage().services().sqs().mode(), config.storage().mode()),
                        5000L, AwsNamespaces.SQS, ServiceProtocol.QUERY,
                        protocols(ServiceProtocol.QUERY, ServiceProtocol.JSON, ServiceProtocol.CBOR),
                        Set.of("AmazonSQS."), Set.of("sqs"), Set.of("SQS"), Set.of()),
                descriptor("s3", "s3", config.services().s3().enabled(), true,
                        "s3", storageMode(config.storage().services().s3().mode(), config.storage().mode()),
                        5000L, AwsNamespaces.S3, ServiceProtocol.REST_XML,
                        protocols(ServiceProtocol.REST_XML),
                        // s3express: directory-bucket (S3 Express One Zone) clients sign with it
                        Set.of(), Set.of("s3", "s3express"), Set.of(), Set.of()),
                descriptor("dynamodb", "dynamodb", config.services().dynamodb().enabled(), true,
                        "dynamodb", storageMode(config.storage().services().dynamodb().mode(), config.storage().mode()),
                        config.storage().services().dynamodb().flushIntervalMs(), null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON, ServiceProtocol.CBOR),
                        Set.of("DynamoDB_20120810.", "DynamoDBStreams_20120810."),
                        Set.of("dynamodb"), Set.of("DynamoDB", "DynamoDB Streams"), Set.of()),
                descriptor("sns", "sns", config.services().sns().enabled(), true,
                        "sns", storageMode(config.storage().services().sns().mode(), config.storage().mode()),
                        config.storage().services().sns().flushIntervalMs(), AwsNamespaces.SNS, ServiceProtocol.QUERY,
                        protocols(ServiceProtocol.QUERY, ServiceProtocol.JSON, ServiceProtocol.CBOR),
                        Set.of("SNS_20100331."), Set.of("sns"), Set.of("SNS"), Set.of()),
                descriptor("lambda", "lambda", config.services().lambda().enabled(), true,
                        "lambda", storageMode(config.storage().services().lambda().mode(), config.storage().mode()),
                        config.storage().services().lambda().flushIntervalMs(), null, ServiceProtocol.REST_JSON,
                        protocols(ServiceProtocol.REST_JSON),
                        Set.of(), Set.of("lambda"), Set.of(),
                        Set.of(LambdaController.class,
                                LambdaMicrovmsController.class,
                                LambdaNetworkConnectorsController.class)),
                descriptor("apigateway", "apigateway", config.services().apigateway().enabled(), true,
                        "apigateway", config.storage().mode(), 5000L, null, ServiceProtocol.REST_JSON,
                        protocols(ServiceProtocol.REST_JSON),
                        Set.of(), Set.of("apigateway", "execute-api"), Set.of(), Set.of()),
                descriptor("iam", "iam", config.services().iam().enabled(), true,
                        "iam", config.storage().mode(), 5000L, AwsNamespaces.IAM, ServiceProtocol.QUERY,
                        protocols(ServiceProtocol.QUERY),
                        Set.of(), Set.of("iam"), Set.of(), Set.of()),
                descriptor("kafka", "msk", config.services().msk().enabled(), true,
                        "msk", config.storage().mode(), 5000L, null, ServiceProtocol.REST_JSON,
                        protocols(ServiceProtocol.REST_JSON),
                        Set.of(), Set.of("kafka"), Set.of(), Set.of(io.github.hectorvent.floci.services.msk.MskController.class)),
                descriptor("mq", "amazonmq", config.services().amazonmq().enabled(), true,
                        "amazonmq", config.storage().mode(), 5000L, null, ServiceProtocol.REST_JSON,
                        protocols(ServiceProtocol.REST_JSON),
                        Set.of(), Set.of("mq"), Set.of(), Set.of(io.github.hectorvent.floci.services.amazonmq.AmazonMqController.class)),
                descriptor("sts", "iam", config.services().iam().enabled(), false,
                        null, null, 5000L, AwsNamespaces.STS, ServiceProtocol.QUERY,
                        protocols(ServiceProtocol.QUERY),
                        Set.of(), Set.of("sts"), Set.of(), Set.of()),
                descriptor("signin", "iam", config.services().iam().enabled(), false,
                        null, null, 5000L, null, ServiceProtocol.REST_JSON,
                        protocols(ServiceProtocol.REST_JSON),
                        Set.of(), Set.of("signin"), Set.of(),
                        Set.of(io.github.hectorvent.floci.services.signin.SigninController.class)),
                descriptor("elasticache", "elasticache", config.services().elasticache().enabled(), true,
                        "elasticache", storageMode(config.storage().services().elasticache().mode(), config.storage().mode()),
                        config.storage().services().elasticache().flushIntervalMs(), AwsNamespaces.EC, ServiceProtocol.QUERY,
                        protocols(ServiceProtocol.QUERY),
                        Set.of(), Set.of("elasticache"), Set.of(), Set.of()),
                descriptor("memorydb", "memorydb", config.services().memorydb().enabled(), true,
                        "memorydb", storageMode(config.storage().services().memorydb().mode(), config.storage().mode()),
                        config.storage().services().memorydb().flushIntervalMs(), null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("AmazonMemoryDB."), Set.of("memorydb"), Set.of(), Set.of()),
                descriptor("rds", "rds", config.services().rds().enabled(), true,
                        "rds", storageMode(config.storage().services().rds().mode(), config.storage().mode()),
                        5000L, AwsNamespaces.RDS, ServiceProtocol.QUERY,
                        protocols(ServiceProtocol.QUERY),
                        Set.of(), Set.of("rds"), Set.of(), Set.of()),
                descriptor("rds-data", "rds-data",
                        config.services().rds().enabled() && config.services().rdsData().enabled(), true,
                        null, null, 5000L, null, ServiceProtocol.REST_JSON,
                        protocols(ServiceProtocol.REST_JSON),
                        Set.of(), Set.of("rds-data"), Set.of(), Set.of(RdsDataController.class)),
                descriptor("neptune", "neptune", config.services().neptune().enabled(), true,
                        "neptune", storageMode(config.storage().services().neptune().mode(), config.storage().mode()),
                        5000L, AwsNamespaces.RDS, ServiceProtocol.QUERY,
                        protocols(ServiceProtocol.QUERY),
                        Set.of(), Set.of("neptune"), Set.of(), Set.of()),
                descriptor("docdb", "docdb", config.services().docdb().enabled(), true,
                        "docdb", config.storage().mode(),                        
                        5000L, AwsNamespaces.RDS, ServiceProtocol.QUERY,
                        protocols(ServiceProtocol.QUERY),
                        Set.of(), Set.of("docdb"), Set.of(), Set.of()),
                descriptor("redshift", "redshift", config.services().redshift().enabled(), true,
                        "redshift", storageMode(config.storage().services().redshift().mode(), config.storage().mode()),
                        5000L, AwsNamespaces.REDSHIFT, ServiceProtocol.QUERY,
                        protocols(ServiceProtocol.QUERY),
                        Set.of(), Set.of("redshift"), Set.of(), Set.of()),
                
                descriptor("events", "eventbridge", config.services().eventbridge().enabled(), true,
                        "eventbridge", config.storage().mode(), 5000L, null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("AWSEvents."), Set.of("events"), Set.of(), Set.of()),
                descriptor("servicediscovery", "cloudmap", config.services().cloudmap().enabled(), true,
                        "cloudmap", config.storage().mode(), 5000L, null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("Route53AutoNaming_v20170314."), Set.of("servicediscovery"), Set.of(), Set.of()),
                descriptor("elasticmapreduce", "emr", config.services().emr().enabled(), true,
                        "emr", config.storage().mode(), 5000L, null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("ElasticMapReduce."), Set.of("elasticmapreduce"), Set.of(), Set.of()),
                descriptor("emr-serverless", "emrserverless", config.services().emrserverless().enabled(), true,
                        "emrserverless", storageMode(config.storage().services().emrserverless().mode(), config.storage().mode()),
                        config.storage().services().emrserverless().flushIntervalMs(), null, ServiceProtocol.REST_JSON,
                        protocols(ServiceProtocol.REST_JSON),
                        Set.of(), Set.of("emr-serverless"), Set.of(),
                        Set.of(io.github.hectorvent.floci.services.emrserverless.EmrServerlessController.class)),
                descriptor("wafv2", "wafv2", config.services().wafv2().enabled(), true,
                        "wafv2", config.storage().mode(), 5000L, null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("AWSWAF_20190729."), Set.of("wafv2"), Set.of(), Set.of()),
                descriptor("scheduler", "scheduler", config.services().scheduler().enabled(), true,
                        "scheduler", config.storage().mode(), 5000L, null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of(), Set.of("scheduler"), Set.of(), Set.of()),
                descriptor("logs", "cloudwatchlogs", config.services().cloudwatchlogs().enabled(), true,
                        "cloudwatchlogs", storageMode(config.storage().services().cloudwatchlogs().mode(), config.storage().mode()),
                        config.storage().services().cloudwatchlogs().flushIntervalMs(), null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("Logs_20140328."), Set.of("logs"), Set.of(), Set.of()),
                descriptor("monitoring", "cloudwatchmetrics", config.services().cloudwatchmetrics().enabled(), true,
                        "cloudwatchmetrics", storageMode(config.storage().services().cloudwatchmetrics().mode(), config.storage().mode()),
                        config.storage().services().cloudwatchmetrics().flushIntervalMs(), AwsNamespaces.CW, ServiceProtocol.QUERY,
                        protocols(ServiceProtocol.QUERY, ServiceProtocol.JSON, ServiceProtocol.CBOR),
                        Set.of("GraniteServiceVersion20100801."), Set.of("monitoring"),
                        Set.of("GraniteServiceVersion20100801"), Set.of()),
                descriptor("secretsmanager", "secretsmanager", config.services().secretsmanager().enabled(), true,
                        "secretsmanager", storageMode(config.storage().services().secretsmanager().mode(), config.storage().mode()),
                        config.storage().services().secretsmanager().flushIntervalMs(), null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("secretsmanager."), Set.of("secretsmanager"), Set.of(), Set.of()),
                descriptor("apigatewayv2", "apigatewayv2", config.services().apigatewayv2().enabled(), true,
                        "apigatewayv2", config.storage().mode(), 5000L, null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("AmazonApiGatewayV2."), Set.of("apigatewayv2"), Set.of(), Set.of()),
                descriptor("kinesis", "kinesis", config.services().kinesis().enabled(), true,
                        "kinesis", config.storage().mode(), 5000L, null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON, ServiceProtocol.CBOR),
                        Set.of("Kinesis_20131202."), Set.of("kinesis"), Set.of(), Set.of()),
                descriptor("kinesisanalytics", "kinesisanalytics",
                        config.services().kinesisAnalytics().enabled(), true,
                        "kinesisanalytics", config.storage().mode(), 5000L, null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("KinesisAnalytics_20180523."), Set.of("kinesisanalytics"), Set.of(), Set.of()),
                descriptor("kms", "kms", config.services().kms().enabled(), true,
                        "kms", config.storage().mode(), 5000L, null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("TrentService."), Set.of("kms"), Set.of(), Set.of()),
                descriptor("cognito-idp", "cognito", config.services().cognito().enabled(), true,
                        "cognito", config.storage().mode(), 5000L, null, ServiceProtocol.REST_JSON,
                        protocols(ServiceProtocol.REST_JSON, ServiceProtocol.JSON, ServiceProtocol.QUERY),
                        Set.of("AWSCognitoIdentityProviderService."), Set.of("cognito-idp"), Set.of(),
                        Set.of(CognitoOAuthController.class, CognitoWellKnownController.class)),
                descriptor("states", "stepfunctions", config.services().stepfunctions().enabled(), true,
                        "stepfunctions", config.storage().mode(), 5000L, null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON, ServiceProtocol.CBOR),
                        Set.of("AWSStepFunctions."), Set.of("states"), Set.of("SFN"), Set.of()),
                descriptor("swf", "swf", config.services().swf().enabled(), true,
                        "swf", config.storage().mode(), 5000L, null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("SimpleWorkflowService."), Set.of("swf"), Set.of(), Set.of()),
                descriptor("cloudformation", "cloudformation", config.services().cloudformation().enabled(), true,
                        null, null, 5000L, null, ServiceProtocol.QUERY,
                        protocols(ServiceProtocol.QUERY),
                        Set.of(), Set.of("cloudformation"), Set.of(), Set.of()),
                descriptor("acm", "acm", config.services().acm().enabled(), true,
                        "acm", storageMode(config.storage().services().acm().mode(), config.storage().mode()),
                        config.storage().services().acm().flushIntervalMs(), null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("CertificateManager."), Set.of("acm"), Set.of(), Set.of()),
                descriptor("athena", "athena", config.services().athena().enabled(), true,
                        "athena", config.storage().mode(), 5000L, null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("AmazonAthena."), Set.of("athena"), Set.of(), Set.of()),
                descriptor("glue", "glue", config.services().glue().enabled(), true,
                        "glue", config.storage().mode(), 5000L, null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("AWSGlue."), Set.of("glue"), Set.of(), Set.of()),
                descriptor("firehose", "firehose", config.services().firehose().enabled(), true,
                        "firehose", config.storage().mode(), 5000L, null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("Firehose_20150804."), Set.of("firehose"), Set.of(), Set.of()),
                descriptor("email", "ses", config.services().ses().enabled(), true,
                        "ses", config.storage().mode(), 5000L, AwsNamespaces.SES, ServiceProtocol.REST_JSON,
                        protocols(ServiceProtocol.REST_JSON, ServiceProtocol.QUERY),
                        Set.of(), Set.of("email", "ses", "sesv2"), Set.of(), Set.of(SesController.class)),
                descriptor("es", "opensearch", config.services().opensearch().enabled(), true,
                        "opensearch", storageMode(config.storage().services().opensearch().mode(), config.storage().mode()),
                        config.storage().services().opensearch().flushIntervalMs(), null, ServiceProtocol.REST_JSON,
                        protocols(ServiceProtocol.REST_JSON),
                        Set.of(), Set.of("es"), Set.of(), Set.of(OpenSearchController.class)),
                descriptor("ec2", "ec2", config.services().ec2().enabled(), true,
                        "ec2", storageMode(config.storage().services().ec2().mode(), config.storage().mode()),
                        5000L, AwsNamespaces.EC2, ServiceProtocol.QUERY,
                        protocols(ServiceProtocol.QUERY),
                        Set.of(), Set.of("ec2"), Set.of(), Set.of()),
                descriptor("ecs", "ecs", config.services().ecs().enabled(), true,
                        "ecs", storageMode(config.storage().services().ecs().mode(), config.storage().mode()),
                        config.storage().services().ecs().flushIntervalMs(), null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("AmazonEC2ContainerServiceV20141113."), Set.of("ecs"), Set.of(), Set.of()),
                descriptor("appconfig", "appconfig", config.services().appconfig().enabled(), true,
                        "appconfig", storageMode(config.storage().services().appconfig().mode(), config.storage().mode()),
                        config.storage().services().appconfig().flushIntervalMs(), null, ServiceProtocol.REST_JSON,
                        protocols(ServiceProtocol.REST_JSON),
                        Set.of(), Set.of("appconfig"), Set.of(), Set.of(AppConfigController.class)),
                descriptor("appconfigdata", "appconfigdata", config.services().appconfigdata().enabled(), true,
                        "appconfigdata", storageMode(config.storage().services().appconfigdata().mode(), config.storage().mode()),
                        config.storage().services().appconfigdata().flushIntervalMs(), null, ServiceProtocol.REST_JSON,
                        protocols(ServiceProtocol.REST_JSON),
                        Set.of(), Set.of("appconfigdata"), Set.of(), Set.of(AppConfigDataController.class)),
                descriptor("ecr", "ecr", config.services().ecr().enabled(), true,
                        null, null, 5000L, null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("AmazonEC2ContainerRegistry_V20150921."), Set.of("ecr"), Set.of(), Set.of()),
                descriptor("tagging", "tagging", config.services().tagging().enabled(), true,
                        "tagging", storageMode(config.storage().services().tagging().mode(), config.storage().mode()),
                        config.storage().services().tagging().flushIntervalMs(), null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("ResourceGroupsTaggingAPI_20170126."), Set.of("tagging"), Set.of(), Set.of()),
                descriptor("bedrock-runtime", "bedrock-runtime",
                        config.services().bedrockRuntime().enabled(), true,
                        null, null, 5000L, null, ServiceProtocol.REST_JSON,
                        protocols(ServiceProtocol.REST_JSON),
                        Set.of(),
                        // Register both signing names. boto3's service model declares
                        // signingName=bedrock for bedrock-runtime; register the endpoint
                        // id too as a safety net (catalog lookup is exact-match).
                        Set.of("bedrock", "bedrock-runtime"),
                        Set.of(),
                        Set.of(BedrockRuntimeController.class)),
                descriptor("eks", "eks", config.services().eks().enabled(), true,
                        "eks", config.storage().mode(), 5000L, null, ServiceProtocol.REST_JSON,
                        protocols(ServiceProtocol.REST_JSON),
                        Set.of(), Set.of("eks"), Set.of(), Set.of(EksController.class)),
                descriptor("mwaa", "mwaa", config.services().mwaa().enabled(), true,
                        "mwaa", config.storage().mode(), 5000L, null, ServiceProtocol.REST_JSON,
                        protocols(ServiceProtocol.REST_JSON),
                        Set.of(),
                        // Register both the signing name and the endpoint id. botocore's service
                        // model declares signingName=airflow for mwaa (endpointPrefix=airflow too);
                        // register the "mwaa" config/external key as well as a safety net, same
                        // double-registration technique as bedrock-runtime/bedrock above.
                        Set.of("airflow", "mwaa"),
                        Set.of(),
                        Set.of(MwaaController.class)),
                descriptor("pipes", "pipes", config.services().pipes().enabled(), true,
                        "pipes", config.storage().mode(), 5000L, null, ServiceProtocol.REST_JSON,
                        protocols(ServiceProtocol.REST_JSON),
                        Set.of(), Set.of("pipes"), Set.of(), Set.of(PipesController.class)),
                descriptor("bedrock-agentcore-control", "bedrock-agentcore-control",
                        config.services().bedrockAgentCoreControl().enabled(), true,
                        "bedrockagentcore", config.storage().mode(), 5000L, null, ServiceProtocol.REST_JSON,
                        protocols(ServiceProtocol.REST_JSON),
                        Set.of(), Set.of("bedrock-agentcore"), Set.of(),
                        Set.of(BedrockAgentCoreControlController.class, BedrockAgentCoreIdentityController.class,
                                BedrockAgentCoreGatewayController.class, BedrockAgentCoreMemoryController.class)),
                descriptor("bedrock-agentcore", "bedrock-agentcore",
                        config.services().bedrockAgentCore().enabled(), true,
                        null, null, 5000L, null, ServiceProtocol.REST_JSON,
                        protocols(ServiceProtocol.REST_JSON),
                        Set.of(), Set.of(), Set.of(),
                        Set.of(BedrockAgentCoreController.class)),
                descriptor("elasticloadbalancing", "elbv2", config.services().elbv2().enabled(), true,
                        "elbv2", config.storage().mode(), 5000L, AwsNamespaces.ELB_V2, ServiceProtocol.QUERY,
                        protocols(ServiceProtocol.QUERY),
                        Set.of(), Set.of("elasticloadbalancing"), Set.of(), Set.of()),
                // Classic (v1) ELB shares the elasticloadbalancing endpoint and credential scope
                // with ELBv2 above, so it claims neither here; AwsQueryController splits the two on
                // the request's Version parameter. Listed so the service and its config knob are
                // visible in status.
                descriptor("elb", "elb", config.services().elb().enabled(), true,
                        "elb", config.storage().mode(), 5000L, AwsNamespaces.ELB_CLASSIC, ServiceProtocol.QUERY,
                        protocols(ServiceProtocol.QUERY),
                        Set.of(), Set.of(), Set.of(), Set.of()),
                descriptor("codebuild", "codebuild", config.services().codebuild().enabled(), true,
                        "codebuild", storageMode(config.storage().services().codebuild().mode(), config.storage().mode()),
                        config.storage().services().codebuild().flushIntervalMs(), null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("CodeBuild_20161006."), Set.of("codebuild"), Set.of(), Set.of()),
                descriptor("batch", "batch", config.services().batch().enabled(), true,
                        "batch", storageMode(config.storage().services().batch().mode(), config.storage().mode()),
                        config.storage().services().batch().flushIntervalMs(), null, ServiceProtocol.REST_JSON,
                        protocols(ServiceProtocol.REST_JSON),
                        Set.of(), Set.of("batch"), Set.of(), Set.of(BatchController.class)),
                descriptor("codedeploy", "codedeploy", config.services().codedeploy().enabled(), true,
                        "codedeploy", storageMode(config.storage().services().codedeploy().mode(), config.storage().mode()),
                        config.storage().services().codedeploy().flushIntervalMs(), null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("CodeDeploy_20141006."), Set.of("codedeploy"), Set.of(), Set.of()),
                descriptor("codepipeline", "codepipeline", config.services().codepipeline().enabled(), true,
                        "codepipeline",
                        storageMode(config.storage().services().codepipeline().mode(), config.storage().mode()),
                        config.storage().services().codepipeline().flushIntervalMs(), null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("CodePipeline_20150709."), Set.of("codepipeline"), Set.of(), Set.of()),
                descriptor("servicequotas", "servicequotas", config.services().servicequotas().enabled(), true,
                        null, null, 5000L, null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("ServiceQuotasV20190624."), Set.of("servicequotas"), Set.of(), Set.of()),
                descriptor("ram", "ram", config.services().ram().enabled(), true,
                        null, null, 5000L, null, ServiceProtocol.REST_JSON,
                        protocols(ServiceProtocol.REST_JSON),
                        Set.of(), Set.of("ram"), Set.of(),
                        Set.of(io.github.hectorvent.floci.services.ram.RamController.class)),
                descriptor("config", "configservice", config.services().configservice().enabled(), true,
                        "config", storageMode(config.storage().services().config().mode(), config.storage().mode()),
                        config.storage().services().config().flushIntervalMs(), null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("StarlingDoveService."), Set.of("config"), Set.of(), Set.of()),
                descriptor("cloudtrail", "cloudtrail", config.services().cloudtrail().enabled(), true,
                        "cloudtrail", storageMode(config.storage().services().cloudtrail().mode(), config.storage().mode()),
                        config.storage().services().cloudtrail().flushIntervalMs(), null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("CloudTrail_20131101.",
                               "com.amazonaws.cloudtrail.v20131101.CloudTrail_20131101."),
                        Set.of("cloudtrail"), Set.of(), Set.of()),
                descriptor("lightsail", "lightsail", config.services().lightsail().enabled(), true,
                        "lightsail", storageMode(config.storage().services().lightsail().mode(), config.storage().mode()),
                        config.storage().services().lightsail().flushIntervalMs(), null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("Lightsail_20161128."), Set.of("lightsail"), Set.of(), Set.of()),
                descriptor("cloudcontrol", "cloudcontrol", config.services().cloudcontrol().enabled(), true,
                        null, null, 5000L, null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("CloudApiService."), Set.of("cloudcontrolapi"), Set.of(), Set.of()),
                // SSO Admin (IAM Identity Center) signs with scope "sso"; its Smithy target
                // prefix is the AWS-internal codename SWBExternalService (cf. config's
                // StarlingDoveService above).
                descriptor("sso", "ssoadmin", config.services().ssoadmin().enabled(), true,
                        null, null, 5000L, null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("SWBExternalService."), Set.of("sso"), Set.of(), Set.of()),
                descriptor("autoscaling", "autoscaling", config.services().autoscaling().enabled(), true,
                        "autoscaling", config.storage().mode(), 5000L, AwsNamespaces.AUTOSCALING, ServiceProtocol.QUERY,
                        protocols(ServiceProtocol.QUERY),
                        Set.of(), Set.of("autoscaling"), Set.of(), Set.of()),
                descriptor("application-autoscaling", "applicationautoscaling",
                        config.services().applicationautoscaling().enabled(), true,
                        "applicationautoscaling", config.storage().mode(), 5000L, null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("AnyScaleFrontendService."), Set.of("application-autoscaling"),
                        Set.of(), Set.of()),
                descriptor("elasticbeanstalk", "elasticbeanstalk",
                        config.services().elasticbeanstalk().enabled(), true,
                        "elasticbeanstalk",
                        storageMode(config.storage().services().elasticbeanstalk().mode(), config.storage().mode()),
                        config.storage().services().elasticbeanstalk().flushIntervalMs(),
                        AwsNamespaces.ELASTIC_BEANSTALK, ServiceProtocol.QUERY,
                        protocols(ServiceProtocol.QUERY),
                        Set.of(), Set.of("elasticbeanstalk"), Set.of(), Set.of()),
                descriptor("backup", "backup", config.services().backup().enabled(), true,
                        "backup", storageMode(config.storage().services().backup().mode(), config.storage().mode()),
                        config.storage().services().backup().flushIntervalMs(), null, ServiceProtocol.REST_JSON,
                        protocols(ServiceProtocol.REST_JSON),
                        Set.of(), Set.of("backup"), Set.of(), Set.of(BackupController.class)),
                descriptor("resource-explorer-2", "resourceexplorer2",
                        config.services().resourceexplorer2().enabled(), true,
                        "resourceexplorer2",
                        storageMode(config.storage().services().resourceexplorer2().mode(), config.storage().mode()),
                        config.storage().services().resourceexplorer2().flushIntervalMs(),
                        null, ServiceProtocol.REST_JSON,
                        protocols(ServiceProtocol.REST_JSON),
                        Set.of(), Set.of("resource-explorer-2"), Set.of(),
                        Set.of(ResourceExplorer2Controller.class)),
                descriptor("fis", "fis", config.services().fis().enabled(), true,
                        "fis", storageMode(config.storage().services().fis().mode(), config.storage().mode()),
                        config.storage().services().fis().flushIntervalMs(), null, ServiceProtocol.REST_JSON,
                        protocols(ServiceProtocol.REST_JSON),
                        Set.of(), Set.of("fis"), Set.of(), Set.of(FisController.class)),
                descriptor("ec2messages", "ec2messages", config.services().ssm().enabled(), false,
                        null, null, 5000L, null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("AmazonSSMMessageDeliveryService."), Set.of("ec2messages"), Set.of(), Set.of()),
                descriptor("transfer", "transfer", config.services().transfer().enabled(), true,
                        "transfer", config.storage().mode(), 5000L, null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("TransferService."), Set.of("transfer"), Set.of(), Set.of()),
                descriptor("route53", "route53", config.services().route53().enabled(), true,
                        "route53", config.storage().mode(), 5000L, null, ServiceProtocol.REST_XML,
                        protocols(ServiceProtocol.REST_XML),
                        Set.of(), Set.of("route53"), Set.of(), Set.of(Route53Controller.class)),
                descriptor("textract", "textract", config.services().textract().enabled(), true,
                        null, null, 5000L, null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("Textract."), Set.of("textract"), Set.of(), Set.of()),
                descriptor("comprehend", "comprehend", config.services().comprehend().enabled(), true,
                        null, null, 5000L, null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("Comprehend_20171127."), Set.of("comprehend"), Set.of(), Set.of()),
                descriptor("rekognition", "rekognition", config.services().rekognition().enabled(), true,
                        null, null, 5000L, null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("RekognitionService."), Set.of("rekognition"), Set.of(), Set.of()),
                descriptor("pricing", "pricing", config.services().pricing().enabled(), true,
                        null, null, 5000L, null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("AWSPriceListService."), Set.of("pricing", "api.pricing"), Set.of(), Set.of()),
                descriptor("transcribe", "transcribe", config.services().transcribe().enabled(), true,
                        "transcribe", storageMode(config.storage().services().transcribe().mode(), config.storage().mode()),
                        config.storage().services().transcribe().flushIntervalMs(), null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("Transcribe."), Set.of("transcribe"), Set.of(), Set.of()),
                descriptor("translate", "translate", config.services().translate().enabled(), true,
                        null, null, 5000L, null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("AWSShineFrontendService_20170701."), Set.of("translate"), Set.of(), Set.of()),
                descriptor("ce", "ce", config.services().ce().enabled(), true,
                        null, null, 5000L, null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("AWSInsightsIndexService."), Set.of("ce"), Set.of(), Set.of()),
                descriptor("cur", "cur", config.services().cur().enabled(), true,
                        "cur", config.storage().mode(), 5000L, null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("AWSOrigamiServiceGatewayService."), Set.of("cur"), Set.of(), Set.of()),
                descriptor("bcm-data-exports", "bcmdataexports", config.services().bcmDataExports().enabled(), true,
                        "bcmdataexports", config.storage().mode(), 5000L, null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("AWSBillingAndCostManagementDataExports."), Set.of("bcm-data-exports"), Set.of(), Set.of()),
                descriptor("cloudfront", "cloudfront", config.services().cloudfront().enabled(), true,
                        "cloudfront", storageMode(config.storage().services().cloudfront().mode(), config.storage().mode()),
                        5000L, AwsNamespaces.CLOUDFRONT, ServiceProtocol.REST_XML,
                        protocols(ServiceProtocol.REST_XML),
                        Set.of(), Set.of("cloudfront"), Set.of(),
                        Set.of(CloudFrontController.class, CloudFrontServingController.class)),
                descriptor("appsync", "appsync", config.services().appsync().enabled(), true,
                        "appsync", storageMode(config.storage().services().appsync().mode(), config.storage().mode()),
                        config.storage().services().appsync().flushIntervalMs(), null, ServiceProtocol.REST_JSON,
                        protocols(ServiceProtocol.REST_JSON),
                        Set.of(), Set.of("appsync"), Set.of(), Set.of(AppSyncController.class,
                                io.github.hectorvent.floci.services.appsync.graphql.AppSyncExecutionController.class)),
                descriptor("s3vectors", "s3vectors", config.services().s3vectors().enabled(), true,
                        "s3vectors", storageMode(config.storage().services().s3vectors().mode(), config.storage().mode()),
                        config.storage().services().s3vectors().flushIntervalMs(), null, ServiceProtocol.REST_JSON,
                        protocols(ServiceProtocol.REST_JSON),
                        Set.of(), Set.of("s3vectors"), Set.of(), Set.of(S3VectorsController.class)),
                descriptor("s3tables", "s3tables", config.services().s3tables().enabled(), true,
                        "s3tables", storageMode(config.storage().services().s3tables().mode(), config.storage().mode()),
                        config.storage().services().s3tables().flushIntervalMs(), null, ServiceProtocol.REST_JSON,
                        protocols(ServiceProtocol.REST_JSON),
                        Set.of(), Set.of("s3tables"), Set.of(), Set.of(S3TablesController.class)),
                descriptor("iot", "iot", config.services().iot().enabled(), true,
                        "iot", config.storage().mode(), 5000L, null, ServiceProtocol.REST_JSON,
                        protocols(ServiceProtocol.REST_JSON),
                        // iot-jobs-data: the IoT Jobs Data Plane (GetPendingJobExecutions,
                        // DescribeJobExecution, StartNextPendingJobExecution, UpdateJobExecution)
                        // signs under its own name while IotController serves its /things/*/jobs routes
                        Set.of(), Set.of("iot", "execute-api", "iot-jobs-data"), Set.of(),
                        Set.of(IotController.class, IotDomainConfigurationController.class)),
                descriptor("iotdata", "iotdata", config.services().iotdata().enabled(), true,
                        "iot", config.storage().mode(), 5000L, null, ServiceProtocol.REST_JSON,
                        protocols(ServiceProtocol.REST_JSON),
                        Set.of(), Set.of("iotdata"), Set.of(), Set.of(IotDataController.class)),
                descriptor("cloudhsmv2", "cloudhsmv2", config.services().cloudhsmv2().enabled(), true,
                        "cloudhsmv2", config.storage().mode(), 5000L, null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("BaldrApiService."), Set.of("cloudhsm"), Set.of(), Set.of()),
                descriptor("organizations", "organizations", config.services().organizations().enabled(), true,
                        "organizations", config.storage().mode(), 5000L, null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("AWSOrganizationsV20161128."), Set.of("organizations"), Set.of(), Set.of()),
                descriptor("rum", "rum", config.services().rum().enabled(), true,
                        "rum", storageMode(config.storage().services().rum().mode(), config.storage().mode()),
                        config.storage().services().rum().flushIntervalMs(), null, ServiceProtocol.REST_JSON,
                        protocols(ServiceProtocol.REST_JSON),
                        Set.of(), Set.of("rum"), Set.of(), Set.of(RumController.class)),
                descriptor("guardduty", "guardduty", config.services().guardduty().enabled(), true,
                        "guardduty",
                        storageMode(config.storage().services().guardduty().mode(), config.storage().mode()),
                        config.storage().services().guardduty().flushIntervalMs(), null, ServiceProtocol.REST_JSON,
                        protocols(ServiceProtocol.REST_JSON),
                        Set.of(), Set.of("guardduty"), Set.of(), Set.of(GuardDutyController.class)),
                descriptor("route53resolver", "route53resolver", config.services().route53resolver().enabled(), true,
                        "route53resolver", config.storage().mode(), 5000L, null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("Route53Resolver."), Set.of("route53resolver"), Set.of(), Set.of()),
                descriptor("connect", "connect", config.services().connect().enabled(), true,
                        "connect", config.storage().mode(), 5000L, null, ServiceProtocol.REST_JSON,
                        protocols(ServiceProtocol.REST_JSON),
                        Set.of(), Set.of("connect"), Set.of(),
                        Set.of(io.github.hectorvent.floci.services.connect.ConnectController.class)),
                descriptor("network-firewall", "networkfirewall", config.services().networkfirewall().enabled(), true,
                        "networkfirewall", config.storage().mode(), 5000L, null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("NetworkFirewall_20201112."), Set.of("network-firewall"), Set.of(), Set.of()),
                descriptor("servicecatalog", "servicecatalog", config.services().servicecatalog().enabled(), true,
                        "servicecatalog", config.storage().mode(), 5000L, null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("AWS242ServiceCatalogService."), Set.of("servicecatalog"), Set.of(), Set.of()),
                descriptor("controltower", "controltower", config.services().controltower().enabled(), true,
                        "controltower", storageMode(config.storage().services().controltower().mode(), config.storage().mode()),
                        config.storage().services().controltower().flushIntervalMs(), null, ServiceProtocol.REST_JSON,
                        protocols(ServiceProtocol.REST_JSON),
                        Set.of(), Set.of("controltower"), Set.of(), Set.of(ControlTowerController.class)),
                descriptor("aps", "aps", config.services().aps().enabled(), true,
                        "aps", storageMode(config.storage().services().aps().mode(), config.storage().mode()),
                        config.storage().services().aps().flushIntervalMs(), null, ServiceProtocol.REST_JSON,
                        protocols(ServiceProtocol.REST_JSON),
                        Set.of(), Set.of("aps"), Set.of(), Set.of(ApsController.class)),
                descriptor("lakeformation", "lakeformation", config.services().lakeformation().enabled(), true,
                        "lakeformation",
                        storageMode(config.storage().services().lakeformation().mode(), config.storage().mode()),
                        config.storage().services().lakeformation().flushIntervalMs(), null, ServiceProtocol.REST_JSON,
                        protocols(ServiceProtocol.REST_JSON),
                        Set.of(), Set.of("lakeformation"), Set.of(), Set.of(io.github.hectorvent.floci.services.lakeformation.LakeFormationController.class)),
                descriptor("efs", "efs", config.services().efs().enabled(), true,
                        "efs",
                        storageMode(config.storage().services().efs().mode(), config.storage().mode()),
                        config.storage().services().efs().flushIntervalMs(), null, ServiceProtocol.REST_JSON,
                        protocols(ServiceProtocol.REST_JSON),
                        Set.of(), Set.of("elasticfilesystem"), Set.of(), Set.of(EfsController.class)),
                descriptor("codeguru-reviewer", "codegurureviewer",
                        config.services().codegurureviewer().enabled(), true,
                        "codegurureviewer", config.storage().mode(), 5000L, null, ServiceProtocol.REST_JSON,
                        protocols(ServiceProtocol.REST_JSON),
                        Set.of(), Set.of("codeguru-reviewer"), Set.of(),
                        Set.of(io.github.hectorvent.floci.services.codegurureviewer.CodeGuruReviewerController.class))
        ));
    }

    public Optional<ServiceDescriptor> byExternalKey(String externalKey) {
        return catalog.byExternalKey(externalKey);
    }

    public Optional<ServiceDescriptor> byStorageKey(String storageKey) {
        return catalog.byStorageKey(storageKey);
    }

    public Optional<ServiceDescriptor> byTarget(String target) {
        return catalog.byTarget(target);
    }

    public Optional<ServiceCatalog.TargetMatch> matchTarget(String target) {
        return catalog.matchTarget(target);
    }

    public Optional<ServiceDescriptor> byCredentialScope(String credentialScope) {
        return catalog.byCredentialScope(credentialScope);
    }

    /**
     * Canonical IAM namespace for a credential scope. A service may answer requests signed
     * under more than one scope (S3 also accepts {@code s3express}), but IAM action rules,
     * resource ARNs and condition keys are all keyed by the canonical one — an alias left
     * unnormalised resolves to no action, which the enforcement filter treats as ALLOW.
     *
     * <p>Deliberately an explicit table rather than something derived from the descriptor:
     * a descriptor's external key is a routing key, not an IAM namespace. SES routes under
     * {@code email} and Bedrock Runtime under {@code bedrock-runtime}, while their IAM
     * namespaces are {@code ses:} and {@code bedrock:} — deriving from the external key would
     * rewrite valid scopes onto prefixes AWS never issues, and silently skip enforcement for
     * those services. Add an entry here only when two scopes genuinely share one namespace.
     */
    public String canonicalCredentialScope(String credentialScope) {
        return CREDENTIAL_SCOPE_ALIASES.getOrDefault(credentialScope, credentialScope);
    }

    public Optional<ServiceDescriptor> byResourceClass(Class<?> resourceClass) {
        return catalog.byResourceClass(resourceClass);
    }

    public Optional<ServiceDescriptor> byCborSdkServiceId(String serviceId) {
        return catalog.byCborSdkServiceId(serviceId);
    }

    public List<ServiceDescriptor> all() {
        return catalog.all();
    }

    public List<ServiceDescriptor> allStatusDescriptors() {
        return catalog.allStatusDescriptors();
    }

    private static ServiceDescriptor descriptor(
            String externalKey,
            String configKey,
            boolean enabled,
            boolean includeInStatus,
            String storageKey,
            String storageMode,
            long storageFlushIntervalMs,
            String xmlNamespace,
            ServiceProtocol defaultProtocol,
            Set<ServiceProtocol> supportedProtocols,
            Set<String> targetPrefixes,
            Set<String> credentialScopes,
            Set<String> cborSdkServiceIds,
            Set<Class<?>> resourceClasses
    ) {
        return new ServiceDescriptor(
                externalKey,
                configKey,
                enabled,
                includeInStatus,
                storageKey,
                storageMode,
                storageFlushIntervalMs,
                xmlNamespace,
                defaultProtocol,
                Set.copyOf(supportedProtocols),
                Set.copyOf(targetPrefixes),
                Set.copyOf(credentialScopes),
                Set.copyOf(cborSdkServiceIds),
                Set.copyOf(resourceClasses)
        );
    }

    private static String storageMode(Optional<String> override, String globalMode) {
        return override.orElse(globalMode);
    }

    private static Set<ServiceProtocol> protocols(ServiceProtocol... protocols) {
        EnumSet<ServiceProtocol> values = EnumSet.noneOf(ServiceProtocol.class);
        values.addAll(Arrays.asList(protocols));
        return Set.copyOf(values);
    }
}
