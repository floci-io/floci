package io.floci.aws.services.lambda;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.LambdaCodeFixtures;
import io.floci.aws.testing.PartitionCleanup;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;

import java.io.IOException;
import java.net.URI;

final class LambdaFunctionUrlFixtures {

    private LambdaFunctionUrlFixtures() {
    }

    static String createFunction(LambdaClient client, String name, String account, Region region,
                                 URI endpoint, PartitionCleanup cleanup) throws IOException {
        byte[] archive = LambdaCodeFixtures.nodeJsZip(
                "exports.handler = async () => ({statusCode: 200, body: 'ok'});");
        String role = AwsArnUtils.Arn.global(AwsRegions.partitionFor(region.id()),
                "iam", account, "role/url-list").toString();
        String arn = client.createFunction(request -> request.functionName(name)
                .runtime("nodejs22.x").handler("index.handler").role(role)
                .code(code -> code.zipFile(SdkBytes.fromByteArray(archive)))).functionArn();
        cleanup.register(() -> {
            try (LambdaClient owner = AwsTestClients.create(LambdaClient.builder(), account, region, endpoint)) {
                owner.deleteFunction(request -> request.functionName(name));
            }
        });
        client.publishVersion(request -> request.functionName(name));
        return arn;
    }
}
