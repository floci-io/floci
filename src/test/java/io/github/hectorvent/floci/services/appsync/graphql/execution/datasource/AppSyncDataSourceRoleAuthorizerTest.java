package io.github.hectorvent.floci.services.appsync.graphql.execution.datasource;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.appsync.graphql.execution.FieldError;
import io.github.hectorvent.floci.services.appsync.graphql.execution.GraphQlRequestContext;
import io.github.hectorvent.floci.services.appsync.model.DataSource;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppSyncDataSourceRoleAuthorizerTest {

    private static final String ROLE_ARN = "arn:aws:iam::111111111111:role/AppSyncDataSource";
    private static final String TRUST = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
             "Principal":{"Service":"appsync.amazonaws.com"},"Action":"sts:AssumeRole"}]}
            """;
    private static final String GET_ONLY = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
             "Action":"dynamodb:GetItem",
             "Resource":"arn:aws:dynamodb:us-east-1:111111111111:table/Todos"}]}
            """;

    private final IamService iamService = mock(IamService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AppSyncDataSourceRoleAuthorizer authorizer = new AppSyncDataSourceRoleAuthorizer(
            iamService, new IamPolicyEvaluator(objectMapper), new AssumeRolePolicyEvaluator(objectMapper));
    private final GraphQlRequestContext context = new GraphQlRequestContext(
            "api", "111111111111", "us-east-1", "API Key Authorization", null, Map.of(), Map.of());
    private final DataSource dataSource = new DataSource();

    @BeforeEach
    void setUp() {
        dataSource.setName("todos");
        dataSource.setServiceRoleArn(ROLE_ARN);
        IamRole role = new IamRole("AROA1", "AppSyncDataSource", "/", ROLE_ARN, TRUST);
        when(iamService.findRole("111111111111", "AppSyncDataSource")).thenReturn(Optional.of(role));
        when(iamService.resolvePrincipalContext(ROLE_ARN)).thenReturn(CallerContext.of(List.of(GET_ONLY)));
    }

    @Test
    void trustedRoleWithActionPermissionIsAllowed() {
        assertTrue(authorizer.authorizeDynamoDb(
                dataSource, "dynamodb:GetItem", "Todos", "us-east-1", context).isEmpty());
    }

    @Test
    void roleWithoutActionPermissionIsDenied() {
        FieldError error = authorizer.authorizeDynamoDb(
                dataSource, "dynamodb:PutItem", "Todos", "us-east-1", context).orElseThrow();

        assertEquals("DynamoDB:AccessDeniedException", error.errorType());
        assertTrue(error.message().contains("dynamodb:PutItem"));
        assertTrue(error.message().contains("table/Todos"));
    }

    @Test
    void roleWithoutAppSyncTrustIsDenied() {
        IamRole role = new IamRole("AROA1", "AppSyncDataSource", "/", ROLE_ARN,
                TRUST.replace("appsync.amazonaws.com", "lambda.amazonaws.com"));
        when(iamService.findRole("111111111111", "AppSyncDataSource")).thenReturn(Optional.of(role));

        FieldError error = authorizer.authorizeDynamoDb(
                dataSource, "dynamodb:GetItem", "Todos", "us-east-1", context).orElseThrow();

        assertEquals("DynamoDB:AccessDeniedException", error.errorType());
        assertEquals("Unable to assume role " + ROLE_ARN, error.message());
    }

    @Test
    void missingServiceRoleIsDenied() {
        dataSource.setServiceRoleArn(null);

        FieldError error = authorizer.authorizeDynamoDb(
                dataSource, "dynamodb:GetItem", "Todos", "us-east-1", context).orElseThrow();

        assertEquals("DynamoDB:AccessDeniedException", error.errorType());
    }
}
