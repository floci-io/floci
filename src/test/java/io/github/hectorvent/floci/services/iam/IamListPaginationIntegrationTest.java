package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ListRoles, ListPolicies, ListUsers, ListGroups and the attached and inline policy lists of users,
 * groups and roles page through {@code MaxItems} and {@code Marker}. Every one of these requests models
 * {@code MaxItems} with a range of 1 to 1000 and document that an omitted value "defaults to 100".
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
        names.forEach(name -> createRole(name, path));
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
            names.forEach(IamListPaginationIntegrationTest::deleteRole);
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

    @Test
    void pagedListsRejectMaxItemsOutsideOneToOneThousand() {
        for (String action : List.of("ListRoles", "ListPolicies", "ListUsers", "ListGroups")) {
            iam(action).formParam("MaxItems", "0")
            .when().post("/").then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message", equalTo(
                        "1 validation error detected: Value '0' at 'maxItems' failed to satisfy "
                                + "constraint: Member must have value greater than or equal to 1"));

            iam(action).formParam("MaxItems", "1001")
            .when().post("/").then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message", equalTo(
                        "1 validation error detected: Value '1001' at 'maxItems' failed to satisfy "
                                + "constraint: Member must have value less than or equal to 1000"));

            iam(action).formParam("MaxItems", "1000").when().post("/").then().statusCode(200);
        }
    }

    @Test
    void pagedListsRejectAMarkerTheyDidNotIssue() {
        for (String action : List.of("ListRoles", "ListPolicies", "ListUsers", "ListGroups")) {
            iam(action).formParam("Marker", "not-a-marker")
            .when().post("/").then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidInput"));
        }
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

    @Test
    void listUsersAndListGroupsDefaultToOneHundredItems() {
        String tag = suffix();
        String path = "/pg-principals-default-" + tag + "/";
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            names.add(String.format("pg-default-%s-%03d", tag, i));
        }
        for (String name : names) {
            call("CreateUser", "UserName", name, "Path", path);
            call("CreateGroup", "GroupName", name, "Path", path);
        }
        try {
            for (String[] list : List.of(new String[]{"ListUsers", "Users", "UserName"},
                    new String[]{"ListGroups", "Groups", "GroupName"})) {
                Page first = page(list[0], list[1], list[2], iam(list[0]).formParam("PathPrefix", path));
                assertEquals(names.subList(0, 100), first.names(), list[0]);
                assertTrue(first.truncated(), list[0]);
                assertNotNull(first.marker(), list[0]);

                Page second = page(list[0], list[1], list[2], iam(list[0]).formParam("PathPrefix", path)
                        .formParam("Marker", first.marker()));
                assertEquals(List.of(names.get(100)), second.names(), list[0]);
                assertFalse(second.truncated(), list[0]);
                assertNull(second.marker(), list[0]);
            }
        } finally {
            for (String name : names) {
                call("DeleteUser", "UserName", name);
                call("DeleteGroup", "GroupName", name);
            }
        }
    }

    /**
     * These lists have only ever issued signed markers, so a bare number is not an offset into
     * them: it is refused like any other marker they did not issue.
     */
    @Test
    void newlyPagedListsRejectAnUnsignedNumericMarker() {
        String name = "pg-numeric-" + suffix();
        createRole(name, "/");
        call("CreateUser", "UserName", name);
        call("CreateGroup", "GroupName", name);
        try {
            List<String[]> requests = List.of(new String[]{"ListUsers"}, new String[]{"ListGroups"},
                    new String[]{"ListAttachedUserPolicies", "UserName"},
                    new String[]{"ListAttachedGroupPolicies", "GroupName"},
                    new String[]{"ListAttachedRolePolicies", "RoleName"},
                    new String[]{"ListUserPolicies", "UserName"},
                    new String[]{"ListGroupPolicies", "GroupName"},
                    new String[]{"ListRolePolicies", "RoleName"});
            for (String[] r : requests) {
                RequestSpecification request = iam(r[0]).formParam("Marker", "1");
                if (r.length > 1) {
                    request.formParam(r[1], name);
                }
                request.when().post("/").then()
                    .statusCode(400)
                    .body("ErrorResponse.Error.Code", equalTo("InvalidInput"));
            }
        } finally {
            deleteRole(name);
            call("DeleteUser", "UserName", name);
            call("DeleteGroup", "GroupName", name);
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
}
