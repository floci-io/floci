package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeLaunchTemplateVersionsRequest;
import software.amazon.awssdk.services.ec2.model.LaunchTemplateVersion;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.reflect.Field;
import java.net.URI;
import java.time.Duration;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class Ec2LaunchTemplateVersionsIntegrationTest {

    private static final String ACCOUNT = "741623400001";
    private static final List<String> TYPES = List.of("t3.micro", "t3.small", "t3.large");

    @Inject
    Ec2Service service;

    @ParameterizedTest
    @CsvSource(value = {
            "false;;;;1/2/3",
            "true;;;;1/2/3",
            "false;1/3;;;1/3",
            "false;$Default;;;1",
            "false;$Latest;;;3",
            "false;$Latest/$Default;;;3/1",
            "false;;1;;2/3",
            "false;;;2;1/2",
            "false;;1;2;2",
            "false;1;2;3;1",
            "false;;3;;EMPTY"
    }, delimiter = ';')
    void sdkSelectsStoredVersions(boolean byId, String requested, String min, String max, String expected) {
        String name = "versions-6234-" + UUID.randomUUID();
        try (Ec2Client client = client()) {
            String id = createVersions(client, name);
            try {
                DescribeLaunchTemplateVersionsRequest.Builder request = DescribeLaunchTemplateVersionsRequest.builder()
                        .minVersion(min).maxVersion(max);
                if (byId) {
                    request.launchTemplateId(id);
                } else {
                    request.launchTemplateName(name);
                }
                if (requested != null) {
                    request.versions(requested.split("/"));
                }
                List<LaunchTemplateVersion> versions = client.describeLaunchTemplateVersions(request.build())
                        .launchTemplateVersions();
                List<Long> numbers = "EMPTY".equals(expected) ? List.of()
                        : Arrays.stream(expected.split("/")).map(Long::valueOf).toList();
                assertEquals(numbers, versions.stream().map(LaunchTemplateVersion::versionNumber).toList());
                for (LaunchTemplateVersion version : versions) {
                    assertEquals(id, version.launchTemplateId());
                    assertEquals(name, version.launchTemplateName());
                    assertEquals(TYPES.get(version.versionNumber().intValue() - 1),
                            version.launchTemplateData().instanceTypeAsString());
                    assertEquals(version.versionNumber() == 1L, version.defaultVersion());
                }
            } finally {
                client.deleteLaunchTemplate(request -> request.launchTemplateId(id));
            }
        }
    }

    @Test
    void sdkSkipsDeletedVersions() {
        String name = "versions-deleted-6234-" + UUID.randomUUID();
        try (Ec2Client client = client()) {
            String id = createVersions(client, name);
            try {
                // The deletion API is not implemented; seed a gap through the existing service path.
                RequestScopes.runAs(ACCOUNT, () -> service.deleteLaunchTemplateVersion("us-east-1", id, "2"));
                assertEquals(List.of(1L, 3L), client.describeLaunchTemplateVersions(request -> request.launchTemplateId(id))
                        .launchTemplateVersions().stream().map(LaunchTemplateVersion::versionNumber).toList());
            } finally {
                client.deleteLaunchTemplate(request -> request.launchTemplateId(id));
            }
        }
    }

    @Test
    void sdkOrdersVersionsNumericallyBeyondNine() {
        String name = "versions-numeric-6234-" + UUID.randomUUID();
        try (Ec2Client client = client()) {
            String id = createVersions(client, name);
            try {
                for (int version = 4; version <= 11; version++) {
                    client.createLaunchTemplateVersion(request -> request.launchTemplateId(id).sourceVersion("1"));
                }
                assertEquals(LongStream.rangeClosed(1, 11).boxed().toList(),
                        client.describeLaunchTemplateVersions(request -> request.launchTemplateId(id))
                                .launchTemplateVersions().stream().map(LaunchTemplateVersion::versionNumber).toList());
            } finally {
                client.deleteLaunchTemplate(request -> request.launchTemplateId(id));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void listingDoesNotFailWhenAVersionChangesDuringIteration(boolean delete) throws Exception {
        String name = "versions-race-6234-" + UUID.randomUUID();
        LaunchTemplate template = RequestScopes.callAs(ACCOUNT, () -> {
            LaunchTemplate created = service.createLaunchTemplate("us-east-1", name, new LaunchTemplateData(), List.of());
            service.createLaunchTemplateVersion("us-east-1", created.getLaunchTemplateId(), null, "1", new LaunchTemplateData());
            return created;
        });
        CountDownLatch startWriter = new CountDownLatch(1);
        AtomicReference<Thread> writerThread = new AtomicReference<>();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> writer = executor.submit(() -> {
                writerThread.set(Thread.currentThread());
                startWriter.await();
                RequestScopes.runAs(ACCOUNT, () -> {
                    if (delete) {
                        service.deleteLaunchTemplateVersion("us-east-1", template.getLaunchTemplateId(), "1");
                    } else {
                        service.createLaunchTemplateVersion(
                                "us-east-1", template.getLaunchTemplateId(), null, "1", new LaunchTemplateData());
                    }
                });
                return null;
            });
            try {
                Field field = LaunchTemplate.class.getDeclaredField("versions");
                field.setAccessible(true);
                field.set(template, interleavingVersions(template.getVersions(), () -> {
                    long readerId = Thread.currentThread().threadId();
                    startWriter.countDown();
                    // Force the writer to finish or wait on this reader before the next iterator step.
                    await().atMost(Duration.ofSeconds(5)).until(() -> {
                        if (writer.isDone()) {
                            return true;
                        }
                        Thread thread = writerThread.get();
                        ThreadInfo info = thread == null ? null
                                : ManagementFactory.getThreadMXBean().getThreadInfo(thread.threadId());
                        return info != null && info.getLockOwnerId() == readerId;
                    });
                }));
                List<LaunchTemplate> snapshot = assertDoesNotThrow(() -> RequestScopes.callAs(ACCOUNT,
                        () -> service.describeLaunchTemplateVersions(
                                "us-east-1", template.getLaunchTemplateId(), null, List.of(), null, null)));
                assertEquals(List.of("1", "2"), snapshot.stream().map(LaunchTemplate::getLatestVersionNumber).toList());
                writer.get(5, TimeUnit.SECONDS);
                assertEquals(delete ? "2" : "3", template.getLatestVersionNumber());
                assertEquals(!delete, template.getVersions().containsKey("1"));
            } finally {
                startWriter.countDown();
            }
        } finally {
            RequestScopes.runAs(ACCOUNT, () -> service.deleteLaunchTemplate("us-east-1", template.getLaunchTemplateId(), null));
        }
    }

    private static Map<String, LaunchTemplateData> interleavingVersions(Map<String, LaunchTemplateData> source, Runnable hook) {
        return new LinkedHashMap<>(source) {
            private boolean triggered;

            @Override
            public Set<String> keySet() {
                Set<String> keys = super.keySet();
                return new AbstractSet<>() {
                    @Override
                    public Iterator<String> iterator() {
                        Iterator<String> iterator = keys.iterator();
                        return new Iterator<>() {
                            @Override
                            public boolean hasNext() { return iterator.hasNext(); }

                            @Override
                            public String next() {
                                String key = iterator.next();
                                if (!triggered) {
                                    triggered = true;
                                    hook.run();
                                }
                                return key;
                            }
                        };
                    }

                    @Override
                    public int size() { return keys.size(); }
                };
            }
        };
    }

    @Test
    void legacyVersionAliasStillSelectsAnOlderVersion() {
        String name = "versions-alias-6234-" + UUID.randomUUID();
        try (Ec2Client client = client()) {
            String id = createVersions(client, name);
            try {
                given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
                                + "/20261023/us-east-1/ec2/aws4_request")
                        .formParam("Action", "DescribeLaunchTemplateVersions")
                        .formParam("LaunchTemplateId", id).formParam("Versions.1", "1")
                        .post("/").then().statusCode(200)
                        .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.versionNumber",
                                equalTo("1"));
            } finally {
                client.deleteLaunchTemplate(request -> request.launchTemplateId(id));
            }
        }
    }

    @Test
    void internalLookupStillDefaultsToLatestAndAllowsMissingTemplates() {
        String name = "versions-internal-6234-" + UUID.randomUUID();
        LaunchTemplate template = service.createLaunchTemplate(
                "us-east-1", name, new LaunchTemplateData(), List.of());
        try {
            service.createLaunchTemplateVersion("us-east-1", template.getLaunchTemplateId(), null, null,
                    new LaunchTemplateData());
            assertEquals(List.of("2"), service.describeLaunchTemplateVersions(
                            "us-east-1", template.getLaunchTemplateId(), null, List.of()).stream()
                    .map(LaunchTemplate::getLatestVersionNumber).toList());
            assertEquals(List.of(), service.describeLaunchTemplateVersions("us-east-1", null, name + "-missing", List.of()));
        } finally {
            service.deleteLaunchTemplate("us-east-1", template.getLaunchTemplateId(), null);
        }
    }

    private static String createVersions(Ec2Client client, String name) {
        String id = client.createLaunchTemplate(request -> request.launchTemplateName(name)
                        .launchTemplateData(data -> data.imageId("ami-0abcdef1234567890").instanceType(TYPES.getFirst())))
                .launchTemplate().launchTemplateId();
        try {
            for (String type : TYPES.subList(1, TYPES.size())) {
                client.createLaunchTemplateVersion(request -> request.launchTemplateId(id).sourceVersion("1")
                        .launchTemplateData(data -> data.instanceType(type)));
            }
            return id;
        } catch (RuntimeException failure) {
            client.deleteLaunchTemplate(request -> request.launchTemplateId(id));
            throw failure;
        }
    }

    private static Ec2Client client() {
        return Ec2Client.builder().endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCOUNT, "test"))).build();
    }
}
