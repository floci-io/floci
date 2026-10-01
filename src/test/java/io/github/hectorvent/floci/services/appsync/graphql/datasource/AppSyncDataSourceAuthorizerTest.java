package io.github.hectorvent.floci.services.appsync.graphql.datasource;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.appsync.model.DataSource;
import io.github.hectorvent.floci.services.appsync.model.DataSourceType;
import io.github.hectorvent.floci.services.iam.AssumeRolePolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AppSyncDataSourceAuthorizerTest {

    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/appsync/Resolver";
    private static final String DATA_SOURCE_ARN =
            "arn:aws:appsync:us-east-1:000000000000:apis/example/datasources/Products";
    private static final String TRUST = """
            {"Statement":{"Effect":"Allow","Action":"sts:AssumeRole",
              "Principal":{"Service":"appsync.amazonaws.com"}}}
            """;

    private final IamService iam = mock(IamService.class);
    private final RegionResolver regionResolver = mock(RegionResolver.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final IamPolicyEvaluator policyEvaluator = new IamPolicyEvaluator(mapper);
    private final EmulatorConfig config = mock(EmulatorConfig.class);
    private final EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
    private final EmulatorConfig.IamServiceConfig iamConfig = mock(EmulatorConfig.IamServiceConfig.class);
    private final AppSyncDataSourceAuthorizer authorizer = new AppSyncDataSourceAuthorizer(
            iam, new AssumeRolePolicyEvaluator(mapper, policyEvaluator), policyEvaluator,
            regionResolver, mapper, config);

    @BeforeEach
    void enableIamEnforcement() {
        when(config.services()).thenReturn(services);
        when(services.iam()).thenReturn(iamConfig);
        when(iamConfig.enforcementEnabled()).thenReturn(true);
    }

    private record DynamoDbRequest(String operation, String index) {}

    private DataSource dataSource(DataSourceType type) {
        DataSource source = new DataSource();
        source.setName("Products");
        source.setType(type);
        source.setDataSourceArn(DATA_SOURCE_ARN);
        source.setServiceRoleArn(ROLE_ARN);
        source.setDynamodbConfig(Map.of("tableName", "Products"));
        source.setLambdaConfig(Map.of("lambdaFunctionArn",
                "arn:aws:lambda:us-east-1:000000000000:function:products"));
        source.setRelationalDatabaseConfig(Map.of("rdsHttpEndpointConfig", Map.of(
                "dbClusterIdentifier", "products", "awsSecretStoreArn",
                "arn:aws:secretsmanager:us-east-1:000000000000:secret:products")));
        return source;
    }

    private void role(String trust, String policy) {
        IamRole role = new IamRole("id", "Resolver", "/appsync/", ROLE_ARN, trust);
        when(iam.findRole("000000000000", "Resolver")).thenReturn(Optional.of(role));
        when(iam.resolvePrincipalContext(ROLE_ARN))
                .thenReturn(CallerContext.of(List.of(policy)));
    }

    private static String allow(String action, String resource) {
        return """
                {"Statement":{"Effect":"Allow","Action":"%s","Resource":"%s"}}
                """.formatted(action, resource);
    }

    private static AwsException denied(AppSyncDataSourceAuthorizer authorizer, DataSource source,
                                       Object request) {
        AwsException error = assertThrows(AwsException.class,
                () -> authorizer.authorize(source, request, "us-east-1"));
        assertEquals("AccessDeniedException", error.getErrorCode());
        return error;
    }

    @Test
    void noneNeedsNoRoleOrIamCall() {
        DataSource source = dataSource(DataSourceType.NONE);
        source.setServiceRoleArn(null);
        authorizer.authorize(source, Map.of(), "us-east-1");
        verifyNoInteractions(iam);
    }

    @Test
    void disabledEnforcementPreservesUnmanagedDataSources() {
        when(iamConfig.enforcementEnabled()).thenReturn(false);
        for (DataSourceType type : List.of(DataSourceType.AMAZON_DYNAMODB,
                DataSourceType.AWS_LAMBDA, DataSourceType.RELATIONAL_DATABASE)) {
            DataSource source = dataSource(type);
            source.setServiceRoleArn(null);
            authorizer.authorize(source, Map.of(), "us-east-1");
        }
        verifyNoInteractions(iam);
    }

    @Test
    void missingRoleAndCrossAccountRoleDenyBeforeDispatch() {
        DataSource source = dataSource(DataSourceType.AMAZON_DYNAMODB);
        source.setServiceRoleArn(null);
        denied(authorizer, source, Map.of("operation", "GetItem"));
        source.setServiceRoleArn("arn:aws:iam::111111111111:role/Resolver");
        denied(authorizer, source, Map.of("operation", "GetItem"));
        verifyNoInteractions(iam);
    }

    @Test
    void untrustedRoleDenies() {
        DataSource source = dataSource(DataSourceType.AWS_LAMBDA);
        role("""
                {"Statement":{"Effect":"Allow","Action":"sts:AssumeRole",
                  "Principal":{"Service":"lambda.amazonaws.com"}}}
                """, allow("lambda:InvokeFunction", "*"));
        denied(authorizer, source, Map.of("operation", "Invoke"));
    }

    @Test
    void unknownRoleRemainsPermissiveWhenEnforcementIsEnabled() {
        DataSource source = dataSource(DataSourceType.AWS_LAMBDA);
        when(iam.findRole("000000000000", "Resolver")).thenReturn(Optional.empty());

        authorizer.authorize(source, Map.of("operation", "Invoke"), "us-east-1");

        verify(iam).findRole("000000000000", "Resolver");
        verify(iam, never()).resolvePrincipalContext(ROLE_ARN);
    }

    @Test
    void partialWildcardServicePrincipalCannotTrustAppSync() {
        DataSource source = dataSource(DataSourceType.AWS_LAMBDA);
        role("""
                {"Statement":{"Effect":"Allow","Action":"sts:AssumeRole",
                  "Principal":{"Service":"appsync.*"}}}
                """, allow("lambda:InvokeFunction", "*"));
        denied(authorizer, source, Map.of("operation", "Invoke"));
    }

    @Test
    void sourceConditionsRestrictWhichApiCanUseTheRole() {
        DataSource source = dataSource(DataSourceType.AWS_LAMBDA);
        String policy = allow("lambda:InvokeFunction", "*");
        role("""
                {"Statement":{"Effect":"Allow","Action":"sts:AssumeRole",
                  "Principal":{"Service":"appsync.amazonaws.com"},
                  "Condition":{"StringEquals":{"aws:SourceAccount":"000000000000"},
                               "ArnEquals":{"aws:SourceArn":"arn:aws:appsync:us-east-1:000000000000:apis/example"}}}}
                """, policy);
        authorizer.authorize(source, Map.of("operation", "Invoke"), "us-east-1");
        source.setDataSourceArn(
                "arn:aws:appsync:us-east-1:000000000000:apis/another/datasources/Products");
        denied(authorizer, source, Map.of("operation", "Invoke"));
    }

    @Test
    void unsupportedTrustConditionFailsClosed() {
        DataSource source = dataSource(DataSourceType.AWS_LAMBDA);
        role("""
                {"Statement":{"Effect":"Allow","Action":"sts:AssumeRole",
                  "Principal":{"Service":"appsync.amazonaws.com"},
                  "Condition":{"StringEquals":{"aws:PrincipalTag/team":"admin"}}}}
                """, allow("lambda:InvokeFunction", "*"));
        denied(authorizer, source, Map.of("operation", "Invoke"));
    }

    @Test
    void sourceArnConditionsAreCaseSensitive() {
        DataSource source = dataSource(DataSourceType.AWS_LAMBDA);
        role("""
                {"Statement":{"Effect":"Allow","Action":"sts:AssumeRole",
                  "Principal":{"Service":"appsync.amazonaws.com"},
                  "Condition":{"ArnLike":{"aws:SourceArn":"arn:aws:appsync:us-east-1:000000000000:apis/EXAMPLE"}}}}
                """, allow("lambda:InvokeFunction", "*"));
        denied(authorizer, source, Map.of("operation", "Invoke"));
    }

    @Test
    void missingTrustEffectDoesNotGrantAccess() {
        DataSource source = dataSource(DataSourceType.AWS_LAMBDA);
        role("""
                {"Statement":{"Action":"sts:AssumeRole",
                  "Principal":{"Service":"appsync.amazonaws.com"}}}
                """, allow("lambda:InvokeFunction", "*"));
        denied(authorizer, source, Map.of("operation", "Invoke"));
    }

    @Test
    void requestedRegionConditionUsesInvocationRegion() {
        DataSource source = dataSource(DataSourceType.AWS_LAMBDA);
        role(TRUST, """
                {"Statement":{"Effect":"Allow","Action":"lambda:InvokeFunction",
                  "Resource":"*","Condition":{"StringEquals":{"aws:RequestedRegion":"us-east-1"}}}}
                """);
        authorizer.authorize(source, Map.of("operation", "Invoke"), "us-east-1");
        assertThrows(AwsException.class,
                () -> authorizer.authorize(source, Map.of("operation", "Invoke"), "eu-west-1"));
    }

    @Test
    void unsupportedOperationCannotSkipPolicyEvaluation() {
        DataSource source = dataSource(DataSourceType.AMAZON_DYNAMODB);
        role(TRUST, allow("dynamodb:GetItem", "*"));
        denied(authorizer, source, Map.of("operation", "BatchGetItem"));
    }

    @Test
    void conditionalDenyOnlyAppliesToItsSourceAccount() {
        DataSource source = dataSource(DataSourceType.AWS_LAMBDA);
        role("""
                {"Statement":[
                  {"Effect":"Allow","Action":"sts:AssumeRole",
                   "Principal":{"Service":"appsync.amazonaws.com"}},
                  {"Effect":"Deny","Action":"sts:AssumeRole",
                   "Principal":{"Service":"appsync.amazonaws.com"},
                   "Condition":{"StringEquals":{"aws:SourceAccount":"111111111111"}}}
                ]}
                """, allow("lambda:InvokeFunction", "*"));
        authorizer.authorize(source, Map.of("operation", "Invoke"), "us-east-1");
    }

    @Test
    void missingDataSourceArnCannotBypassSourceConditions() {
        DataSource source = dataSource(DataSourceType.AWS_LAMBDA);
        source.setDataSourceArn(null);
        role(TRUST, allow("lambda:InvokeFunction", "*"));
        denied(authorizer, source, Map.of("operation", "Invoke"));
    }

    @Test
    void lambdaNeedsInvokeOnTheConfiguredFunction() {
        DataSource source = dataSource(DataSourceType.AWS_LAMBDA);
        role(TRUST, allow("lambda:InvokeFunction",
                "arn:aws:lambda:us-east-1:000000000000:function:products"));
        authorizer.authorize(source, Map.of("operation", "Invoke"), "us-east-1");
        role(TRUST, allow("lambda:InvokeFunction",
                "arn:aws:lambda:us-east-1:000000000000:function:other"));
        denied(authorizer, source, Map.of("operation", "Invoke"));
    }

    @Test
    void dynamoDbIndexQueryUsesIndexArnAndItsOwnAction() {
        DataSource source = dataSource(DataSourceType.AMAZON_DYNAMODB);
        role(TRUST, allow("dynamodb:Query",
                "arn:aws:dynamodb:us-east-1:000000000000:table/Products/index/byCategory"));
        authorizer.authorize(source, Map.of("operation", "Query", "index", "byCategory"), "us-east-1");
        denied(authorizer, source, Map.of("operation", "Scan", "index", "byCategory"));
        denied(authorizer, source, Map.of("operation", "Query", "index", "other"));
    }

    @Test
    void jsonNodeDynamoDbRequestsCannotBypassOperationAndIndexPermissions() throws Exception {
        DataSource source = dataSource(DataSourceType.AMAZON_DYNAMODB);
        role(TRUST, allow("dynamodb:GetItem", "*"));
        denied(authorizer, source, mapper.readTree("""
                {"operation":"PutItem","key":{"id":{"S":"7"}}}
                """));

        role(TRUST, allow("dynamodb:Query",
                "arn:aws:dynamodb:us-east-1:000000000000:table/Products"));
        denied(authorizer, source, mapper.readTree("""
                {"operation":"Query","index":"byCategory"}
                """));
        role(TRUST, allow("dynamodb:Query",
                "arn:aws:dynamodb:us-east-1:000000000000:table/Products/index/byCategory"));
        authorizer.authorize(source, mapper.readTree("""
                {"operation":"Query","index":"byCategory"}
                """), "us-east-1");
    }

    @Test
    void serializableDynamoDbRequestsUseTheSameAuthorizationShapeAsTheInvoker() {
        DataSource source = dataSource(DataSourceType.AMAZON_DYNAMODB);
        role(TRUST, allow("dynamodb:GetItem", "*"));
        denied(authorizer, source, new DynamoDbRequest("PutItem", null));
        role(TRUST, allow("dynamodb:Query",
                "arn:aws:dynamodb:us-east-1:000000000000:table/Products"));
        denied(authorizer, source, new DynamoDbRequest("Query", "byCategory"));
    }

    @Test
    void rdsRequiresBothClusterAndSecretPermissions() {
        DataSource source = dataSource(DataSourceType.RELATIONAL_DATABASE);
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        String cluster = "arn:aws:rds:us-east-1:000000000000:cluster:products";
        String secret = "arn:aws:secretsmanager:us-east-1:000000000000:secret:products";
        role(TRUST, allow("rds-data:ExecuteStatement", cluster));
        denied(authorizer, source, "select 1");
        role(TRUST, """
                {"Statement":[
                  {"Effect":"Allow","Action":"rds-data:ExecuteStatement","Resource":"%s"},
                  {"Effect":"Allow","Action":"secretsmanager:GetSecretValue","Resource":"%s"}
                ]}
                """.formatted(cluster, secret));
        authorizer.authorize(source, "select 1", "us-east-1");
    }

    @Test
    void rdsAuthorizationChecksTheActualTargetAccount() {
        DataSource source = dataSource(DataSourceType.RELATIONAL_DATABASE);
        when(regionResolver.getAccountId()).thenReturn("111111111111");
        role(TRUST, """
                {"Statement":[
                  {"Effect":"Allow","Action":"rds-data:ExecuteStatement",
                   "Resource":"arn:aws:rds:us-east-1:000000000000:cluster:products"},
                  {"Effect":"Allow","Action":"secretsmanager:GetSecretValue","Resource":"*"}
                ]}
                """);
        denied(authorizer, source, "select 1");
    }
}
