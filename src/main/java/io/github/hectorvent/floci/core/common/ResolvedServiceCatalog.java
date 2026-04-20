package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.appconfig.AppConfigController;
import io.github.hectorvent.floci.services.appconfig.AppConfigDataController;
import io.github.hectorvent.floci.services.bedrockruntime.BedrockRuntimeController;
import io.github.hectorvent.floci.services.cognito.CognitoOAuthController;
import io.github.hectorvent.floci.services.cognito.CognitoWellKnownController;
import io.github.hectorvent.floci.services.eks.EksController;
import io.github.hectorvent.floci.services.pipes.PipesController;
import io.github.hectorvent.floci.services.lambda.LambdaController;
import io.github.hectorvent.floci.services.opensearch.OpenSearchController;
import io.github.hectorvent.floci.services.ses.SesController;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.EnumSet;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class ResolvedServiceCatalog {

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
                        Set.of(), Set.of("s3"), Set.of(), Set.of()),
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
                        Set.of(), Set.of("lambda"), Set.of(), Set.of(LambdaController.class)),
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
                descriptor("sts", "iam", config.services().iam().enabled(), false,
                        null, null, 5000L, AwsNamespaces.STS, ServiceProtocol.QUERY,
                        protocols(ServiceProtocol.QUERY),
                        Set.of(), Set.of("sts"), Set.of(), Set.of()),
                descriptor("elasticache", "elasticache", config.services().elasticache().enabled(), true,
                        "elasticache", storageMode(config.storage().services().elasticache().mode(), config.storage().mode()),
                        config.storage().services().elasticache().flushIntervalMs(), AwsNamespaces.EC, ServiceProtocol.QUERY,
                        protocols(ServiceProtocol.QUERY),
                        Set.of(), Set.of("elasticache"), Set.of(), Set.of()),
                descriptor("rds", "rds", config.services().rds().enabled(), true,
                        null, null, 5000L, AwsNamespaces.RDS, ServiceProtocol.QUERY,
                        protocols(ServiceProtocol.QUERY),
                        Set.of(), Set.of("rds"), Set.of(), Set.of()),
                descriptor("events", "eventbridge", config.services().eventbridge().enabled(), true,
                        "eventbridge", config.storage().mode(), 5000L, null, ServiceProtocol.JSON,
                        protocols(ServiceProtocol.JSON),
                        Set.of("AWSEvents."), Set.of("events"), Set.of(), Set.of()),
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
                        null, null, 5000L, AwsNamespaces.EC2, ServiceProtocol.QUERY,
                        protocols(ServiceProtocol.QUERY),
                        Set.of(), Set.of("ec2"), Set.of(), Set.of()),
                descriptor("ecs", "ecs", config.services().ecs().enabled(), true,
                        null, null, 5000L, null, ServiceProtocol.JSON,
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
                        null, null, 5000L, null, ServiceProtocol.JSON,
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
                descriptor("pipes", "pipes", config.services().pipes().enabled(), true,
                        "pipes", config.storage().mode(), 5000L, null, ServiceProtocol.REST_JSON,
                        protocols(ServiceProtocol.REST_JSON),
                        Set.of(), Set.of("pipes"), Set.of(), Set.of(PipesController.class))
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
