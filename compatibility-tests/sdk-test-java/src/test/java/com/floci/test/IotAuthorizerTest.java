package com.floci.test;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.PartitionMetadata;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iot.IotClient;
import software.amazon.awssdk.services.iot.model.AuthorizerDescription;
import software.amazon.awssdk.services.iot.model.AuthorizerStatus;
import software.amazon.awssdk.services.iot.model.AuthorizerSummary;
import software.amazon.awssdk.services.iot.model.CreateAuthorizerRequest;
import software.amazon.awssdk.services.iot.model.CreateAuthorizerResponse;
import software.amazon.awssdk.services.iot.model.DeleteConflictException;
import software.amazon.awssdk.services.iot.model.InvalidRequestException;
import software.amazon.awssdk.services.iot.model.IotException;
import software.amazon.awssdk.services.iot.model.ListAuthorizersRequest;
import software.amazon.awssdk.services.iot.model.ListAuthorizersResponse;
import software.amazon.awssdk.services.iot.model.ResourceAlreadyExistsException;
import software.amazon.awssdk.services.iot.model.ResourceNotFoundException;
import software.amazon.awssdk.services.iot.model.SetDefaultAuthorizerResponse;
import software.amazon.awssdk.services.iot.model.Tag;
import software.amazon.awssdk.services.iot.model.UpdateAuthorizerResponse;
import software.amazon.awssdk.services.sts.StsClient;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assumptions.assumeThat;

@DisplayName("IoT custom authorizer management")
class IotAuthorizerTest {

    private static IotClient iot;
    private static String partition;
    private static String region;
    private static String account;
    private static String rsaKey1;
    private static String rsaKey2;
    private static String rsa1024Key;
    private static String ecKey;

    private String prefix;
    private final List<String> created = new ArrayList<>();

    @BeforeAll
    static void setUpClients() throws GeneralSecurityException {
        iot = TestFixtures.iotClient();
        Region clientRegion = iot.serviceClientConfiguration().region();
        region = clientRegion.id();
        partition = PartitionMetadata.of(clientRegion).id();
        try (StsClient sts = TestFixtures.stsClient()) {
            account = sts.getCallerIdentity().account();
        }
        rsaKey1 = publicKeyPem("RSA", 2048);
        rsaKey2 = publicKeyPem("RSA", 2048);
        rsa1024Key = publicKeyPem("RSA", 1024);
        ecKey = publicKeyPem("EC", 256);
    }

    @AfterAll
    static void closeClients() {
        iot.close();
    }

