package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * An {@code AWS::S3::Bucket} created through CloudFormation carries the configuration its template
 * declares, and an {@code AWS::S3::BucketPolicy} puts its document on the bucket.
 *
 * <p>Before this, {@code S3CfnProvisioner} translated {@code CorsConfiguration} and
 * {@code VersioningConfiguration} and nothing else, and the policy resource took a physical id and
 * stopped, so a stack reported {@code CREATE_COMPLETE} for a bucket with no lifecycle, no
 * public-access block, no default encryption, no tags and no policy. The template below is the
 * shape a bootstrap stack deploys: a versioned bucket, locked down, with a transport policy.
 */
@QuarkusTest
class CloudFormationS3BucketPropertiesIntegrationTest {

    private static String template(String bucket, int noncurrentDays) {
        return """
            {
              "Resources": {
                "recordsBucket": {
                  "Type": "AWS::S3::Bucket",
                  "Properties": {
                    "BucketName": "%s",
                    "VersioningConfiguration": { "Status": "Enabled" },
                    "PublicAccessBlockConfiguration": {
                      "BlockPublicAcls": true, "BlockPublicPolicy": true,
                      "IgnorePublicAcls": true, "RestrictPublicBuckets": true
                    },
                    "LifecycleConfiguration": { "Rules": [ {
                      "Id": "expire-superseded-record-versions",
                      "Status": "Enabled",
                      "NoncurrentVersionExpiration": { "NoncurrentDays": %d },
                      "AbortIncompleteMultipartUpload": { "DaysAfterInitiation": 7 }
                    } ] },
                    "BucketEncryption": { "ServerSideEncryptionConfiguration": [ {
                      "ServerSideEncryptionByDefault": { "SSEAlgorithm": "aws:kms", "KMSMasterKeyID": "arn:aws:kms:us-east-1:000000000000:key/abc" },
                      "BucketKeyEnabled": true
                    } ] },
                    "Tags": [ { "Key": "purpose", "Value": "records" },
                              { "Key": "escaped", "Value": { "Fn::Sub": "${!NotAVariable}-${AWS::Region}" } } ]
                  }
                },
                "recordsBucketPolicy": {
                  "Type": "AWS::S3::BucketPolicy",
                  "Properties": {
                    "Bucket": { "Ref": "recordsBucket" },
                    "PolicyDocument": {
                      "Version": "2012-10-17",
                      "Statement": [ {
                        "Sid": "DenyInsecureTransport", "Effect": "Deny", "Principal": "*", "Action": "s3:*",
                        "Resource": [ { "Fn::GetAtt": ["recordsBucket", "Arn"] }, { "Fn::Sub": "${recordsBucket.Arn}/*" } ],
                        "Condition": { "Bool": { "aws:SecureTransport": "false" } }
                      } ]
                    }
                  }
                }
              }
            }
            """.formatted(bucket, noncurrentDays);
    }

    @Test
    void theBucketCarriesWhatItsTemplateDeclares() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cfn-props-bucket-" + suffix;
        String stackId = stack("CreateStack", "cfn-props-stack-" + suffix, template(bucket, 30));
        await(stackId, "CREATE_COMPLETE");

        bucketGet(bucket, "versioning").body(containsString("<Status>Enabled</Status>"));

        bucketGet(bucket, "publicAccessBlock")
            .body(containsString("<BlockPublicAcls>true</BlockPublicAcls>"))
            .body(containsString("<IgnorePublicAcls>true</IgnorePublicAcls>"))
            .body(containsString("<BlockPublicPolicy>true</BlockPublicPolicy>"))
            .body(containsString("<RestrictPublicBuckets>true</RestrictPublicBuckets>"));

        bucketGet(bucket, "lifecycle")
            .body(containsString("<ID>expire-superseded-record-versions</ID>"))
            .body(containsString("<Status>Enabled</Status>"))
            .body(containsString("<NoncurrentDays>30</NoncurrentDays>"))
            .body(containsString("<DaysAfterInitiation>7</DaysAfterInitiation>"));

        bucketGet(bucket, "encryption")
            .body(containsString("<SSEAlgorithm>aws:kms</SSEAlgorithm>"))
            .body(containsString("<KMSMasterKeyID>arn:aws:kms:us-east-1:000000000000:key/abc</KMSMasterKeyID>"))
            .body(containsString("<BucketKeyEnabled>true</BucketKeyEnabled>"));

        bucketGet(bucket, "tagging").body(containsString("<Key>purpose</Key>")).body(containsString("<Value>records</Value>"));
        // Fn::Sub: ${!Name} is a literal, ${AWS::Region} a pseudo-parameter, and (in the policy
        // below) ${recordsBucket.Arn} the GetAtt shorthand, which used to come back unresolved.
        bucketGet(bucket, "tagging").body(containsString("<Value>${NotAVariable}-us-east-1</Value>"));

