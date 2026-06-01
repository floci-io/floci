package io.github.hectorvent.floci.services.sns;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.sns.model.PlatformApplication;
import io.github.hectorvent.floci.services.sns.model.PlatformEndpoint;
import io.github.hectorvent.floci.services.sns.model.PushNotification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers Floci's mock SNS mobile push surface: iOS (APNS / APNS_SANDBOX), Android
 * (GCM / FCM), and the error codes the real AWS SNS API returns for push.
 */
class SnsMobilePushTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private SnsService snsService;

    @BeforeEach
    void setUp() {
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT);
        snsService = new SnsService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                regionResolver,
                null,
                null);
    }

    // --- CreatePlatformApplication ---

    @Test
    void createPlatformApplication_apnsReturnsArn() {
        PlatformApplication app = snsService.createPlatformApplication(
                "ios-app", "APNS", Map.of("PlatformCredential", "fake-cert"), REGION);
        assertEquals("arn:aws:sns:us-east-1:000000000000:app/APNS/ios-app", app.getArn());
        assertEquals("APNS", app.getPlatform());
        assertEquals("true", app.getAttributes().get("Enabled"));
    }

    @Test
    void createPlatformApplication_gcmReturnsArn() {
        PlatformApplication app = snsService.createPlatformApplication(
                "android-app", "GCM", Map.of("PlatformCredential", "fake-key"), REGION);
        assertEquals("arn:aws:sns:us-east-1:000000000000:app/GCM/android-app", app.getArn());
    }

    @Test
    void createPlatformApplication_rejectsUnsupportedPlatform() {
        AwsException e = assertThrows(AwsException.class,
                () -> snsService.createPlatformApplication("desktop", "WNS", Map.of(), REGION));
        assertEquals("InvalidParameter", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
    }

    @Test
    void createPlatformApplication_requiresName() {
        AwsException e = assertThrows(AwsException.class,
                () -> snsService.createPlatformApplication("", "APNS", Map.of(), REGION));
        assertEquals("InvalidParameter", e.getErrorCode());
    }

    @Test
    void createPlatformApplication_idempotentByName() {
        PlatformApplication first = snsService.createPlatformApplication("ios-app", "APNS", Map.of(), REGION);
        PlatformApplication second = snsService.createPlatformApplication("ios-app", "APNS", Map.of(), REGION);
        assertEquals(first.getArn(), second.getArn());
    }

    // --- CreatePlatformEndpoint ---

    @Test
    void createPlatformEndpoint_iosReturnsEndpointArn() {
        PlatformApplication app = snsService.createPlatformApplication("ios-app", "APNS", Map.of(), REGION);
        PlatformEndpoint endpoint = snsService.createPlatformEndpoint(
                app.getArn(), "ios-device-token-abc", "ios-user-42", Map.of(), REGION);
        assertTrue(endpoint.getArn().startsWith("arn:aws:sns:us-east-1:000000000000:endpoint/APNS/ios-app/"));
        assertEquals("ios-device-token-abc", endpoint.getToken());
        assertEquals("true", endpoint.getAttributes().get("Enabled"));
    }

    @Test
    void createPlatformEndpoint_androidReturnsEndpointArn() {
        PlatformApplication app = snsService.createPlatformApplication("android-app", "GCM", Map.of(), REGION);
        PlatformEndpoint endpoint = snsService.createPlatformEndpoint(
                app.getArn(), "fcm-registration-token-xyz", null, Map.of(), REGION);
        assertTrue(endpoint.getArn().contains(":endpoint/GCM/android-app/"));
    }

    @Test
    void createPlatformEndpoint_rejectsMissingToken() {
        PlatformApplication app = snsService.createPlatformApplication("ios-app", "APNS", Map.of(), REGION);
        AwsException e = assertThrows(AwsException.class,
                () -> snsService.createPlatformEndpoint(app.getArn(), null, null, Map.of(), REGION));
        assertEquals("InvalidParameter", e.getErrorCode());
    }

    @Test
    void createPlatformEndpoint_unknownApplicationArnReturnsNotFound() {
        AwsException e = assertThrows(AwsException.class,
                () -> snsService.createPlatformEndpoint(
                        "arn:aws:sns:us-east-1:000000000000:app/APNS/missing",
                        "token", null, Map.of(), REGION));
        assertEquals("NotFound", e.getErrorCode());
        assertEquals(404, e.getHttpStatus());
    }

    @Test
    void createPlatformEndpoint_sameTokenSameDataReturnsExisting() {
        PlatformApplication app = snsService.createPlatformApplication("ios-app", "APNS", Map.of(), REGION);
        PlatformEndpoint first = snsService.createPlatformEndpoint(app.getArn(), "tok-1", "user-7", Map.of(), REGION);
        PlatformEndpoint second = snsService.createPlatformEndpoint(app.getArn(), "tok-1", "user-7", Map.of(), REGION);
        assertEquals(first.getArn(), second.getArn());
    }

    @Test
    void createPlatformEndpoint_sameTokenDifferentDataRejected() {
        PlatformApplication app = snsService.createPlatformApplication("ios-app", "APNS", Map.of(), REGION);
        snsService.createPlatformEndpoint(app.getArn(), "tok-1", "user-7", Map.of(), REGION);
        AwsException e = assertThrows(AwsException.class,
                () -> snsService.createPlatformEndpoint(app.getArn(), "tok-1", "user-99", Map.of(), REGION));
        assertEquals("InvalidParameter", e.getErrorCode());
        assertTrue(e.getMessage().contains("already exists with the same Token"));
    }

    @Test
    void createPlatformEndpoint_rejectsWhenPlatformAppDisabled() {
        PlatformApplication app = snsService.createPlatformApplication("ios-app", "APNS", Map.of(), REGION);
        snsService.setPlatformApplicationAttributes(app.getArn(), Map.of("Enabled", "false"), REGION);
        AwsException e = assertThrows(AwsException.class,
                () -> snsService.createPlatformEndpoint(app.getArn(), "tok-1", null, Map.of(), REGION));
        assertEquals("PlatformApplicationDisabledException", e.getErrorCode());
    }

    // --- Publish: happy paths ---

    @Test
    void publish_iosDirectToEndpointCapturesPayload() {
        PlatformApplication app = snsService.createPlatformApplication("ios-app", "APNS", Map.of(), REGION);
        PlatformEndpoint endpoint = snsService.createPlatformEndpoint(
                app.getArn(), "ios-token", null, Map.of(), REGION);

        String messageId = snsService.publish(null, endpoint.getArn(), null,
                "{\"aps\":{\"alert\":\"hi iOS\"}}", null, null, null, null, null, REGION);
        assertNotNull(messageId);

        List<PushNotification> captured = snsService.peekPushNotifications(endpoint.getArn());
        assertEquals(1, captured.size());
        assertEquals("APNS", captured.get(0).platform());
        assertEquals("ios-token", captured.get(0).token());
        assertTrue(captured.get(0).payload().contains("hi iOS"));
    }

    @Test
    void publish_androidDirectToEndpointCapturesPayload() {
        PlatformApplication app = snsService.createPlatformApplication("android-app", "GCM", Map.of(), REGION);
        PlatformEndpoint endpoint = snsService.createPlatformEndpoint(
                app.getArn(), "fcm-token", null, Map.of(), REGION);

        String messageId = snsService.publish(null, endpoint.getArn(), null,
                "{\"notification\":{\"body\":\"hi Android\"}}", null, null, null, null, null, REGION);
        assertNotNull(messageId);

        List<PushNotification> captured = snsService.peekPushNotifications(endpoint.getArn());
        assertEquals(1, captured.size());
        assertEquals("GCM", captured.get(0).platform());
        assertTrue(captured.get(0).payload().contains("hi Android"));
    }

    @Test
    void publish_jsonStructureResolvesPlatformSpecificPayload() {
        PlatformApplication iosApp = snsService.createPlatformApplication("ios-app", "APNS", Map.of(), REGION);
        PlatformApplication andApp = snsService.createPlatformApplication("android-app", "GCM", Map.of(), REGION);
        PlatformEndpoint iosEndpoint = snsService.createPlatformEndpoint(iosApp.getArn(), "ios-token", null, Map.of(), REGION);
        PlatformEndpoint andEndpoint = snsService.createPlatformEndpoint(andApp.getArn(), "fcm-token", null, Map.of(), REGION);

        String envelope = "{"
                + "\"default\":\"plain fallback\","
                + "\"APNS\":\"{\\\"aps\\\":{\\\"alert\\\":\\\"ios body\\\"}}\","
                + "\"GCM\":\"{\\\"notification\\\":{\\\"body\\\":\\\"android body\\\"}}\""
                + "}";

        snsService.publish(null, iosEndpoint.getArn(), null, envelope, null, "json", null, null, null, REGION);
        snsService.publish(null, andEndpoint.getArn(), null, envelope, null, "json", null, null, null, REGION);

        assertTrue(snsService.peekPushNotifications(iosEndpoint.getArn()).get(0).payload().contains("ios body"));
        assertTrue(snsService.peekPushNotifications(andEndpoint.getArn()).get(0).payload().contains("android body"));
    }

    @Test
    void publish_jsonStructureFallsBackToDefaultWhenPlatformKeyMissing() {
        PlatformApplication app = snsService.createPlatformApplication("ios-app", "APNS", Map.of(), REGION);
        PlatformEndpoint endpoint = snsService.createPlatformEndpoint(app.getArn(), "ios-token", null, Map.of(), REGION);

        snsService.publish(null, endpoint.getArn(), null,
                "{\"default\":\"fallback\"}", null, "json", null, null, null, REGION);
        assertEquals("fallback", snsService.peekPushNotifications(endpoint.getArn()).get(0).payload());
    }

    // --- Publish: error codes ---

    @Test
    void publish_jsonStructureRejectsMissingDefaultKey() {
        PlatformApplication app = snsService.createPlatformApplication("ios-app", "APNS", Map.of(), REGION);
        PlatformEndpoint endpoint = snsService.createPlatformEndpoint(app.getArn(), "ios-token", null, Map.of(), REGION);

        AwsException e = assertThrows(AwsException.class, () -> snsService.publish(
                null, endpoint.getArn(), null, "{\"APNS\":\"x\"}", null, "json",
                null, null, null, REGION));
        assertEquals("InvalidParameter", e.getErrorCode());
        assertTrue(e.getMessage().contains("default"));
    }

    @Test
    void publish_jsonStructureRejectsInvalidJson() {
        PlatformApplication app = snsService.createPlatformApplication("ios-app", "APNS", Map.of(), REGION);
        PlatformEndpoint endpoint = snsService.createPlatformEndpoint(app.getArn(), "ios-token", null, Map.of(), REGION);

        AwsException e = assertThrows(AwsException.class, () -> snsService.publish(
                null, endpoint.getArn(), null, "not json at all", null, "json",
                null, null, null, REGION));
        assertEquals("InvalidParameter", e.getErrorCode());
    }

    @Test
    void publish_disabledEndpointThrowsEndpointDisabledException() {
        PlatformApplication app = snsService.createPlatformApplication("ios-app", "APNS", Map.of(), REGION);
        PlatformEndpoint endpoint = snsService.createPlatformEndpoint(app.getArn(), "ios-token", null, Map.of(), REGION);
        snsService.setEndpointAttributes(endpoint.getArn(), Map.of("Enabled", "false"), REGION);

        AwsException e = assertThrows(AwsException.class, () -> snsService.publish(
                null, endpoint.getArn(), null, "hello", null, null, null, null, null, REGION));
        assertEquals("EndpointDisabledException", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
        assertTrue(snsService.peekPushNotifications(endpoint.getArn()).isEmpty());
    }

    @Test
    void publish_expiredSentinelTokenCreatesDisabledEndpointAndPublishFails() {
        PlatformApplication app = snsService.createPlatformApplication("ios-app", "APNS", Map.of(), REGION);
        PlatformEndpoint endpoint = snsService.createPlatformEndpoint(
                app.getArn(), "EXPIRED-ios-device", null, Map.of(), REGION);
        assertEquals("false", endpoint.getAttributes().get("Enabled"));

        AwsException e = assertThrows(AwsException.class, () -> snsService.publish(
                null, endpoint.getArn(), null, "hello", null, null, null, null, null, REGION));
        assertEquals("EndpointDisabledException", e.getErrorCode());
    }

    @Test
    void publish_unknownEndpointArnReturnsNotFound() {
        AwsException e = assertThrows(AwsException.class, () -> snsService.publish(
                null,
                "arn:aws:sns:us-east-1:000000000000:endpoint/APNS/missing/" + java.util.UUID.randomUUID(),
                null, "hello", null, null, null, null, null, REGION));
        assertEquals("NotFound", e.getErrorCode());
        assertEquals(404, e.getHttpStatus());
    }

    @Test
    void publish_platformApplicationArnAsTargetIsRejected() {
        PlatformApplication app = snsService.createPlatformApplication("ios-app", "APNS", Map.of(), REGION);
        AwsException e = assertThrows(AwsException.class, () -> snsService.publish(
                null, app.getArn(), null, "hello", null, null, null, null, null, REGION));
        assertEquals("InvalidParameter", e.getErrorCode());
    }

    @Test
    void publish_disabledPlatformApplicationThrowsForEnabledEndpoint() {
        PlatformApplication app = snsService.createPlatformApplication("ios-app", "APNS", Map.of(), REGION);
        PlatformEndpoint endpoint = snsService.createPlatformEndpoint(app.getArn(), "ios-token", null, Map.of(), REGION);
        snsService.setPlatformApplicationAttributes(app.getArn(), Map.of("Enabled", "false"), REGION);

        AwsException e = assertThrows(AwsException.class, () -> snsService.publish(
                null, endpoint.getArn(), null, "hello", null, null, null, null, null, REGION));
        assertEquals("PlatformApplicationDisabledException", e.getErrorCode());
    }

    // --- Inspection helpers ---

    @Test
    void peekPushNotifications_filtersByEndpointArn() {
        PlatformApplication app = snsService.createPlatformApplication("ios-app", "APNS", Map.of(), REGION);
        PlatformEndpoint a = snsService.createPlatformEndpoint(app.getArn(), "tok-a", null, Map.of(), REGION);
        PlatformEndpoint b = snsService.createPlatformEndpoint(app.getArn(), "tok-b", null, Map.of(), REGION);
        snsService.publish(null, a.getArn(), null, "to-a", null, null, null, null, null, REGION);
        snsService.publish(null, b.getArn(), null, "to-b", null, null, null, null, null, REGION);
        snsService.publish(null, a.getArn(), null, "to-a-again", null, null, null, null, null, REGION);

        assertEquals(2, snsService.peekPushNotifications(a.getArn()).size());
        assertEquals(1, snsService.peekPushNotifications(b.getArn()).size());
        assertEquals(3, snsService.peekPushNotifications(null).size());

        snsService.clearPushNotifications();
        assertTrue(snsService.peekPushNotifications(null).isEmpty());
    }

    // --- Endpoint lifecycle ---

    @Test
    void deleteEndpoint_isIdempotent() {
        assertDoesNotThrow(() -> snsService.deleteEndpoint(
                "arn:aws:sns:us-east-1:000000000000:endpoint/APNS/x/" + java.util.UUID.randomUUID(), REGION));
    }

    @Test
    void getEndpointAttributes_returnsTokenEnabledAndCustomData() {
        PlatformApplication app = snsService.createPlatformApplication("ios-app", "APNS", Map.of(), REGION);
        PlatformEndpoint endpoint = snsService.createPlatformEndpoint(app.getArn(), "ios-token", "user-42", Map.of(), REGION);

        Map<String, String> attrs = snsService.getEndpointAttributes(endpoint.getArn(), REGION);
        assertEquals("ios-token", attrs.get("Token"));
        assertEquals("user-42", attrs.get("CustomUserData"));
        assertEquals("true", attrs.get("Enabled"));
    }

    @Test
    void listEndpointsByPlatformApplication_returnsAllEndpointsForApp() {
        PlatformApplication app = snsService.createPlatformApplication("ios-app", "APNS", Map.of(), REGION);
        snsService.createPlatformEndpoint(app.getArn(), "tok-1", null, Map.of(), REGION);
        snsService.createPlatformEndpoint(app.getArn(), "tok-2", null, Map.of(), REGION);
        assertEquals(2, snsService.listEndpointsByPlatformApplication(app.getArn(), REGION).size());
    }

    @Test
    void deletePlatformApplication_cascadesEndpoints() {
        PlatformApplication app = snsService.createPlatformApplication("ios-app", "APNS", Map.of(), REGION);
        snsService.createPlatformEndpoint(app.getArn(), "tok-1", null, Map.of(), REGION);
        snsService.deletePlatformApplication(app.getArn(), REGION);
        assertTrue(snsService.listEndpointsByPlatformApplication(app.getArn(), REGION).isEmpty());
        assertTrue(snsService.listPlatformApplications(REGION).isEmpty());
    }
}
