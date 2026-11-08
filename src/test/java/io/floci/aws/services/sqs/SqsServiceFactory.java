package io.floci.aws.services.sqs;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.sns.SnsService;

/**
 * Test helper to create SqsService instances
 */
public class SqsServiceFactory {

    public static SqsService createInMemory(String baseUrl, RegionResolver regionResolver) {
        return new SqsService(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                30, 1048576, baseUrl, regionResolver);
    }

    public static SqsService createInMemoryWithFifoDedupPurgeAndSns(String baseUrl, RegionResolver regionResolver,
                                                                    SnsService snsService) {
        return new SqsService(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                30, 1048576, baseUrl, regionResolver, true, snsService);
    }
}
