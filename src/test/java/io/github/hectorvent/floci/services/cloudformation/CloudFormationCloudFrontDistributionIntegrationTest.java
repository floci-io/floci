package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.services.cloudfront.CloudFrontService;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.KeyGroup;
import io.github.hectorvent.floci.services.cloudfront.model.OriginAccessControl;
import io.github.hectorvent.floci.services.cloudfront.model.PublicKey;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end check that CloudFormation provisions an {@code AWS::CloudFront::Distribution} for real
 * (into {@link io.github.hectorvent.floci.services.cloudfront.CloudFrontService}) rather than stubbing
 * it: {@code Fn::GetAtt DomainName} resolves to the assigned {@code *.cloudfront.net} domain (closes
 * #1147, where it previously returned the raw {@code LogicalId.DomainName} token), and the provisioned
 * distribution actually serves its S3 origin.
 */
@QuarkusTest
class CloudFormationCloudFrontDistributionIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";

    @Inject
    S3Service s3Service;

    @Inject
    CloudFrontService cloudFrontService;

    @Test
    void createStackProvisionsBrowsableDistributionWithResolvedDomainName() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cfn-cf-content-" + suffix;
        String alias = "cfn-viewer-" + suffix + ".example.test";
        String stackName = "cfn-cloudfront-stack-" + suffix;

        s3Service.createBucket(bucket, "us-east-1");
        s3Service.putObject(bucket, "index.html",
                ("CFN-INDEX-" + suffix).getBytes(StandardCharsets.UTF_8), "text/html", Map.of());
        OriginAccessControl oac = new OriginAccessControl();
        oac.setName("cfn-oac-" + suffix);
        oac.setSigningBehavior("always");
        oac.setSigningProtocol("sigv4");
        oac.setOriginAccessControlOriginType("s3");
        oac = cloudFrontService.createOriginAccessControl(oac);

        String template = """
                {
                  "Resources": {
                    "Dist": {
                      "Type": "AWS::CloudFront::Distribution",
                      "Properties": {
                        "DistributionConfig": {
                          "Enabled": true,
                          "DefaultRootObject": "index.html",
                          "Aliases": ["%s"],
                          "Origins": [
                            {
                              "Id": "s3-origin",
                              "DomainName": "%s.s3.us-east-1.amazonaws.com",
                              "OriginAccessControlId": "%s",
                              "OriginCustomHeaders": [
                                {"HeaderName": "X-Origin-Verify", "HeaderValue": "cfn-secret"}
                              ],
                              "S3OriginConfig": { "OriginAccessIdentity": "" }
                            }
                          ],
                          "DefaultCacheBehavior": {
                            "TargetOriginId": "s3-origin",
                            "ViewerProtocolPolicy": "allow-all",
                            "ResponseHeadersPolicyId": "60669652-455b-4ae9-85a4-c4c02393f86c"
                          },
                          "CacheBehaviors": [{
                            "PathPattern": "/api/*",
                            "TargetOriginId": "s3-origin",
                            "ViewerProtocolPolicy": "allow-all",
                            "ResponseHeadersPolicyId": "67f7725c-6f97-4210-82d7-5512b31e9d03"
                          }]
                        }
                      }
                    }
                  },
                  "Outputs": {
                    "DistDomain": {"Value": {"Fn::GetAtt": ["Dist", "DomainName"]}},
                    "DistId": {"Value": {"Ref": "Dist"}}
                  }
                }
                """.formatted(alias, bucket, oac.getId());

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Stack completes and Fn::GetAtt(Dist, DomainName) resolves to a real *.cloudfront.net domain
        // (not the unresolved "Dist.DomainName" token).
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"))
            .body(containsString(".cloudfront.net"))
            .body(not(containsString("Dist.DomainName")));

        Distribution provisioned = distributionsWithAlias(alias).getFirst();
        assertEquals(
                oac.getId(),
                provisioned.getConfig().getOrigins().getFirst().getOriginAccessControlId());
        assertEquals(List.of(Map.of("HeaderName", "X-Origin-Verify", "HeaderValue", "cfn-secret")),
                provisioned.getConfig().getOrigins().getFirst().getCustomHeaders());
        assertEquals("60669652-455b-4ae9-85a4-c4c02393f86c",
                provisioned.getConfig().getDefaultCacheBehavior().getResponseHeadersPolicyId());
        assertEquals(List.of("67f7725c-6f97-4210-82d7-5512b31e9d03"),
                provisioned.getConfig().getCacheBehaviors().stream()
                .map(behavior -> behavior.getResponseHeadersPolicyId())
                .toList());

        // The provisioned distribution is browsable: a request to its alias serves the S3 origin's
        // default root object.
        given()
            .header("Host", alias)
            .header("Origin", "https://viewer.example")
        .when()
            .get("/")
        .then()
            .statusCode(200)
            .header("Access-Control-Allow-Origin", equalTo("*"))
            .body(containsString("CFN-INDEX-" + suffix));
    }

    @Test
    void updateStackUpdatesDistributionInPlace() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cfn-cf-update-" + suffix;
        String alias = "cfn-update-" + suffix + ".example.test";
        String stackName = "cfn-cloudfront-update-" + suffix;
        s3Service.createBucket(bucket, "us-east-1");

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", updateTemplate(alias, bucket, "before-" + suffix))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        Distribution original = distributionsWithAlias(alias).getFirst();

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "UpdateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", updateTemplate(alias, bucket, "after-" + suffix))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        List<Distribution> matching = distributionsWithAlias(alias);
        assertEquals(1, matching.size(), "UpdateStack must not leak a second distribution");
        Distribution updated = matching.getFirst();
        assertEquals(original.getId(), updated.getId());
        assertEquals(original.getDomainName(), updated.getDomainName());
        assertEquals("after-" + suffix, updated.getConfig().getComment());
    }

    @Test
    void preservesTrustedKeyGroupsOnDefaultAndOrderedBehaviors() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String alias = "cfn-private-" + suffix + ".example.test";
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        PublicKey publicKey = new PublicKey();
        publicKey.setCallerReference("cfn-key-reference-" + suffix);
        publicKey.setName("cfn-key-" + suffix);
        publicKey.setEncodedKey("-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(
                        generator.generateKeyPair().getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----");
        publicKey = cloudFrontService.createPublicKey(publicKey);
        KeyGroup defaultKeyGroup = createKeyGroup("cfn-default-" + suffix, publicKey.getId());
        KeyGroup orderedKeyGroupA = createKeyGroup("cfn-ordered-a-" + suffix, publicKey.getId());
        KeyGroup orderedKeyGroupB = createKeyGroup("cfn-ordered-b-" + suffix, publicKey.getId());
        String template = """
                {
                  "Resources": {
                    "Dist": {
                      "Type": "AWS::CloudFront::Distribution",
                      "Properties": {
                        "DistributionConfig": {
                          "Enabled": true,
                          "Aliases": ["%s"],
                          "Origins": [{
                            "Id": "s3-origin",
                            "DomainName": "private-content.s3.us-east-1.amazonaws.com",
                            "S3OriginConfig": {"OriginAccessIdentity": ""}
                          }],
                          "DefaultCacheBehavior": {
                            "TargetOriginId": "s3-origin",
                            "ViewerProtocolPolicy": "allow-all",
                            "TrustedKeyGroups": ["%s"]
                          },
                          "CacheBehaviors": [{
                            "PathPattern": "/private/*",
                            "TargetOriginId": "s3-origin",
                            "ViewerProtocolPolicy": "allow-all",
                            "TrustedKeyGroups": ["%s", "%s"]
                          }]
                        }
                      }
                    }
                  }
                }
                """.formatted(
                        alias,
                        defaultKeyGroup.getId(),
                        orderedKeyGroupA.getId(),
                        orderedKeyGroupB.getId());

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", "cfn-cloudfront-private-" + suffix)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        Distribution distribution = distributionsWithAlias(alias).getFirst();
        assertEquals(List.of(defaultKeyGroup.getId()),
                distribution.getConfig().getDefaultCacheBehavior().getTrustedKeyGroups());
        assertEquals(List.of(orderedKeyGroupA.getId(), orderedKeyGroupB.getId()),
                distribution.getConfig().getCacheBehaviors().getFirst().getTrustedKeyGroups());
    }

    private KeyGroup createKeyGroup(String name, String publicKeyId) {
        KeyGroup keyGroup = new KeyGroup();
        keyGroup.setName(name);
        keyGroup.setItems(List.of(publicKeyId));
        return cloudFrontService.createKeyGroup(keyGroup);
    }

    private List<Distribution> distributionsWithAlias(String alias) {
        return cloudFrontService.listDistributions(null, 1000).stream()
                .filter(distribution -> distribution.getConfig() != null
                        && distribution.getConfig().getAliases() != null
                        && distribution.getConfig().getAliases().contains(alias))
                .toList();
    }

    private static String updateTemplate(String alias, String bucket, String comment) {
        return """
                {
                  "Resources": {
                    "Dist": {
                      "Type": "AWS::CloudFront::Distribution",
                      "Properties": {
                        "DistributionConfig": {
                          "Enabled": true,
                          "Comment": "%s",
                          "Aliases": ["%s"],
                          "Origins": [{
                            "Id": "s3-origin",
                            "DomainName": "%s.s3.us-east-1.amazonaws.com",
                            "S3OriginConfig": {"OriginAccessIdentity": ""}
                          }],
                          "DefaultCacheBehavior": {
                            "TargetOriginId": "s3-origin",
                            "ViewerProtocolPolicy": "allow-all"
                          }
                        }
                      }
                    }
                  }
                }
                """.formatted(comment, alias, bucket);
    }
}
