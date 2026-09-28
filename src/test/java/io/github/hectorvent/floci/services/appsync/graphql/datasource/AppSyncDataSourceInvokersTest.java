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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AppSyncDataSourceInvokersTest {

    @Test
    void deniedRoleCannotReachTheVtlBackingInvoker() {
        AppSyncDataSourceAuthorizer authorizer = mock(AppSyncDataSourceAuthorizer.class);
        AppSyncDataSourceInvoker invoker = mock(AppSyncDataSourceInvoker.class);
        when(invoker.type()).thenReturn(DataSourceType.AWS_LAMBDA);
        DataSource source = new DataSource();
        source.setName("Products");
        source.setType(DataSourceType.AWS_LAMBDA);
        Map<String, Object> request = Map.of("version", "2018-05-29", "operation", "Invoke");
        doThrow(new AwsException("AccessDeniedException", "Denied", 403))
                .when(authorizer).authorize(source, request, "us-east-1");

        AppSyncDataSourceInvokers dispatch = new AppSyncDataSourceInvokers(List.of(invoker), authorizer);
        AwsException error = assertThrows(AwsException.class,
                () -> dispatch.invokeVtl(source, request, "us-east-1"));

        assertEquals("AccessDeniedException", error.getErrorCode());
        verify(authorizer).authorize(source, request, "us-east-1");
        verify(invoker, never()).invokeVtl(any(), any(), any());
    }

    @Test
    void deniedRoleCannotReachTheBackingInvoker() {
        AppSyncDataSourceAuthorizer authorizer = mock(AppSyncDataSourceAuthorizer.class);
        DataSource source = new DataSource();
        source.setName("Products");
        source.setType(DataSourceType.AWS_LAMBDA);
        Map<String, Object> request = Map.of("operation", "Invoke");
        doThrow(new AwsException("AccessDeniedException", "Denied", 403))
                .when(authorizer).authorize(source, request, "us-east-1");

        AppSyncDataSourceInvoker invoker = new AppSyncDataSourceInvoker() {
            @Override
            public DataSourceType type() {
                return DataSourceType.AWS_LAMBDA;
            }

            @Override
            public Object invoke(DataSource dataSource, Object input, String region) {
                throw new AssertionError("A denied role must not invoke the Lambda function");
            }
        };
        AppSyncDataSourceInvokers dispatch = new AppSyncDataSourceInvokers(List.of(invoker), authorizer);
        AwsException error = assertThrows(AwsException.class,
                () -> dispatch.invoke(source, request, "us-east-1"));
        assertEquals("AccessDeniedException", error.getErrorCode());
        verify(authorizer).authorize(source, request, "us-east-1");
    }

    @Test
    void realRolePolicyIsEnforcedBeforeLambdaDispatch() {
        String roleArn = "arn:aws:iam::000000000000:role/appsync/Resolver";
        String functionArn = "arn:aws:lambda:us-east-1:000000000000:function:products";
        IamService iam = mock(IamService.class);
        IamRole role = new IamRole("id", "Resolver", "/appsync/", roleArn, """
                {"Statement":{"Effect":"Allow","Action":"sts:AssumeRole",
                  "Principal":{"Service":"appsync.amazonaws.com"}}}
                """);
        when(iam.findRole("000000000000", "Resolver")).thenReturn(Optional.of(role));
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.IamServiceConfig iamConfig = mock(EmulatorConfig.IamServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.iam()).thenReturn(iamConfig);
        when(iamConfig.enforcementEnabled()).thenReturn(true);
        ObjectMapper mapper = new ObjectMapper();
        AppSyncDataSourceAuthorizer authorizer = new AppSyncDataSourceAuthorizer(iam,
                new AssumeRolePolicyEvaluator(mapper), new IamPolicyEvaluator(mapper),
                mock(RegionResolver.class), mapper, config);
        AppSyncDataSourceInvoker invoker = mock(AppSyncDataSourceInvoker.class);
        when(invoker.type()).thenReturn(DataSourceType.AWS_LAMBDA);
        AppSyncDataSourceInvokers dispatch = new AppSyncDataSourceInvokers(List.of(invoker), authorizer);
        DataSource source = new DataSource();
        source.setName("Products");
        source.setType(DataSourceType.AWS_LAMBDA);
        source.setDataSourceArn(
                "arn:aws:appsync:us-east-1:000000000000:apis/example/datasources/Products");
        source.setServiceRoleArn(roleArn);
        source.setLambdaConfig(Map.of("lambdaFunctionArn", functionArn));
        Map<String, Object> request = Map.of("operation", "Invoke", "payload", Map.of());

        when(iam.resolvePrincipalContext(roleArn)).thenReturn(CallerContext.of(List.of("""
                {"Statement":{"Effect":"Allow","Action":"lambda:InvokeFunction",
                  "Resource":"arn:aws:lambda:us-east-1:000000000000:function:other"}}
                """)));
        AwsException error = assertThrows(AwsException.class,
                () -> dispatch.invoke(source, request, "us-east-1"));
        assertEquals("AccessDeniedException", error.getErrorCode());
        verify(invoker, never()).invoke(any(), any(), any());

        when(iam.resolvePrincipalContext(roleArn)).thenReturn(CallerContext.of(List.of("""
                {"Statement":{"Effect":"Allow","Action":"lambda:InvokeFunction",
                  "Resource":"arn:aws:lambda:us-east-1:000000000000:function:products"}}
                """)));
        when(invoker.invoke(source, request, "us-east-1")).thenReturn(Map.of("accepted", true));
        assertEquals(Map.of("accepted", true), dispatch.invoke(source, request, "us-east-1"));
        verify(invoker).invoke(source, request, "us-east-1");
    }

    @Test
    void disabledEnforcementAllowsJsAndVtlDispatchWithoutLocalRole() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.IamServiceConfig iamConfig = mock(EmulatorConfig.IamServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.iam()).thenReturn(iamConfig);
        when(iamConfig.enforcementEnabled()).thenReturn(false);
        IamService iam = mock(IamService.class);
        ObjectMapper mapper = new ObjectMapper();
        AppSyncDataSourceAuthorizer authorizer = new AppSyncDataSourceAuthorizer(iam,
                new AssumeRolePolicyEvaluator(mapper), new IamPolicyEvaluator(mapper),
                mock(RegionResolver.class), mapper, config);
        AppSyncDataSourceInvoker invoker = mock(AppSyncDataSourceInvoker.class);
        when(invoker.type()).thenReturn(DataSourceType.AWS_LAMBDA);
        AppSyncDataSourceInvokers dispatch = new AppSyncDataSourceInvokers(List.of(invoker), authorizer);
        DataSource source = new DataSource();
        source.setName("Products");
        source.setType(DataSourceType.AWS_LAMBDA);
        Map<String, Object> request = Map.of("version", "2018-05-29", "operation", "Invoke");

        dispatch.invoke(source, request, "us-east-1");
        dispatch.invokeVtl(source, request, "us-east-1");

        verify(invoker).invoke(source, request, "us-east-1");
        verify(invoker).invokeVtl(source, request, "us-east-1");
        verifyNoInteractions(iam);
    }
}
