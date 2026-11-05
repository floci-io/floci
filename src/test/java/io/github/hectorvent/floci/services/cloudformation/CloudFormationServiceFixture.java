package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnDynamicReferences;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnResourceDispatcher;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.ssm.SsmService;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;

import java.time.Clock;

import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A real {@link CloudFormationService} over in-memory storage, shared by the tests that need one. */
public final class CloudFormationServiceFixture {

    private static final String ACCOUNT = "000000000000";

    private CloudFormationServiceFixture() {
    }

    public static CloudFormationService create(CfnResourceDispatcher dispatcher) {
        return create(dispatcher, mock(S3Service.class), Clock.systemUTC(), new InMemoryStorageFactory(ACCOUNT));
    }

    public static CloudFormationService create(CfnResourceDispatcher dispatcher, S3Service s3Service, Clock clock,
            StorageFactory storageFactory) {
        ObjectMapper objectMapper = new ObjectMapper();
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT);
        return new CloudFormationService(dispatcher, s3Service, mock(SsmService.class),
                mock(GetStackOutputRoleAuthorizer.class), new CfnDynamicReferences(null, null, objectMapper),
                objectMapper, config, regionResolver, clock, storageFactory);
    }
}
