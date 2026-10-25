package io.github.hectorvent.floci.services.organizations;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.organizations.OrganizationsClient;
import software.amazon.awssdk.services.organizations.model.Account;
import software.amazon.awssdk.services.organizations.model.OrganizationalUnit;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class OrganizationsPathsIntegrationTest {

    private static final AtomicLong ACCOUNTS = new AtomicLong(741623600001L);

    private final String owner = String.valueOf(ACCOUNTS.getAndIncrement());
    private OrganizationsClient client;
    private String organizationId;
    private String rootId;
    private String parentId;
    private String childId;
    private String memberId;
    private OrganizationalUnit createdChild;

    @BeforeAll
    static void configureContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void createHierarchy() {
        client = OrganizationsClient.builder().endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(owner, "test"))).build();
        organizationId = client.createOrganization(request -> request.featureSet("ALL")).organization().id();
        rootId = client.listRoots().roots().getFirst().id();
        parentId = client.createOrganizationalUnit(request -> request.parentId(rootId).name("Parent"))
                .organizationalUnit().id();
        createdChild = client.createOrganizationalUnit(request -> request.parentId(parentId).name("Nested"))
                .organizationalUnit();
        childId = createdChild.id();
        memberId = client.createAccount(request -> request.accountName("Member")
                .email(UUID.randomUUID() + "@example.test")).createAccountStatus().accountId();
    }

    @AfterEach
    void deleteHierarchy() {
        if (client == null) {
            return;
        }
        try {
            if (memberId != null) {
                client.removeAccountFromOrganization(request -> request.accountId(memberId));
            }
            if (childId != null) {
                client.deleteOrganizationalUnit(request -> request.organizationalUnitId(childId));
            }
            if (parentId != null) {
                client.deleteOrganizationalUnit(request -> request.organizationalUnitId(parentId));
            }
            if (organizationId != null) {
                client.deleteOrganization();
            }
        } finally {
            client.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CreateOrganizationalUnit", "UpdateOrganizationalUnit",
            "DescribeOrganizationalUnit", "ListOrganizationalUnitsForParent"})
    void sdkReturnsTheNestedOuPath(String action) {
        OrganizationalUnit unit = switch (action) {
            case "CreateOrganizationalUnit" -> createdChild;
            case "UpdateOrganizationalUnit" -> client.updateOrganizationalUnit(request -> request
                    .organizationalUnitId(childId).name("Renamed")).organizationalUnit();
            case "DescribeOrganizationalUnit" -> client.describeOrganizationalUnit(request -> request
                    .organizationalUnitId(childId)).organizationalUnit();
            default -> client.listOrganizationalUnitsForParent(request -> request.parentId(parentId))
                    .organizationalUnits().getFirst();
        };
        assertEquals(childId, unit.id());
        assertEquals(organizationId + "/" + rootId + "/" + parentId + "/" + childId + "/", unit.path());
    }

    @ParameterizedTest
    @CsvSource({"DescribeAccount,false", "DescribeAccount,true", "ListAccounts,false", "ListAccounts,true",
            "ListAccountsForParent,false", "ListAccountsForParent,true"})
    void sdkReturnsTheCurrentAccountPath(String action, boolean nested) {
        if (nested) {
            client.moveAccount(request -> request.accountId(memberId).sourceParentId(rootId).destinationParentId(childId));
        }
        String parent = nested ? childId : rootId;
        Account account = switch (action) {
            case "DescribeAccount" -> client.describeAccount(request -> request.accountId(memberId)).account();
            case "ListAccounts" -> {
                List<Account> accounts = client.listAccounts().accounts();
                assertEquals(List.of(organizationId + "/" + rootId + "/" + owner + "/"), accounts.stream()
                        .filter(candidate -> owner.equals(candidate.id())).findFirst().orElseThrow().paths());
                yield accounts.stream().filter(candidate -> memberId.equals(candidate.id())).findFirst().orElseThrow();
            }
            default -> client.listAccountsForParent(request -> request.parentId(parent)).accounts().stream()
                    .filter(candidate -> memberId.equals(candidate.id())).findFirst().orElseThrow();
        };
        String ancestry = nested ? parentId + "/" + childId + "/" : "";
        assertEquals(List.of(organizationId + "/" + rootId + "/" + ancestry + memberId + "/"), account.paths());
        if (nested) {
            client.moveAccount(request -> request.accountId(memberId).sourceParentId(childId).destinationParentId(rootId));
            assertEquals(List.of(organizationId + "/" + rootId + "/" + memberId + "/"),
                    client.describeAccount(request -> request.accountId(memberId)).account().paths());
        }
    }

    @Test
    void delegatedAdministratorShapeDoesNotGainPaths() {
        client.enableAWSServiceAccess(request -> request.servicePrincipal("config.amazonaws.com"));
        client.registerDelegatedAdministrator(request -> request.accountId(memberId).servicePrincipal("config.amazonaws.com"));
        given().contentType("application/x-amz-json-1.1")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + owner
                        + "/20261023/us-east-1/organizations/aws4_request")
                .header("X-Amz-Target", "AWSOrganizationsV20161128.ListDelegatedAdministrators")
                .body("{\"ServicePrincipal\":\"config.amazonaws.com\"}").post("/").then().statusCode(200)
                .body("DelegatedAdministrators[0].Id", equalTo(memberId))
                .body("DelegatedAdministrators[0]", not(hasKey("Paths")));
    }
}
