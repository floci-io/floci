package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the Access Advisor job operations and ListPoliciesGrantingServiceAccess.
 *
 * <p>Floci records no service-access history, so no report here carries an access timestamp. The
 * service list itself is real, though: AWS lists a service the entity could reach even when it was
 * never used, and that list comes from permissions-policy logic, which Floci can evaluate. So the
 * assertions cover both halves, the policy-derived list and the absent attempt details.
 *
 * <p>IAM state is shared across the suite, so every test names its own entities uniquely.
 */
@QuarkusTest
class ServiceLastAccessedIntegrationTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request";

    private static final String S3_POLICY =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}";

    private static final String TRUST_POLICY =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Principal\":{\"Service\":\"ec2.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";

    private static RequestSpecification iam(String action) {
        return given().header("Authorization", IAM_AUTH).formParam("Action", action);
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private static String createUser(String userName) {
        iam("CreateUser").formParam("UserName", userName)
        .when().post("/").then().statusCode(200);
        return "arn:aws:iam::000000000000:user/" + userName;
    }

    private static String generateJobFor(String arn) {
        return iam("GenerateServiceLastAccessedDetails").formParam("Arn", arn)
            .when().post("/").then().statusCode(200)
            .extract().path("GenerateServiceLastAccessedDetailsResponse."
                    + "GenerateServiceLastAccessedDetailsResult.JobId");
    }

    @Test
    void generateServiceLastAccessedDetailsReturnsAJobIdOfTheModeledLength() {
        String arn = createUser("laa-gen-" + suffix());

        String jobId = generateJobFor(arn);

        // jobIDType is min 36 / max 36, so a client buffer sized to the model must fit it exactly.
        assertEquals(36, jobId.length(), "expected a 36-character job id, got: " + jobId);
    }

    @Test
    void generateServiceLastAccessedDetailsAcceptsRoleGroupAndPolicyArns() {
        String tag = suffix();
        String roleName = "laa-role-" + tag;
        iam("CreateRole").formParam("RoleName", roleName).formParam("Path", "/")
            .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
        .when().post("/").then().statusCode(200);
        String groupName = "laa-group-" + tag;
        iam("CreateGroup").formParam("GroupName", groupName)
        .when().post("/").then().statusCode(200);
        String policyArn = iam("CreatePolicy").formParam("PolicyName", "laa-policy-" + tag)
            .formParam("PolicyDocument", S3_POLICY)
        .when().post("/").then().statusCode(200)
            .extract().path("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");

        for (String arn : new String[] {
                "arn:aws:iam::000000000000:role/" + roleName,
                "arn:aws:iam::000000000000:group/" + groupName,
                policyArn }) {
            assertEquals(36, generateJobFor(arn).length(), "expected a job id for " + arn);
        }
    }

    @Test
    void generateServiceLastAccessedDetailsForAnUnknownEntityReturnsNoSuchEntity() {
        iam("GenerateServiceLastAccessedDetails")
            .formParam("Arn", "arn:aws:iam::000000000000:user/no-such-user-" + suffix())
        .when().post("/").then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    @Test
    void generateServiceLastAccessedDetailsRejectsANonIamArn() {
        iam("GenerateServiceLastAccessedDetails")
            .formParam("Arn", "arn:aws:s3:::some-bucket")
        .when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("InvalidInput"));
    }

    @Test
    void generateServiceLastAccessedDetailsRejectsAnUnmodeledGranularity() {
        String arn = createUser("laa-granularity-" + suffix());

        iam("GenerateServiceLastAccessedDetails").formParam("Arn", arn)
            .formParam("Granularity", "MINUTE_LEVEL")
        .when().post("/").then()
            .statusCode(400)
            .body(containsString("ValidationError"));
    }

    /**
     * AWS lists a service the entity could reach even when it was never used, leaving the
     * attempt details null rather than dropping the service, so the list reflects the entity's
     * policies while the timestamps stay absent.
     */
    @Test
    void getServiceLastAccessedDetailsListsGrantedServicesWithNoAttemptDetails() {
        String tag = suffix();
        String userName = "laa-get-" + tag;
        String arn = createUser(userName);
        iam("PutUserPolicy").formParam("UserName", userName)
            .formParam("PolicyName", "laa-get-policy-" + tag)
            .formParam("PolicyDocument", S3_POLICY)
        .when().post("/").then().statusCode(200);
        String jobId = generateJobFor(arn);

        String body = iam("GetServiceLastAccessedDetails").formParam("JobId", jobId)
            .when().post("/").then()
                .statusCode(200)
                .body("GetServiceLastAccessedDetailsResponse.GetServiceLastAccessedDetailsResult.JobStatus",
                        equalTo("COMPLETED"))
                .body("GetServiceLastAccessedDetailsResponse.GetServiceLastAccessedDetailsResult.JobType",
                        equalTo("SERVICE_LEVEL"))
                .body(containsString("<ServiceNamespace>s3</ServiceNamespace>"))
                .extract().asString();

        // Required members must be present even though nothing was ever accessed.
        assertTrue(body.contains("<JobCreationDate>") && body.contains("<JobCompletionDate>"),
                "expected both job timestamps in: " + body);
        // Nothing accessed the service, so AWS leaves these two out entirely.
        assertTrue(!body.contains("<LastAuthenticated>"),
                "no access is recorded, so LastAuthenticated must be absent, in: " + body);
        assertTrue(!body.contains("<TotalAuthenticatedEntities>"),
                "no access is recorded, so TotalAuthenticatedEntities must be absent, in: " + body);
    }

    /** A service the entity's policies never mention is not part of its report. */
    @Test
    void getServiceLastAccessedDetailsOmitsServicesTheEntityCannotReach() {
        String tag = suffix();
        String userName = "laa-scope-" + tag;
        String arn = createUser(userName);
        iam("PutUserPolicy").formParam("UserName", userName)
            .formParam("PolicyName", "laa-scope-policy-" + tag)
            .formParam("PolicyDocument", S3_POLICY)
        .when().post("/").then().statusCode(200);

        iam("GetServiceLastAccessedDetails").formParam("JobId", generateJobFor(arn))
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<ServiceNamespace>s3</ServiceNamespace>"))
            .body(not(containsString("<ServiceNamespace>dynamodb</ServiceNamespace>")));
    }

    /** A managed-policy ARN reports on that policy's own document, not on any identity. */
    @Test
    void getServiceLastAccessedDetailsForAPolicyArnUsesThatPolicysDocument() {
        String tag = suffix();
        String policyArn = iam("CreatePolicy").formParam("PolicyName", "laa-policy-report-" + tag)
            .formParam("PolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                    + "\"Action\":\"dynamodb:GetItem\",\"Resource\":\"*\"}]}")
        .when().post("/").then().statusCode(200)
            .extract().path("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");

        iam("GetServiceLastAccessedDetails").formParam("JobId", generateJobFor(policyArn))
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<ServiceNamespace>dynamodb</ServiceNamespace>"));
    }

    @Test
    void getServiceLastAccessedDetailsEchoesTheRequestedGranularity() {
        String arn = createUser("laa-action-level-" + suffix());
        String jobId = iam("GenerateServiceLastAccessedDetails").formParam("Arn", arn)
            .formParam("Granularity", "ACTION_LEVEL")
        .when().post("/").then().statusCode(200)
            .extract().path("GenerateServiceLastAccessedDetailsResponse."
                    + "GenerateServiceLastAccessedDetailsResult.JobId");

        iam("GetServiceLastAccessedDetails").formParam("JobId", jobId)
        .when().post("/").then()
            .statusCode(200)
            .body("GetServiceLastAccessedDetailsResponse.GetServiceLastAccessedDetailsResult.JobType",
                    equalTo("ACTION_LEVEL"));
    }

    @Test
    void getServiceLastAccessedDetailsForAnUnknownJobReturnsNoSuchEntity() {
        iam("GetServiceLastAccessedDetails")
            .formParam("JobId", "00000000-0000-0000-0000-000000000000")
        .when().post("/").then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    /**
     * AWS reports the entities that could have used the permissions to reach the service, which is
     * policy-derived, so a user report names that user with no access timestamp.
     */
    @Test
    void getServiceLastAccessedDetailsWithEntitiesNamesTheReportedUser() {
        String tag = suffix();
        String userName = "laa-entities-" + tag;
        String arn = createUser(userName);
        iam("PutUserPolicy").formParam("UserName", userName)
            .formParam("PolicyName", "laa-entities-policy-" + tag)
            .formParam("PolicyDocument", S3_POLICY)
        .when().post("/").then().statusCode(200);

        String body = iam("GetServiceLastAccessedDetailsWithEntities")
            .formParam("JobId", generateJobFor(arn))
            .formParam("ServiceNamespace", "s3")
        .when().post("/").then()
            .statusCode(200)
            .body("GetServiceLastAccessedDetailsWithEntitiesResponse."
                    + "GetServiceLastAccessedDetailsWithEntitiesResult.JobStatus", equalTo("COMPLETED"))
            .body(containsString("<Name>" + userName + "</Name>"))
            .body(containsString("<Type>USER</Type>"))
            .extract().asString();

        assertTrue(!body.contains("<LastAuthenticated>"),
                "no access is recorded, so LastAuthenticated must be absent, in: " + body);
    }

    /** A group report names the group's users, not the group, since users hold its permissions. */
    @Test
    void getServiceLastAccessedDetailsWithEntitiesForAGroupNamesItsUsers() {
        String tag = suffix();
        String groupName = "laa-ent-group-" + tag;
        String userName = "laa-ent-member-" + tag;
        createUser(userName);
        iam("CreateGroup").formParam("GroupName", groupName)
        .when().post("/").then().statusCode(200);
        iam("AddUserToGroup").formParam("GroupName", groupName).formParam("UserName", userName)
        .when().post("/").then().statusCode(200);
        iam("PutGroupPolicy").formParam("GroupName", groupName)
            .formParam("PolicyName", "laa-ent-group-policy-" + tag)
            .formParam("PolicyDocument", S3_POLICY)
        .when().post("/").then().statusCode(200);

        iam("GetServiceLastAccessedDetailsWithEntities")
            .formParam("JobId", generateJobFor("arn:aws:iam::000000000000:group/" + groupName))
            .formParam("ServiceNamespace", "s3")
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<Name>" + userName + "</Name>"))
            .body(containsString("<Type>USER</Type>"));
    }

    /** A policy report names the users and roles the policy is attached to. */
    @Test
    void getServiceLastAccessedDetailsWithEntitiesForAPolicyNamesItsAttachedEntities() {
        String tag = suffix();
        String userName = "laa-ent-attached-" + tag;
        createUser(userName);
        String policyArn = iam("CreatePolicy").formParam("PolicyName", "laa-ent-policy-" + tag)
            .formParam("PolicyDocument", S3_POLICY)
        .when().post("/").then().statusCode(200)
            .extract().path("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");
        iam("AttachUserPolicy").formParam("UserName", userName).formParam("PolicyArn", policyArn)
        .when().post("/").then().statusCode(200);

        iam("GetServiceLastAccessedDetailsWithEntities")
            .formParam("JobId", generateJobFor(policyArn))
            .formParam("ServiceNamespace", "s3")
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<Name>" + userName + "</Name>"));
    }

    /** A service the reported permissions never grant yields no entities at all. */
    @Test
    void getServiceLastAccessedDetailsWithEntitiesOmitsEntitiesForAnUngrantedService() {
        String tag = suffix();
        String userName = "laa-ent-ungranted-" + tag;
        String arn = createUser(userName);
        iam("PutUserPolicy").formParam("UserName", userName)
            .formParam("PolicyName", "laa-ent-ungranted-policy-" + tag)
            .formParam("PolicyDocument", S3_POLICY)
        .when().post("/").then().statusCode(200);

        iam("GetServiceLastAccessedDetailsWithEntities")
            .formParam("JobId", generateJobFor(arn))
            .formParam("ServiceNamespace", "dynamodb")
        .when().post("/").then()
            .statusCode(200)
            .body(not(containsString("<Name>" + userName + "</Name>")));
    }

    @Test
    void getServiceLastAccessedDetailsWithEntitiesForAnUnknownJobReturnsNoSuchEntity() {
        iam("GetServiceLastAccessedDetailsWithEntities")
            .formParam("JobId", "00000000-0000-0000-0000-000000000000")
            .formParam("ServiceNamespace", "s3")
        .when().post("/").then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    @Test
    void listPoliciesGrantingServiceAccessReturnsAnAttachedManagedPolicy() {
        String tag = suffix();
        String userName = "laa-managed-" + tag;
        String arn = createUser(userName);
        String policyArn = iam("CreatePolicy").formParam("PolicyName", "laa-granting-" + tag)
            .formParam("PolicyDocument", S3_POLICY)
        .when().post("/").then().statusCode(200)
            .extract().path("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");
        iam("AttachUserPolicy").formParam("UserName", userName).formParam("PolicyArn", policyArn)
        .when().post("/").then().statusCode(200);

        iam("ListPoliciesGrantingServiceAccess").formParam("Arn", arn)
            .formParam("ServiceNamespaces.member.1", "s3")
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<ServiceNamespace>s3</ServiceNamespace>"))
            .body(containsString("<PolicyType>MANAGED</PolicyType>"))
            .body(containsString(policyArn));
    }

    @Test
    void listPoliciesGrantingServiceAccessIdentifiesAnInlinePolicyByItsEntityNotAnArn() {
        String tag = suffix();
        String userName = "laa-inline-" + tag;
        String arn = createUser(userName);
        iam("PutUserPolicy").formParam("UserName", userName)
            .formParam("PolicyName", "laa-inline-policy-" + tag)
            .formParam("PolicyDocument", S3_POLICY)
        .when().post("/").then().statusCode(200);

        String body = iam("ListPoliciesGrantingServiceAccess").formParam("Arn", arn)
            .formParam("ServiceNamespaces.member.1", "s3")
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<PolicyType>INLINE</PolicyType>"))
            .body(containsString("<EntityType>USER</EntityType>"))
            .body(containsString("<EntityName>" + userName + "</EntityName>"))
            .extract().asString();

        // Inline policies have no ARN, so none must be emitted for one.
        assertTrue(!body.contains("<PolicyArn>"), "inline policies carry no ARN, in: " + body);
    }

    @Test
    void listPoliciesGrantingServiceAccessIncludesPoliciesInheritedFromAUsersGroup() {
        String tag = suffix();
        String userName = "laa-inherit-" + tag;
        String arn = createUser(userName);
        String groupName = "laa-inherit-group-" + tag;
        iam("CreateGroup").formParam("GroupName", groupName)
        .when().post("/").then().statusCode(200);
        iam("AddUserToGroup").formParam("GroupName", groupName).formParam("UserName", userName)
        .when().post("/").then().statusCode(200);
        iam("PutGroupPolicy").formParam("GroupName", groupName)
            .formParam("PolicyName", "laa-group-policy-" + tag)
            .formParam("PolicyDocument", S3_POLICY)
        .when().post("/").then().statusCode(200);

        iam("ListPoliciesGrantingServiceAccess").formParam("Arn", arn)
            .formParam("ServiceNamespaces.member.1", "s3")
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<EntityType>GROUP</EntityType>"))
            .body(containsString("<EntityName>" + groupName + "</EntityName>"));
    }

    /** AWS: for a group ARN, a policy attached to one of its users is not included. */
    @Test
    void listPoliciesGrantingServiceAccessForAGroupExcludesItsUsersOwnPolicies() {
        String tag = suffix();
        String userName = "laa-groupscope-user-" + tag;
        createUser(userName);
        String groupName = "laa-groupscope-" + tag;
        iam("CreateGroup").formParam("GroupName", groupName)
        .when().post("/").then().statusCode(200);
        iam("AddUserToGroup").formParam("GroupName", groupName).formParam("UserName", userName)
        .when().post("/").then().statusCode(200);
        String userPolicyName = "laa-groupscope-userpolicy-" + tag;
        iam("PutUserPolicy").formParam("UserName", userName)
            .formParam("PolicyName", userPolicyName)
            .formParam("PolicyDocument", S3_POLICY)
        .when().post("/").then().statusCode(200);

        // Positive control first: the policy really is discoverable through the user's own ARN,
        // so its absence from the group's answer below means scoping, not a missing policy.
        iam("ListPoliciesGrantingServiceAccess")
            .formParam("Arn", "arn:aws:iam::000000000000:user/" + userName)
            .formParam("ServiceNamespaces.member.1", "s3")
        .when().post("/").then()
            .statusCode(200)
            .body(containsString(userPolicyName));

        iam("ListPoliciesGrantingServiceAccess")
            .formParam("Arn", "arn:aws:iam::000000000000:group/" + groupName)
            .formParam("ServiceNamespaces.member.1", "s3")
        .when().post("/").then()
            .statusCode(200)
            .body(not(containsString(userPolicyName)));
    }

    @Test
    void listPoliciesGrantingServiceAccessReturnsAnEmptyPolicyListForAnUngrantedService() {
        String tag = suffix();
        String userName = "laa-ungranted-" + tag;
        String arn = createUser(userName);
        String policyName = "laa-ungranted-policy-" + tag;
        iam("PutUserPolicy").formParam("UserName", userName)
            .formParam("PolicyName", policyName)
            .formParam("PolicyDocument", S3_POLICY)
        .when().post("/").then().statusCode(200);

        // The namespace is still echoed back, with no policies under it.
        iam("ListPoliciesGrantingServiceAccess").formParam("Arn", arn)
            .formParam("ServiceNamespaces.member.1", "dynamodb")
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<ServiceNamespace>dynamodb</ServiceNamespace>"))
            .body(not(containsString(policyName)));
    }

    /** {@code Allow} with {@code NotAction} grants everything it does not carve out. */
    @Test
    void listPoliciesGrantingServiceAccessHonoursNotActionGrants() {
        String tag = suffix();
        String userName = "laa-notaction-" + tag;
        String arn = createUser(userName);
        String granted = "laa-notaction-granted-" + tag;
        iam("PutUserPolicy").formParam("UserName", userName)
            .formParam("PolicyName", granted)
            .formParam("PolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                    + "\"NotAction\":\"iam:*\",\"Resource\":\"*\"}]}")
        .when().post("/").then().statusCode(200);

        // s3 is not carved out, so the policy still grants it.
        iam("ListPoliciesGrantingServiceAccess").formParam("Arn", arn)
            .formParam("ServiceNamespaces.member.1", "s3")
        .when().post("/").then()
            .statusCode(200)
            .body(containsString(granted));

        // iam is carved out entirely, so it does not.
        iam("ListPoliciesGrantingServiceAccess").formParam("Arn", arn)
            .formParam("ServiceNamespaces.member.1", "iam")
        .when().post("/").then()
            .statusCode(200)
            .body(not(containsString(granted)));
    }

    /** A Deny-only policy grants nothing, so it is not a policy "granting service access". */
    @Test
    void listPoliciesGrantingServiceAccessExcludesADenyOnlyPolicy() {
        String tag = suffix();
        String userName = "laa-deny-" + tag;
        String arn = createUser(userName);
        String denyName = "laa-deny-policy-" + tag;
        iam("PutUserPolicy").formParam("UserName", userName)
            .formParam("PolicyName", denyName)
            .formParam("PolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Deny\","
                    + "\"Action\":\"s3:*\",\"Resource\":\"*\"}]}")
        .when().post("/").then().statusCode(200);

        iam("ListPoliciesGrantingServiceAccess").formParam("Arn", arn)
            .formParam("ServiceNamespaces.member.1", "s3")
        .when().post("/").then()
            .statusCode(200)
            .body(not(containsString(denyName)));
    }

    @Test
    void listPoliciesGrantingServiceAccessRequiresAtLeastOneServiceNamespace() {
        String arn = createUser("laa-nonamespace-" + suffix());

        iam("ListPoliciesGrantingServiceAccess").formParam("Arn", arn)
        .when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("InvalidInput"));
    }


    /**
     * A name is unique within an account, so a lookup by name alone would resolve a same-named
     * local identity for an ARN naming a different account. The account in the ARN has to be
     * checked, or one account's report answers for another's ARN.
     */
    @Test
    void generateServiceLastAccessedDetailsRejectsAnArnFromAnotherAccount() {
        String userName = "laa-cross-account-" + suffix();
        createUser(userName);

        iam("GenerateServiceLastAccessedDetails")
            .formParam("Arn", "arn:aws:iam::999999999999:user/" + userName)
        .when().post("/").then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    /** An ARN carrying a path the entity does not have names nothing in AWS. */
    @Test
    void generateServiceLastAccessedDetailsRejectsAnArnWithTheWrongPath() {
        String userName = "laa-wrong-path-" + suffix();
        createUser(userName);

        iam("GenerateServiceLastAccessedDetails")
            .formParam("Arn", "arn:aws:iam::000000000000:user/engineering/" + userName)
        .when().post("/").then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    @Test
    void listPoliciesGrantingServiceAccessRejectsAnArnFromAnotherAccount() {
        String userName = "laa-cross-list-" + suffix();
        createUser(userName);

        iam("ListPoliciesGrantingServiceAccess")
            .formParam("Arn", "arn:aws:iam::999999999999:user/" + userName)
            .formParam("ServiceNamespaces.member.1", "s3")
        .when().post("/").then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    /**
     * AWS fixes a report when the job runs and the readers only retrieve it, so editing the
     * policies afterwards must not change what an already-completed job answers.
     */
    @Test
    void aCompletedReportDoesNotChangeWhenThePoliciesDo() {
        String tag = suffix();
        String userName = "laa-snapshot-" + tag;
        String arn = createUser(userName);
        iam("PutUserPolicy").formParam("UserName", userName)
            .formParam("PolicyName", "laa-snapshot-policy-" + tag)
            .formParam("PolicyDocument", S3_POLICY)
        .when().post("/").then().statusCode(200);
        String jobId = generateJobFor(arn);

        // Replace the policy so the live answer would now be dynamodb rather than s3.
        iam("PutUserPolicy").formParam("UserName", userName)
            .formParam("PolicyName", "laa-snapshot-policy-" + tag)
            .formParam("PolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                    + "\"Action\":\"dynamodb:GetItem\",\"Resource\":\"*\"}]}")
        .when().post("/").then().statusCode(200);

        iam("GetServiceLastAccessedDetails").formParam("JobId", jobId)
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<ServiceNamespace>s3</ServiceNamespace>"))
            .body(not(containsString("<ServiceNamespace>dynamodb</ServiceNamespace>")));
    }

    /** Deleting the entity must not make an already-issued, valid JobId stop resolving. */
    @Test
    void aCompletedReportStillResolvesAfterItsEntityIsDeleted() {
        String userName = "laa-deleted-entity-" + suffix();
        String arn = createUser(userName);
        String jobId = generateJobFor(arn);

        iam("DeleteUser").formParam("UserName", userName)
        .when().post("/").then().statusCode(200);

        iam("GetServiceLastAccessedDetails").formParam("JobId", jobId)
        .when().post("/").then()
            .statusCode(200)
            .body("GetServiceLastAccessedDetailsResponse.GetServiceLastAccessedDetailsResult.JobStatus",
                    equalTo("COMPLETED"));
    }

    /** MaxItems bounds the page and the Marker continues it, rather than everything at once. */
    @Test
    void getServiceLastAccessedDetailsHonoursMaxItemsAndMarker() {
        String tag = suffix();
        String userName = "laa-paging-" + tag;
        String arn = createUser(userName);
        iam("PutUserPolicy").formParam("UserName", userName)
            .formParam("PolicyName", "laa-paging-policy-" + tag)
            .formParam("PolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                    + "\"Action\":[\"s3:GetObject\",\"dynamodb:GetItem\"],\"Resource\":\"*\"}]}")
        .when().post("/").then().statusCode(200);
        String jobId = generateJobFor(arn);

        // Namespaces are stored sorted, so the first page is dynamodb and the second s3.
        String marker = iam("GetServiceLastAccessedDetails")
            .formParam("JobId", jobId).formParam("MaxItems", "1")
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<ServiceNamespace>dynamodb</ServiceNamespace>"))
            .body(not(containsString("<ServiceNamespace>s3</ServiceNamespace>")))
            .body("GetServiceLastAccessedDetailsResponse.GetServiceLastAccessedDetailsResult.IsTruncated",
                    equalTo("true"))
            .extract().path("GetServiceLastAccessedDetailsResponse."
                    + "GetServiceLastAccessedDetailsResult.Marker");

        iam("GetServiceLastAccessedDetails")
            .formParam("JobId", jobId).formParam("Marker", marker)
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<ServiceNamespace>s3</ServiceNamespace>"))
            .body(not(containsString("<ServiceNamespace>dynamodb</ServiceNamespace>")))
            .body("GetServiceLastAccessedDetailsResponse.GetServiceLastAccessedDetailsResult.IsTruncated",
                    equalTo("false"));
    }

    @Test
    void getServiceLastAccessedDetailsWithEntitiesWithoutAServiceNamespaceIsRejected() {
        String arn = createUser("laa-missing-ns-" + suffix());

        iam("GetServiceLastAccessedDetailsWithEntities")
            .formParam("JobId", generateJobFor(arn))
        .when().post("/").then()
            .statusCode(400)
            .body(containsString("ValidationError"));
    }

    /** A malformed request stays malformed even when the marker sits past the end of the list. */
    @Test
    void getServiceLastAccessedDetailsRejectsMaxItemsBelowOneEvenPastTheEnd() {
        String arn = createUser("laa-maxitems-" + suffix());
        String jobId = generateJobFor(arn);

        iam("GetServiceLastAccessedDetails")
            .formParam("JobId", jobId).formParam("Marker", "999").formParam("MaxItems", "0")
        .when().post("/").then()
            .statusCode(400)
            .body(containsString("ValidationError"));
    }

    @Test
    void getServiceLastAccessedDetailsRejectsANegativeMarker() {
        String arn = createUser("laa-neg-marker-" + suffix());

        iam("GetServiceLastAccessedDetails")
            .formParam("JobId", generateJobFor(arn)).formParam("Marker", "-1")
        .when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("InvalidInput"));
    }

    /** ListPoliciesGrantingServiceAccess models a Marker, so it walks the namespace entries too. */
    @Test
    void listPoliciesGrantingServiceAccessHonoursItsMarker() {
        String arn = createUser("laa-list-marker-" + suffix());

        iam("ListPoliciesGrantingServiceAccess").formParam("Arn", arn)
            .formParam("ServiceNamespaces.member.1", "s3")
            .formParam("ServiceNamespaces.member.2", "dynamodb")
            .formParam("Marker", "1")
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<ServiceNamespace>dynamodb</ServiceNamespace>"))
            .body(not(containsString("<ServiceNamespace>s3</ServiceNamespace>")));
    }

    @Test
    void listPoliciesGrantingServiceAccessRejectsAMalformedMarker() {
        String arn = createUser("laa-list-bad-marker-" + suffix());

        iam("ListPoliciesGrantingServiceAccess").formParam("Arn", arn)
            .formParam("ServiceNamespaces.member.1", "s3")
            .formParam("Marker", "not-a-marker")
        .when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("InvalidInput"));
    }

    /**
     * An AWS-managed policy ARN carries the literal "aws" in its account field, which is the
     * global catalog rather than a foreign account, so the account check must let it through.
     */
    @Test
    void generateServiceLastAccessedDetailsAcceptsAnAwsManagedPolicyArn() {
        String jobId = generateJobFor("arn:aws:iam::aws:policy/ReadOnlyAccess");

        assertEquals(36, jobId.length(), "expected a job id for an AWS-managed policy");
        iam("GetServiceLastAccessedDetails").formParam("JobId", jobId)
        .when().post("/").then()
            .statusCode(200)
            .body("GetServiceLastAccessedDetailsResponse.GetServiceLastAccessedDetailsResult.JobStatus",
                    equalTo("COMPLETED"));
    }

    /** A huge MaxItems must page, not overflow into a negative index. */
    @Test
    void getServiceLastAccessedDetailsSurvivesAnEnormousMaxItems() {
        String tag = suffix();
        String userName = "laa-overflow-" + tag;
        String arn = createUser(userName);
        iam("PutUserPolicy").formParam("UserName", userName)
            .formParam("PolicyName", "laa-overflow-policy-" + tag)
            .formParam("PolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                    + "\"Action\":[\"s3:GetObject\",\"dynamodb:GetItem\"],\"Resource\":\"*\"}]}")
        .when().post("/").then().statusCode(200);

        iam("GetServiceLastAccessedDetails")
            .formParam("JobId", generateJobFor(arn))
            .formParam("Marker", "1")
            .formParam("MaxItems", Integer.toString(Integer.MAX_VALUE))
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<ServiceNamespace>s3</ServiceNamespace>"));
    }

    /** An empty report is a valid answer, not a request that fails its own page-size check. */
    @Test
    void getServiceLastAccessedDetailsReturnsAnEmptyReportWithoutMaxItems() {
        String arn = createUser("laa-empty-report-" + suffix());

        iam("GetServiceLastAccessedDetails").formParam("JobId", generateJobFor(arn))
        .when().post("/").then()
            .statusCode(200)
            .body("GetServiceLastAccessedDetailsResponse.GetServiceLastAccessedDetailsResult.IsTruncated",
                    equalTo("false"));
    }

    @Test
    void getServiceLastAccessedDetailsWithoutAJobIdIsRejected() {
        iam("GetServiceLastAccessedDetails")
        .when().post("/").then()
            .statusCode(400)
            .body(containsString("ValidationError"));
    }

    /**
     * Without an Authorization header there is no credential scope to resolve, so the request
     * reaches IAM only through AwsQueryController's action-name fallback. This pins the entries
     * added there for these actions.
     */
    @Test
    void lastAccessedActionsRouteToIamWithoutACredentialScope() {
        String arn = createUser("laa-unsigned-" + suffix());

        given().formParam("Action", "GenerateServiceLastAccessedDetails")
            .formParam("Arn", arn)
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("GenerateServiceLastAccessedDetailsResponse"));

        given().formParam("Action", "ListPoliciesGrantingServiceAccess")
            .formParam("Arn", arn)
            .formParam("ServiceNamespaces.member.1", "s3")
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("ListPoliciesGrantingServiceAccessResponse"));
    }
}
