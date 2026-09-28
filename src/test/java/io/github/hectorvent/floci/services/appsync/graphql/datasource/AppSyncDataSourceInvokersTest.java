package io.github.hectorvent.floci.services.appsync.graphql.datasource;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.appsync.model.DataSource;
import io.github.hectorvent.floci.services.appsync.model.DataSourceType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AppSyncDataSourceInvokersTest {

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
}
