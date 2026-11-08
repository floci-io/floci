package io.floci.aws.services.ec2;

import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.LaunchTemplateData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * IMDSv2 session tokens, as documented in the EC2 User Guide: the PUT must carry a TTL between
 * 1 and 21,600 seconds (otherwise 400), and a GET that presents an invalid or expired token gets
 * 401 so the caller fetches a new token. A token is not valid on other instances. When the
 * instance's HttpTokens is required, a GET without a valid token also gets 401.
 */
class Ec2MetadataTokenTest {

    private Ec2MetadataTestFixture fixture;
    private Instance instance;

    @BeforeEach
    void startServer() throws Exception {
        fixture = new Ec2MetadataTestFixture();
        fixture.start();
        instance = fixture.instance();
    }

    @AfterEach
    void stopServer() throws Exception {
        fixture.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "21601", "-1", "sixty", ""})
    void tokenRequestWithTtlOutsideOneToSixHoursIsRejected(String ttl) throws Exception {
        assertEquals(400, fixture.putToken(ttl).statusCode());
    }

    @Test
    void tokenWithTheMaximumTtlIsIssued() throws Exception {
        HttpResponse<String> token = fixture.putToken("21600");

        assertEquals(200, token.statusCode());
        assertEquals("21600", token.headers().firstValue(Ec2MetadataTestFixture.TTL_HEADER).orElseThrow());
    }

    @Test
    void tokenIsAcceptedUntilItsTtlElapses() throws Exception {
        String token = fixture.putToken("60").body();
        fixture.clock().advance(Duration.ofSeconds(59));

        HttpResponse<String> response = getInstanceId(token);

        assertEquals(200, response.statusCode());
        assertEquals("i-0123456789abcdef0", response.body());
    }

    @Test
    void expiredTokenIsRejectedAsUnauthorized() throws Exception {
        String token = fixture.putToken("60").body();
        fixture.clock().advance(Duration.ofSeconds(60));

        assertEquals(401, getInstanceId(token).statusCode());
    }

    @Test
    void unknownTokenIsRejectedAsUnauthorized() throws Exception {
        assertEquals(401, getInstanceId("not-a-token").statusCode());
    }

    @Test
    void tokenPresentedFromAnotherInstanceIsRejectedAsUnauthorized() throws Exception {
        String token = fixture.putToken("60").body();
        Instance other = Ec2MetadataTestFixture.instance("i-0fedcba9876543210");
        fixture.server().unregisterContainer(Ec2MetadataTestFixture.CALLER_IP, instance);
        fixture.server().registerContainer(Ec2MetadataTestFixture.CALLER_IP, other.getInstanceId(), other);

        assertEquals(401, getInstanceId(token).statusCode());
    }

    @Test
    void tokenIdentifiesItsInstanceWhenTheCallerIpIsNotRegistered() throws Exception {
        String token = fixture.putToken("60").body();
        fixture.server().unregisterContainer(Ec2MetadataTestFixture.CALLER_IP, instance);

        HttpResponse<String> response = getInstanceId(token);

        assertEquals(200, response.statusCode());
        assertEquals("i-0123456789abcdef0", response.body());
    }

    @Test
    void requestWithoutTokenStillUsesImdsV1() throws Exception {
        HttpResponse<String> response = getInstanceId(null);

        assertEquals(200, response.statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/latest/meta-data/instance-id",
            "/latest/dynamic/instance-identity/document",
            "/latest/user-data",
            "/latest/meta-data/iam/info",
            "/latest/meta-data/iam/security-credentials/",
            "/latest/meta-data/identity-credentials/ec2/security-credentials/ec2-instance",
            "/latest/meta-data/tags/instance/"
    })
    void requiredTokenRefusesDataBeforeResolvingItsContents(String path) throws Exception {
        setTokenRequirement("required");
        instance.setUserData("private-user-data");

        HttpResponse<String> response = getWithoutToken(path);

        assertEquals(401, response.statusCode());
        assertEquals("", response.body());
    }

    /** AWS refuses the request before looking at the path, so a listing IMDS does not serve is 401 too. */
    @ParameterizedTest
    @ValueSource(strings = {"/latest/meta-data/", "/latest/meta-data/no-such-item", "/latest/"})
    void requiredTokenRefusesPathsImdsDoesNotServe(String path) throws Exception {
        setTokenRequirement("required");

        assertEquals(401, getWithoutToken(path).statusCode());
    }

    @Test
    void requiredTokenRefusesATokenlessHeadRequest() throws Exception {
        setTokenRequirement("required");

        assertEquals(401, fixture.send("HEAD", "/latest/meta-data/instance-id", null).statusCode());
    }

    /** No route serves HEAD, so with a token the router answers as it would for any unserved method. */
    @Test
    void aHeadRequestWithATokenGetsTheRoutersAnswer() throws Exception {
        setTokenRequirement("required");
        String token = fixture.putToken("60").body();

        assertEquals(405, fixture.send("HEAD", "/latest/meta-data/instance-id", token).statusCode());
        assertEquals(404, fixture.send("HEAD", "/latest/meta-data/no-such-item", token).statusCode());
    }

    /** The token check sits ahead of the routes without claiming the methods IMDS does not serve. */
    @Test
    void aMethodImdsDoesNotServeIsStillNotFound() throws Exception {
        setTokenRequirement("required");
        String token = fixture.putToken("60").body();

        assertEquals(404, fixture.send("POST", "/latest/meta-data/", token).statusCode());
        assertEquals(404, fixture.send("POST", "/latest/meta-data/", null).statusCode());
    }

    @Test
    void aPathImdsDoesNotServeIsStillNotFoundWithAToken() throws Exception {
        setTokenRequirement("required");
        String token = fixture.putToken("60").body();

        assertEquals(404, fixture.get("/latest/meta-data/no-such-item", token).statusCode());
        setTokenRequirement("optional");
        assertEquals(404, getWithoutToken("/latest/meta-data/no-such-item").statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void blankTokenDoesNotBypassRequiredTokens(String token) throws Exception {
        setTokenRequirement("required");

        assertEquals(401, getInstanceId(token).statusCode());
    }

    @Test
    void requiredTokenPreservesIssuanceValidationAndExpiry() throws Exception {
        setTokenRequirement("required");
        HttpResponse<String> issued = fixture.putToken("60");
        assertEquals(200, issued.statusCode());

        HttpResponse<String> accepted = getInstanceId(issued.body());
        assertEquals(200, accepted.statusCode());
        assertEquals(instance.getInstanceId(), accepted.body());
        assertEquals(401, getInstanceId("invalid-token").statusCode());

        fixture.clock().advance(Duration.ofSeconds(60));
        assertEquals(401, getInstanceId(issued.body()).statusCode());
    }

    @Test
    void tokenRequirementTracksTheRegisteredInstancesCurrentOptions() throws Exception {
        setTokenRequirement("optional");
        assertEquals(200, getWithoutToken("/latest/meta-data/instance-id").statusCode());

        setTokenRequirement("required");
        assertEquals(401, getWithoutToken("/latest/meta-data/instance-id").statusCode());

        setTokenRequirement("optional");
        assertEquals(200, getWithoutToken("/latest/meta-data/instance-id").statusCode());
    }

    private void setTokenRequirement(String value) {
        LaunchTemplateData.MetadataOptions options = LaunchTemplateData.MetadataOptions.launchDefaults();
        options.setHttpTokens(value);
        instance.setMetadataOptions(options);
    }

    private HttpResponse<String> getWithoutToken(String path) throws Exception {
        return fixture.get(path, null);
    }

    private HttpResponse<String> getInstanceId(String token) throws Exception {
        return fixture.get("/latest/meta-data/instance-id", token, HttpResponse.BodyHandlers.ofString());
    }
}
