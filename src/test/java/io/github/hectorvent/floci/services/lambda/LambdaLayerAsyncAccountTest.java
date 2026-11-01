package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.lambda.model.LambdaLayerVersion;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class LambdaLayerAsyncAccountTest {
    private static final String DEFAULT_ACCOUNT = "000000000000";
    private static final String OWNER = "123456789012";

    @Test
    void backgroundLookupUsesTheStoredOwnerAndDoesNotSubstituteTheDefaultAccountsLayer() throws Exception {
        AccountAwareStorageBackend<LambdaLayerVersion> storage = AccountAwareStorageBackend.inMemory(DEFAULT_ACCOUNT);
        LambdaLayerService service = service(storage);
        LambdaLayerVersion defaultLayer = layer("arn:aws:lambda:eu-west-1:" + DEFAULT_ACCOUNT + ":layer:shared:1");
        LambdaLayerVersion ownerLayer = layer("arn:aws:lambda:eu-west-1:" + OWNER + ":layer:shared:1");
        storage.putForAccount(DEFAULT_ACCOUNT, "layer::eu-west-1::shared::1", defaultLayer);
        storage.putForAccount(OWNER, "layer::eu-west-1::shared::1", ownerLayer);

        assertNull(CompletableFuture.supplyAsync(
                () -> service.resolveLayerByArn(ownerLayer.getLayerVersionArn())).get(5, TimeUnit.SECONDS));
        assertSame(ownerLayer, CompletableFuture.supplyAsync(() -> service.resolveLayerByArnForAccount(
                ownerLayer.getLayerVersionArn(), OWNER, "eu-west-1")).get(5, TimeUnit.SECONDS));
        assertSame(defaultLayer, service.resolveLayerByArn(defaultLayer.getLayerVersionArn()));
        assertNull(service.resolveLayerByArnForAccount(defaultLayer.getLayerVersionArn(), OWNER, "eu-west-1"));
        assertNull(service.resolveLayerByArnForAccount(ownerLayer.getLayerVersionArn(), "222222222222", "eu-west-1"));
    }

    @Test
    void backgroundLookupUsesTheFunctionsPartitionAndChecksTheStoredArn() {
        AccountAwareStorageBackend<LambdaLayerVersion> storage = AccountAwareStorageBackend.inMemory(DEFAULT_ACCOUNT);
        LambdaLayerService service = service(storage);
        LambdaLayerVersion ownerLayer = layer("arn:aws-cn:lambda:cn-north-1:" + OWNER + ":layer:shared:1");
        storage.putForAccount(OWNER, "layer::cn-north-1::shared::1", ownerLayer);
        assertSame(ownerLayer, service.resolveLayerByArnForAccount(ownerLayer.getLayerVersionArn(), OWNER, "cn-north-1"));
        assertNull(service.resolveLayerByArnForAccount(ownerLayer.getLayerVersionArn(), OWNER, "eu-west-1"));
        assertNull(service.resolveLayerByArnForAccount(
                "arn:aws:lambda:cn-north-1:" + OWNER + ":layer:shared:1", OWNER, "cn-north-1"));
        storage.putForAccount(OWNER, "layer::cn-north-1::shared::1",
                layer("arn:aws-cn:lambda:cn-north-1:222222222222:layer:shared:1"));
        assertNull(service.resolveLayerByArnForAccount(ownerLayer.getLayerVersionArn(), OWNER, "cn-north-1"));
    }

    @Test
    void backgroundLookupInUnpublishedRegionUsesDeploymentPartition() {
        String region = "xx-nowhere-9";
        AccountAwareStorageBackend<LambdaLayerVersion> storage = AccountAwareStorageBackend.inMemory(DEFAULT_ACCOUNT);
        LambdaLayerService service = new LambdaLayerService(new LambdaLayerStore(storage), null, null,
                new RegionResolver("cn-north-1", DEFAULT_ACCOUNT), null);
        LambdaLayerVersion ownerLayer = layer("arn:aws-cn:lambda:" + region + ":" + OWNER + ":layer:shared:1");
        storage.putForAccount(OWNER, "layer::" + region + "::shared::1", ownerLayer);

        assertSame(ownerLayer, service.resolveLayerByArnForAccount(ownerLayer.getLayerVersionArn(), OWNER, region));
        assertNull(service.resolveLayerByArnForAccount(
                "arn:aws:lambda:" + region + ":" + OWNER + ":layer:shared:1", OWNER, region));
    }

    @Test
    void absentLayerOrInvalidOwnerNeverFallsBackToAmbientStorage() {
        AccountAwareStorageBackend<LambdaLayerVersion> storage = AccountAwareStorageBackend.inMemory(DEFAULT_ACCOUNT);
        LambdaLayerService service = service(storage);
        String arn = "arn:aws:lambda:eu-west-1:" + OWNER + ":layer:shared:1";
        assertNull(service.resolveLayerByArnForAccount(arn, OWNER, "eu-west-1"));
        assertNull(service.resolveLayerByArnForAccount(arn, null, "eu-west-1"));
        assertNull(service.resolveLayerByArnForAccount(arn, "", "eu-west-1"));
        assertNull(service.resolveLayerByArnForAccount(arn, OWNER, ""));
    }

    private static LambdaLayerService service(AccountAwareStorageBackend<LambdaLayerVersion> storage) {
        return new LambdaLayerService(new LambdaLayerStore(storage), null, null,
                new RegionResolver("us-east-1", DEFAULT_ACCOUNT), null);
    }

    private static LambdaLayerVersion layer(String arn) {
        LambdaLayerVersion layer = new LambdaLayerVersion();
        layer.setLayerName("shared");
        layer.setVersion(1);
        layer.setLayerVersionArn(arn);
        return layer;
    }
}