    @BeforeEach
    void setUpPrefix() {
        prefix = "compat-auth-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void cleanUp() {
        RuntimeException failure = null;
        try {
            String current = iot.describeDefaultAuthorizer(r -> { }).authorizerDescription().authorizerName();
            if (created.contains(current)) {
                iot.clearDefaultAuthorizer(r -> { });
            }
        } catch (ResourceNotFoundException expected) {
            // No default authorizer is configured, so there is nothing of ours to clear.
        } catch (RuntimeException e) {
            failure = e;
        }
        for (String name : created) {
            try {
                AuthorizerDescription description = describe(name);
                if (description.status() == AuthorizerStatus.ACTIVE) {
                    iot.updateAuthorizer(r -> r.authorizerName(name).status(AuthorizerStatus.INACTIVE));
                }
                iot.deleteAuthorizer(r -> r.authorizerName(name));
            } catch (ResourceNotFoundException expected) {
                // The test already deleted it, or the create under test was rejected.
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                }
            }
        }
        created.clear();
        if (failure != null) {
            throw failure;
        }
    }

    @Test
    @DisplayName("Create with signing disabled and no status yields an INACTIVE authorizer with defaults")
    void createSigningDisabledAuthorizerWithDefaults() {
        String name = name("plain");
        String function = functionArn("fn");

        CreateAuthorizerResponse response = create(name, b -> b.authorizerFunctionArn(function).signingDisabled(true));

        assertThat(response.authorizerName()).isEqualTo(name);
        assertThat(response.authorizerArn()).isEqualTo(authorizerArn(name));

        AuthorizerDescription description = describe(name);
        assertThat(description.authorizerName()).isEqualTo(name);
        assertThat(description.authorizerArn()).isEqualTo(authorizerArn(name));
        assertThat(description.authorizerFunctionArn()).isEqualTo(function);
        assertThat(description.status()).isEqualTo(AuthorizerStatus.INACTIVE);
        assertThat(description.signingDisabled()).isTrue();
        assertThat(description.enableCachingForHttp()).isFalse();
        assertThat(description.tokenKeyName()).isNull();
        assertThat(description.hasTokenSigningPublicKeys())
                .as("AWS returns tokenSigningPublicKeys as JSON null for a signing-disabled authorizer")
                .isFalse();
        assertThat(description.tokenSigningPublicKeys()).isEmpty();
        assertThat(description.creationDate()).isNotNull().isEqualTo(description.lastModifiedDate());
    }

    @Test
    @DisplayName("Create a signed authorizer stores the token key name and the PEM verbatim, text before the header included")
    void createSignedAuthorizerStoresPublicKeyVerbatim() {
        String name = name("signed");

        CreateAuthorizerResponse response = create(name, b -> b.authorizerFunctionArn(functionArn("fn"))
                .tokenKeyName("tok")
                .tokenSigningPublicKeys(Map.of("k1", rsaKey1)));

        assertThat(response.authorizerName()).isEqualTo(name);
        assertThat(response.authorizerArn()).isEqualTo(authorizerArn(name));
        AuthorizerDescription description = describe(name);
        assertThat(description.signingDisabled()).isFalse();
        assertThat(description.tokenKeyName()).isEqualTo("tok");
        assertThat(description.tokenSigningPublicKeys()).containsExactly(Map.entry("k1", rsaKey1));
        assertThat(description.status()).isEqualTo(AuthorizerStatus.INACTIVE);
        assertThat(description.enableCachingForHttp()).isFalse();

        String prefixed = name("prefixed");
        String prefixedPem = "junk\n" + rsaKey2;
        create(prefixed, b -> b.authorizerFunctionArn(functionArn("fn"))
                .tokenKeyName("tok")
                .tokenSigningPublicKeys(Map.of("k1", prefixedPem)));
        assertThat(describe(prefixed).tokenSigningPublicKeys())
                .as("text before the PEM header is skipped and the value is stored verbatim")
                .containsExactly(Map.entry("k1", prefixedPem));
    }

    @Test
    @DisplayName("Create rejects an invalid signing configuration with 400 InvalidRequestException")
    void createRejectsInvalidSigningConfiguration() {
        String noSigning = name("nosign");
        assertCreateRejected(noSigning, b -> b.authorizerFunctionArn(functionArn("fn")),
                "Token key name for authorizer " + noSigning + " cannot be null");

        String disabledWithKeys = name("disabledkeys");
        assertCreateRejected(disabledWithKeys, b -> b.authorizerFunctionArn(functionArn("fn"))
                        .signingDisabled(true)
                        .tokenKeyName("tok")
                        .tokenSigningPublicKeys(Map.of("k1", rsaKey1)),
                "Token signing keys map must be null for authorizer " + disabledWithKeys
                        + " if using optional signature header");

        String threeKeys = name("threekeys");
        assertCreateRejected(threeKeys, b -> b.authorizerFunctionArn(functionArn("fn"))
                        .tokenKeyName("tok")
                        .tokenSigningPublicKeys(Map.of("k1", rsaKey1, "k2", rsaKey2, "k3", rsaKey1)),
                "Token signing keys map for authorizer " + threeKeys + " cannot contain more than 2 keys");

        String garbage = name("garbage");
        assertCreateRejected(garbage, b -> b.authorizerFunctionArn(functionArn("fn"))
                        .tokenKeyName("tok")
                        .tokenSigningPublicKeys(Map.of("k1", "not-a-pem")),
                "Authorizer " + garbage + " public key for key name k1 not a valid RSA key");

        String ec = name("ec");
        assertCreateRejected(ec, b -> b.authorizerFunctionArn(functionArn("fn"))
                        .tokenKeyName("tok")
                        .tokenSigningPublicKeys(Map.of("k1", ecKey)),
                "Authorizer " + ec + " public key for key name k1 not a valid RSA key");

        String shortRsa = name("rsa1024");
        assertCreateRejected(shortRsa, b -> b.authorizerFunctionArn(functionArn("fn"))
                        .tokenKeyName("tok")
                        .tokenSigningPublicKeys(Map.of("k1", rsa1024Key)),
                "Authorizer " + shortRsa + " public key for key name k1 invalid: Key must be 2048 bits but was 1024 bits");

        List<String> body = rsaKey1.lines().filter(line -> !line.startsWith("-----")).toList();
        String base64 = String.join("", body);
        String bodyLines = String.join("\n", body);
        Map<String, String> notRsa = new LinkedHashMap<>();
        notRsa.put("rawb64", base64);
        notRsa.put("bodyonly", bodyLines);
        notRsa.put("oneline", "-----BEGIN PUBLIC KEY-----" + base64 + "-----END PUBLIC KEY-----");
        notRsa.forEach((suffix, pem) -> {
            String envelope = name(suffix);
            assertCreateRejected(envelope, b -> b.authorizerFunctionArn(functionArn("fn"))
                            .tokenKeyName("tok")
                            .tokenSigningPublicKeys(Map.of("k1", pem)),
                    "Authorizer " + envelope + " public key for key name k1 not a valid RSA key");
        });

        String noEnd = name("noend");
        assertCreateRejected(noEnd, b -> b.authorizerFunctionArn(functionArn("fn"))
                        .tokenKeyName("tok")
                        .tokenSigningPublicKeys(Map.of("k1", "-----BEGIN PUBLIC KEY-----\n" + bodyLines)),
                "Cannot convert public key PEM for authorizer " + noEnd + " to RSA key");
    }

    @Test
    @DisplayName("Create rejects a malformed authorizer name, token key name and function ARN")
    void createRejectsMalformedNameAndFunctionArn() {
        assertAwsError(() -> iot.createAuthorizer(r -> r.authorizerName("bad name!")
                        .authorizerFunctionArn(functionArn("fn"))
                        .signingDisabled(true)),
                InvalidRequestException.class, 400,
                "1 validation error detected: Value at 'authorizerName' failed to satisfy constraint: "
                        + "Member must satisfy regular expression pattern: [\\w=,@-]+");

        assertAwsError(() -> iot.createAuthorizer(r -> r.authorizerName("!".repeat(129))
                        .authorizerFunctionArn(functionArn("fn"))
                        .signingDisabled(true)),
                InvalidRequestException.class, 400,
                "2 validation errors detected: Value at 'authorizerName' failed to satisfy constraint: "
                        + "Member must satisfy regular expression pattern: [\\w=,@-]+; "
                        + "Value at 'authorizerName' failed to satisfy constraint: "
                        + "Member must have length less than or equal to 128");

        String emptyToken = name("emptytok");
        assertCreateRejected(emptyToken, b -> b.authorizerFunctionArn(functionArn("fn"))
                        .tokenKeyName("")
                        .tokenSigningPublicKeys(Map.of("k1", rsaKey1)),
                "2 validation errors detected: Value at 'tokenKeyName' failed to satisfy constraint: "
                        + "Member must satisfy regular expression pattern: [a-zA-Z0-9_-]+; "
                        + "Value at 'tokenKeyName' failed to satisfy constraint: "
                        + "Member must have length greater than or equal to 1");

        String badArn = name("badarn");
        assertCreateRejected(badArn, b -> b.authorizerFunctionArn("not-an-arn").signingDisabled(true),
                "Lambda function arn for authorizer " + badArn + " is not in proper ARN syntax");
    }

    @Test
    @DisplayName("A duplicate create returns 409 and leaves the original unchanged")
    void duplicateCreateIsRejected() {
        String name = name("dup");
        String function = functionArn("fn");
        create(name, b -> b.authorizerFunctionArn(function).signingDisabled(true));
        AuthorizerDescription before = describe(name);

        assertAwsError(() -> iot.createAuthorizer(r -> r.authorizerName(name)
                        .authorizerFunctionArn(functionArn("other"))
                        .signingDisabled(true)
                        .status(AuthorizerStatus.ACTIVE)),
                ResourceAlreadyExistsException.class, 409,
                "Authorizer " + name + " already exist for this account");

        assertThat(describe(name)).isEqualTo(before);
    }

    @Test
    @DisplayName("Describe, update and delete of a missing authorizer return 404")
    void missingAuthorizerIsNotFound() {
        String name = name("missing");
        String message = "Authorizer " + name + " not found";

        assertAwsError(() -> iot.describeAuthorizer(r -> r.authorizerName(name)),
                ResourceNotFoundException.class, 404, message);
        assertAwsError(() -> iot.updateAuthorizer(r -> r.authorizerName(name).status(AuthorizerStatus.ACTIVE)),
                ResourceNotFoundException.class, 404, message);
        assertAwsError(() -> iot.deleteAuthorizer(r -> r.authorizerName(name)),
                ResourceNotFoundException.class, 404, message);
    }

    @Test
    @DisplayName("Describe, update and delete validate the authorizer name before looking it up")
    void pathOperationsValidateTheName() {
        String constraint = "Value at 'authorizerName' failed to satisfy constraint: ";
        String pattern = constraint + "Member must satisfy regular expression pattern: [\\w=,@-]+";
        String badName = "1 validation error detected: " + pattern;
        String overlong = "!".repeat(129);
        String bothViolated = "2 validation errors detected: " + pattern + "; " + constraint
                + "Member must have length less than or equal to 128";

        assertAwsError(() -> iot.describeAuthorizer(r -> r.authorizerName("bad name!")),
                InvalidRequestException.class, 400, badName);
        assertAwsError(() -> iot.describeAuthorizer(r -> r.authorizerName(overlong)),
                InvalidRequestException.class, 400, bothViolated);
        assertAwsError(() -> iot.updateAuthorizer(r -> r.authorizerName("bad name!").status(AuthorizerStatus.INACTIVE)),
                InvalidRequestException.class, 400, badName);
        assertAwsError(() -> iot.updateAuthorizer(r -> r.authorizerName(overlong).status(AuthorizerStatus.INACTIVE)),
                InvalidRequestException.class, 400, bothViolated);
        assertAwsError(() -> iot.deleteAuthorizer(r -> r.authorizerName("bad name!")),
                InvalidRequestException.class, 400, badName);
        assertAwsError(() -> iot.deleteAuthorizer(r -> r.authorizerName(overlong)),
                InvalidRequestException.class, 400, bothViolated);
    }

    @Test
    @DisplayName("Update changes only the fields sent and bumps lastModifiedDate on a status change")
    void updateAppliesOnlyTheFieldsSent() throws InterruptedException {
        String name = name("upd");
        String firstFunction = functionArn("fn1");
        String secondFunction = functionArn("fn2");
        create(name, b -> b.authorizerFunctionArn(firstFunction).signingDisabled(true));
        AuthorizerDescription original = describe(name);

        Thread.sleep(1100);
        UpdateAuthorizerResponse response =
                iot.updateAuthorizer(r -> r.authorizerName(name).status(AuthorizerStatus.ACTIVE));
        assertThat(response.authorizerName()).isEqualTo(name);
        assertThat(response.authorizerArn()).isEqualTo(authorizerArn(name));
        AuthorizerDescription active = describe(name);
        assertThat(active.status()).isEqualTo(AuthorizerStatus.ACTIVE);
        assertThat(active.authorizerFunctionArn()).isEqualTo(firstFunction);
        assertThat(active.signingDisabled()).isTrue();
        assertThat(active.enableCachingForHttp()).isFalse();
        assertThat(active.tokenKeyName()).isNull();
        assertThat(active.creationDate()).isEqualTo(original.creationDate());
        assertThat(active.lastModifiedDate()).isAfter(original.lastModifiedDate());

        iot.updateAuthorizer(r -> r.authorizerName(name).enableCachingForHttp(true));
        AuthorizerDescription caching = describe(name);
        assertThat(caching.enableCachingForHttp()).isTrue();
        assertThat(caching.status()).isEqualTo(AuthorizerStatus.ACTIVE);
        assertThat(caching.authorizerFunctionArn()).isEqualTo(firstFunction);

        iot.updateAuthorizer(r -> r.authorizerName(name).authorizerFunctionArn(secondFunction));
        AuthorizerDescription moved = describe(name);
        assertThat(moved.authorizerFunctionArn()).isEqualTo(secondFunction);
        assertThat(moved.status()).isEqualTo(AuthorizerStatus.ACTIVE);
        assertThat(moved.enableCachingForHttp()).isTrue();
        assertThat(moved.signingDisabled()).isTrue();

        Thread.sleep(1100);
        iot.updateAuthorizer(r -> r.authorizerName(name).status(AuthorizerStatus.INACTIVE));
        AuthorizerDescription inactive = describe(name);
        assertThat(inactive.status()).isEqualTo(AuthorizerStatus.INACTIVE);
        assertThat(inactive.authorizerFunctionArn()).isEqualTo(secondFunction);
        assertThat(inactive.enableCachingForHttp()).isTrue();
        assertThat(inactive.creationDate()).isEqualTo(original.creationDate());
        assertThat(inactive.lastModifiedDate()).isAfter(moved.lastModifiedDate());
    }

    @Test
    @DisplayName("Update merges signing keys per key name and refuses a third key")
    void updateMergesSigningKeys() {
        String name = name("merge");
        create(name, b -> b.authorizerFunctionArn(functionArn("fn"))
                .tokenKeyName("tok")
                .tokenSigningPublicKeys(Map.of("k1", rsaKey1)));

        iot.updateAuthorizer(r -> r.authorizerName(name).tokenSigningPublicKeys(Map.of("k2", rsaKey2)));
        AuthorizerDescription merged = describe(name);
        assertThat(merged.tokenSigningPublicKeys())
                .containsOnly(Map.entry("k1", rsaKey1), Map.entry("k2", rsaKey2));
        assertThat(merged.tokenKeyName()).isEqualTo("tok");

        assertAwsError(() -> iot.updateAuthorizer(r -> r.authorizerName(name)
                        .tokenSigningPublicKeys(Map.of("k3", rsaKey1))),
                InvalidRequestException.class, 400,
                "Number of signing keys for authorizer " + name + " must neither be zero nor greater than 2");
        assertThat(describe(name).tokenSigningPublicKeys())
                .containsOnly(Map.entry("k1", rsaKey1), Map.entry("k2", rsaKey2));
    }

    @Test
    @DisplayName("Delete refuses an ACTIVE authorizer, deletes an INACTIVE one and frees the name")
    void deleteRequiresInactiveStatus() {
        String name = name("del");
        create(name, b -> b.authorizerFunctionArn(functionArn("fn"))
                .signingDisabled(true)
                .status(AuthorizerStatus.ACTIVE));

        assertAwsError(() -> iot.deleteAuthorizer(r -> r.authorizerName(name)),
                InvalidRequestException.class, 400,
                "Cannot delete authorizer " + name + " in ACTIVE status. Update status to INACTIVE and delete.");
        assertThat(describe(name).status()).isEqualTo(AuthorizerStatus.ACTIVE);

        iot.updateAuthorizer(r -> r.authorizerName(name).status(AuthorizerStatus.INACTIVE));
        iot.deleteAuthorizer(r -> r.authorizerName(name));
        assertAwsError(() -> iot.describeAuthorizer(r -> r.authorizerName(name)),
                ResourceNotFoundException.class, 404, "Authorizer " + name + " not found");

        CreateAuthorizerResponse recreated =
                create(name, b -> b.authorizerFunctionArn(functionArn("fn")).signingDisabled(true));
        assertThat(recreated.authorizerArn()).isEqualTo(authorizerArn(name));
        assertThat(describe(name).status()).isEqualTo(AuthorizerStatus.INACTIVE);
    }

    @Test
    @DisplayName("ListAuthorizers orders by creation time, filters by status and pages with nextMarker")
    void listAuthorizersOrdersFiltersAndPages() {
        String first = name("l1");
        String second = name("l2");
        String third = name("l3");
        for (String name : List.of(first, second, third)) {
            create(name, b -> b.authorizerFunctionArn(functionArn("fn")).signingDisabled(true));
        }
        iot.updateAuthorizer(r -> r.authorizerName(second).status(AuthorizerStatus.ACTIVE));

        List<AuthorizerSummary> newestFirst = ours(listAll(b -> { }));
        assertThat(newestFirst).extracting(AuthorizerSummary::authorizerName)
                .containsExactly(third, second, first);
        assertThat(newestFirst).allSatisfy(summary ->
                assertThat(summary.authorizerArn()).isEqualTo(authorizerArn(summary.authorizerName())));

        assertThat(ours(listAll(b -> b.ascendingOrder(true)))).extracting(AuthorizerSummary::authorizerName)
                .containsExactly(first, second, third);
        assertThat(ours(listAll(b -> b.status(AuthorizerStatus.ACTIVE))))
                .extracting(AuthorizerSummary::authorizerName).containsExactly(second);
        assertThat(ours(listAll(b -> b.status(AuthorizerStatus.INACTIVE))))
                .extracting(AuthorizerSummary::authorizerName).containsExactly(third, first);

        List<String> paged = new ArrayList<>();
        String marker = null;
        int pages = 0;
        do {
            String current = marker;
            ListAuthorizersResponse page = iot.listAuthorizers(r -> r.pageSize(1).marker(current));
            assertThat(page.authorizers()).as("pageSize 1 returns one item per page").hasSize(1);
            page.authorizers().forEach(summary -> paged.add(summary.authorizerName()));
            marker = page.nextMarker();
            pages++;
        } while (marker != null && pages < 1000);
        assertThat(marker).as("the last page carries no nextMarker").isNull();
        assertThat(paged.stream().filter(n -> n.startsWith(prefix)).toList())
                .containsExactly(third, second, first);
    }

    @Test
    @DisplayName("Default authorizer set, replace, describe, delete conflict and clear")
    void defaultAuthorizerLifecycle() {
        String existingDefault = currentDefaultName();
        assumeThat(existingDefault)
                .as("the account already has a default authorizer that this test did not create")
                .isNull();
        assertAwsError(() -> iot.describeDefaultAuthorizer(r -> { }),
                ResourceNotFoundException.class, 404, "Default authorizer not found");

        String first = name("d1");
        String second = name("d2");
        create(first, b -> b.authorizerFunctionArn(functionArn("fn")).signingDisabled(true));
        create(second, b -> b.authorizerFunctionArn(functionArn("fn")).signingDisabled(true));

        SetDefaultAuthorizerResponse set = iot.setDefaultAuthorizer(r -> r.authorizerName(first));
        assertThat(set.authorizerName()).isEqualTo(first);
        assertThat(set.authorizerArn()).isEqualTo(authorizerArn(first));

        assertAwsError(() -> iot.setDefaultAuthorizer(r -> r.authorizerName(first)),
                ResourceAlreadyExistsException.class, 409,
                "Duplicate default authorizer " + first + " already configured for this account");

        SetDefaultAuthorizerResponse replaced = iot.setDefaultAuthorizer(r -> r.authorizerName(second));
        assertThat(replaced.authorizerName()).isEqualTo(second);
        assertThat(replaced.authorizerArn()).isEqualTo(authorizerArn(second));
        assertThat(iot.describeDefaultAuthorizer(r -> { }).authorizerDescription())
                .isEqualTo(describe(second));

        assertAwsError(() -> iot.deleteAuthorizer(r -> r.authorizerName(second)),
                DeleteConflictException.class, 409,
                "Cannot delete default authorizer " + second + ". Change default and retry delete.");
        assertThat(describe(second).status()).isEqualTo(AuthorizerStatus.INACTIVE);

        iot.clearDefaultAuthorizer(r -> { });
        assertAwsError(() -> iot.describeDefaultAuthorizer(r -> { }),
                ResourceNotFoundException.class, 404, "Default authorizer not found");
        assertAwsError(() -> iot.clearDefaultAuthorizer(r -> { }),
                ResourceNotFoundException.class, 404, "Default authorizer not found");
    }

    @Test
    @DisplayName("SetDefaultAuthorizer validates the authorizer name before looking it up")
    void setDefaultAuthorizerValidatesTheName() {
        String constraint = "Value at 'authorizerName' failed to satisfy constraint: ";
        String pattern = constraint + "Member must satisfy regular expression pattern: [\\w=,@-]+";

        assertAwsError(() -> iot.setDefaultAuthorizer(r -> { }), InvalidRequestException.class, 400,
                "1 validation error detected: " + constraint + "Member must not be null");
        assertAwsError(() -> iot.setDefaultAuthorizer(r -> r.authorizerName("")), InvalidRequestException.class, 400,
                "2 validation errors detected: " + pattern + "; " + constraint
                        + "Member must have length greater than or equal to 1");
        assertAwsError(() -> iot.setDefaultAuthorizer(r -> r.authorizerName("bad name!")),
                InvalidRequestException.class, 400, "1 validation error detected: " + pattern);
        assertAwsError(() -> iot.setDefaultAuthorizer(r -> r.authorizerName("!".repeat(129))),
                InvalidRequestException.class, 400,
                "2 validation errors detected: " + pattern + "; " + constraint
                        + "Member must have length less than or equal to 128");

        String missing = name("nodefault");
        assertAwsError(() -> iot.setDefaultAuthorizer(r -> r.authorizerName(missing)),
                ResourceNotFoundException.class, 404, "Authorizer " + missing + " not found");
    }

    @Test
    @DisplayName("Tags on an authorizer ARN are created, overwritten, removed and listed")
    void tagsOnAuthorizerArn() {
        String name = name("tags");
        String arn = authorizerArn(name);
        create(name, b -> b.authorizerFunctionArn(functionArn("fn"))
                .signingDisabled(true)
                .tags(tag("env", "dev"), tag("team", "a")));
        assertThat(tags(arn)).containsOnly(Map.entry("env", "dev"), Map.entry("team", "a"));

        iot.tagResource(r -> r.resourceArn(arn).tags(tag("team", "b"), tag("owner", "x")));
        assertThat(tags(arn)).containsOnly(Map.entry("env", "dev"), Map.entry("team", "b"), Map.entry("owner", "x"));

        iot.untagResource(r -> r.resourceArn(arn).tagKeys("env"));
        assertThat(tags(arn)).containsOnly(Map.entry("team", "b"), Map.entry("owner", "x"));
    }

    @Test
    @DisplayName("Tags on a missing authorizer ARN list empty and refuse TagResource with 404")
    void tagsOnMissingAuthorizer() {
        String name = name("notag");
        String arn = authorizerArn(name);

        assertThat(iot.listTagsForResource(r -> r.resourceArn(arn)).tags()).isEmpty();
        assertAwsError(() -> iot.tagResource(r -> r.resourceArn(arn).tags(tag("k", "v"))),
                ResourceNotFoundException.class, 404, "Authorizer " + name + " not found");
    }

    private String name(String suffix) {
        return prefix + "-" + suffix;
    }

    private String functionArn(String suffix) {
        return "arn:" + partition + ":lambda:" + region + ":" + account + ":function:" + prefix + "-" + suffix;
    }

    private String authorizerArn(String name) {
        return "arn:" + partition + ":iot:" + region + ":" + account + ":authorizer/" + name;
    }

    private CreateAuthorizerResponse create(String name, Consumer<CreateAuthorizerRequest.Builder> request) {
        created.add(name);
        return iot.createAuthorizer(b -> request.accept(b.authorizerName(name)));
    }

    private AuthorizerDescription describe(String name) {
        return iot.describeAuthorizer(r -> r.authorizerName(name)).authorizerDescription();
    }

    private String currentDefaultName() {
        try {
            return iot.describeDefaultAuthorizer(r -> { }).authorizerDescription().authorizerName();
        } catch (ResourceNotFoundException expected) {
            // No default authorizer is configured in this account and region.
            return null;
        }
    }

    private void assertCreateRejected(String name, Consumer<CreateAuthorizerRequest.Builder> request,
            String message) {
        assertAwsError(() -> create(name, request), InvalidRequestException.class, 400, message);
        assertAwsError(() -> iot.describeAuthorizer(r -> r.authorizerName(name)),
                ResourceNotFoundException.class, 404, "Authorizer " + name + " not found");
    }

    private List<AuthorizerSummary> listAll(Consumer<ListAuthorizersRequest.Builder> filter) {
        List<AuthorizerSummary> all = new ArrayList<>();
        String marker = null;
        do {
            String current = marker;
            ListAuthorizersResponse page = iot.listAuthorizers(b -> {
                filter.accept(b);
                b.marker(current);
            });
            all.addAll(page.authorizers());
            marker = page.nextMarker();
        } while (marker != null);
        return all;
    }

    private List<AuthorizerSummary> ours(List<AuthorizerSummary> summaries) {
        return summaries.stream().filter(s -> s.authorizerName().startsWith(prefix)).toList();
    }

    private Map<String, String> tags(String arn) {
        return iot.listTagsForResource(r -> r.resourceArn(arn)).tags().stream()
                .collect(Collectors.toMap(Tag::key, Tag::value, (a, b) -> b, LinkedHashMap::new));
    }

    private static Tag tag(String key, String value) {
        return Tag.builder().key(key).value(value).build();
    }

    private static <T extends IotException> void assertAwsError(ThrowingCallable call, Class<T> type,
            int status, String message) {
        T error = catchThrowableOfType(call, type);
        assertThat(error).as("expected %s: %s", type.getSimpleName(), message).isNotNull();
        assertThat(error.statusCode()).isEqualTo(status);
        assertThat(error.awsErrorDetails().errorMessage()).isEqualTo(message);
    }

    private static String publicKeyPem(String algorithm, int bits) throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm);
        generator.initialize(bits);
        byte[] der = generator.generateKeyPair().getPublic().getEncoded();
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----";
    }
}
