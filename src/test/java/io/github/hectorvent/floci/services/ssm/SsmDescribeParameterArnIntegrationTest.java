package io.github.hectorvent.floci.services.ssm;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.DescribeParametersResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;
import software.amazon.awssdk.services.ssm.model.ParameterMetadata;
import software.amazon.awssdk.services.ssm.model.ParameterStringFilter;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SsmDescribeParameterArnIntegrationTest {

    @ParameterizedTest
    @CsvSource({"us-east-1,111111111111,aws", "eu-west-2,222222222222,aws",
            "cn-north-1,333333333333,aws-cn", "us-gov-west-1,444444444444,aws-us-gov"})
    void pagedMetadataCarriesTheStoredParameterArn(String region, String account, String partition) {
        String prefix = "describe-arn-" + UUID.randomUUID();
        List<String> names = new ArrayList<>();
        try (SsmClient client = SsmClient.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .build()) {
            try {
                for (String type : List.of("String", "StringList", "SecureString")) {
                    for (String name : List.of(prefix + "-" + type, "/" + prefix + "/" + type)) {
                        client.putParameter(request -> request.name(name).type(type).value("value"));
                        names.add(name);
                    }
                }
                ParameterStringFilter filter = ParameterStringFilter.builder()
                        .key("Name").option("Equals").values(names).build();
                String token = null;
                Set<String> seen = new HashSet<>();
                for (int page = 0; page < names.size(); page++) {
                    String nextToken = token;
                    DescribeParametersResponse response = client.describeParameters(request -> request
                            .parameterFilters(filter).maxResults(1).nextToken(nextToken));
                    assertEquals(1, response.parameters().size());
                    ParameterMetadata metadata = response.parameters().get(0);
                    assertTrue(names.contains(metadata.name()));
                    assertTrue(seen.add(metadata.name()));
                    Parameter parameter = client.getParameter(request -> request.name(metadata.name())).parameter();
                    String resource = metadata.name().startsWith("/") ? metadata.name() : "/" + metadata.name();
                    String expectedArn = "arn:" + partition + ":ssm:" + region + ":" + account + ":parameter" + resource;
                    assertEquals(expectedArn, parameter.arn());
                    assertEquals(expectedArn, metadata.arn());
                    assertEquals(parameter.type(), metadata.type());
                    assertEquals(parameter.version(), metadata.version());
                    assertEquals(parameter.lastModifiedDate(), metadata.lastModifiedDate());
                    assertEquals("text", metadata.dataType());
                    assertEquals("Standard", metadata.tierAsString());
                    token = response.nextToken();
                    if (page < names.size() - 1) {
                        assertNotNull(token);
                    } else {
                        assertNull(token);
                    }
                }
                assertEquals(new HashSet<>(names), seen);
            } finally {
                for (String name : names) {
                    client.deleteParameter(request -> request.name(name));
                }
            }
        }
    }
}
