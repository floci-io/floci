package io.floci.aws.services.lambda;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.lambda.model.LambdaLayerVersion;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.PartitionCleanup;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.PublishLayerVersionResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class LambdaLayerFixtures {
    private LambdaLayerFixtures() {}

    public static byte[] layerArchive() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("python/shared.py"));
            zip.write("VALUE = 1".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    public static PublishLayerVersionResponse publishLayerVersion(
            LambdaClient client, String name, String account, Region region, URI endpoint, PartitionCleanup cleanup)
            throws IOException {
        byte[] archive = layerArchive();
        PublishLayerVersionResponse published = client.publishLayerVersion(request -> request.layerName(name)
                .content(content -> content.zipFile(SdkBytes.fromByteArray(archive))));
        cleanup.register(() -> {
            try (LambdaClient owner = AwsTestClients.create(LambdaClient.builder(), account, region, endpoint)) {
                owner.deleteLayerVersion(request -> request.layerName(name).versionNumber(published.version()));
            }
        });
        return published;
    }

    public static LambdaLayerService accountScopedService(
            String defaultAccount, String region, Map<String, LambdaLayerVersion> layersByAccount) {
        AccountAwareStorageBackend<LambdaLayerVersion> storage = AccountAwareStorageBackend.inMemory(defaultAccount);
        layersByAccount.forEach((account, layer) -> storage.putForAccount(account,
                "layer::" + region + "::" + layer.getLayerName() + "::" + layer.getVersion(), layer));
        return new LambdaLayerService(new LambdaLayerStore(storage), null, null,
                new RegionResolver("us-east-1", defaultAccount), null);
    }

    public static LambdaLayerVersion layer(String account, String region, String name, long version) {
        LambdaLayerVersion layer = new LambdaLayerVersion();
        layer.setLayerName(name);
        layer.setVersion(version);
        layer.setLayerVersionArn("arn:aws:lambda:" + region + ":" + account + ":layer:" + name + ":" + version);
        return layer;
    }
}
