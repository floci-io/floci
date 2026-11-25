package io.floci.aws.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ec2.model.VpcEndpoint;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testing.SignedIamUsers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.VpcEndpointType;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class Ec2VpcEndpointTagTargetIamIntegrationTest {

    private static final String ADMIN = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"*","Resource":"*"}]}""";
    private static final String TAG_ACTIONS = """
            ["ec2:CreateTags","ec2:DeleteTags"]""";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Inject
    StorageFactory factory;

    @TestHTTPResource
    URI endpoint;

    @Test
    void aResourceGrantTagsOnlyItsEndpointAndRefusesMixedBatchesBeforeMutation() throws Exception {
        Ec2Client admin = client(SignedIamUsers.createUser(endpoint, "vpce-target-admin-", "admin", ADMIN));
        String vpc = vpc(admin, "10.61.0.0/16");
        String allowed = gatewayEndpoint(admin, vpc);
        String other = gatewayEndpoint(admin, vpc);
        String arn = endpointArn(admin, allowed);
        String policy = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":%s,"Resource":"%s"}]}
                """.formatted(TAG_ACTIONS, arn);
        Ec2Client scoped = client(SignedIamUsers.createUser(endpoint, "vpce-target-scoped-", "tags", policy));
        Tag original = tag("Name", "original");
        admin.createTags(request -> request.resources(other).tags(original));

        scoped.createTags(request -> request.resources(allowed).tags(original));
        assertEquals(Map.of("Name", "original"), tags(admin, allowed));
        denied(() -> scoped.createTags(request -> request.resources(allowed, other).tags(tag("Name", "changed"))));
        assertEquals(Map.of("Name", "original"), tags(admin, allowed));
        assertEquals(Map.of("Name", "original"), tags(admin, other));
        denied(() -> scoped.deleteTags(request -> request.resources(allowed, other).tags(original)));
        assertEquals(Map.of("Name", "original"), tags(admin, allowed));
        assertEquals(Map.of("Name", "original"), tags(admin, other));
        denied(() -> scoped.createTags(request -> request.resources(other).tags(tag("Blocked", "yes"))));
        denied(() -> scoped.deleteTags(request -> request.resources(other).tags(original)));
        assertEquals(Map.of("Name", "original"), tags(admin, other));

        scoped.deleteTags(request -> request.resources(allowed).tags(original));
        assertEquals(Map.of(), tags(admin, allowed));
        assertEquals(Map.of("Name", "original"), tags(admin, other));
    }

    @Test
    void anEndpointDenyOverridesWildcardTagGrantsAndProtectsEveryBatchMember() throws Exception {
        Ec2Client admin = client(SignedIamUsers.createUser(endpoint, "vpce-target-admin-", "admin", ADMIN));
        String vpc = vpc(admin, "10.62.0.0/16");
        String protectedId = gatewayEndpoint(admin, vpc);
        String other = gatewayEndpoint(admin, vpc);
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":%s,"Resource":"*"},
                  {"Effect":"Deny","Action":%s,"Resource":"%s"}]}
                """.formatted(TAG_ACTIONS, TAG_ACTIONS, endpointArn(admin, protectedId));
        Ec2Client scoped = client(SignedIamUsers.createUser(endpoint, "vpce-target-denied-", "tags", policy));
        Tag original = tag("Name", "original");
        admin.createTags(request -> request.resources(protectedId, other).tags(original));

        denied(() -> scoped.createTags(request -> request.resources(protectedId).tags(tag("Blocked", "yes"))));
        denied(() -> scoped.deleteTags(request -> request.resources(protectedId).tags(original)));
        denied(() -> scoped.createTags(request -> request.resources(other, protectedId).tags(tag("Name", "changed"))));
        denied(() -> scoped.deleteTags(request -> request.resources(other, protectedId).tags(original)));
        assertEquals(Map.of("Name", "original"), tags(admin, protectedId));
        assertEquals(Map.of("Name", "original"), tags(admin, other));

        scoped.createTags(request -> request.resources(other).tags(tag("Allowed", "yes")));
        assertEquals(Map.of("Name", "original", "Allowed", "yes"), tags(admin, other));
        scoped.deleteTags(request -> request.resources(other).tags(original, tag("Allowed", "yes")));
        assertEquals(Map.of(), tags(admin, other));
        assertEquals(Map.of("Name", "original"), tags(admin, protectedId));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exactEndpointGrantFindsLegacyStorageBeforeTheHandlerMigratesIt(boolean delete) throws Exception {
        Ec2Client admin = client(SignedIamUsers.createUser(endpoint, "vpce-legacy-admin-", "admin", ADMIN));
        String id = gatewayEndpoint(admin, vpc(admin, "10.63.0.0/16"));
        String arn = endpointArn(admin, id);
        String policy = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":%s,"Resource":"%s"}]}
                """.formatted(TAG_ACTIONS, arn);
        Ec2Client scoped = client(SignedIamUsers.createUser(endpoint, "vpce-legacy-grant-", "tags", policy));
        admin.createTags(request -> request.resources(id).tags(tag("Name", "original")));
        makeLegacy(id, arn);
        if (delete) {
            scoped.deleteTags(request -> request.resources(id).tags(tag("Name", "original")));
            assertEquals(Map.of(), tags(admin, id));
        } else {
            scoped.createTags(request -> request.resources(id).tags(tag("Name", "allowed")));
            assertEquals(Map.of("Name", "allowed"), tags(admin, id));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void endpointDenyProtectsLegacyStorageWithoutMigratingOrChangingIt(boolean delete) throws Exception {
        Ec2Client admin = client(SignedIamUsers.createUser(endpoint, "vpce-legacy-admin-", "admin", ADMIN));
        String id = gatewayEndpoint(admin, vpc(admin, "10.64.0.0/16"));
        String arn = endpointArn(admin, id);
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":%s,"Resource":"*"},
                  {"Effect":"Deny","Action":%s,"Resource":"%s"}]}
                """.formatted(TAG_ACTIONS, TAG_ACTIONS, arn);
        Ec2Client scoped = client(SignedIamUsers.createUser(endpoint, "vpce-legacy-deny-", "tags", policy));
        admin.createTags(request -> request.resources(id).tags(tag("Name", "original")));
        StorageBackend<String, VpcEndpoint> raw = makeLegacy(id, arn);
        String key = Region.US_EAST_1.id() + "::" + id;
        if (delete) {
            denied(() -> scoped.deleteTags(request -> request.resources(id).tags(tag("Name", "original"))));
        } else {
            denied(() -> scoped.createTags(request -> request.resources(id).tags(tag("Name", "changed"))));
        }
        assertTrue(raw.get(key).isPresent());
        assertTrue(raw.get(AwsArnUtils.parse(arn).accountId() + "/" + key).isEmpty());
        assertEquals(Map.of("Name", "original"), tags(admin, id));
    }

    /** Public APIs cannot create old storage keys; only this fixture's endpoint is moved. */
    @SuppressWarnings("unchecked")
    private StorageBackend<String, VpcEndpoint> makeLegacy(String id, String arn) throws ReflectiveOperationException {
        AccountAwareStorageBackend<VpcEndpoint> store = factory.create("ec2", "ec2-vpc-endpoints.json",
                new TypeReference<Map<String, VpcEndpoint>>() {});
        Field delegate = AccountAwareStorageBackend.class.getDeclaredField("delegate");
        delegate.setAccessible(true);
        StorageBackend<String, VpcEndpoint> raw = (StorageBackend<String, VpcEndpoint>) delegate.get(store);
        String key = Region.US_EAST_1.id() + "::" + id;
        String scopedKey = AwsArnUtils.parse(arn).accountId() + "/" + key;
        VpcEndpoint held = raw.get(scopedKey).orElseThrow();
        cleanup.register(() -> raw.get(key).ifPresent(legacy -> {
            raw.put(scopedKey, legacy);
            raw.delete(key);
        }));
        raw.put(key, held);
        raw.delete(scopedKey);
        return raw;
    }

    private Ec2Client client(SignedIamUsers.User user) {
        cleanup.register(() -> {
            try {
                user.close();
            } catch (Exception error) {
                throw new IllegalStateException("Cannot retire the owned IAM test user", error);
            }
        });
        Ec2Client client = Ec2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(user.accessKeyId(), user.secretAccessKey()))).build();
        cleanup.register(client::close);
        return client;
    }

    private String vpc(Ec2Client admin, String cidr) {
        String id = admin.createVpc(request -> request.cidrBlock(cidr)).vpc().vpcId();
        cleanup.register(() -> admin.deleteVpc(request -> request.vpcId(id)));
        return id;
    }

    private String gatewayEndpoint(Ec2Client admin, String vpc) {
        String id = admin.createVpcEndpoint(request -> request.vpcId(vpc)
                        .serviceName("com.amazonaws.us-east-1.s3").vpcEndpointType(VpcEndpointType.GATEWAY))
                .vpcEndpoint().vpcEndpointId();
        cleanup.register(() -> admin.deleteVpcEndpoints(request -> request.vpcEndpointIds(id)));
        return id;
    }

    private static String endpointArn(Ec2Client admin, String id) {
        String owner = admin.describeVpcs(request -> request.vpcIds(admin.describeVpcEndpoints(
                        endpoint -> endpoint.vpcEndpointIds(id)).vpcEndpoints().getFirst().vpcId()))
                .vpcs().getFirst().ownerId();
        return AwsArnUtils.Arn.of("ec2", Region.US_EAST_1.id(), owner, "vpc-endpoint/" + id).toString();
    }

    private static Map<String, String> tags(Ec2Client admin, String id) {
        return admin.describeTags(request -> request.filters(filter -> filter.name("resource-id").values(id)))
                .tags().stream().collect(Collectors.toMap(tag -> tag.key(), tag -> tag.value()));
    }

    private static Tag tag(String key, String value) {
        return Tag.builder().key(key).value(value).build();
    }

    private static void denied(Runnable action) {
        assertEquals(403, assertThrows(Ec2Exception.class, action::run).statusCode());
    }
}