        // The policy is on the bucket, with its intrinsics resolved to the bucket's real ARN.
        bucketGet(bucket, "policy")
            .body(containsString("DenyInsecureTransport"))
            .body(containsString("arn:aws:s3:::" + bucket + "/*"))
            .body(not(containsString("Fn::")));
    }

    @Test
    void aStackUpdateChangesTheLifecycleWindow() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cfn-props-update-" + suffix;
        String stackName = "cfn-props-update-stack-" + suffix;
        String stackId = stack("CreateStack", stackName, template(bucket, 30));
        await(stackId, "CREATE_COMPLETE");

        stack("UpdateStack", stackName, template(bucket, 45));
        await(stackId, "UPDATE_COMPLETE");

        bucketGet(bucket, "lifecycle").body(containsString("<NoncurrentDays>45</NoncurrentDays>"));
        bucketGet(bucket, "versioning").body(containsString("<Status>Enabled</Status>"));
    }

    @Test
    void aBucketDeclaringNoneOfItStaysBare() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cfn-props-bare-" + suffix;
        String stackId = stack("CreateStack", "cfn-props-bare-stack-" + suffix, """
            { "Resources": { "b": { "Type": "AWS::S3::Bucket", "Properties": { "BucketName": "%s" } } } }
            """.formatted(bucket));
        await(stackId, "CREATE_COMPLETE");

        given().when().get("/" + bucket + "?versioning").then().statusCode(200).body(not(containsString("<Status>")));
        given().when().get("/" + bucket + "?lifecycle").then().statusCode(404);
        given().when().get("/" + bucket + "?publicAccessBlock").then().statusCode(404);
    }

    @Test
    void droppingThePolicyResourceTakesThePolicyOffTheBucket() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cfn-props-drop-" + suffix;
        String stackName = "cfn-props-drop-stack-" + suffix;
        String stackId = stack("CreateStack", stackName, template(bucket, 30));
        await(stackId, "CREATE_COMPLETE");
        bucketGet(bucket, "policy").body(containsString("DenyInsecureTransport"));

        // The same template with the policy resource removed. CloudFormation deletes the dropped
        // resource, which must clear the policy rather than report DELETE_COMPLETE and leave it.
        stack("UpdateStack", stackName, """
            { "Resources": { "recordsBucket": { "Type": "AWS::S3::Bucket",
              "Properties": { "BucketName": "%s" } } } }
            """.formatted(bucket));
        await(stackId, "UPDATE_COMPLETE");

        given().when().get("/" + bucket + "?policy").then().statusCode(404);
    }

    @Test
    void thePolicyResourceIsIdentifiedByItsBucket() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cfn-props-ref-" + suffix;
        String stackId = stack("CreateStack", "cfn-props-ref-stack-" + suffix, template(bucket, 30));
        await(stackId, "CREATE_COMPLETE");

        // primaryIdentifier in the registry schema is /properties/Bucket, so that is the physical
        // id, and it is what makes the policy findable at delete.
        String xml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", stackId)
        .when().post("/").then().statusCode(200).extract().asString();
        assertEquals(bucket, physicalIdOf(xml, "recordsBucketPolicy"),
                "the policy's physical id is its bucket: " + xml);
    }

    /** The PhysicalResourceId of one member of a DescribeStackResources response. */
    private static String physicalIdOf(String xml, String logicalId) {
        String marker = "<LogicalResourceId>" + logicalId + "</LogicalResourceId>";
        int at = xml.indexOf(marker);
        assertTrue(at > 0, logicalId + " is not in the stack: " + xml);
        int memberStart = xml.lastIndexOf("<member>", at);
        int memberEnd = xml.indexOf("</member>", at);
        String member = xml.substring(memberStart, memberEnd);
        int idStart = member.indexOf("<PhysicalResourceId>");
        assertTrue(idStart > 0, logicalId + " has no physical id: " + member);
        idStart += "<PhysicalResourceId>".length();
        return member.substring(idStart, member.indexOf("</PhysicalResourceId>", idStart));
    }

    private static io.restassured.response.ValidatableResponse bucketGet(String bucket, String subresource) {
        return given().when().get("/" + bucket + "?" + subresource).then().statusCode(200);
    }

    private static String stack(String action, String stackName, String template) {
        String xml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", action)
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();
        int start = xml.indexOf("<StackId>") + "<StackId>".length();
        return xml.substring(start, xml.indexOf("</StackId>", start));
    }

    private static void await(String stackId, String status) throws InterruptedException {
        String xml = "";
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            xml = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stackId)
            .when().post("/").then().statusCode(200).extract().asString();
            if (xml.contains("<StackStatus>" + status + "</StackStatus>")) {
                return;
            }
            if (xml.contains("_FAILED</StackStatus>") || xml.contains("ROLLBACK_COMPLETE</StackStatus>")) {
                break;
            }
            Thread.sleep(50);
        }
        fail("stack " + stackId + " never reached " + status + ": " + xml);
    }
}
