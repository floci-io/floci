package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.SshPublicKeys;
import io.github.hectorvent.floci.core.common.Totp;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.testing.SamlMetadata;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every IAM operation that pages goes through {@code MaxItems} and {@code Marker} as the vendored
 * {@code aws/iam-paginators.json} describes it, which {@link #everyPaginatedOperationHonoursMaxItemsAndMarker}
 * checks operation by operation. The rest pin what that one path does on the lists where it is
 * easiest to see: name order, a marker anchored to an item rather than a position, the default page
 * of 100, and a marker that resumes only the listing and account that issued it.
 *
 * <p>IAM state is shared across the suite, so each test scopes its listing to a path of its own
 * and removes what it created.
 */
@QuarkusTest
class IamListPaginationIntegrationTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request";
    private static final String TRUST_POLICY = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",\
            "Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";
    private static final String POLICY_DOCUMENT = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"s3:GetObject",\
            "Resource":"*"}]}""";

    @TestHTTPResource("/")
    URI endpoint;

    private static final HttpClient BULK = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    /**
     * Sends one {@code action} per parameter set, concurrently and without RestAssured, whose
     * per-request overhead is most of the cost of the hundred-item fixtures. Each must answer 200.
     */
    private void bulk(String action, List<List<String>> requests) {
        List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
        for (List<String> pairs : requests) {
            StringBuilder form = new StringBuilder("Action=").append(action);
            for (int i = 0; i < pairs.size(); i += 2) {
                form.append('&').append(pairs.get(i)).append('=')
                        .append(URLEncoder.encode(pairs.get(i + 1), StandardCharsets.UTF_8));
            }
            sent.add(BULK.sendAsync(HttpRequest.newBuilder(endpoint)
                    .header("Authorization", IAM_AUTH)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form.toString())).build(),
                    HttpResponse.BodyHandlers.ofString()));
        }
        for (CompletableFuture<HttpResponse<String>> response : sent) {
            HttpResponse<String> answered = response.join();
            assertEquals(200, answered.statusCode(), action + ": " + answered.body());
        }
    }

    private static RequestSpecification iam(String action) {
        return given().header("Authorization", IAM_AUTH).formParam("Action", action);
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private record Page(List<String> names, boolean truncated, String marker) {}

    private static Page page(String action, String listElement, String nameElement,
                             RequestSpecification request) {
        String result = action + "Response." + action + "Result.";
        XmlPath xml = request.when().post("/").then().statusCode(200).extract().xmlPath();
        String marker = xml.getString(result + "Marker");
        return new Page(xml.getList(result + listElement + ".member." + nameElement, String.class),
                Boolean.parseBoolean(xml.getString(result + "IsTruncated")),
                marker == null || marker.isEmpty() ? null : marker);
    }

    private static Page rolePage(String pathPrefix, String maxItems, String marker) {
        RequestSpecification request = iam("ListRoles").formParam("PathPrefix", pathPrefix);
        if (maxItems != null) {
            request.formParam("MaxItems", maxItems);
        }
        if (marker != null) {
            request.formParam("Marker", marker);
        }
        return page("ListRoles", "Roles", "RoleName", request);
    }

    private static Page policyPage(String scope, String pathPrefix, String maxItems, String marker) {
        RequestSpecification request = iam("ListPolicies").formParam("Scope", scope);
        if (pathPrefix != null) {
            request.formParam("PathPrefix", pathPrefix);
        }
        if (maxItems != null) {
            request.formParam("MaxItems", maxItems);
        }
        if (marker != null) {
            request.formParam("Marker", marker);
        }
        return page("ListPolicies", "Policies", "Arn", request);
    }

    /**
     * Follows {@code Marker} until {@code IsTruncated} is false, checking on every page that a
     * marker is present exactly when the page is truncated.
     */
    private static List<Page> walk(Function<String, Page> next) {
        List<Page> pages = new ArrayList<>();
        String marker = null;
        do {
            Page current = next.apply(marker);
            if (current.truncated()) {
                assertNotNull(current.marker(), "a truncated page carries a Marker");
            } else {
                assertNull(current.marker(), "the last page carries no Marker");
            }
            pages.add(current);
            marker = current.marker();
        } while (marker != null);
        return pages;
    }

    private static List<String> flatten(List<Page> pages) {
        List<String> all = new ArrayList<>();
        pages.forEach(p -> all.addAll(p.names()));
        return all;
    }

    private static void createRole(String name, String path) {
        iam("CreateRole").formParam("RoleName", name).formParam("Path", path)
            .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
        .when().post("/").then().statusCode(200);
    }

    private static void deleteRole(String name) {
        iam("DeleteRole").formParam("RoleName", name).when().post("/").then().statusCode(200);
    }

    private static String createPolicy(String name, String path) {
        return iam("CreatePolicy").formParam("PolicyName", name).formParam("Path", path)
            .formParam("PolicyDocument", POLICY_DOCUMENT)
        .when().post("/").then().statusCode(200)
            .extract().path("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");
    }

    private static void deletePolicy(String arn) {
        iam("DeletePolicy").formParam("PolicyArn", arn).when().post("/").then().statusCode(200);
    }

    @Test
    void listRolesPagesThroughThePathPrefixInNameOrder() {
        String tag = suffix();
        String path = "/pg-roles-" + tag + "/";
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            expected.add("pg-role-" + tag + "-" + i);
        }
        String outside = "pg-role-" + tag + "-outside";
        expected.forEach(name -> createRole(name, path));
        createRole(outside, "/pg-roles-other-" + tag + "/");
        try {
            List<Page> pages = walk(marker -> rolePage(path, "2", marker));

            assertEquals(List.of(2, 2, 1), pages.stream().map(p -> p.names().size()).toList());
            assertEquals(List.of(true, true, false), pages.stream().map(Page::truncated).toList());
            assertEquals(expected, flatten(pages), "every role once, in name order, nothing outside the prefix");
        } finally {
            expected.forEach(IamListPaginationIntegrationTest::deleteRole);
            deleteRole(outside);
        }
    }

    @Test
    void listRolesDefaultsToOneHundredItems() {
        String tag = suffix();
        String path = "/pg-roles-default-" + tag + "/";
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            names.add(String.format("pg-default-%s-%03d", tag, i));
        }
        bulk("CreateRole", names.stream().map(name -> List.of("RoleName", name, "Path", path,
                "AssumeRolePolicyDocument", TRUST_POLICY)).toList());
        try {
            Page first = rolePage(path, null, null);
            assertEquals(100, first.names().size());
            assertTrue(first.truncated());
            assertNotNull(first.marker());

            Page second = rolePage(path, null, first.marker());
            assertEquals(List.of(names.get(100)), second.names());
            assertFalse(second.truncated());
            assertNull(second.marker());
        } finally {
            bulk("DeleteRole", names.stream().map(name -> List.of("RoleName", name)).toList());
        }
    }

    @Test
    void listPoliciesPagesThroughScopeAndPathPrefix() {
        String tag = suffix();
        String path = "/pg-policies-" + tag + "/";
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            expected.add(createPolicy("pg-policy-" + tag + "-" + i, path));
        }
        String outside = createPolicy("pg-policy-" + tag + "-outside", "/pg-policies-other-" + tag + "/");
        try {
            List<Page> pages = walk(marker -> policyPage("Local", path, "2", marker));

            assertEquals(List.of(2, 2, 1), pages.stream().map(p -> p.names().size()).toList());
            assertEquals(List.of(true, true, false), pages.stream().map(Page::truncated).toList());
            assertEquals(expected, flatten(pages), "every policy once, in name order, nothing outside the prefix");
        } finally {
            expected.forEach(IamListPaginationIntegrationTest::deletePolicy);
            deletePolicy(outside);
        }
    }

    @Test
    void listPoliciesDefaultsToOneHundredAndWalksTheWholeCatalog() {
        Page first = policyPage("AWS", null, null, null);
        assertEquals(100, first.names().size(), "an omitted MaxItems defaults to 100");
        assertTrue(first.truncated());

        List<String> byDefault = flatten(walk(marker -> policyPage("AWS", null, null, marker)));
        List<String> byLargest = flatten(walk(marker -> policyPage("AWS", null, "1000", marker)));

        assertTrue(byDefault.size() > 100, "the AWS managed catalog is wider than one default page");
        assertEquals(byDefault.size(), new HashSet<>(byDefault).size(), "no policy repeats across pages");
        assertEquals(byLargest, byDefault, "the page size changes where pages split, not what they hold");
    }

    /**
     * The API reference documents Marker as the place "where the next call should start", so a
     * continuation returns the roles after the ones already seen, whatever happened ahead of it.
     */
    @Test
    void aRoleDeletedAheadOfTheMarkerSkipsNoRoleOnTheNextPage() {
        String tag = suffix();
        String path = "/pg-anchor-deleted-" + tag + "/";
        List<String> names = roleNames(tag, 5);
        names.forEach(name -> createRole(name, path));
        List<String> remaining = new ArrayList<>(names);
        try {
            Page first = rolePage(path, "2", null);
            assertEquals(names.subList(0, 2), first.names());

            deleteRole(names.getFirst());
            remaining.remove(names.getFirst());

            assertEquals(names.subList(2, 5), flatten(walk(marker -> rolePage(path, "2",
                    marker == null ? first.marker() : marker))));
        } finally {
            remaining.forEach(IamListPaginationIntegrationTest::deleteRole);
        }
    }

    @Test
    void aRoleCreatedAheadOfTheMarkerReturnsNoRoleTwice() {
        String tag = suffix();
        String path = "/pg-anchor-created-" + tag + "/";
        List<String> names = roleNames(tag, 5);
        names.subList(1, 5).forEach(name -> createRole(name, path));
        try {
            Page first = rolePage(path, "2", null);
            assertEquals(names.subList(1, 3), first.names());

            createRole(names.getFirst(), path);

            assertEquals(names.subList(3, 5), flatten(walk(marker -> rolePage(path, "2",
                    marker == null ? first.marker() : marker))));
        } finally {
            names.forEach(IamListPaginationIntegrationTest::deleteRole);
        }
    }

    @Test
    void theLastRoleOfAPageDeletedBeforeTheNextPageSkipsNoRole() {
        String tag = suffix();
        String path = "/pg-anchor-gone-" + tag + "/";
        List<String> names = roleNames(tag, 5);
        names.forEach(name -> createRole(name, path));
        List<String> remaining = new ArrayList<>(names);
        try {
            Page first = rolePage(path, "2", null);
            assertEquals(names.subList(0, 2), first.names());

            deleteRole(names.get(1));
            remaining.remove(names.get(1));

            assertEquals(names.subList(2, 5), flatten(walk(marker -> rolePage(path, "2",
                    marker == null ? first.marker() : marker))));
        } finally {
            remaining.forEach(IamListPaginationIntegrationTest::deleteRole);
        }
    }

    /**
     * ListPolicies lists customer-managed policies before the AWS-managed catalog. A marker naming
     * the last customer-managed policy, deleted before the next page, resumes at the first
     * AWS-managed one, although its name sorts after every managed policy's ARN.
     */
    @Test
    void aDeletedCustomerManagedPolicyResumesAtTheNextPolicyInListPoliciesOrder() {
        String name = "zzzz-pg-anchor-" + suffix();
        String arn = createPolicy(name, "/pg-anchor-policy/");
        boolean deleted = false;
        try {
            List<String> all = flatten(walk(marker -> policyPage("All", null, "1000", marker)));
            int index = all.indexOf(arn);
            assertTrue(index >= 0, "the created policy is listed");
            String following = all.get(index + 1);
            assertTrue(following.startsWith("arn:aws:iam::aws:policy/"),
                    "the policy is the last customer-managed one: " + following);

            Page first = policyPage("All", null, Integer.toString(index + 1), null);
            assertEquals(arn, first.names().getLast());

            deletePolicy(arn);
            deleted = true;

            assertEquals(List.of(following), policyPage("All", null, "1", first.marker()).names());
        } finally {
            if (!deleted) {
                deletePolicy(arn);
            }
        }
    }

    /**
     * ListAttachedRolePolicies keys a page by policy ARN, and a long path makes that ARN longer than
     * the 320 characters the model allows a Marker. The marker issued still fits, so the request
     * carrying it clears the model check, and with the policy it ended on deleted before the next
     * page it resumes at the next policy, skipping none and repeating none.
     */
    @Test
    void aMarkerEndingOnALongPolicyArnFitsTheModelAndResumesPastItsDeletion() {
        String tag = suffix();
        String path = "/" + ("pg-long-path-" + tag + "/").repeat(15);
        String role = "pg-long-" + tag;
        createRole(role, "/");
        List<String> arns = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            arns.add(createPolicy(role + "-" + i, path));
            call("AttachRolePolicy", "RoleName", role, "PolicyArn", arns.getLast());
        }
        List<String> remaining = new ArrayList<>(arns);
        try {
            assertTrue(arns.getFirst().length() > 320, arns.getFirst().length() + " characters");
            Page first = page("ListAttachedRolePolicies", "AttachedPolicies", "PolicyArn",
                    iam("ListAttachedRolePolicies").formParam("RoleName", role).formParam("MaxItems", "1"));
            assertEquals(List.of(arns.getFirst()), first.names());
            assertNotNull(first.marker());
            assertTrue(first.marker().length() <= 320, first.marker().length() + " characters");

            call("DetachRolePolicy", "RoleName", role, "PolicyArn", arns.getFirst());
            deletePolicy(arns.getFirst());
            remaining.remove(arns.getFirst());

            Page rest = page("ListAttachedRolePolicies", "AttachedPolicies", "PolicyArn",
                    withMarker(iam("ListAttachedRolePolicies").formParam("RoleName", role), first.marker()));
            assertEquals(arns.subList(1, 3), rest.names());
            assertFalse(rest.truncated());
        } finally {
            remaining.forEach(arn -> {
                call("DetachRolePolicy", "RoleName", role, "PolicyArn", arn);
                deletePolicy(arn);
            });
            deleteRole(role);
        }
    }

    /** A marker shaped like an issued one but never issued is refused, as any other unissued marker is. */
    @Test
    void listRolesRejectsAnAnchoredMarkerItDidNotIssue() {
        String forged = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("pg-anchor-forged.not-a-signature".getBytes(StandardCharsets.UTF_8));

        iam("ListRoles").formParam("Marker", forged)
        .when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("InvalidInput"));
    }

    private static List<String> roleNames(String tag, int count) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            names.add("pg-anchor-" + tag + "-" + i);
        }
        return names;
    }

    private static Page listPage(String action, String listElement, String nameElement,
                                 String entityParam, String entity, String maxItems, String marker) {
        RequestSpecification request = iam(action);
        if (entityParam != null) {
            request.formParam(entityParam, entity);
        }
        if (maxItems != null) {
            request.formParam("MaxItems", maxItems);
        }
        if (marker != null) {
            request.formParam("Marker", marker);
        }
        return page(action, listElement, nameElement, request);
    }

    private static void call(String action, String... pairs) {
        RequestSpecification request = iam(action);
        for (int i = 0; i < pairs.length; i += 2) {
            request.formParam(pairs[i], pairs[i + 1]);
        }
        request.when().post("/").then().statusCode(200);
    }

    @Test
    void listUsersAndListGroupsPageThroughThePathPrefixInNameOrder() {
        String tag = suffix();
        String path = "/pg-principals-" + tag + "/";
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            expected.add("pg-principal-" + tag + "-" + i);
        }
        // Created out of name order, so the listing has to sort rather than echo creation order.
        for (String name : expected.reversed()) {
            call("CreateUser", "UserName", name, "Path", path);
            call("CreateGroup", "GroupName", name, "Path", path);
        }
        try {
            List<Page> users = walk(marker -> page("ListUsers", "Users", "UserName",
                    withMarker(iam("ListUsers").formParam("PathPrefix", path).formParam("MaxItems", "2"), marker)));
            List<Page> groups = walk(marker -> page("ListGroups", "Groups", "GroupName",
                    withMarker(iam("ListGroups").formParam("PathPrefix", path).formParam("MaxItems", "2"), marker)));

            for (List<Page> pages : List.of(users, groups)) {
                assertEquals(List.of(2, 2, 1), pages.stream().map(p -> p.names().size()).toList());
                assertEquals(List.of(true, true, false), pages.stream().map(Page::truncated).toList());
                assertEquals(expected, flatten(pages), "every entry once, in name order");
            }
            assertEquals(expected, page("ListUsers", "Users", "UserName",
                    iam("ListUsers").formParam("PathPrefix", path)).names(), "no MaxItems lists them all");
        } finally {
            for (String name : expected) {
                call("DeleteUser", "UserName", name);
                call("DeleteGroup", "GroupName", name);
            }
        }
    }

    private static RequestSpecification withMarker(RequestSpecification request, String marker) {
        return marker == null ? request : request.formParam("Marker", marker);
    }

    @Test
    void attachedAndInlinePolicyListsPageThroughMaxItemsAndMarker() {
        String tag = suffix();
        String name = "pg-holder-" + tag;
        createRole(name, "/");
        call("CreateUser", "UserName", name);
        call("CreateGroup", "GroupName", name);
        List<String> arns = new ArrayList<>();
        List<String> inline = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            arns.add(createPolicy("pg-attached-" + tag + "-" + i, "/"));
            inline.add("pg-inline-" + tag + "-" + i);
        }
        List<String[]> holders = List.of(new String[]{"Role", "RoleName"},
                new String[]{"User", "UserName"}, new String[]{"Group", "GroupName"});
        try {
            for (String[] h : holders) {
                for (int i = 2; i >= 0; i--) {
                    call("Attach" + h[0] + "Policy", h[1], name, "PolicyArn", arns.get(i));
                    call("Put" + h[0] + "Policy", h[1], name, "PolicyName", inline.get(i),
                            "PolicyDocument", POLICY_DOCUMENT);
                }
            }
            for (String[] h : holders) {
                List<Page> attached = walk(marker -> listPage("ListAttached" + h[0] + "Policies",
                        "AttachedPolicies", "PolicyArn", h[1], name, "2", marker));
                assertEquals(List.of(2, 1), attached.stream().map(p -> p.names().size()).toList(), h[0]);
                assertEquals(arns, flatten(attached), h[0] + " attached policies, each once");

                List<Page> names = walk(marker -> {
                    String action = "List" + h[0] + "Policies";
                    XmlPath xml = withMarker(iam(action).formParam(h[1], name).formParam("MaxItems", "2"), marker)
                            .when().post("/").then().statusCode(200).extract().xmlPath();
                    String result = action + "Response." + action + "Result.";
                    String next = xml.getString(result + "Marker");
                    return new Page(xml.getList(result + "PolicyNames.member", String.class),
                            Boolean.parseBoolean(xml.getString(result + "IsTruncated")),
                            next == null || next.isEmpty() ? null : next);
                });
                assertEquals(List.of(2, 1), names.stream().map(p -> p.names().size()).toList(), h[0]);
                assertEquals(inline, flatten(names), h[0] + " inline policies, each once");
            }

            // The policy a marker names, detached before the next page, skips nothing after it.
            Page first = listPage("ListAttachedRolePolicies", "AttachedPolicies", "PolicyArn",
                    "RoleName", name, "2", null);
            call("DetachRolePolicy", "RoleName", name, "PolicyArn", arns.get(1));
            assertEquals(List.of(arns.get(2)), listPage("ListAttachedRolePolicies", "AttachedPolicies",
                    "PolicyArn", "RoleName", name, "2", first.marker()).names());
            call("AttachRolePolicy", "RoleName", name, "PolicyArn", arns.get(1));

            iam("ListRolePolicies").formParam("RoleName", name).formParam("MaxItems", "1001")
            .when().post("/").then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
            iam("ListAttachedRolePolicies").formParam("RoleName", name).formParam("Marker", "not-a-marker")
            .when().post("/").then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidInput"));
        } finally {
            for (String[] h : holders) {
                for (int i = 0; i < 3; i++) {
                    call("Detach" + h[0] + "Policy", h[1], name, "PolicyArn", arns.get(i));
                    call("Delete" + h[0] + "Policy", h[1], name, "PolicyName", inline.get(i));
                }
            }
            arns.forEach(IamListPaginationIntegrationTest::deletePolicy);
            deleteRole(name);
            call("DeleteUser", "UserName", name);
            call("DeleteGroup", "GroupName", name);
        }
    }

    /**
     * Only the default page size: resuming after a default page is {@link #listRolesDefaultsToOneHundredItems},
     * and resuming these two lists is {@link #everyPaginatedOperationHonoursMaxItemsAndMarker}.
     */
    @Test
    void listUsersAndListGroupsDefaultToOneHundredItems() {
        String tag = suffix();
        String path = "/pg-principals-default-" + tag + "/";
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            names.add(String.format("pg-default-%s-%03d", tag, i));
        }
        bulk("CreateUser", names.stream().map(name -> List.of("UserName", name, "Path", path)).toList());
        bulk("CreateGroup", names.stream().map(name -> List.of("GroupName", name, "Path", path)).toList());
        try {
            for (String[] list : List.of(new String[]{"ListUsers", "Users", "UserName"},
                    new String[]{"ListGroups", "Groups", "GroupName"})) {
                Page first = page(list[0], list[1], list[2], iam(list[0]).formParam("PathPrefix", path));
                assertEquals(names.subList(0, 100), first.names(), list[0]);
                assertTrue(first.truncated(), list[0]);
                assertNotNull(first.marker(), list[0]);
            }
        } finally {
            bulk("DeleteUser", names.stream().map(name -> List.of("UserName", name)).toList());
            bulk("DeleteGroup", names.stream().map(name -> List.of("GroupName", name)).toList());
        }
    }

    /** A marker resumes only the listing that issued it: same action, same principal. */
    @Test
    void aMarkerFromAnotherListingIsRejected() {
        String tag = suffix();
        String roleA = "pg-scope-a-" + tag;
        String roleB = "pg-scope-b-" + tag;
        createRole(roleA, "/");
        createRole(roleB, "/");
        List<String> arns = new ArrayList<>();
        List<String> users = List.of("pg-scope-u0-" + tag, "pg-scope-u1-" + tag);
        for (int i = 0; i < 2; i++) {
            arns.add(createPolicy("pg-scope-" + tag + "-" + i, "/"));
            call("CreateUser", "UserName", users.get(i), "Path", "/pg-scope-" + tag + "/");
            call("CreateGroup", "GroupName", users.get(i), "Path", "/pg-scope-" + tag + "/");
            for (String role : List.of(roleA, roleB)) {
                call("AttachRolePolicy", "RoleName", role, "PolicyArn", arns.get(i));
                call("PutRolePolicy", "RoleName", role, "PolicyName", "pg-inline-" + i,
                        "PolicyDocument", POLICY_DOCUMENT);
            }
        }
        try {
            String attachedA = listPage("ListAttachedRolePolicies", "AttachedPolicies", "PolicyArn",
                    "RoleName", roleA, "1", null).marker();
            assertNotNull(attachedA);
            String usersMarker = page("ListUsers", "Users", "UserName", iam("ListUsers")
                    .formParam("PathPrefix", "/pg-scope-" + tag + "/").formParam("MaxItems", "1")).marker();
            assertNotNull(usersMarker);

            List<RequestSpecification> misuses = List.of(
                    iam("ListAttachedRolePolicies").formParam("RoleName", roleB).formParam("Marker", attachedA),
                    iam("ListRolePolicies").formParam("RoleName", roleA).formParam("Marker", attachedA),
                    iam("ListGroups").formParam("PathPrefix", "/pg-scope-" + tag + "/")
                            .formParam("Marker", usersMarker));
            for (RequestSpecification misuse : misuses) {
                misuse.when().post("/").then()
                    .statusCode(400)
                    .body("ErrorResponse.Error.Code", equalTo("InvalidInput"));
            }
            assertEquals(List.of(arns.get(1)), listPage("ListAttachedRolePolicies", "AttachedPolicies",
                    "PolicyArn", "RoleName", roleA, "1", attachedA).names(), "the issuing listing still resumes");
        } finally {
            for (int i = 0; i < 2; i++) {
                for (String role : List.of(roleA, roleB)) {
                    call("DetachRolePolicy", "RoleName", role, "PolicyArn", arns.get(i));
                    call("DeleteRolePolicy", "RoleName", role, "PolicyName", "pg-inline-" + i);
                }
                call("DeleteUser", "UserName", users.get(i));
                call("DeleteGroup", "GroupName", users.get(i));
            }
            arns.forEach(IamListPaginationIntegrationTest::deletePolicy);
            deleteRole(roleA);
            deleteRole(roleB);
        }
    }

    /** IAM is per account, so a marker from one account's listing does not resume another's. */
    @Test
    void aMarkerFromAnotherAccountIsRejected() {
        String accountA = "AWS4-HMAC-SHA256 Credential=111111111111/20260227/us-east-1/iam/aws4_request";
        String accountB = "AWS4-HMAC-SHA256 Credential=222222222222/20260227/us-east-1/iam/aws4_request";
        String tag = suffix();
        String path = "/pg-account-" + tag + "/";
        List<String> names = List.of("pg-account-" + tag + "-0", "pg-account-" + tag + "-1");
        for (String account : List.of(accountA, accountB)) {
            for (String name : names) {
                given().header("Authorization", account).formParam("Action", "CreateUser")
                        .formParam("UserName", name).formParam("Path", path)
                .when().post("/").then().statusCode(200);
                given().header("Authorization", account).formParam("Action", "PutUserPolicy")
                        .formParam("UserName", names.getFirst()).formParam("PolicyName", name)
                        .formParam("PolicyDocument", POLICY_DOCUMENT)
                .when().post("/").then().statusCode(200);
            }
        }
        try {
            String usersMarker = page("ListUsers", "Users", "UserName", given().header("Authorization", accountA)
                    .formParam("Action", "ListUsers").formParam("PathPrefix", path).formParam("MaxItems", "1")).marker();
            String inlineMarker = given().header("Authorization", accountA).formParam("Action", "ListUserPolicies")
                    .formParam("UserName", names.getFirst()).formParam("MaxItems", "1")
                .when().post("/").then().statusCode(200).extract()
                    .path("ListUserPoliciesResponse.ListUserPoliciesResult.Marker");
            assertNotNull(usersMarker);
            assertNotNull(inlineMarker);

            given().header("Authorization", accountB).formParam("Action", "ListUsers")
                    .formParam("PathPrefix", path).formParam("Marker", usersMarker)
            .when().post("/").then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidInput"));
            given().header("Authorization", accountB).formParam("Action", "ListUserPolicies")
                    .formParam("UserName", names.getFirst()).formParam("Marker", inlineMarker)
            .when().post("/").then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidInput"));
        } finally {
            for (String account : List.of(accountA, accountB)) {
                for (String name : names) {
                    given().header("Authorization", account).formParam("Action", "DeleteUserPolicy")
                            .formParam("UserName", names.getFirst()).formParam("PolicyName", name)
                    .when().post("/").then().statusCode(200);
                }
                for (String name : names) {
                    given().header("Authorization", account).formParam("Action", "DeleteUser")
                            .formParam("UserName", name)
                    .when().post("/").then().statusCode(200);
                }
            }
        }
    }

    /**
     * A list that omits {@code UserName} is the caller's, so the marker is bound to the user it
     * resolved to: another user in the same account, also omitting it, cannot resume it, while the
     * same listing named explicitly still can.
     */
    @Test
    void aMarkerFromAnotherCallersImplicitListingIsRejected() {
        String tag = suffix();
        List<String> users = List.of("pg-caller-a-" + tag, "pg-caller-b-" + tag);
        Map<String, List<String>> keys = new HashMap<>();
        Map<String, List<String>> sshKeys = new HashMap<>();
        for (String user : users) {
            createUser(user);
            keys.put(user, new ArrayList<>());
            sshKeys.put(user, new ArrayList<>());
            for (int i = 0; i < 2; i++) {
                keys.get(user).add(iam("CreateAccessKey").formParam("UserName", user).when().post("/").then()
                    .statusCode(200).extract().path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId"));
                sshKeys.get(user).add(iam("UploadSSHPublicKey").formParam("UserName", user)
                    .formParam("SSHPublicKeyBody", SSH_PUBLIC_KEYS.get(i)).when().post("/").then().statusCode(200).extract()
                    .path("UploadSSHPublicKeyResponse.UploadSSHPublicKeyResult.SSHPublicKey.SSHPublicKeyId"));
            }
        }
        Function<String, String> as = user -> "AWS4-HMAC-SHA256 Credential=" + keys.get(user).getFirst()
                + "/20260227/us-east-1/iam/aws4_request";
        String a = users.get(0);
        String b = users.get(1);
        try {
            for (String[] op : List.of(new String[]{"ListAccessKeys", "AccessKeyMetadata", "AccessKeyId"},
                    new String[]{"ListSSHPublicKeys", "SSHPublicKeys", "SSHPublicKeyId"})) {
                Page first = page(op[0], op[1], op[2], given().header("Authorization", as.apply(a))
                        .formParam("Action", op[0]).formParam("MaxItems", "1"));
                assertTrue(first.truncated(), op[0] + " as " + a + " is cut at one item");
                assertNotNull(first.marker());

                refused(given().header("Authorization", as.apply(b)).formParam("Action", op[0])
                        .formParam("Marker", first.marker()), "InvalidInput");

                List<String> all = new ArrayList<>(first.names());
                all.addAll(page(op[0], op[1], op[2], given().header("Authorization", as.apply(a))
                        .formParam("Action", op[0]).formParam("Marker", first.marker())).names());
                List<String> owned = op[0].equals("ListAccessKeys") ? keys.get(a) : sshKeys.get(a);
                assertEquals(new TreeSet<>(owned), new TreeSet<>(all), op[0] + " resumes for its own caller");
                assertEquals(List.of(all.get(1)), page(op[0], op[1], op[2], given()
                        .header("Authorization", as.apply(b)).formParam("Action", op[0])
                        .formParam("UserName", a).formParam("Marker", first.marker())).names(),
                        op[0] + " named explicitly is the same listing");
            }
        } finally {
            for (String user : users) {
                sshKeys.get(user).forEach(id -> call("DeleteSSHPublicKey", "UserName", user, "SSHPublicKeyId", id));
                keys.get(user).forEach(key -> call("DeleteAccessKey", "UserName", user, "AccessKeyId", key));
                call("DeleteUser", "UserName", user);
            }
        }
    }

    // =========================================================================
    // Every operation the generated table pages
    // =========================================================================

    /** Operations that cannot list two items: an account holds at most one alias. */
    private static final Set<String> AT_MOST_ONE_ITEM = Set.of("ListAccountAliases");

    private static final CertificateGenerator CERTIFICATES = new CertificateGenerator();
    private static final String THUMBPRINT = "9e99a48a9960b14926bb7f3b02e22da2b0ab7280";

    /** A request that lists at least two items of one operation, and what removes them again. */
    private record Fixture(List<String> params, Runnable cleanup) {
        static Fixture of(Runnable cleanup, String... params) {
            return new Fixture(List.of(params), cleanup);
        }
    }

    private record Listing(List<String> items, boolean truncated, String marker) {}

    /** The paginated operations of the vendored table, each with its paging entry. */
    private static Map<String, JsonNode> paginators() throws IOException {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("aws/iam-paginators.json")) {
            Map<String, JsonNode> found = new TreeMap<>();
            new ObjectMapper().readTree(in).path("operations").properties().forEach(entry -> {
                if (entry.getValue().path("paginator").asBoolean()) {
                    found.put(entry.getKey(), entry.getValue());
                }
            });
            return found;
        }
    }

    private static RequestSpecification listRequest(String action, Fixture fixture, String maxItems, String marker) {
        RequestSpecification request = iam(action);
        for (int i = 0; i < fixture.params().size(); i += 2) {
            request.formParam(fixture.params().get(i), fixture.params().get(i + 1));
        }
        if (maxItems != null) {
            request.formParam("MaxItems", maxItems);
        }
        return withMarker(request, marker);
    }

    /** One page, its items read from every result key the table names for the operation. */
    private static Listing listing(String action, JsonNode paginator, Fixture fixture, String maxItems, String marker) {
        String body = listRequest(action, fixture, maxItems, marker).when().post("/")
                .then().statusCode(200).extract().asString();
        Element result;
        try {
            result = (Element) XmlParser.parseDocument(body).getDocumentElement()
                    .getElementsByTagName(action + "Result").item(0);
        } catch (Exception e) {
            throw new IllegalStateException(action + " answered unparseable XML: " + body, e);
        }
        List<String> items = new ArrayList<>();
        for (JsonNode resultKey : paginator.path("resultKeys")) {
            Element list = child(result, resultKey.asText());
            for (Node item = list == null ? null : list.getFirstChild(); item != null; item = item.getNextSibling()) {
                if (item instanceof Element member) {
                    items.add(resultKey.asText() + ":" + member.getTextContent());
                }
            }
        }
        Element next = child(result, paginator.path("outputToken").asText());
        Element truncated = child(result, paginator.path("moreResults").asText());
        return new Listing(items, truncated != null && Boolean.parseBoolean(truncated.getTextContent()),
                next == null || next.getTextContent().isEmpty() ? null : next.getTextContent());
    }

    /** The direct child of {@code parent} named {@code name}, or null. */
    private static Element child(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && element.getTagName().equals(name)) {
                return element;
            }
        }
        return null;
    }

    private static List<String> walkFrom(String action, JsonNode paginator, Fixture fixture, String maxItems, String marker) {
        List<String> items = new ArrayList<>();
        do {
            Listing page = listing(action, paginator, fixture, maxItems, marker);
            assertEquals(page.truncated(), page.marker() != null, action + ": a Marker exactly when truncated");
            items.addAll(page.items());
            marker = page.marker();
        } while (marker != null);
        return items;
    }

    private static void refused(RequestSpecification request, String code) {
        request.when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo(code));
    }

    /**
     * botocore's {@code paginators-1.json} lists every IAM operation that pages, and the vendored
     * table carries each one. Each must hold {@code MaxItems} to the model's range, cut a page at
     * it, resume from the {@code Marker} it issued without losing or repeating an item, default to
     * the documented page size, and refuse a marker it did not issue. The fixture map has to name
     * exactly the table's operations, so one AWS starts paging later fails here until it has one.
     */
    /** {@code maxItemsType}'s range, which {@link IamRequestShapes} holds every paged request to. */
    private static final int MAX_ITEMS_MIN = 1;
    private static final int MAX_ITEMS_MAX = 1000;

    @Test
    void everyPaginatedOperationHonoursMaxItemsAndMarker() throws IOException {
        Map<String, JsonNode> paginators = paginators();
        Map<String, Supplier<Fixture>> fixtures = fixtures();
        assertEquals(paginators.keySet(), new TreeSet<>(fixtures.keySet()),
                "every operation the table pages has a fixture, and nothing else does");

        String foreignMarker = null;
        for (Map.Entry<String, JsonNode> entry : paginators.entrySet()) {
            String action = entry.getKey();
            JsonNode paginator = entry.getValue();
            int min = MAX_ITEMS_MIN;
            int max = MAX_ITEMS_MAX;
            Fixture fixture = fixtures.get(action).get();
            try {
                listRequest(action, fixture, Integer.toString(min - 1), null).when().post("/").then()
                    .statusCode(400)
                    .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                    .body("ErrorResponse.Error.Message", equalTo("1 validation error detected: "
                            + "Value at 'maxItems' failed to satisfy constraint: "
                            + "Member must have value greater than or equal to " + min));
                listRequest(action, fixture, Integer.toString(max + 1), null).when().post("/").then()
                    .statusCode(400)
                    .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                    .body("ErrorResponse.Error.Message", equalTo("1 validation error detected: "
                            + "Value at 'maxItems' failed to satisfy constraint: "
                            + "Member must have value less than or equal to " + max));

                List<String> all = walkFrom(action, paginator, fixture, Integer.toString(max), null);
                assertEquals(all.size(), new HashSet<>(all).size(), action + ": no item twice");

                Listing first = listing(action, paginator, fixture, "1", null);
                if (AT_MOST_ONE_ITEM.contains(action)) {
                    assertTrue(all.size() <= 1, action);
                    assertFalse(first.truncated(), action);
                } else {
                    assertTrue(all.size() >= 2, action + ": the fixture lists two items, not " + all);
                    assertEquals(1, first.items().size(), action + ": MaxItems=1 is one item");
                    assertTrue(first.truncated(), action + ": a cut page is truncated");
                    assertNotNull(first.marker(), action + ": a cut page carries a Marker");
                    List<String> resumed = new ArrayList<>(first.items());
                    resumed.addAll(walkFrom(action, paginator, fixture, Integer.toString(max), first.marker()));
                    assertEquals(all, resumed, action + ": the Marker resumes after the item it ended on");

                    if (foreignMarker != null) {
                        refused(listRequest(action, fixture, null, foreignMarker), "InvalidInput");
                    }
                    foreignMarker = first.marker();
                }

                Listing unlimited = listing(action, paginator, fixture, null, null);
                int pageSize = paginator.path("defaultItems").asInt();
                assertEquals(all.subList(0, Math.min(pageSize, all.size())), unlimited.items(),
                        action + ": an omitted MaxItems is the documented " + pageSize);
                assertEquals(all.size() > pageSize, unlimited.truncated(), action);

                refused(listRequest(action, fixture, null, "not-a-marker"), "InvalidInput");
                refused(listRequest(action, fixture, null, "1"), "InvalidInput");
            } catch (AssertionError e) {
                throw new AssertionError(action + ": " + e.getMessage(), e);
            } finally {
                fixture.cleanup().run();
            }
        }
    }

    private static String createUser(String name) {
        return iam("CreateUser").formParam("UserName", name).when().post("/").then().statusCode(200)
            .extract().path("CreateUserResponse.CreateUserResult.User.Arn");
    }

    private static void tag(String action, String idParam, String id) {
        call(action, idParam, id, "Tags.member.1.Key", "pg-a", "Tags.member.1.Value", "1",
                "Tags.member.2.Key", "pg-b", "Tags.member.2.Value", "2");
    }

    private static String createVirtualMfaDevice(String name) {
        return iam("CreateVirtualMFADevice").formParam("VirtualMFADeviceName", name).when().post("/").then()
            .statusCode(200).extract()
            .path("CreateVirtualMFADeviceResponse.CreateVirtualMFADeviceResult.VirtualMFADevice.SerialNumber");
    }

    private static void enableMfaDevice(String userName, String deviceName) {
        XmlPath created = iam("CreateVirtualMFADevice").formParam("VirtualMFADeviceName", deviceName)
            .when().post("/").then().statusCode(200).extract().xmlPath();
        String base = "CreateVirtualMFADeviceResponse.CreateVirtualMFADeviceResult.VirtualMFADevice.";
        String seed = new String(Base64.getDecoder().decode(created.getString(base + "Base32StringSeed").trim()),
                StandardCharsets.UTF_8);
        long step = Totp.stepAt(Instant.now());
        call("EnableMFADevice", "UserName", userName, "SerialNumber", created.getString(base + "SerialNumber"),
                "AuthenticationCode1", Totp.codeAt(seed, step - 1), "AuthenticationCode2", Totp.codeAt(seed, step));
    }

    /** Two distinct keys, since a user may not upload one twice; RSA generation is slow, so once per run. */
    private static final List<String> SSH_PUBLIC_KEYS = List.of(sshPublicKey(), sshPublicKey());

    private static String sshPublicKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return SshPublicKeys.toOpenSsh((RSAPublicKey) generator.generateKeyPair().getPublic());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String samlMetadata(String entityId) {
        return SamlMetadata.forEntity(entityId, "cGFnaW5n");
    }

    /** One fixture per paginated operation, each listing at least two items where it can. */
    private static Map<String, Supplier<Fixture>> fixtures() {
        Map<String, Supplier<Fixture>> fixtures = new HashMap<>();
        fixtures.put("GetAccountAuthorizationDetails", () -> {
            String a = "pg-gaad-a-" + suffix();
            String b = "pg-gaad-b-" + suffix();
            createUser(a);
            createUser(b);
            return Fixture.of(() -> {
                call("DeleteUser", "UserName", a);
                call("DeleteUser", "UserName", b);
            });
        });
        fixtures.put("GetGroup", () -> {
            String group = "pg-getgroup-" + suffix();
            List<String> users = List.of(group + "-u0", group + "-u1");
            call("CreateGroup", "GroupName", group);
            users.forEach(user -> {
                createUser(user);
                call("AddUserToGroup", "GroupName", group, "UserName", user);
            });
            return Fixture.of(() -> {
                users.forEach(user -> {
                    call("RemoveUserFromGroup", "GroupName", group, "UserName", user);
                    call("DeleteUser", "UserName", user);
                });
                call("DeleteGroup", "GroupName", group);
            }, "GroupName", group);
        });
        fixtures.put("ListAccessKeys", () -> {
            String user = "pg-keys-" + suffix();
            createUser(user);
            List<String> keys = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                keys.add(iam("CreateAccessKey").formParam("UserName", user).when().post("/").then().statusCode(200)
                    .extract().path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId"));
            }
            return Fixture.of(() -> {
                keys.forEach(key -> call("DeleteAccessKey", "UserName", user, "AccessKeyId", key));
                call("DeleteUser", "UserName", user);
            }, "UserName", user);
        });
        fixtures.put("ListAccountAliases", () -> Fixture.of(() -> { }));
        for (String[] holder : List.of(new String[]{"User", "UserName"}, new String[]{"Group", "GroupName"},
                new String[]{"Role", "RoleName"})) {
            Function<String, Runnable> createHolder = name -> {
                Runnable create = switch (holder[0]) {
                    case "User" -> () -> createUser(name);
                    case "Group" -> () -> call("CreateGroup", "GroupName", name);
                    default -> () -> createRole(name, "/");
                };
                create.run();
                return () -> call("Delete" + holder[0], holder[1], name);
            };
            fixtures.put("ListAttached" + holder[0] + "Policies", () -> {
                String name = "pg-attached-" + suffix();
                Runnable deleteHolder = createHolder.apply(name);
                List<String> arns = List.of(createPolicy(name + "-0", "/"), createPolicy(name + "-1", "/"));
                arns.forEach(arn -> call("Attach" + holder[0] + "Policy", holder[1], name, "PolicyArn", arn));
                return Fixture.of(() -> {
                    arns.forEach(arn -> {
                        call("Detach" + holder[0] + "Policy", holder[1], name, "PolicyArn", arn);
                        deletePolicy(arn);
                    });
                    deleteHolder.run();
                }, holder[1], name);
            });
            fixtures.put("List" + holder[0] + "Policies", () -> {
                String name = "pg-inline-" + suffix();
                Runnable deleteHolder = createHolder.apply(name);
                List<String> policies = List.of("pg-inline-0", "pg-inline-1");
                policies.forEach(policy -> call("Put" + holder[0] + "Policy", holder[1], name,
                        "PolicyName", policy, "PolicyDocument", POLICY_DOCUMENT));
                return Fixture.of(() -> {
                    policies.forEach(policy -> call("Delete" + holder[0] + "Policy", holder[1], name,
                            "PolicyName", policy));
                    deleteHolder.run();
                }, holder[1], name);
            });
        }
        fixtures.put("ListEntitiesForPolicy", () -> {
            String name = "pg-entities-" + suffix();
            String arn = createPolicy(name, "/");
            createUser(name);
            createRole(name, "/");
            call("AttachUserPolicy", "UserName", name, "PolicyArn", arn);
            call("AttachRolePolicy", "RoleName", name, "PolicyArn", arn);
            return Fixture.of(() -> {
                call("DetachUserPolicy", "UserName", name, "PolicyArn", arn);
                call("DetachRolePolicy", "RoleName", name, "PolicyArn", arn);
                call("DeleteUser", "UserName", name);
                deleteRole(name);
                deletePolicy(arn);
            }, "PolicyArn", arn);
        });
        for (String[] principal : List.of(new String[]{"Users", "User", "UserName"},
                new String[]{"Groups", "Group", "GroupName"}, new String[]{"Roles", "Role", "RoleName"})) {
            fixtures.put("List" + principal[0], () -> {
                String tag = suffix();
                String path = "/pg-" + principal[0].toLowerCase() + "-" + tag + "/";
                List<String> names = List.of("pg-" + tag + "-0", "pg-" + tag + "-1");
                for (String name : names) {
                    if (principal[1].equals("Role")) {
                        createRole(name, path);
                    } else {
                        call("Create" + principal[1], principal[2], name, "Path", path);
                    }
                }
                return Fixture.of(() -> names.forEach(name -> call("Delete" + principal[1], principal[2], name)),
                        "PathPrefix", path);
            });
        }
        fixtures.put("ListGroupsForUser", () -> {
            String user = "pg-memberships-" + suffix();
            List<String> groups = List.of(user + "-g0", user + "-g1");
            createUser(user);
            groups.forEach(group -> {
                call("CreateGroup", "GroupName", group);
                call("AddUserToGroup", "GroupName", group, "UserName", user);
            });
            return Fixture.of(() -> {
                groups.forEach(group -> {
                    call("RemoveUserFromGroup", "GroupName", group, "UserName", user);
                    call("DeleteGroup", "GroupName", group);
                });
                call("DeleteUser", "UserName", user);
            }, "UserName", user);
        });
        fixtures.put("ListInstanceProfiles", () -> {
            String tag = suffix();
            String path = "/pg-profiles-" + tag + "/";
            List<String> profiles = List.of("pg-profile-" + tag + "-0", "pg-profile-" + tag + "-1");
            profiles.forEach(profile -> call("CreateInstanceProfile", "InstanceProfileName", profile, "Path", path));
            return Fixture.of(() -> profiles.forEach(profile -> call("DeleteInstanceProfile",
                    "InstanceProfileName", profile)), "PathPrefix", path);
        });
        fixtures.put("ListInstanceProfilesForRole", () -> {
            String role = "pg-profiled-" + suffix();
            List<String> profiles = List.of(role + "-0", role + "-1");
            createRole(role, "/");
            profiles.forEach(profile -> {
                call("CreateInstanceProfile", "InstanceProfileName", profile);
                call("AddRoleToInstanceProfile", "InstanceProfileName", profile, "RoleName", role);
            });
            return Fixture.of(() -> {
                profiles.forEach(profile -> {
                    call("RemoveRoleFromInstanceProfile", "InstanceProfileName", profile, "RoleName", role);
                    call("DeleteInstanceProfile", "InstanceProfileName", profile);
                });
                deleteRole(role);
            }, "RoleName", role);
        });
        fixtures.put("ListMFADevices", () -> {
            String user = "pg-mfa-" + suffix();
            createUser(user);
            enableMfaDevice(user, user + "-0");
            enableMfaDevice(user, user + "-1");
            return Fixture.of(() -> {
                List<String> serials = iam("ListMFADevices").formParam("UserName", user).when().post("/").then()
                    .statusCode(200).extract().xmlPath()
                    .getList("ListMFADevicesResponse.ListMFADevicesResult.MFADevices.member.SerialNumber", String.class);
                serials.forEach(serial -> {
                    call("DeactivateMFADevice", "UserName", user, "SerialNumber", serial);
                    call("DeleteVirtualMFADevice", "SerialNumber", serial);
                });
                call("DeleteUser", "UserName", user);
            }, "UserName", user);
        });
        fixtures.put("ListVirtualMFADevices", () -> {
            String name = "pg-virtual-" + suffix();
            List<String> serials = List.of(createVirtualMfaDevice(name + "-0"), createVirtualMfaDevice(name + "-1"));
            return Fixture.of(() -> serials.forEach(serial -> call("DeleteVirtualMFADevice", "SerialNumber", serial)));
        });
        fixtures.put("ListPolicies", () -> {
            String tag = suffix();
            String path = "/pg-policies-" + tag + "/";
            List<String> arns = List.of(createPolicy("pg-" + tag + "-0", path), createPolicy("pg-" + tag + "-1", path));
            return Fixture.of(() -> arns.forEach(IamListPaginationIntegrationTest::deletePolicy),
                    "Scope", "Local", "PathPrefix", path);
        });
        fixtures.put("ListPolicyVersions", () -> {
            String arn = createPolicy("pg-versions-" + suffix(), "/");
            call("CreatePolicyVersion", "PolicyArn", arn, "PolicyDocument", POLICY_DOCUMENT);
            return Fixture.of(() -> {
                call("DeletePolicyVersion", "PolicyArn", arn, "VersionId", "v2");
                deletePolicy(arn);
            }, "PolicyArn", arn);
        });
        fixtures.put("ListServerCertificates", () -> {
            String tag = suffix();
            String path = "/pg-certs-" + tag + "/";
            List<String> names = List.of("pg-cert-" + tag + "-0", "pg-cert-" + tag + "-1");
            names.forEach(name -> uploadServerCertificate(name, path));
            return Fixture.of(() -> names.forEach(name -> call("DeleteServerCertificate", "ServerCertificateName", name)),
                    "PathPrefix", path);
        });
        fixtures.put("ListSigningCertificates", () -> {
            String user = "pg-signing-" + suffix();
            createUser(user);
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                ids.add(iam("UploadSigningCertificate").formParam("UserName", user)
                        .formParam("CertificateBody", CERTIFICATES.generateSelfSignedCertificate(
                                "signing.test.local", List.of(), KeyAlgorithm.EC_prime256v1).certificatePem())
                    .when().post("/").then().statusCode(200).extract()
                    .path("UploadSigningCertificateResponse.UploadSigningCertificateResult.Certificate.CertificateId"));
            }
            return Fixture.of(() -> {
                ids.forEach(id -> call("DeleteSigningCertificate", "UserName", user, "CertificateId", id));
                call("DeleteUser", "UserName", user);
            }, "UserName", user);
        });
        fixtures.put("ListSSHPublicKeys", () -> {
            String user = "pg-ssh-" + suffix();
            createUser(user);
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                ids.add(iam("UploadSSHPublicKey").formParam("UserName", user).formParam("SSHPublicKeyBody", SSH_PUBLIC_KEYS.get(i))
                    .when().post("/").then().statusCode(200).extract()
                    .path("UploadSSHPublicKeyResponse.UploadSSHPublicKeyResult.SSHPublicKey.SSHPublicKeyId"));
            }
            return Fixture.of(() -> {
                ids.forEach(id -> call("DeleteSSHPublicKey", "UserName", user, "SSHPublicKeyId", id));
                call("DeleteUser", "UserName", user);
            }, "UserName", user);
        });
        fixtures.put("SimulateCustomPolicy", () -> Fixture.of(() -> { },
                "PolicyInputList.member.1", POLICY_DOCUMENT,
                "ActionNames.member.1", "s3:GetObject", "ActionNames.member.2", "s3:PutObject"));
        fixtures.put("SimulatePrincipalPolicy", () -> {
            String user = "pg-simulate-" + suffix();
            String arn = createUser(user);
            return Fixture.of(() -> call("DeleteUser", "UserName", user), "PolicySourceArn", arn,
                    "ActionNames.member.1", "s3:GetObject", "ActionNames.member.2", "s3:PutObject");
        });
        fixtures.put("ListUserTags", () -> {
            String user = "pg-usertags-" + suffix();
            createUser(user);
            tag("TagUser", "UserName", user);
            return Fixture.of(() -> call("DeleteUser", "UserName", user), "UserName", user);
        });
        fixtures.put("ListRoleTags", () -> {
            String role = "pg-roletags-" + suffix();
            createRole(role, "/");
            tag("TagRole", "RoleName", role);
            return Fixture.of(() -> deleteRole(role), "RoleName", role);
        });
        fixtures.put("ListPolicyTags", () -> {
            String arn = createPolicy("pg-policytags-" + suffix(), "/");
            tag("TagPolicy", "PolicyArn", arn);
            return Fixture.of(() -> deletePolicy(arn), "PolicyArn", arn);
        });
        fixtures.put("ListInstanceProfileTags", () -> {
            String profile = "pg-profiletags-" + suffix();
            call("CreateInstanceProfile", "InstanceProfileName", profile);
            tag("TagInstanceProfile", "InstanceProfileName", profile);
            return Fixture.of(() -> call("DeleteInstanceProfile", "InstanceProfileName", profile),
                    "InstanceProfileName", profile);
        });
        fixtures.put("ListMFADeviceTags", () -> {
            String serial = createVirtualMfaDevice("pg-mfatags-" + suffix());
            tag("TagMFADevice", "SerialNumber", serial);
            return Fixture.of(() -> call("DeleteVirtualMFADevice", "SerialNumber", serial), "SerialNumber", serial);
        });
        fixtures.put("ListOpenIDConnectProviderTags", () -> {
            String arn = iam("CreateOpenIDConnectProvider").formParam("Url", "https://pg-" + suffix() + ".example.test")
                    .formParam("ThumbprintList.member.1", THUMBPRINT)
                .when().post("/").then().statusCode(200).extract()
                    .path("CreateOpenIDConnectProviderResponse.CreateOpenIDConnectProviderResult.OpenIDConnectProviderArn");
            tag("TagOpenIDConnectProvider", "OpenIDConnectProviderArn", arn);
            return Fixture.of(() -> call("DeleteOpenIDConnectProvider", "OpenIDConnectProviderArn", arn),
                    "OpenIDConnectProviderArn", arn);
        });
        fixtures.put("ListSAMLProviderTags", () -> {
            String name = "pg-saml-" + suffix();
            String arn = iam("CreateSAMLProvider").formParam("Name", name)
                    .formParam("SAMLMetadataDocument", samlMetadata("https://idp.example.test/" + name))
                .when().post("/").then().statusCode(200).extract()
                    .path("CreateSAMLProviderResponse.CreateSAMLProviderResult.SAMLProviderArn");
            tag("TagSAMLProvider", "SAMLProviderArn", arn);
            return Fixture.of(() -> call("DeleteSAMLProvider", "SAMLProviderArn", arn), "SAMLProviderArn", arn);
        });
        fixtures.put("ListServerCertificateTags", () -> {
            String name = "pg-certtags-" + suffix();
            uploadServerCertificate(name, "/");
            tag("TagServerCertificate", "ServerCertificateName", name);
            return Fixture.of(() -> call("DeleteServerCertificate", "ServerCertificateName", name),
                    "ServerCertificateName", name);
        });
        return fixtures;
    }

    private static void uploadServerCertificate(String name, String path) {
        CertificateGenerator.GeneratedCertificate pair = CERTIFICATES.generateSelfSignedCertificate(
                "certs.test.local", List.of(), KeyAlgorithm.EC_prime256v1);
        call("UploadServerCertificate", "ServerCertificateName", name, "Path", path,
                "CertificateBody", pair.certificatePem(), "PrivateKey", pair.privateKeyPem());
    }
}
