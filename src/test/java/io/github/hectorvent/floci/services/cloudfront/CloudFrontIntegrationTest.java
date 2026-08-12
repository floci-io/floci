package io.github.hectorvent.floci.services.cloudfront;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CloudFront REST-XML wire behavior: response roots, the structures clients dereference
 * without checking, tag round-trips and the distribution lifecycle.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CloudFrontIntegrationTest {

    private static final String API = "/2020-05-31";
    private static final String XML = "application/xml";

    private static String distributionId;
    private static String distributionArn;
    private static String oacId;

    /**
     * A CreateDistributionWithTags body. The nested {@code <Enabled>false</Enabled>} of
     * TrustedKeyGroups precedes the config's own {@code <Enabled>true</Enabled>}, exactly as
     * the AWS SDKs order it — reading scalars by document order picks up the wrong one.
     */
    private static String createBody(String callerReference) {
        return """
            <DistributionConfigWithTags xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
              %s
              <Tags><Items><Tag><Key>estate</Key><Value>probe</Value></Tag></Items></Tags>
            </DistributionConfigWithTags>
            """.formatted(configBody(callerReference, true));
    }

    /** The DistributionConfig on its own, as UpdateDistribution takes it. */
    private static String configBody(String callerReference, boolean enabled) {
        return """
              <DistributionConfig xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
                <CallerReference>%s</CallerReference>
                <Aliases><Quantity>1</Quantity><Items><CNAME>cdn.example.test</CNAME></Items></Aliases>
                <Comment>integration</Comment>
                <DefaultCacheBehavior>
                  <TargetOriginId>o1</TargetOriginId>
                  <TrustedKeyGroups><Enabled>false</Enabled><Quantity>0</Quantity></TrustedKeyGroups>
                  <TrustedSigners><Enabled>false</Enabled><Quantity>0</Quantity></TrustedSigners>
                  <ViewerProtocolPolicy>allow-all</ViewerProtocolPolicy>
                  <AllowedMethods>
                    <Quantity>3</Quantity>
                    <Items><Method>GET</Method><Method>HEAD</Method><Method>OPTIONS</Method></Items>
                    <CachedMethods><Quantity>2</Quantity><Items><Method>GET</Method><Method>HEAD</Method></Items></CachedMethods>
                  </AllowedMethods>
                  <Compress>true</Compress>
                  <ForwardedValues>
                    <QueryString>true</QueryString>
                    <Cookies><Forward>none</Forward></Cookies>
                    <Headers><Quantity>1</Quantity><Items><Name>Origin</Name></Items></Headers>
                    <QueryStringCacheKeys><Quantity>0</Quantity></QueryStringCacheKeys>
                  </ForwardedValues>
                  <MinTTL>0</MinTTL>
                  <DefaultTTL>3600</DefaultTTL>
                  <MaxTTL>86400</MaxTTL>
                </DefaultCacheBehavior>
                <Enabled>%s</Enabled>
                <Origins>
                  <Quantity>1</Quantity>
                  <Items>
                    <Origin>
                      <Id>o1</Id>
                      <DomainName>origin.example.test</DomainName>
                      <OriginPath></OriginPath>
                      <CustomHeaders>
                        <Quantity>1</Quantity>
                        <Items><OriginCustomHeader><HeaderName>x-probe</HeaderName><HeaderValue>yes</HeaderValue></OriginCustomHeader></Items>
                      </CustomHeaders>
                      <CustomOriginConfig>
                        <HTTPPort>80</HTTPPort>
                        <HTTPSPort>443</HTTPSPort>
                        <OriginProtocolPolicy>http-only</OriginProtocolPolicy>
                        <OriginSslProtocols><Quantity>1</Quantity><Items><SslProtocol>TLSv1.2</SslProtocol></Items></OriginSslProtocols>
                        <OriginReadTimeout>45</OriginReadTimeout>
                        <OriginKeepaliveTimeout>7</OriginKeepaliveTimeout>
                      </CustomOriginConfig>
                      <ConnectionAttempts>3</ConnectionAttempts>
                      <ConnectionTimeout>10</ConnectionTimeout>
                    </Origin>
                  </Items>
                </Origins>
                <Restrictions>
                  <GeoRestriction>
                    <RestrictionType>whitelist</RestrictionType>
                    <Quantity>2</Quantity>
                    <Items><Location>US</Location><Location>CA</Location></Items>
                  </GeoRestriction>
                </Restrictions>
                <ViewerCertificate>
                  <CloudFrontDefaultCertificate>true</CloudFrontDefaultCertificate>
                  <MinimumProtocolVersion>TLSv1</MinimumProtocolVersion>
                </ViewerCertificate>
                <WebACLId></WebACLId>
              </DistributionConfig>
            """.formatted(callerReference, enabled);
    }

    @Test
    @Order(1)
    void createDistributionWithTagsReadsScalarsFromTheConfigItself() {
        String body = given()
                .contentType(XML)
                .body(createBody("integration-1"))
        .when()
                .post(API + "/distribution?WithTags")
        .then()
                .statusCode(201)
                .header("ETag", notNullValue())
                .extract().body().asString();

        XmlPath xml = new XmlPath(body);
        distributionId = xml.getString("Distribution.Id");
        distributionArn = xml.getString("Distribution.ARN");
        assertNotNull(distributionId);
        assertTrue(distributionArn.endsWith(":distribution/" + distributionId), distributionArn);
        assertEquals("true", xml.getString("Distribution.DistributionConfig.Enabled"),
                "the config's own Enabled must win over the one nested in TrustedKeyGroups");
    }

    @Test
    @Order(2)
    void getDistributionReturnsTheStructuresClientsDereference() {
        String body = given()
        .when()
                .get(API + "/distribution/" + distributionId)
        .then()
                .statusCode(200)
                .extract().body().asString();

        XmlPath xml = new XmlPath(body);
        String config = "Distribution.DistributionConfig.";
        // OriginGroups and Restrictions are always present in a CloudFront response; clients
        // read straight through them without a null check.
        assertEquals("0", xml.getString(config + "OriginGroups.Quantity"));
        assertEquals("whitelist", xml.getString(config + "Restrictions.GeoRestriction.RestrictionType"));
        assertEquals(List.of("US", "CA"), xml.getList(config + "Restrictions.GeoRestriction.Items.Location"));

        String behavior = config + "DefaultCacheBehavior.";
        assertEquals(List.of("GET", "HEAD", "OPTIONS"), xml.getList(behavior + "AllowedMethods.Items.Method"));
        assertEquals(List.of("GET", "HEAD"), xml.getList(behavior + "AllowedMethods.CachedMethods.Items.Method"));
        assertEquals("3600", xml.getString(behavior + "DefaultTTL"));
        assertEquals("true", xml.getString(behavior + "ForwardedValues.QueryString"));
        assertEquals("none", xml.getString(behavior + "ForwardedValues.Cookies.Forward"));
        assertEquals(List.of("Origin"), xml.getList(behavior + "ForwardedValues.Headers.Items.Name"));
        assertEquals("false", xml.getString(behavior + "SmoothStreaming"));

        String origin = config + "Origins.Items.Origin.";
        assertEquals("TLSv1.2", xml.getString(origin + "CustomOriginConfig.OriginSslProtocols.Items.SslProtocol"));
        assertEquals("45", xml.getString(origin + "CustomOriginConfig.OriginReadTimeout"));
        assertEquals("7", xml.getString(origin + "CustomOriginConfig.OriginKeepaliveTimeout"));
        assertEquals("x-probe", xml.getString(origin + "CustomHeaders.Items.OriginCustomHeader.HeaderName"));

        assertEquals("cdn.example.test", xml.getString(config + "Aliases.Items.CNAME"));
        assertEquals("0", xml.getString("Distribution.InProgressInvalidationBatches"));
    }

    @Test
    @Order(3)
    void listDistributionsIsRootedAtDistributionList() {
        String body = given()
        .when()
                .get(API + "/distribution")
        .then()
                .statusCode(200)
                // The payload structure is the response root: a List*Result wrapper leaves
                // SDK clients with an empty list.
                .body(not(containsString("ListDistributionsResult")))
                .extract().body().asString();

        assertTrue(body.startsWith("<DistributionList"), body.substring(0, Math.min(80, body.length())));

        XmlPath xml = new XmlPath(body);
        assertTrue(xml.getList("DistributionList.Items.DistributionSummary.Id").contains(distributionId),
                "created distribution missing from ListDistributions");
        assertTrue(xml.getList("DistributionList.Items.DistributionSummary.ARN").contains(distributionArn));
        assertEquals("false", xml.getString("DistributionList.IsTruncated"));
    }

    @Test
    @Order(4)
    void listTagsForResourceReturnsTagsSetAtCreate() {
        given()
        .when()
                .get(API + "/tagging?Resource=" + distributionArn)
        .then()
                .statusCode(200)
                .body(not(containsString("ListTagsForResourceResult")))
                .body("Tags.Items.Tag.Key", equalTo("estate"))
                .body("Tags.Items.Tag.Value", equalTo("probe"));
    }

    @Test
    @Order(5)
    void tagAndUntagResourceRoundTrip() {
        given()
                .contentType(XML)
                .body("""
                    <Tags xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
                      <Items><Tag><Key>extra</Key><Value>value</Value></Tag></Items>
                    </Tags>
                    """)
        .when()
                .post(API + "/tagging?Operation=Tag&Resource=" + distributionArn)
        .then()
                .statusCode(204);

        String body = given()
        .when()
                .get(API + "/tagging?Resource=" + distributionArn)
        .then()
                .statusCode(200)
                .extract().body().asString();
        assertEquals(List.of("estate", "extra"), new XmlPath(body).getList("Tags.Items.Tag.Key"));

        given()
                .contentType(XML)
                .body("""
                    <TagKeys xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
                      <Items><Key>extra</Key></Items>
                    </TagKeys>
                    """)
        .when()
                .post(API + "/tagging?Operation=Untag&Resource=" + distributionArn)
        .then()
                .statusCode(204);

        given()
        .when()
                .get(API + "/tagging?Resource=" + distributionArn)
        .then()
                .statusCode(200)
                .body("Tags.Items.Tag.Key", equalTo("estate"));
    }

    @Test
    @Order(6)
    void createOriginAccessControlThenListIt() {
        String created = given()
                .contentType(XML)
                .body("""
                    <OriginAccessControlConfig xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
                      <Name>integration-oac</Name>
                      <Description>integration</Description>
                      <SigningProtocol>sigv4</SigningProtocol>
                      <SigningBehavior>always</SigningBehavior>
                      <OriginAccessControlOriginType>s3</OriginAccessControlOriginType>
                    </OriginAccessControlConfig>
                    """)
        .when()
                .post(API + "/origin-access-control")
        .then()
                .statusCode(201)
                .extract().body().asString();

        oacId = new XmlPath(created).getString("OriginAccessControl.Id");
        assertNotNull(oacId);

        String body = given()
        .when()
                .get(API + "/origin-access-control")
        .then()
                .statusCode(200)
                .body(not(containsString("ListOriginAccessControlsResult")))
                .extract().body().asString();

        assertTrue(body.startsWith("<OriginAccessControlList"), body.substring(0, Math.min(80, body.length())));
        XmlPath xml = new XmlPath(body);
        assertTrue(xml.getList("OriginAccessControlList.Items.OriginAccessControlSummary.Id").contains(oacId),
                "created origin access control missing from ListOriginAccessControls");
        assertTrue(xml.getList("OriginAccessControlList.Items.OriginAccessControlSummary.Name")
                .contains("integration-oac"));
    }

    @Test
    @Order(7)
    void disableThenDeleteDistribution() {
        String etag = given()
        .when()
                .get(API + "/distribution/" + distributionId + "/config")
        .then()
                .statusCode(200)
                .extract().header("ETag");

        String updatedEtag = given()
                .contentType(XML)
                .header("If-Match", etag)
                .body(configBody("integration-1", false))
        .when()
                .put(API + "/distribution/" + distributionId + "/config")
        .then()
                .statusCode(200)
                .body("Distribution.DistributionConfig.Enabled", equalTo("false"))
                .extract().header("ETag");

        given()
                .header("If-Match", updatedEtag)
        .when()
                .delete(API + "/distribution/" + distributionId)
        .then()
                .statusCode(204);

        given()
        .when()
                .get(API + "/distribution/" + distributionId)
        .then()
                .statusCode(404);

        given()
        .when()
                .get(API + "/tagging?Resource=" + distributionArn)
        .then()
                .statusCode(200)
                .body(not(containsString("<Tag>")));
    }
}
