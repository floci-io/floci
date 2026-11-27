package io.floci.aws.services.rolesanywhere;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.rolesanywhere.model.AttributeMapping;
import io.floci.aws.services.rolesanywhere.model.Crl;
import io.floci.aws.services.rolesanywhere.model.NotificationSetting;
import io.floci.aws.services.rolesanywhere.model.Profile;
import io.floci.aws.services.rolesanywhere.model.Subject;
import io.floci.aws.services.rolesanywhere.model.TrustAnchor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RolesAnywhereServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String ROLE_ARN = "arn:aws:iam::" + ACCOUNT + ":role/workload";
    private static final String UUID_REGEX = "[a-f0-9]{8}-([a-f0-9]{4}-){3}[a-f0-9]{12}";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AccountAwareStorageBackend<TrustAnchor> trustAnchors;
    private AccountAwareStorageBackend<Profile> profiles;
    private AccountAwareStorageBackend<Crl> crls;
    private AccountAwareStorageBackend<Subject> subjects;
    private RolesAnywhereService service;

    @BeforeEach
    void setUp() {
        trustAnchors = AccountAwareStorageBackend.inMemory(ACCOUNT);
        profiles = AccountAwareStorageBackend.inMemory(ACCOUNT);
        crls = AccountAwareStorageBackend.inMemory(ACCOUNT);
        subjects = AccountAwareStorageBackend.inMemory(ACCOUNT);
        service = new RolesAnywhereService(trustAnchors, profiles, crls, subjects,
                new RegionResolver(REGION, ACCOUNT));
    }

    // ------------------------------------------------------------ trust anchors

    @Test
    void createTrustAnchorMintsArnsAndAppliesTheDefaults() {
        TrustAnchor anchor = service.createTrustAnchor(anchorRequest("corp-ca"), REGION);

        assertThat(anchor.getTrustAnchorId(), matchesPattern(UUID_REGEX));
        assertThat(anchor.getTrustAnchorArn(), equalTo(
                "arn:aws:rolesanywhere:us-east-1:" + ACCOUNT + ":trust-anchor/" + anchor.getTrustAnchorId()));
        assertThat(anchor.getName(), equalTo("corp-ca"));
        assertThat(anchor.getSourceType(), equalTo("CERTIFICATE_BUNDLE"));
        assertThat(anchor.getX509CertificateData(), equalTo(RolesAnywhereTestMaterial.CA_PEM));
        assertThat(anchor.isEnabled(), is(true));
        assertThat(anchor.getCrlDpRevocationEnabled(), is(false));
        assertThat(anchor.getCreatedAt(), notNullValue());
        assertThat(anchor.getAliasName(), nullValue());
        List<NotificationSetting> settings = anchor.getNotificationSettings();
        assertThat(settings, hasSize(2));
        assertThat(settings.get(0).getEvent(), equalTo("CA_CERTIFICATE_EXPIRY"));
        assertThat(settings.get(1).getEvent(), equalTo("END_ENTITY_CERTIFICATE_EXPIRY"));
        for (NotificationSetting setting : settings) {
            assertThat(setting.isEnabled(), is(true));
            assertThat(setting.getThreshold(), equalTo(45));
            assertThat(setting.getChannel(), equalTo("ALL"));
            assertThat(setting.getConfiguredBy(), equalTo(ServicePrincipals.of("rolesanywhere")));
        }
    }

    @Test
    void createTrustAnchorKeepsWhatTheRequestSets() {
        ObjectNode request = anchorRequest("self-signed");
        request.with("source").put("sourceType", "SELF_SIGNED_REPOSITORY");
        request.put("enabled", false);
        request.put("crlDpRevocationEnabled", true);
        request.put("aliasName", "corp/root");
        request.set("tags", tags("team", "identity"));

        TrustAnchor anchor = service.createTrustAnchor(request, REGION);

        assertThat(anchor.getSourceType(), equalTo("SELF_SIGNED_REPOSITORY"));
        assertThat(anchor.isEnabled(), is(false));
        assertThat(anchor.getCrlDpRevocationEnabled(), is(true));
        assertThat(anchor.getAliasName(), equalTo("corp/root"));
        assertThat(anchor.getAliasArn(), equalTo(
                "arn:aws:rolesanywhere:us-east-1:" + ACCOUNT + ":alias/trust-anchor/corp/root"));
        assertThat(anchor.getTags(), equalTo(Map.of("team", "identity")));
    }

    @Test
    void aTrustAnchorIsFoundByItsIdItsArnAndItsAliasArn() {
        ObjectNode request = anchorRequest("lookup");
        request.put("aliasName", "lookup-alias");
        TrustAnchor anchor = service.createTrustAnchor(request, REGION);

        assertThat(service.getTrustAnchor(anchor.getTrustAnchorId(), REGION).getName(), equalTo("lookup"));
        assertThat(service.getTrustAnchor(anchor.getTrustAnchorArn(), REGION).getName(), equalTo("lookup"));
        assertThat(service.getTrustAnchor(anchor.getAliasArn(), REGION).getName(), equalTo("lookup"));
    }

    @Test
    void anIdentifierOfAnotherAccountOrRegionOrOfNothingIsNotFound() {
        TrustAnchor anchor = service.createTrustAnchor(anchorRequest("scoped"), REGION);
        String id = anchor.getTrustAnchorId();

        assertNotFound(() -> service.getTrustAnchor(id, "eu-west-1"));
        assertNotFound(() -> service.getTrustAnchor(
                "arn:aws:rolesanywhere:us-east-1:111122223333:trust-anchor/" + id, REGION));
        assertNotFound(() -> service.getTrustAnchor(
                "arn:aws:rolesanywhere:eu-west-1:" + ACCOUNT + ":trust-anchor/" + id, REGION));
        assertNotFound(() -> service.getTrustAnchor(
                "arn:aws:rolesanywhere:us-east-1:" + ACCOUNT + ":alias/trust-anchor/missing", REGION));
        assertNotFound(() -> service.getTrustAnchor("00000000-0000-0000-0000-000000000000", REGION));
    }

    @Test
    void anIdentifierOutsideTheModelPatternIsAValidationError() {
        AwsException error = assertThrows(AwsException.class, () -> service.getTrustAnchor("not-an-id", REGION));

        assertThat(error.getErrorCode(), equalTo("ValidationException"));
        assertThat(error.getHttpStatus(), equalTo(400));
        assertThat(error.getMessage(), startsWith(
                "1 validation error detected: Value 'not-an-id' at 'trustAnchorId' failed to satisfy constraint: "
                        + "Member must satisfy regular expression pattern: "));
    }

    @Test
    void createTrustAnchorReportsEveryViolationInTheSmithyShape() {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("name", "bad/name");

        AwsException error = assertThrows(AwsException.class, () -> service.createTrustAnchor(request, REGION));

        assertThat(error.getErrorCode(), equalTo("ValidationException"));
        assertThat(error.getMessage(), equalTo("2 validation errors detected: "
                + "Value 'bad/name' at 'name' failed to satisfy constraint: Member must satisfy regular expression "
                + "pattern: [ a-zA-Z0-9-_]*; "
                + "Value null at 'source' failed to satisfy constraint: Member must not be null"));
    }

    @Test
    void anUnknownSourceTypeAndAnUnparseableCertificateAreRefused() {
        ObjectNode unknown = anchorRequest("unknown");
        unknown.with("source").put("sourceType", "CERTIFICATE_STORE");
        AwsException enumError = assertThrows(AwsException.class, () -> service.createTrustAnchor(unknown, REGION));
        assertThat(enumError.getMessage(), containsString("Value 'CERTIFICATE_STORE' at 'source.sourceType' failed "
                + "to satisfy constraint: Member must satisfy enum value set: "
                + "[AWS_ACM_PCA, CERTIFICATE_BUNDLE, SELF_SIGNED_REPOSITORY]"));

        ObjectNode garbage = anchorRequest("garbage");
        garbage.with("source").with("sourceData").put("x509CertificateData",
                "-----BEGIN CERTIFICATE-----\nnot base64 at all\n-----END CERTIFICATE-----\n");
        AwsException pemError = assertThrows(AwsException.class, () -> service.createTrustAnchor(garbage, REGION));
        assertThat(pemError.getErrorCode(), equalTo("ValidationException"));
        assertThat(pemError.getMessage(), containsString("x509CertificateData"));

        ObjectNode text = anchorRequest("text");
        text.with("source").with("sourceData").put("x509CertificateData", "hello");
        assertThrows(AwsException.class, () -> service.createTrustAnchor(text, REGION));
    }

    @Test
    void anAcmPcaTrustAnchorIsRefusedBecausePrivateCaIsNotEmulated() {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("name", "pca");
        ObjectNode source = request.putObject("source");
        source.put("sourceType", "AWS_ACM_PCA");
        source.putObject("sourceData").put("acmPcaArn",
                "arn:aws:acm-pca:us-east-1:" + ACCOUNT + ":certificate-authority/abc");

        AwsException error = assertThrows(AwsException.class, () -> service.createTrustAnchor(request, REGION));

        assertThat(error.getErrorCode(), equalTo("ValidationException"));
        assertThat(error.getMessage(), containsString("Floci does not emulate AWS Private Certificate Authority"));
        assertThat(trustAnchors.scan(key -> true), empty());
    }

    @Test
    void enableAndDisableFlipTheFlagAndUpdateKeepsWhatItIsNotGiven() {
        TrustAnchor anchor = service.createTrustAnchor(anchorRequest("toggle"), REGION);
        String id = anchor.getTrustAnchorId();

        assertThat(service.setTrustAnchorEnabled(id, false, REGION).isEnabled(), is(false));
        assertThat(service.getTrustAnchor(id, REGION).isEnabled(), is(false));
        assertThat(service.setTrustAnchorEnabled(anchor.getTrustAnchorArn(), true, REGION).isEnabled(), is(true));

        ObjectNode update = objectMapper.createObjectNode();
        update.put("name", "renamed");
        update.put("crlDpRevocationEnabled", true);
        TrustAnchor updated = service.updateTrustAnchor(id, update, REGION);
        assertThat(updated.getName(), equalTo("renamed"));
        assertThat(updated.getCrlDpRevocationEnabled(), is(true));
        assertThat(updated.getX509CertificateData(), equalTo(RolesAnywhereTestMaterial.CA_PEM));
        assertThat(updated.getNotificationSettings(), hasSize(2));
    }

    @Test
    void anAliasIsUniquePerTypeAndAChangeRecordsThePreviousOne() {
        ObjectNode first = anchorRequest("first");
        first.put("aliasName", "shared");
        TrustAnchor anchor = service.createTrustAnchor(first, REGION);
        ObjectNode second = anchorRequest("second");
        second.put("aliasName", "shared");

        AwsException clash = assertThrows(AwsException.class, () -> service.createTrustAnchor(second, REGION));
        assertThat(clash.getErrorCode(), equalTo("ValidationException"));

        // Another type may use the same alias name.
        ObjectNode profileRequest = profileRequest("profile");
        profileRequest.put("aliasName", "shared");
        assertThat(service.createProfile(profileRequest, REGION).getAliasName(), equalTo("shared"));

        ObjectNode rename = objectMapper.createObjectNode();
        rename.put("aliasName", "renamed");
        TrustAnchor renamed = service.updateTrustAnchor(anchor.getTrustAnchorId(), rename, REGION);
        assertThat(renamed.getAliasName(), equalTo("renamed"));
        assertThat(renamed.getPreviousAliasName(), equalTo("shared"));
        assertThat(renamed.getAliasArn(), equalTo(
                "arn:aws:rolesanywhere:us-east-1:" + ACCOUNT + ":alias/trust-anchor/renamed"));
        assertNotFound(() -> service.getTrustAnchor(
                "arn:aws:rolesanywhere:us-east-1:" + ACCOUNT + ":alias/trust-anchor/shared", REGION));

        ObjectNode badAlias = objectMapper.createObjectNode();
        badAlias.put("aliasName", "1-starts-with-a-digit");
        assertThrows(AwsException.class, () -> service.updateTrustAnchor(anchor.getTrustAnchorId(), badAlias, REGION));
    }

    @Test
    void deletingATrustAnchorDeletesItsCrlsAndReturnsTheAnchor() {
        TrustAnchor anchor = service.createTrustAnchor(anchorRequest("doomed"), REGION);
        TrustAnchor kept = service.createTrustAnchor(anchorRequest("kept"), REGION);
        Crl doomedCrl = service.importCrl(crlRequest("doomed-crl", anchor.getTrustAnchorArn()), REGION);
        Crl keptCrl = service.importCrl(crlRequest("kept-crl", kept.getTrustAnchorArn()), REGION);

        TrustAnchor deleted = service.deleteTrustAnchor(anchor.getTrustAnchorId(), REGION);

        assertThat(deleted.getName(), equalTo("doomed"));
        assertNotFound(() -> service.getTrustAnchor(anchor.getTrustAnchorId(), REGION));
        assertNotFound(() -> service.getCrl(doomedCrl.getCrlId(), REGION));
        assertThat(service.getCrl(keptCrl.getCrlId(), REGION).getName(), equalTo("kept-crl"));
    }

    @Test
    void putNotificationSettingsReplacesTheSettingAndResetRestoresTheDefault() {
        TrustAnchor anchor = service.createTrustAnchor(anchorRequest("notify"), REGION);
        ObjectNode put = objectMapper.createObjectNode();
        put.put("trustAnchorId", anchor.getTrustAnchorId());
        ObjectNode setting = put.putArray("notificationSettings").addObject();
        setting.put("enabled", false);
        setting.put("event", "CA_CERTIFICATE_EXPIRY");
        setting.put("threshold", 10);

        TrustAnchor changed = service.putNotificationSettings(put, REGION);

        assertThat(changed.getNotificationSettings(), hasSize(2));
        NotificationSetting ca = changed.getNotificationSettings().get(0);
        assertThat(ca.getEvent(), equalTo("CA_CERTIFICATE_EXPIRY"));
        assertThat(ca.isEnabled(), is(false));
        assertThat(ca.getThreshold(), equalTo(10));
        assertThat(ca.getChannel(), equalTo("ALL"));
        assertThat(ca.getConfiguredBy(), equalTo(ACCOUNT));

        ObjectNode reset = objectMapper.createObjectNode();
        reset.put("trustAnchorId", anchor.getTrustAnchorId());
        reset.putArray("notificationSettingKeys").addObject().put("event", "CA_CERTIFICATE_EXPIRY");
        NotificationSetting restored = service.resetNotificationSettings(reset, REGION).getNotificationSettings()
                .get(0);
        assertThat(restored.isEnabled(), is(true));
        assertThat(restored.getThreshold(), equalTo(45));
        assertThat(restored.getConfiguredBy(), equalTo(ServicePrincipals.of("rolesanywhere")));
    }

    @Test
    void notificationSettingsAreValidatedAgainstTheModel() {
        TrustAnchor anchor = service.createTrustAnchor(anchorRequest("limits"), REGION);
        ObjectNode put = objectMapper.createObjectNode();
        put.put("trustAnchorId", anchor.getTrustAnchorId());
        ObjectNode setting = put.putArray("notificationSettings").addObject();
        setting.put("event", "SOMETHING_ELSE");
        setting.put("threshold", 361);

        AwsException error = assertThrows(AwsException.class, () -> service.putNotificationSettings(put, REGION));

        assertThat(error.getMessage(), startsWith("3 validation errors detected: "));
        assertThat(error.getMessage(), containsString("'notificationSettings.1.member.enabled'"));
        assertThat(error.getMessage(), containsString("'notificationSettings.1.member.event'"));
        assertThat(error.getMessage(), containsString("Value '361' at 'notificationSettings.1.member.threshold' "
                + "failed to satisfy constraint: Member must have value less than or equal to 360"));

        ObjectNode byArn = objectMapper.createObjectNode();
        byArn.put("trustAnchorId", anchor.getTrustAnchorArn());
        byArn.putArray("notificationSettings");
        assertThrows(AwsException.class, () -> service.putNotificationSettings(byArn, REGION));
    }

    // ----------------------------------------------------------------- profiles

    @Test
    void createProfileAppliesTheDefaults() {
        Profile profile = service.createProfile(profileRequest("workload"), REGION);

        assertThat(profile.getProfileArn(), equalTo(
                "arn:aws:rolesanywhere:us-east-1:" + ACCOUNT + ":profile/" + profile.getProfileId()));
        assertThat(profile.getDurationSeconds(), equalTo(3600));
        assertThat(profile.isEnabled(), is(true));
        assertThat(profile.getRequireInstanceProperties(), is(false));
        assertThat(profile.getAcceptRoleSessionName(), is(false));
        assertThat(profile.getRoleArns(), contains(ROLE_ARN));
        assertThat(profile.getManagedPolicyArns(), empty());
        assertThat(profile.getCreatedBy(), equalTo("arn:aws:iam::" + ACCOUNT + ":root"));
        List<String> fields = new ArrayList<>();
        for (AttributeMapping mapping : profile.getAttributeMappings()) {
            fields.add(mapping.getCertificateField());
        }
        assertThat(fields, contains("x509Subject", "x509Issuer", "x509SAN"));
        assertThat(profile.getAttributeMappings().get(0).getSpecifiers(),
                contains("CN", "OU", "O", "C", "ST", "L"));
        assertThat(profile.getAttributeMappings().get(2).getSpecifiers(), contains("Name/DNS", "Name/URI"));
    }

    @Test
    void createProfileValidatesDurationRolesAndThePolicyDocument() {
        ObjectNode shortSession = profileRequest("short");
        shortSession.put("durationSeconds", 899);
        AwsException duration = assertThrows(AwsException.class, () -> service.createProfile(shortSession, REGION));
        assertThat(duration.getMessage(), equalTo("1 validation error detected: Value '899' at 'durationSeconds' "
                + "failed to satisfy constraint: Member must have value greater than or equal to 900"));

        ObjectNode badRole = profileRequest("bad-role");
        badRole.putArray("roleArns").add("arn:aws:iam::" + ACCOUNT + ":user/someone");
        AwsException role = assertThrows(AwsException.class, () -> service.createProfile(badRole, REGION));
        assertThat(role.getMessage(), containsString("at 'roleArns.1.member' failed to satisfy constraint: "
                + "Member must satisfy regular expression pattern"));

        ObjectNode noRoles = objectMapper.createObjectNode();
        noRoles.put("name", "no-roles");
        AwsException missing = assertThrows(AwsException.class, () -> service.createProfile(noRoles, REGION));
        assertThat(missing.getMessage(), equalTo("1 validation error detected: Value null at 'roleArns' failed to "
                + "satisfy constraint: Member must not be null"));

        ObjectNode tooMany = profileRequest("too-many");
        ArrayNode policies = tooMany.putArray("managedPolicyArns");
        for (int i = 0; i < 51; i++) {
            policies.add("arn:aws:iam::aws:policy/Policy" + i);
        }
        assertThrows(AwsException.class, () -> service.createProfile(tooMany, REGION));

        ObjectNode badPolicy = profileRequest("bad-policy");
        badPolicy.put("sessionPolicy", "not json");
        AwsException policy = assertThrows(AwsException.class, () -> service.createProfile(badPolicy, REGION));
        assertThat(policy.getMessage(), containsString("sessionPolicy"));
    }

    @Test
    void updateProfileChangesOnlyWhatItIsGivenAndTheTipConfigurationIsKeptVerbatim() {
        ObjectNode request = profileRequest("tip");
        request.putObject("tipConfiguration").put("status", "ENABLED").put("subjectType", "workload")
                .put("subjectSource", "x509Subject/CN");
        Profile profile = service.createProfile(request, REGION);
        assertThat(profile.getTipConfiguration().path("subjectSource").asText(), equalTo("x509Subject/CN"));

        ObjectNode update = objectMapper.createObjectNode();
        update.put("durationSeconds", 7200);
        update.put("sessionPolicy", "{\"Version\":\"2012-10-17\",\"Statement\":[]}");
        Profile updated = service.updateProfile(profile.getProfileArn(), update, REGION);

        assertThat(updated.getDurationSeconds(), equalTo(7200));
        assertThat(updated.getSessionPolicy(), containsString("2012-10-17"));
        assertThat(updated.getName(), equalTo("tip"));
        assertThat(updated.getRoleArns(), contains(ROLE_ARN));
        assertThat(updated.getTipConfiguration().path("status").asText(), equalTo("ENABLED"));

        ObjectNode badTip = objectMapper.createObjectNode();
        badTip.putObject("tipConfiguration").put("subjectType", "machine");
        AwsException error = assertThrows(AwsException.class,
                () -> service.updateProfile(profile.getProfileId(), badTip, REGION));
        assertThat(error.getMessage(), startsWith("2 validation errors detected: "));
    }

    @Test
    void attributeMappingsAreReplacedPerFieldAndDeletedBySpecifierOrWhole() {
        Profile profile = service.createProfile(profileRequest("mappings"), REGION);
        String id = profile.getProfileId();
        ObjectNode put = objectMapper.createObjectNode();
        put.put("certificateField", "x509Subject");
        put.putArray("mappingRules").add(objectMapper.createObjectNode().put("specifier", "CN"))
                .add(objectMapper.createObjectNode().put("specifier", "O"));

        Profile mapped = service.putAttributeMapping(id, put, REGION);
        assertThat(mapped.getAttributeMappings().get(0).getSpecifiers(), contains("CN", "O"));

        Profile narrowed = service.deleteAttributeMapping(id, "x509Subject", List.of("O"), REGION);
        assertThat(narrowed.getAttributeMappings().get(0).getSpecifiers(), contains("CN"));

        Profile dropped = service.deleteAttributeMapping(id, "x509SAN", List.of(), REGION);
        assertThat(dropped.getAttributeMappings(), hasSize(2));

        AwsException field = assertThrows(AwsException.class,
                () -> service.deleteAttributeMapping(id, "x509Other", null, REGION));
        assertThat(field.getMessage(), containsString("Member must satisfy enum value set: "
                + "[x509Subject, x509Issuer, x509SAN]"));

        ObjectNode longSpecifier = objectMapper.createObjectNode();
        longSpecifier.put("certificateField", "x509SAN");
        longSpecifier.putArray("mappingRules").addObject().put("specifier", "x".repeat(61));
        assertThrows(AwsException.class, () -> service.putAttributeMapping(id, longSpecifier, REGION));
    }

    @Test
    void aProfileIsFoundByItsAliasArnAndEnableDisableAndDeleteReturnIt() {
        ObjectNode request = profileRequest("aliased");
        request.put("aliasName", "team/app");
        Profile profile = service.createProfile(request, REGION);
        String alias = "arn:aws:rolesanywhere:us-east-1:" + ACCOUNT + ":alias/profile/team/app";
        assertThat(profile.getAliasArn(), equalTo(alias));

        assertThat(service.setProfileEnabled(alias, false, REGION).isEnabled(), is(false));
        assertThat(service.setProfileEnabled(alias, true, REGION).isEnabled(), is(true));
        assertThat(service.deleteProfile(alias, REGION).getProfileId(), equalTo(profile.getProfileId()));
        assertNotFound(() -> service.getProfile(profile.getProfileId(), REGION));
    }

    // --------------------------------------------------------------------- CRLs

    @Test
    void importCrlParsesTheCrlAndNamesTheTrustAnchor() {
        TrustAnchor anchor = service.createTrustAnchor(anchorRequest("crl-anchor"), REGION);

        Crl crl = service.importCrl(crlRequest("revoked", anchor.getTrustAnchorArn()), REGION);

        assertThat(crl.getCrlArn(), equalTo("arn:aws:rolesanywhere:us-east-1:" + ACCOUNT + ":crl/" + crl.getCrlId()));
        assertThat(crl.getTrustAnchorArn(), equalTo(anchor.getTrustAnchorArn()));
        assertThat(crl.getCrlData(), equalTo(RolesAnywhereTestMaterial.crlData()));
        assertThat(crl.isEnabled(), is(true));
        assertThat(service.setCrlEnabled(crl.getCrlArn(), false, REGION).isEnabled(), is(false));
    }

    @Test
    void importCrlRefusesDataThatIsNotACrlAndAnAnchorThatDoesNotExist() {
        TrustAnchor anchor = service.createTrustAnchor(anchorRequest("crl-checks"), REGION);
        ObjectNode garbage = crlRequest("garbage", anchor.getTrustAnchorArn());
        garbage.put("crlData", Base64.getEncoder().encodeToString("not a crl".getBytes(StandardCharsets.US_ASCII)));
        AwsException parse = assertThrows(AwsException.class, () -> service.importCrl(garbage, REGION));
        assertThat(parse.getErrorCode(), equalTo("ValidationException"));
        assertThat(parse.getMessage(), containsString("certificate revocation list"));

        String missingAnchor = "arn:aws:rolesanywhere:us-east-1:" + ACCOUNT
                + ":trust-anchor/00000000-0000-0000-0000-000000000000";
        assertNotFound(() -> service.importCrl(crlRequest("orphan", missingAnchor), REGION));

        ObjectNode profileArn = crlRequest("wrong", "arn:aws:rolesanywhere:us-east-1:" + ACCOUNT + ":profile/x");
        AwsException pattern = assertThrows(AwsException.class, () -> service.importCrl(profileArn, REGION));
        assertThat(pattern.getMessage(), containsString("at 'trustAnchorArn' failed to satisfy constraint"));
    }

    @Test
    void updateCrlReplacesTheDataTheNameAndTheAlias() {
        TrustAnchor anchor = service.createTrustAnchor(anchorRequest("crl-update"), REGION);
        Crl crl = service.importCrl(crlRequest("before", anchor.getTrustAnchorArn()), REGION);
        String der = Base64.getEncoder().encodeToString(Base64.getMimeDecoder().decode(
                RolesAnywhereTestMaterial.CRL_PEM.replace("-----BEGIN X509 CRL-----", "")
                        .replace("-----END X509 CRL-----", "")));
        ObjectNode update = objectMapper.createObjectNode();
        update.put("name", "after");
        update.put("aliasName", "crl-alias");
        update.put("crlData", der);

        Crl updated = service.updateCrl(crl.getCrlId(), update, REGION);

        assertThat(updated.getName(), equalTo("after"));
        assertThat(updated.getCrlData(), equalTo(der));
        assertThat(updated.getAliasArn(), equalTo(
                "arn:aws:rolesanywhere:us-east-1:" + ACCOUNT + ":alias/crl/crl-alias"));
        assertThat(service.getCrl(updated.getAliasArn(), REGION).getCrlId(), equalTo(crl.getCrlId()));
        assertThat(service.deleteCrl(crl.getCrlArn(), REGION).getName(), equalTo("after"));
    }

    // ------------------------------------------------------- lists and subjects

    @Test
    void listsPageInCreationOrderWithAnOpaqueToken() {
        List<String> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            created.add(service.createTrustAnchor(anchorRequest("page-" + i), REGION).getName());
        }
        service.createTrustAnchor(anchorRequest("elsewhere"), "eu-west-1");

        RolesAnywhereService.Page<TrustAnchor> first = service.listTrustAnchors("2", null, REGION);
        RolesAnywhereService.Page<TrustAnchor> second = service.listTrustAnchors("2", first.nextToken(), REGION);
        RolesAnywhereService.Page<TrustAnchor> last = service.listTrustAnchors("2", second.nextToken(), REGION);
        List<String> names = new ArrayList<>();
        for (RolesAnywhereService.Page<TrustAnchor> page : List.of(first, second, last)) {
            page.items().forEach(anchor -> names.add(anchor.getName()));
        }

        assertThat(names, equalTo(created));
        assertThat(last.nextToken(), nullValue());
        assertThat(service.listTrustAnchors(null, null, REGION).items(), hasSize(5));
        assertThrows(AwsException.class, () -> service.listTrustAnchors("0", null, REGION));
        assertThrows(AwsException.class, () -> service.listTrustAnchors("2", "garbage", REGION));
    }

    @Test
    void subjectsAreReadFromStorage() {
        Subject subject = new Subject();
        subject.setSubjectId("11111111-2222-3333-4444-555555555555");
        subject.setSubjectArn("arn:aws:rolesanywhere:us-east-1:" + ACCOUNT + ":subject/" + subject.getSubjectId());
        subject.setX509Subject("CN=workload");
        subject.setEnabled(true);
        subject.setCreatedAt(Instant.now());
        subject.setUpdatedAt(Instant.now());
        subjects.put("us-east-1::" + subject.getSubjectId(), subject);

        assertThat(service.getSubject(subject.getSubjectId(), REGION).getX509Subject(), equalTo("CN=workload"));
        assertThat(service.listSubjects(null, null, REGION).items(), hasSize(1));
        assertNotFound(() -> service.getSubject("99999999-2222-3333-4444-555555555555", REGION));
        assertThrows(AwsException.class, () -> service.getSubject("short", REGION));
    }

    // --------------------------------------------------------------------- tags

    @Test
    void tagsAreAddedListedRemovedAndExposedToIam() {
        TrustAnchor anchor = service.createTrustAnchor(anchorRequest("tagged"), REGION);
        String arn = anchor.getTrustAnchorArn();
        service.tagResource(tagRequest(arn, "team", "identity"), REGION);
        service.tagResource(tagRequest(arn, "env", "test"), REGION);

        assertThat(service.listTagsForResource(arn, REGION), equalTo(Map.of("team", "identity", "env", "test")));
        assertThat(service.storedTags(arn), equalTo(Map.of("team", "identity", "env", "test")));

        ObjectNode untag = objectMapper.createObjectNode();
        untag.put("resourceArn", arn);
        untag.putArray("tagKeys").add("env");
        service.untagResource(untag, REGION);

        assertThat(service.listTagsForResource(arn, REGION), equalTo(Map.of("team", "identity")));
        assertThat(service.storedTags("arn:aws:rolesanywhere:us-east-1:111122223333:trust-anchor/"
                + anchor.getTrustAnchorId()), equalTo(Map.of()));
    }

    @Test
    void aTagSetOverTwoHundredIsTooManyTagsAndASubjectIsNotTaggable() {
        Profile profile = service.createProfile(profileRequest("crowded"), REGION);
        ObjectNode request = objectMapper.createObjectNode();
        request.put("resourceArn", profile.getProfileArn());
        ArrayNode tags = request.putArray("tags");
        for (int i = 0; i < 200; i++) {
            tags.addObject().put("key", "k" + i).put("value", "v");
        }
        service.tagResource(request, REGION);

        AwsException tooMany = assertThrows(AwsException.class,
                () -> service.tagResource(tagRequest(profile.getProfileArn(), "one", "more"), REGION));
        assertThat(tooMany.getErrorCode(), equalTo("TooManyTagsException"));
        assertThat(tooMany.getHttpStatus(), equalTo(400));

        AwsException subject = assertThrows(AwsException.class, () -> service.tagResource(tagRequest(
                "arn:aws:rolesanywhere:us-east-1:" + ACCOUNT + ":subject/11111111-2222-3333-4444-555555555555",
                "a", "b"), REGION));
        assertThat(subject.getErrorCode(), equalTo("ValidationException"));

        assertNotFound(() -> service.listTagsForResource(
                "arn:aws:rolesanywhere:us-east-1:" + ACCOUNT + ":crl/00000000-0000-0000-0000-000000000000", REGION));
    }

    @Test
    void tagKeysAndValuesFollowTheModelPattern() {
        TrustAnchor anchor = service.createTrustAnchor(anchorRequest("patterns"), REGION);
        AwsException error = assertThrows(AwsException.class,
                () -> service.tagResource(tagRequest(anchor.getTrustAnchorArn(), "bad#key", "v"), REGION));

        assertThat(error.getMessage(), equalTo("1 validation error detected: Value 'bad#key' at 'tags.1.member.key' "
                + "failed to satisfy constraint: Member must satisfy regular expression pattern: "
                + "[ a-zA-Z0-9_.:/=+@-]*"));
    }

    @Test
    void resolveStoredArnReadsAnArnLabelAsTheResourceItNames() {
        ObjectNode request = anchorRequest("resolved");
        request.put("aliasName", "team/root");
        TrustAnchor anchor = service.createTrustAnchor(request, REGION);
        String format = "arn:aws:rolesanywhere:us-east-1:" + ACCOUNT + ":trust-anchor/";

        assertThat(service.resolveStoredArn(format + spelled(anchor.getTrustAnchorArn())),
                equalTo(Optional.of(anchor.getTrustAnchorArn())));
        assertThat(service.resolveStoredArn(format + spelled(anchor.getAliasArn())),
                equalTo(Optional.of(anchor.getTrustAnchorArn())));

        ObjectNode profileRequest = profileRequest("resolved");
        profileRequest.put("aliasName", "app");
        Profile profile = service.createProfile(profileRequest, REGION);
        assertThat(service.resolveStoredArn("arn:aws:rolesanywhere:us-east-1:" + ACCOUNT + ":profile/"
                + spelled(profile.getAliasArn())), equalTo(Optional.of(profile.getProfileArn())));
    }

    @Test
    void resolveStoredArnLeavesAUuidAForeignAccountAndAnotherServiceAlone() {
        TrustAnchor anchor = service.createTrustAnchor(anchorRequest("untouched"), REGION);
        String format = "arn:aws:rolesanywhere:us-east-1:" + ACCOUNT + ":trust-anchor/";

        assertThat(service.resolveStoredArn(anchor.getTrustAnchorArn()), equalTo(Optional.empty()));
        assertThat(service.resolveStoredArn(format + spelled(
                "arn:aws:rolesanywhere:us-east-1:111122223333:trust-anchor/" + anchor.getTrustAnchorId())),
                equalTo(Optional.empty()));
        assertThat(service.resolveStoredArn(format + spelled(
                "arn:aws:signer:us-east-1:" + ACCOUNT + ":trust-anchor/" + anchor.getTrustAnchorId())),
                equalTo(Optional.empty()));
        assertThat(service.resolveStoredArn("arn:aws:signer:us-east-1:" + ACCOUNT + ":trust-anchor/"
                + spelled(anchor.getTrustAnchorArn())), equalTo(Optional.empty()));
        assertThat(service.resolveStoredArn(format + spelled(
                "arn:aws:rolesanywhere:us-east-1:" + ACCOUNT + ":alias/trust-anchor/missing")),
                equalTo(Optional.empty()));
    }

    /** A path label as the request-resource table writes it into an ARN format. */
    private static String spelled(String label) {
        return label.replace("%", "%25").replace("/", "%2F");
    }

    @Test
    void clearRemovesEveryResource() {
        TrustAnchor anchor = service.createTrustAnchor(anchorRequest("reset"), REGION);
        service.createProfile(profileRequest("reset"), REGION);
        service.importCrl(crlRequest("reset", anchor.getTrustAnchorArn()), REGION);

        service.clear();

        assertThat(service.listTrustAnchors(null, null, REGION).items(), empty());
        assertThat(service.listProfiles(null, null, REGION).items(), empty());
        assertThat(service.listCrls(null, null, REGION).items(), empty());
        assertThat(service.listSubjects(null, null, REGION).items(), empty());
    }

    // ------------------------------------------------------------------ helpers

    private ObjectNode anchorRequest(String name) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("name", name);
        ObjectNode source = request.putObject("source");
        source.put("sourceType", "CERTIFICATE_BUNDLE");
        source.putObject("sourceData").put("x509CertificateData", RolesAnywhereTestMaterial.CA_PEM);
        return request;
    }

    private ObjectNode profileRequest(String name) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("name", name);
        request.putArray("roleArns").add(ROLE_ARN);
        return request;
    }

    private ObjectNode crlRequest(String name, String trustAnchorArn) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("name", name);
        request.put("crlData", RolesAnywhereTestMaterial.crlData());
        request.put("trustAnchorArn", trustAnchorArn);
        return request;
    }

    private ObjectNode tagRequest(String arn, String key, String value) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("resourceArn", arn);
        request.set("tags", tags(key, value));
        return request;
    }

    private ArrayNode tags(String key, String value) {
        ArrayNode tags = objectMapper.createArrayNode();
        tags.addObject().put("key", key).put("value", value);
        return tags;
    }

    private static void assertNotFound(Executable call) {
        AwsException error = assertThrows(AwsException.class, call);
        assertThat(error.getErrorCode(), equalTo("ResourceNotFoundException"));
        assertThat(error.getHttpStatus(), equalTo(404));
    }
}
