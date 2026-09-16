package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ssoadmin.SsoAdminClient;
import software.amazon.awssdk.services.ssoadmin.model.PrincipalType;
import software.amazon.awssdk.services.ssoadmin.model.TargetType;
import software.amazon.awssdk.services.ssoadmin.model.ValidationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("IAM Identity Center account assignments")
class SsoAdminAccountAssignmentTest {

    @Test
    @DisplayName("adds an IAM Identity Center Region through the AWS SDK")
    void addRegionUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Uses the emulator IAM Identity Center instance");

        try (SsoAdminClient sso = TestFixtures.ssoAdminClient()) {
            String instanceArn = sso.listInstances(request -> {}).instances().get(0).instanceArn();
            var added = sso.addRegion(request -> request
                    .instanceArn(instanceArn)
                    .regionName("ap-southeast-3"));

            assertThat(added.statusAsString()).isEqualTo("ADDING");

            var described = sso.describeRegion(request -> request
                    .instanceArn(instanceArn)
                    .regionName("ap-southeast-3"));
            assertThat(described.regionName()).isEqualTo("ap-southeast-3");
            assertThat(described.statusAsString()).isEqualTo("ACTIVE");
            assertThat(described.isPrimaryRegion()).isFalse();
            assertThat(described.addedDate()).isNotNull();

            var regions = sso.listRegions(request -> request.instanceArn(instanceArn));
            assertThat(regions.regions()).anySatisfy(region -> {
                assertThat(region.regionName()).isEqualTo("ap-southeast-3");
                assertThat(region.statusAsString()).isEqualTo("ACTIVE");
            });

            var removed = sso.removeRegion(request -> request
                    .instanceArn(instanceArn)
                    .regionName("ap-southeast-3"));
            assertThat(removed.statusAsString()).isEqualTo("REMOVING");
            assertThatThrownBy(() -> sso.describeRegion(request -> request
                    .instanceArn(instanceArn)
                    .regionName("ap-southeast-3")))
                    .isInstanceOf(software.amazon.awssdk.services.ssoadmin.model.ResourceNotFoundException.class);

            sso.addRegion(request -> request.instanceArn(instanceArn).regionName("ap-southeast-3"));
            assertThatThrownBy(() -> sso.addRegion(request -> request
                    .instanceArn(instanceArn)
                    .regionName("ap-southeast-3")))
                    .isInstanceOf(software.amazon.awssdk.services.ssoadmin.model.ConflictException.class);
        }
    }

    @Test
    @DisplayName("attaches a customer managed policy reference through the AWS SDK")
    void customerManagedPolicyReferenceUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Uses the emulator IAM Identity Center instance");

        try (SsoAdminClient sso = TestFixtures.ssoAdminClient()) {
            String instanceArn = sso.listInstances(request -> {}).instances().get(0).instanceArn();
            String permissionSetArn = sso.createPermissionSet(request -> request
                            .instanceArn(instanceArn)
                            .name("FlociCustomerPolicyAdmins"))
                    .permissionSet()
                    .permissionSetArn();

            var response = sso.attachCustomerManagedPolicyReferenceToPermissionSet(request -> request
                    .instanceArn(instanceArn)
                    .permissionSetArn(permissionSetArn)
                    .customerManagedPolicyReference(reference -> reference
                            .name("PlatformPolicy")
                            .path("/platform/")));

            assertThat(response.sdkHttpResponse().isSuccessful()).isTrue();
            var references = sso.listCustomerManagedPolicyReferencesInPermissionSet(request -> request
                    .instanceArn(instanceArn)
                    .permissionSetArn(permissionSetArn));
            assertThat(references.customerManagedPolicyReferences())
                    .anySatisfy(reference -> {
                        assertThat(reference.name()).isEqualTo("PlatformPolicy");
                        assertThat(reference.path()).isEqualTo("/platform/");
                    });
            assertThatThrownBy(() -> sso.attachCustomerManagedPolicyReferenceToPermissionSet(request -> request
                    .instanceArn(instanceArn)
                    .permissionSetArn(permissionSetArn)
                    .customerManagedPolicyReference(reference -> reference
                            .name("platformpolicy")
                            .path("/platform/"))))
                    .isInstanceOf(software.amazon.awssdk.services.ssoadmin.model.ConflictException.class);

            var detached = sso.detachCustomerManagedPolicyReferenceFromPermissionSet(request -> request
                    .instanceArn(instanceArn)
                    .permissionSetArn(permissionSetArn)
                    .customerManagedPolicyReference(reference -> reference
                            .name("platformpolicy")
                            .path("/platform/")));
            assertThat(detached.sdkHttpResponse().isSuccessful()).isTrue();
            assertThatThrownBy(() -> sso.detachCustomerManagedPolicyReferenceFromPermissionSet(request -> request
                    .instanceArn(instanceArn)
                    .permissionSetArn(permissionSetArn)
                    .customerManagedPolicyReference(reference -> reference
                            .name("PlatformPolicy")
                            .path("/platform/"))))
                    .isInstanceOf(software.amazon.awssdk.services.ssoadmin.model.ResourceNotFoundException.class);
        }
    }

    @Test
    @DisplayName("creates OAuth applications through the AWS SDK")
    void createApplicationUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Uses the emulator IAM Identity Center instance");

        try (SsoAdminClient sso = TestFixtures.ssoAdminClient()) {
            String instanceArn = sso.listInstances(request -> {}).instances().get(0).instanceArn();
            var created = sso.createApplication(request -> request
                    .instanceArn(instanceArn)
                    .applicationProviderArn("arn:aws:sso::aws:applicationProvider/custom")
                    .name("Floci OAuth SDK")
                    .clientToken("sdk-create-application-token")
                    .status("DISABLED")
                    .portalOptions(options -> options
                            .visibility("ENABLED")
                            .signInOptions(signIn -> signIn
                                    .origin("APPLICATION")
                                    .applicationUrl("https://example.com/login")))
                    .tags(tag -> tag.key("Environment").value("test")));

            assertThat(created.applicationArn()).matches(
                    "arn:aws:sso::000000000000:application/ssoins-7223b02a5d9f7c8e/apl-[0-9a-f]{16}");
            assertThat(created.identityStoreArn())
                    .isEqualTo("arn:aws:identitystore::000000000000:identitystore/d-9067f2a3c1");
            var replay = sso.createApplication(request -> request
                    .instanceArn(instanceArn)
                    .applicationProviderArn("arn:aws:sso::aws:applicationProvider/custom")
                    .name("Floci OAuth SDK")
                    .clientToken("sdk-create-application-token")
                    .status("DISABLED")
                    .portalOptions(options -> options
                            .visibility("ENABLED")
                            .signInOptions(signIn -> signIn
                                    .origin("APPLICATION")
                                    .applicationUrl("https://example.com/login")))
                    .tags(tag -> tag.key("Environment").value("test")));
            assertThat(replay.applicationArn()).isEqualTo(created.applicationArn());

            var tags = sso.listTagsForResource(request -> request
                    .instanceArn(instanceArn)
                    .resourceArn(created.applicationArn()));
            assertThat(tags.tags()).singleElement().satisfies(tag -> {
                assertThat(tag.key()).isEqualTo("Environment");
                assertThat(tag.value()).isEqualTo("test");
            });
            var tagged = sso.tagResource(request -> request
                    .instanceArn(instanceArn)
                    .resourceArn(created.applicationArn())
                    .tags(tag -> tag.key("Environment").value("prod"),
                            tag -> tag.key("Owner").value("platform")));
            assertThat(tagged.sdkHttpResponse().isSuccessful()).isTrue();
            assertThat(sso.listTagsForResource(request -> request.resourceArn(created.applicationArn())).tags())
                    .extracting(tag -> tag.key() + "=" + tag.value())
                    .containsExactly("Environment=prod", "Owner=platform");
            var untagged = sso.untagResource(request -> request
                    .resourceArn(created.applicationArn())
                    .tagKeys("Owner"));
            assertThat(untagged.sdkHttpResponse().isSuccessful()).isTrue();
            assertThat(sso.listTagsForResource(request -> request.resourceArn(created.applicationArn())).tags())
                    .extracting(tag -> tag.key() + "=" + tag.value())
                    .containsExactly("Environment=prod");
            var replayAfterTagging = sso.createApplication(request -> request
                    .instanceArn(instanceArn)
                    .applicationProviderArn("arn:aws:sso::aws:applicationProvider/custom")
                    .name("Floci OAuth SDK")
                    .clientToken("sdk-create-application-token")
                    .status("DISABLED")
                    .portalOptions(options -> options
                            .visibility("ENABLED")
                            .signInOptions(signIn -> signIn
                                    .origin("APPLICATION")
                                    .applicationUrl("https://example.com/login")))
                    .tags(tag -> tag.key("Environment").value("test")));
            assertThat(replayAfterTagging.applicationArn()).isEqualTo(created.applicationArn());

            var updatedApplication = sso.updateApplication(request -> request
                    .applicationArn(created.applicationArn())
                    .name("Floci OAuth SDK Updated")
                    .description("Updated through SDK")
                    .status("ENABLED")
                    .portalOptions(options -> options.signInOptions(signIn -> signIn
                            .origin("IDENTITY_CENTER"))));
            assertThat(updatedApplication.sdkHttpResponse().isSuccessful()).isTrue();

            var described = sso.describeApplication(request -> request.applicationArn(created.applicationArn()));
            assertThat(described.applicationArn()).isEqualTo(created.applicationArn());
            assertThat(described.name()).isEqualTo("Floci OAuth SDK Updated");
            assertThat(described.description()).isEqualTo("Updated through SDK");
            assertThat(described.statusAsString()).isEqualTo("ENABLED");
            assertThat(described.instanceArn()).isEqualTo(instanceArn);
            assertThat(described.applicationAccount()).isEqualTo("000000000000");
            assertThat(described.applicationProviderArn()).isEqualTo("arn:aws:sso::aws:applicationProvider/custom");
            assertThat(described.portalOptions().signInOptions().originAsString()).isEqualTo("IDENTITY_CENTER");

            var provider = sso.describeApplicationProvider(request -> request
                    .applicationProviderArn("arn:aws:sso::aws:applicationProvider/custom"));
            assertThat(provider.applicationProviderArn()).isEqualTo("arn:aws:sso::aws:applicationProvider/custom");
            assertThat(provider.federationProtocolAsString()).isEqualTo("OAUTH");

            var providers = sso.listApplicationProviders(request -> {});
            assertThat(providers.applicationProviders()).singleElement().satisfies(applicationProvider -> {
                assertThat(applicationProvider.applicationProviderArn())
                        .isEqualTo("arn:aws:sso::aws:applicationProvider/custom");
                assertThat(applicationProvider.federationProtocolAsString()).isEqualTo("OAUTH");
            });

            var applications = sso.listApplications(request -> request
                    .instanceArn(instanceArn)
                    .filter(filter -> filter
                            .applicationAccount("000000000000")
                            .applicationProvider("arn:aws:sso::aws:applicationProvider/custom")));
            assertThat(applications.applications()).anySatisfy(application ->
                    assertThat(application.applicationArn()).isEqualTo(created.applicationArn()));

            var assignmentConfiguration = sso.getApplicationAssignmentConfiguration(request -> request
                    .applicationArn(created.applicationArn()));
            assertThat(assignmentConfiguration.assignmentRequired()).isTrue();
            var putAssignmentConfiguration = sso.putApplicationAssignmentConfiguration(request -> request
                    .applicationArn(created.applicationArn())
                    .assignmentRequired(false));
            assertThat(putAssignmentConfiguration.sdkHttpResponse().isSuccessful()).isTrue();
            assertThat(sso.getApplicationAssignmentConfiguration(request -> request
                    .applicationArn(created.applicationArn())).assignmentRequired()).isFalse();

            var putScope = sso.putApplicationAccessScope(request -> request
                    .applicationArn(created.applicationArn())
                    .scope("api:read")
                    .authorizedTargets(instanceArn));
            assertThat(putScope.sdkHttpResponse().isSuccessful()).isTrue();
            var accessScope = sso.getApplicationAccessScope(request -> request
                    .applicationArn(created.applicationArn())
                    .scope("api:read"));
            assertThat(accessScope.scope()).isEqualTo("api:read");
            assertThat(accessScope.authorizedTargets()).containsExactly(instanceArn);

            sso.putApplicationAccessScope(request -> request
                    .applicationArn(created.applicationArn())
                    .scope("api:write")
                    .authorizedTargets(instanceArn));
            var firstScopePage = sso.listApplicationAccessScopes(request -> request
                    .applicationArn(created.applicationArn())
                    .maxResults(1));
            assertThat(firstScopePage.scopes()).singleElement().satisfies(scope -> {
                assertThat(scope.scope()).isEqualTo("api:read");
                assertThat(scope.authorizedTargets()).containsExactly(instanceArn);
            });
            assertThat(firstScopePage.nextToken()).isNotBlank();

            var secondScopePage = sso.listApplicationAccessScopes(request -> request
                    .applicationArn(created.applicationArn())
                    .maxResults(1)
                    .nextToken(firstScopePage.nextToken()));
            assertThat(secondScopePage.scopes()).singleElement().satisfies(scope ->
                    assertThat(scope.scope()).isEqualTo("api:write"));
            assertThat(secondScopePage.nextToken()).isNull();
            assertThatThrownBy(() -> sso.listApplicationAccessScopes(request -> request
                    .applicationArn(created.applicationArn())
                    .maxResults(11)))
                    .isInstanceOf(ValidationException.class);
        }
    }

    @Test
    @DisplayName("creates application assignments through the AWS SDK")
    void createApplicationAssignmentUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Uses the emulator IAM Identity Center instance");

        try (SsoAdminClient sso = TestFixtures.ssoAdminClient()) {
            String instanceArn = sso.listInstances(request -> {}).instances().get(0).instanceArn();
            String applicationArn = sso.createApplication(request -> request
                    .instanceArn(instanceArn)
                    .applicationProviderArn("arn:aws:sso::aws:applicationProvider/custom")
                    .name("SDK Assignment App")
                    .clientToken("sdk-assignment-app-token")).applicationArn();

            var response = sso.createApplicationAssignment(request -> request
                    .applicationArn(applicationArn)
                    .principalId("11111111-2222-3333-4444-555555555555")
                    .principalType("USER"));
            assertThat(response.sdkHttpResponse().isSuccessful()).isTrue();

            var described = sso.describeApplicationAssignment(request -> request
                    .applicationArn(applicationArn)
                    .principalId("11111111-2222-3333-4444-555555555555")
                    .principalType("USER"));
            assertThat(described.applicationArn()).isEqualTo(applicationArn);
            assertThat(described.principalId()).isEqualTo("11111111-2222-3333-4444-555555555555");
            assertThat(described.principalTypeAsString()).isEqualTo("USER");

            var listed = sso.listApplicationAssignments(request -> request.applicationArn(applicationArn).maxResults(1));
            assertThat(listed.applicationAssignments()).singleElement().satisfies(assignment -> {
                assertThat(assignment.applicationArn()).isEqualTo(applicationArn);
                assertThat(assignment.principalId()).isEqualTo("11111111-2222-3333-4444-555555555555");
                assertThat(assignment.principalTypeAsString()).isEqualTo("USER");
            });

            assertThatThrownBy(() -> sso.createApplicationAssignment(request -> request
                    .applicationArn(applicationArn)
                    .principalId("11111111-2222-3333-4444-555555555555")
                    .principalType("USER")))
                    .isInstanceOf(software.amazon.awssdk.services.ssoadmin.model.ConflictException.class);

            String groupId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
            sso.createApplicationAssignment(request -> request
                    .applicationArn(applicationArn)
                    .principalId(groupId)
                    .principalType("GROUP"));
            var principalAssignments = sso.listApplicationAssignmentsForPrincipal(request -> request
                    .instanceArn(instanceArn)
                    .principalId(groupId)
                    .principalType("GROUP"));
            assertThat(principalAssignments.applicationAssignments()).singleElement().satisfies(assignment -> {
                assertThat(assignment.applicationArn()).isEqualTo(applicationArn);
                assertThat(assignment.principalId()).isEqualTo(groupId);
                assertThat(assignment.principalTypeAsString()).isEqualTo("GROUP");
            });
        }
    }

    @Test
    @DisplayName("deletes application assignments through the AWS SDK")
    void deleteApplicationAssignmentUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Uses the emulator IAM Identity Center instance");

        try (SsoAdminClient sso = TestFixtures.ssoAdminClient()) {
            String instanceArn = sso.listInstances(request -> {}).instances().get(0).instanceArn();
            String applicationArn = sso.createApplication(request -> request
                    .instanceArn(instanceArn)
                    .applicationProviderArn("arn:aws:sso::aws:applicationProvider/custom")
                    .name("SDK Delete Assignment App"))
                    .applicationArn();
            String principalId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
            sso.createApplicationAssignment(request -> request
                    .applicationArn(applicationArn)
                    .principalId(principalId)
                    .principalType("GROUP"));

            var deleted = sso.deleteApplicationAssignment(request -> request
                    .applicationArn(applicationArn)
                    .principalId(principalId)
                    .principalType("GROUP"));
            assertThat(deleted.sdkHttpResponse().isSuccessful()).isTrue();
            assertThatThrownBy(() -> sso.deleteApplicationAssignment(request -> request
                    .applicationArn(applicationArn)
                    .principalId(principalId)
                    .principalType("GROUP")))
                    .isInstanceOf(software.amazon.awssdk.services.ssoadmin.model.ResourceNotFoundException.class);
        }
    }

    @Test
    @DisplayName("deletes application authentication methods through the AWS SDK")
    void deleteApplicationAuthenticationMethodUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Uses the emulator IAM Identity Center instance");

        try (SsoAdminClient sso = TestFixtures.ssoAdminClient()) {
            String instanceArn = sso.listInstances(request -> {}).instances().get(0).instanceArn();
            String applicationArn = sso.createApplication(request -> request
                    .instanceArn(instanceArn)
                    .applicationProviderArn("arn:aws:sso::aws:applicationProvider/custom")
                    .name("SDK Delete Authentication Method"))
                    .applicationArn();

            var putMethod = sso.putApplicationAuthenticationMethod(request -> request
                    .applicationArn(applicationArn)
                    .authenticationMethodType("IAM")
                    .authenticationMethod(method -> method.iam(iam -> iam.actorPolicy(
                            software.amazon.awssdk.core.document.Document.mapBuilder()
                                    .putString("Version", "2012-10-17")
                                    .putDocument("Statement", software.amazon.awssdk.core.document.Document.fromList(
                                            java.util.List.of()))
                                    .build()))));
            assertThat(putMethod.sdkHttpResponse().isSuccessful()).isTrue();
            var fetchedMethod = sso.getApplicationAuthenticationMethod(request -> request
                    .applicationArn(applicationArn)
                    .authenticationMethodType("IAM"));
            assertThat(fetchedMethod.authenticationMethod().iam().actorPolicy().asMap().get("Version").asString())
                    .isEqualTo("2012-10-17");
            var listedMethods = sso.listApplicationAuthenticationMethods(request -> request
                    .applicationArn(applicationArn));
            assertThat(listedMethods.authenticationMethods()).singleElement().satisfies(method -> {
                assertThat(method.authenticationMethodTypeAsString()).isEqualTo("IAM");
                assertThat(method.authenticationMethod().iam().actorPolicy().asMap().get("Version").asString())
                        .isEqualTo("2012-10-17");
            });

            sso.deleteApplicationAuthenticationMethod(request -> request
                    .applicationArn(applicationArn)
                    .authenticationMethodType("IAM"));
            assertThatThrownBy(() -> sso.getApplicationAuthenticationMethod(request -> request
                    .applicationArn(applicationArn)
                    .authenticationMethodType("IAM")))
                    .isInstanceOf(software.amazon.awssdk.services.ssoadmin.model.ResourceNotFoundException.class);
            assertThatThrownBy(() -> sso.getApplicationAuthenticationMethod(request -> request
                    .applicationArn(applicationArn)
                    .authenticationMethodType("SAML")))
                    .isInstanceOf(software.amazon.awssdk.services.ssoadmin.model.ValidationException.class);

            assertThatThrownBy(() -> sso.deleteApplicationAuthenticationMethod(request -> request
                    .applicationArn(applicationArn)
                    .authenticationMethodType("IAM")))
                    .isInstanceOf(software.amazon.awssdk.services.ssoadmin.model.ResourceNotFoundException.class);
            assertThatThrownBy(() -> sso.deleteApplicationAuthenticationMethod(request -> request
                    .applicationArn(applicationArn)
                    .authenticationMethodType("SAML")))
                    .isInstanceOf(software.amazon.awssdk.services.ssoadmin.model.ValidationException.class);
        }
    }

    @Test
    @DisplayName("deletes application grants through the AWS SDK")
    void deleteApplicationGrantUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Uses the emulator IAM Identity Center instance");

        try (SsoAdminClient sso = TestFixtures.ssoAdminClient()) {
            String instanceArn = sso.listInstances(request -> {}).instances().get(0).instanceArn();
            String applicationArn = sso.createApplication(request -> request
                    .instanceArn(instanceArn)
                    .applicationProviderArn("arn:aws:sso::aws:applicationProvider/custom")
                    .name("SDK Delete Grant"))
                    .applicationArn();

            var sessionConfiguration = sso.getApplicationSessionConfiguration(request -> request
                    .applicationArn(applicationArn));
            assertThat(sessionConfiguration.userBackgroundSessionApplicationStatusAsString()).isEqualTo("DISABLED");
            var putSessionConfiguration = sso.putApplicationSessionConfiguration(request -> request
                    .applicationArn(applicationArn)
                    .userBackgroundSessionApplicationStatus("ENABLED"));
            assertThat(putSessionConfiguration.sdkHttpResponse().isSuccessful()).isTrue();
            assertThat(sso.getApplicationSessionConfiguration(request -> request.applicationArn(applicationArn))
                    .userBackgroundSessionApplicationStatusAsString()).isEqualTo("ENABLED");

            var putGrant = sso.putApplicationGrant(request -> request
                    .applicationArn(applicationArn)
                    .grantType("authorization_code")
                    .grant(grant -> grant.authorizationCode(code -> code
                            .redirectUris("https://example.com/callback"))));
            assertThat(putGrant.sdkHttpResponse().isSuccessful()).isTrue();
            var fetchedGrant = sso.getApplicationGrant(request -> request
                    .applicationArn(applicationArn)
                    .grantType("authorization_code"));
            assertThat(fetchedGrant.grant().authorizationCode().redirectUris())
                    .containsExactly("https://example.com/callback");
            var listedGrants = sso.listApplicationGrants(request -> request.applicationArn(applicationArn));
            assertThat(listedGrants.grants()).singleElement().satisfies(grant -> {
                assertThat(grant.grantTypeAsString()).isEqualTo("authorization_code");
                assertThat(grant.grant().authorizationCode().redirectUris())
                        .containsExactly("https://example.com/callback");
            });
            sso.deleteApplicationGrant(request -> request
                    .applicationArn(applicationArn)
                    .grantType("authorization_code"));
            assertThatThrownBy(() -> sso.getApplicationGrant(request -> request
                    .applicationArn(applicationArn)
                    .grantType("authorization_code")))
                    .isInstanceOf(software.amazon.awssdk.services.ssoadmin.model.ResourceNotFoundException.class);
            assertThatThrownBy(() -> sso.getApplicationGrant(request -> request
                    .applicationArn(applicationArn)
                    .grantType("client_credentials")))
                    .isInstanceOf(software.amazon.awssdk.services.ssoadmin.model.ValidationException.class);

            assertThatThrownBy(() -> sso.deleteApplicationGrant(request -> request
                    .applicationArn(applicationArn)
                    .grantType("authorization_code")))
                    .isInstanceOf(software.amazon.awssdk.services.ssoadmin.model.ResourceNotFoundException.class);
            assertThatThrownBy(() -> sso.deleteApplicationGrant(request -> request
                    .applicationArn(applicationArn)
                    .grantType("client_credentials")))
                    .isInstanceOf(software.amazon.awssdk.services.ssoadmin.model.ValidationException.class);
        }
    }

    @Test
    @DisplayName("creates trusted token issuers through the AWS SDK")
    void createTrustedTokenIssuerUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Uses the emulator IAM Identity Center fixture");

        try (SsoAdminClient sso = TestFixtures.ssoAdminClient()) {
            String instanceArn = sso.listInstances(request -> {}).instances().get(0).instanceArn();
            var created = sso.createTrustedTokenIssuer(request -> request
                    .instanceArn(instanceArn)
                    .name("SdkIssuer")
                    .clientToken("sdk-tti-token")
                    .trustedTokenIssuerType("OIDC_JWT")
                    .trustedTokenIssuerConfiguration(configuration -> configuration
                            .oidcJwtConfiguration(oidc -> oidc
                                    .claimAttributePath("sub")
                                    .identityStoreAttributePath("userName")
                                    .issuerUrl("https://issuer.example.com")
                                    .jwksRetrievalOption("OPEN_ID_DISCOVERY"))));

            assertThat(created.trustedTokenIssuerArn()).matches(
                    "arn:aws:sso::000000000000:trustedTokenIssuer/ssoins-[0-9a-f]{16}/tti-[0-9a-f-]{36}");
            var replay = sso.createTrustedTokenIssuer(request -> request
                    .instanceArn(instanceArn)
                    .name("SdkIssuer")
                    .clientToken("sdk-tti-token")
                    .trustedTokenIssuerType("OIDC_JWT")
                    .trustedTokenIssuerConfiguration(configuration -> configuration
                            .oidcJwtConfiguration(oidc -> oidc
                                    .claimAttributePath("sub")
                                    .identityStoreAttributePath("userName")
                                    .issuerUrl("https://issuer.example.com")
                                    .jwksRetrievalOption("OPEN_ID_DISCOVERY"))));
            assertThat(replay.trustedTokenIssuerArn()).isEqualTo(created.trustedTokenIssuerArn());

            var updateResponse = sso.updateTrustedTokenIssuer(request -> request
                    .trustedTokenIssuerArn(created.trustedTokenIssuerArn())
                    .name("SdkIssuerUpdated")
                    .trustedTokenIssuerConfiguration(configuration -> configuration
                            .oidcJwtConfiguration(oidc -> oidc.claimAttributePath("email")
                                    .identityStoreAttributePath("emails.value")
                                    .jwksRetrievalOption("OPEN_ID_DISCOVERY"))));
            assertThat(updateResponse.sdkHttpResponse().isSuccessful()).isTrue();
            var described = sso.describeTrustedTokenIssuer(request -> request
                    .trustedTokenIssuerArn(created.trustedTokenIssuerArn()));
            assertThat(described.name()).isEqualTo("SdkIssuerUpdated");
            assertThat(described.trustedTokenIssuerTypeAsString()).isEqualTo("OIDC_JWT");
            assertThat(described.trustedTokenIssuerConfiguration().oidcJwtConfiguration().issuerUrl())
                    .isEqualTo("https://issuer.example.com");

            var issuers = sso.listTrustedTokenIssuers(request -> request.instanceArn(instanceArn));
            assertThat(issuers.trustedTokenIssuers()).anySatisfy(issuer -> {
                assertThat(issuer.trustedTokenIssuerArn()).isEqualTo(created.trustedTokenIssuerArn());
                assertThat(issuer.name()).isEqualTo("SdkIssuerUpdated");
                assertThat(issuer.trustedTokenIssuerTypeAsString()).isEqualTo("OIDC_JWT");
            });

            var deleteResponse = sso.deleteTrustedTokenIssuer(request -> request
                    .trustedTokenIssuerArn(created.trustedTokenIssuerArn()));
            assertThat(deleteResponse.sdkHttpResponse().isSuccessful()).isTrue();
            assertThatThrownBy(() -> sso.deleteTrustedTokenIssuer(request -> request
                    .trustedTokenIssuerArn(created.trustedTokenIssuerArn())))
                    .isInstanceOf(software.amazon.awssdk.services.ssoadmin.model.ResourceNotFoundException.class);
        }
    }

    @Test
    @DisplayName("creates an instance ABAC configuration through the AWS SDK")
    void createInstanceAccessControlAttributeConfigurationUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Uses the emulator IAM Identity Center fixture");

        try (SsoAdminClient sso = TestFixtures.ssoAdminClient()) {
            String instanceArn = sso.listInstances(request -> {}).instances().get(0).instanceArn();
            var response = sso.createInstanceAccessControlAttributeConfiguration(request -> request
                    .instanceArn(instanceArn)
                    .instanceAccessControlAttributeConfiguration(configuration -> configuration
                            .accessControlAttributes(attribute -> attribute
                                    .key("Department")
                                    .value(value -> value.source("${path:enterprise.department}")))));

            assertThat(response.sdkHttpResponse().isSuccessful()).isTrue();

            var described = sso.describeInstanceAccessControlAttributeConfiguration(request -> request.instanceArn(instanceArn));
            assertThat(described.statusAsString()).isEqualTo("ENABLED");
            assertThat(described.instanceAccessControlAttributeConfiguration().accessControlAttributes())
                    .singleElement().satisfies(attribute -> {
                        assertThat(attribute.key()).isEqualTo("Department");
                        assertThat(attribute.value().source()).containsExactly("${path:enterprise.department}");
                    });

            assertThatThrownBy(() -> sso.createInstanceAccessControlAttributeConfiguration(request -> request
                    .instanceArn(instanceArn)
                    .instanceAccessControlAttributeConfiguration(configuration -> configuration
                            .accessControlAttributes(attribute -> attribute
                                    .key("Department")
                                    .value(value -> value.source("${path:enterprise.department}"))))))
                    .isInstanceOf(software.amazon.awssdk.services.ssoadmin.model.ConflictException.class);

            var updateResponse = sso.updateInstanceAccessControlAttributeConfiguration(request -> request
                    .instanceArn(instanceArn)
                    .instanceAccessControlAttributeConfiguration(configuration -> configuration
                            .accessControlAttributes(attribute -> attribute
                                    .key("CostCenter")
                                    .value(value -> value.source("${path:enterprise.costCenter}")))));
            assertThat(updateResponse.sdkHttpResponse().isSuccessful()).isTrue();
            assertThat(sso.describeInstanceAccessControlAttributeConfiguration(request -> request.instanceArn(instanceArn))
                    .instanceAccessControlAttributeConfiguration().accessControlAttributes())
                    .singleElement().satisfies(attribute -> assertThat(attribute.key()).isEqualTo("CostCenter"));

            var deleteResponse = sso.deleteInstanceAccessControlAttributeConfiguration(request -> request
                    .instanceArn(instanceArn));
            assertThat(deleteResponse.sdkHttpResponse().isSuccessful()).isTrue();

            var recreateResponse = sso.createInstanceAccessControlAttributeConfiguration(request -> request
                    .instanceArn(instanceArn)
                    .instanceAccessControlAttributeConfiguration(configuration -> configuration
                            .accessControlAttributes(attribute -> attribute
                                    .key("Department")
                                    .value(value -> value.source("${path:enterprise.department}")))));
            assertThat(recreateResponse.sdkHttpResponse().isSuccessful()).isTrue();
        }
    }

    @Test
    @DisplayName("creates an account instance through the AWS SDK")
    void createInstanceUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Uses an emulator-only account instance");

        try (SsoAdminClient sso = TestFixtures.ssoAdminClient("333344445555")) {
            assertThat(sso.listInstances(request -> {}).instances()).isEmpty();
            String instanceArn = sso.createInstance(request -> request
                    .name("SdkAccountInstance")
                    .clientToken("sdk-create-instance")
                    .tags(tag -> tag.key("Environment").value("test")))
                    .instanceArn();

            var described = sso.describeInstance(request -> request.instanceArn(instanceArn));
            assertThat(described.instanceArn()).isEqualTo(instanceArn);
            assertThat(described.ownerAccountId()).isEqualTo("333344445555");
            assertThat(described.name()).isEqualTo("SdkAccountInstance");
            assertThat(described.statusAsString()).isEqualTo("ACTIVE");
            assertThat(described.permissionSetsEnabled()).isFalse();
            assertThat(described.encryptionConfigurationDetails().keyTypeAsString()).isEqualTo("AWS_OWNED_KMS_KEY");

            var renamed = sso.updateInstance(request -> request.instanceArn(instanceArn).name("SdkAccountRenamed"));
            assertThat(renamed.sdkHttpResponse().isSuccessful()).isTrue();
            sso.updateInstance(request -> request.instanceArn(instanceArn).permissionSetsEnabled(true));
            var updated = sso.describeInstance(request -> request.instanceArn(instanceArn));
            assertThat(updated.name()).isEqualTo("SdkAccountRenamed");
            assertThat(updated.permissionSetsEnabled()).isTrue();
            assertThat(sso.createInstance(request -> request
                    .name("SdkAccountInstance")
                    .clientToken("sdk-create-instance")
                    .tags(tag -> tag.key("Environment").value("test"))).instanceArn()).isEqualTo(instanceArn);

            var listed = sso.listInstances(request -> {}).instances();
            assertThat(listed).hasSize(1);
            assertThat(listed.get(0).instanceArn()).isEqualTo(instanceArn);
            assertThat(listed.get(0).ownerAccountId()).isEqualTo("333344445555");
            assertThat(listed.get(0).primaryRegion()).isEqualTo("us-east-1");
        }
    }

    @Test
    @DisplayName("models the singleton CreateInstance quota through the AWS SDK")
    void createInstanceQuotaUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Uses the emulator IAM Identity Center fixture");

        try (SsoAdminClient sso = TestFixtures.ssoAdminClient()) {
            sso.listInstances(request -> {});
            assertThatThrownBy(() -> sso.createInstance(request -> request
                    .name("SecondInstance")
                    .clientToken("sdk-create-instance-quota")))
                    .isInstanceOf(software.amazon.awssdk.services.ssoadmin.model.ServiceQuotaExceededException.class);
        }
    }

    @Test
    @DisplayName("deletes an account instance through the AWS SDK")
    void deleteInstanceUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Uses an emulator-only account instance");

        try (SsoAdminClient sso = TestFixtures.ssoAdminClient("555566667777")) {
            String instanceArn = sso.createInstance(request -> request
                    .name("SdkDisposableInstance")
                    .clientToken("sdk-delete-instance"))
                    .instanceArn();
            sso.createApplication(request -> request
                    .instanceArn(instanceArn)
                    .applicationProviderArn("arn:aws:sso::aws:applicationProvider/custom")
                    .name("SdkDisposableApplication"));

            var deleted = sso.deleteInstance(request -> request.instanceArn(instanceArn));
            assertThat(deleted.sdkHttpResponse().isSuccessful()).isTrue();
            assertThat(sso.listInstances(request -> {}).instances()).isEmpty();
        }
    }

    @Test
    @DisplayName("deletes application access scopes through the AWS SDK")
    void deleteApplicationAccessScopeUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Uses the emulator IAM Identity Center fixture");

        try (SsoAdminClient sso = TestFixtures.ssoAdminClient()) {
            String instanceArn = sso.listInstances(request -> {}).instances().get(0).instanceArn();
            String applicationArn = sso.createApplication(request -> request
                    .instanceArn(instanceArn)
                    .applicationProviderArn("arn:aws:sso::aws:applicationProvider/custom")
                    .name("SdkDeleteApplicationScope"))
                    .applicationArn();

            assertThatThrownBy(() -> sso.deleteApplicationAccessScope(request -> request
                    .applicationArn(applicationArn)
                    .scope("api:read")))
                    .isInstanceOf(software.amazon.awssdk.services.ssoadmin.model.ResourceNotFoundException.class);
            assertThatThrownBy(() -> sso.deleteApplicationAccessScope(request -> request
                    .applicationArn(applicationArn)
                    .scope("bad scope")))
                    .isInstanceOf(software.amazon.awssdk.services.ssoadmin.model.ValidationException.class);
        }
    }

    @Test
    @DisplayName("deletes applications through the AWS SDK")
    void deleteApplicationUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Uses the emulator IAM Identity Center fixture");

        try (SsoAdminClient sso = TestFixtures.ssoAdminClient()) {
            String instanceArn = sso.listInstances(request -> {}).instances().get(0).instanceArn();
            String applicationArn = sso.createApplication(request -> request
                    .instanceArn(instanceArn)
                    .applicationProviderArn("arn:aws:sso::aws:applicationProvider/custom")
                    .name("SdkDeleteApplication"))
                    .applicationArn();

            var response = sso.deleteApplication(request -> request.applicationArn(applicationArn));
            assertThat(response.sdkHttpResponse().isSuccessful()).isTrue();
            assertThatThrownBy(() -> sso.deleteApplication(request -> request.applicationArn(applicationArn)))
                    .isInstanceOf(software.amazon.awssdk.services.ssoadmin.model.ResourceNotFoundException.class);
        }
    }

    @Test
    @DisplayName("lists account assignments for a user or group through the AWS SDK")
    void listAccountAssignmentsForPrincipalUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Uses emulator-only account and principal identifiers");

        try (SsoAdminClient sso = TestFixtures.ssoAdminClient()) {
            String instanceArn = sso.listInstances(request -> {}).instances().get(0).instanceArn();
            String principalId = "bbbbbbbb-cccc-dddd-eeee-ffffffffffff";
            for (String name : java.util.List.of("PrincipalListSdkOne", "PrincipalListSdkTwo")) {
                String permissionSetArn = sso.createPermissionSet(request -> request
                                .instanceArn(instanceArn)
                                .name(name))
                        .permissionSet().permissionSetArn();
                sso.createAccountAssignment(request -> request
                        .instanceArn(instanceArn)
                        .targetId("123456789012")
                        .targetType(TargetType.AWS_ACCOUNT)
                        .permissionSetArn(permissionSetArn)
                        .principalType(PrincipalType.GROUP)
                        .principalId(principalId));
            }

            var first = sso.listAccountAssignmentsForPrincipal(request -> request
                    .instanceArn(instanceArn)
                    .principalId(principalId)
                    .principalType(PrincipalType.GROUP)
                    .maxResults(1));
            assertThat(first.accountAssignments()).hasSize(1);
            assertThat(first.nextToken()).isNotBlank();

            var second = sso.listAccountAssignmentsForPrincipal(request -> request
                    .instanceArn(instanceArn)
                    .principalId(principalId)
                    .principalType(PrincipalType.GROUP)
                    .maxResults(1)
                    .nextToken(first.nextToken()));
            assertThat(second.accountAssignments()).hasSize(1);
            assertThat(second.nextToken()).isNull();

            var filtered = sso.listAccountAssignmentsForPrincipal(request -> request
                    .instanceArn(instanceArn)
                    .principalId(principalId)
                    .principalType(PrincipalType.GROUP)
                    .filter(filter -> filter.accountId("123456789012")));
            assertThat(filtered.accountAssignments()).hasSize(2);
        }
    }

    @Test
    @DisplayName("provisions permission sets to AWS accounts through the AWS SDK")
    void provisionPermissionSetUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Uses emulator-only account identifiers");

        try (SsoAdminClient sso = TestFixtures.ssoAdminClient()) {
            String instanceArn = sso.listInstances(request -> {}).instances().get(0).instanceArn();
            String permissionSetArn = sso.createPermissionSet(request -> request
                            .instanceArn(instanceArn)
                            .name("ProvisionSdkAdmins"))
                    .permissionSet().permissionSetArn();

            var response = sso.provisionPermissionSet(request -> request
                    .instanceArn(instanceArn)
                    .permissionSetArn(permissionSetArn)
                    .targetType("AWS_ACCOUNT")
                    .targetId("123456789012"));

            assertThat(response.permissionSetProvisioningStatus()).isNotNull();
            assertThat(response.permissionSetProvisioningStatus().statusAsString()).isEqualTo("SUCCEEDED");
            assertThat(response.permissionSetProvisioningStatus().accountId()).isEqualTo("123456789012");
            assertThat(response.permissionSetProvisioningStatus().permissionSetArn()).isEqualTo(permissionSetArn);
            assertThat(response.permissionSetProvisioningStatus().requestId()).isNotBlank();

            var described = sso.describePermissionSetProvisioningStatus(request -> request
                    .instanceArn(instanceArn)
                    .provisionPermissionSetRequestId(response.permissionSetProvisioningStatus().requestId()));
            assertThat(described.permissionSetProvisioningStatus().requestId())
                    .isEqualTo(response.permissionSetProvisioningStatus().requestId());
            assertThat(described.permissionSetProvisioningStatus().statusAsString()).isEqualTo("SUCCEEDED");

            var listed = sso.listPermissionSetProvisioningStatus(request -> request
                    .instanceArn(instanceArn)
                    .filter(filter -> filter.status("SUCCEEDED")));
            assertThat(listed.permissionSetsProvisioningStatus())
                    .anyMatch(status -> response.permissionSetProvisioningStatus().requestId().equals(status.requestId()));
        }
    }

    @Test
    @DisplayName("lists permission sets provisioned to an AWS account through the AWS SDK")
    void listPermissionSetsProvisionedToAccountUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Uses emulator-only account identifiers");

        try (SsoAdminClient sso = TestFixtures.ssoAdminClient()) {
            String instanceArn = sso.listInstances(request -> {}).instances().get(0).instanceArn();
            String permissionSetArn = sso.createPermissionSet(request -> request
                            .instanceArn(instanceArn)
                            .name("ProvisionedListSdkAdmins"))
                    .permissionSet().permissionSetArn();
            sso.provisionPermissionSet(request -> request
                    .instanceArn(instanceArn)
                    .permissionSetArn(permissionSetArn)
                    .targetType("AWS_ACCOUNT")
                    .targetId("210987654321"));

            var response = sso.listPermissionSetsProvisionedToAccount(request -> request
                    .instanceArn(instanceArn)
                    .accountId("210987654321")
                    .provisioningStatus("LATEST_PERMISSION_SET_PROVISIONED"));
            assertThat(response.permissionSets()).contains(permissionSetArn);
        }
    }

    @Test
    @DisplayName("lists accounts for a provisioned permission set through the AWS SDK")
    void listAccountsForProvisionedPermissionSetUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Uses emulator-only account identifiers");

        try (SsoAdminClient sso = TestFixtures.ssoAdminClient()) {
            String instanceArn = sso.listInstances(request -> {}).instances().get(0).instanceArn();
            String permissionSetArn = sso.createPermissionSet(request -> request
                            .instanceArn(instanceArn)
                            .name("ProvisionedAccountsSdkAdmins"))
                    .permissionSet().permissionSetArn();
            sso.provisionPermissionSet(request -> request
                    .instanceArn(instanceArn)
                    .permissionSetArn(permissionSetArn)
                    .targetType("AWS_ACCOUNT")
                    .targetId("321098765432"));

            var response = sso.listAccountsForProvisionedPermissionSet(request -> request
                    .instanceArn(instanceArn)
                    .permissionSetArn(permissionSetArn)
                    .provisioningStatus("LATEST_PERMISSION_SET_PROVISIONED"));
            assertThat(response.accountIds()).contains("321098765432");
        }
    }

    @Test
    @DisplayName("gets inline permission-set policies through the AWS SDK")
    void getInlinePolicyUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Uses emulator-only permission-set lifecycle");
        try (SsoAdminClient sso = TestFixtures.ssoAdminClient()) {
            String instanceArn = sso.listInstances(request -> {}).instances().get(0).instanceArn();
            String permissionSetArn = sso.createPermissionSet(request -> request
                            .instanceArn(instanceArn).name("GetInlinePolicySdkAdmins"))
                    .permissionSet().permissionSetArn();
            assertThat(sso.getInlinePolicyForPermissionSet(request -> request
                    .instanceArn(instanceArn).permissionSetArn(permissionSetArn)).inlinePolicy()).isEmpty();
            String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";
            sso.putInlinePolicyToPermissionSet(request -> request
                    .instanceArn(instanceArn).permissionSetArn(permissionSetArn).inlinePolicy(policy));
            assertThat(sso.getInlinePolicyForPermissionSet(request -> request
                    .instanceArn(instanceArn).permissionSetArn(permissionSetArn)).inlinePolicy()).isEqualTo(policy);
        }
    }

    @Test
    @DisplayName("puts permission-set permissions boundaries through the AWS SDK")
    void putPermissionsBoundaryUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Uses emulator-only permission-set lifecycle");
        try (SsoAdminClient sso = TestFixtures.ssoAdminClient()) {
            String instanceArn = sso.listInstances(request -> {}).instances().get(0).instanceArn();
            String permissionSetArn = sso.createPermissionSet(request -> request
                            .instanceArn(instanceArn).name("PutBoundarySdkAdmins"))
                    .permissionSet().permissionSetArn();
            var response = sso.putPermissionsBoundaryToPermissionSet(request -> request
                    .instanceArn(instanceArn)
                    .permissionSetArn(permissionSetArn)
                    .permissionsBoundary(boundary -> boundary
                            .managedPolicyArn("arn:aws:iam::aws:policy/PowerUserAccess")));
            assertThat(response.sdkHttpResponse().isSuccessful()).isTrue();
            var boundary = sso.getPermissionsBoundaryForPermissionSet(request -> request
                    .instanceArn(instanceArn)
                    .permissionSetArn(permissionSetArn));
            assertThat(boundary.permissionsBoundary().managedPolicyArn())
                    .isEqualTo("arn:aws:iam::aws:policy/PowerUserAccess");
            var deleted = sso.deletePermissionsBoundaryFromPermissionSet(request -> request
                    .instanceArn(instanceArn)
                    .permissionSetArn(permissionSetArn));
            assertThat(deleted.sdkHttpResponse().isSuccessful()).isTrue();
            assertThatThrownBy(() -> sso.getPermissionsBoundaryForPermissionSet(request -> request
                    .instanceArn(instanceArn)
                    .permissionSetArn(permissionSetArn)))
                    .isInstanceOf(software.amazon.awssdk.services.ssoadmin.model.ResourceNotFoundException.class);
        }
    }

    @Test
    @DisplayName("deletes permission sets through the AWS SDK")
    void deletePermissionSetUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Uses emulator-only permission-set lifecycle");

        try (SsoAdminClient sso = TestFixtures.ssoAdminClient()) {
            String instanceArn = sso.listInstances(request -> {}).instances().get(0).instanceArn();
            String permissionSetArn = sso.createPermissionSet(request -> request
                            .instanceArn(instanceArn)
                            .name("DeletePermissionSetSdkAdmins"))
                    .permissionSet().permissionSetArn();
            var response = sso.deletePermissionSet(request -> request
                    .instanceArn(instanceArn)
                    .permissionSetArn(permissionSetArn));
            assertThat(response.sdkHttpResponse().isSuccessful()).isTrue();
            assertThatThrownBy(() -> sso.describePermissionSet(request -> request
                    .instanceArn(instanceArn)
                    .permissionSetArn(permissionSetArn)))
                    .isInstanceOf(software.amazon.awssdk.services.ssoadmin.model.ResourceNotFoundException.class);
        }
    }

    @Test
    @DisplayName("deletes account assignments through the AWS SDK")
    void deleteAccountAssignmentUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Uses emulator-only account and principal identifiers");

        try (SsoAdminClient sso = TestFixtures.ssoAdminClient()) {
            String instanceArn = sso.listInstances(request -> {}).instances().get(0).instanceArn();
            String permissionSetArn = sso.createPermissionSet(request -> request
                            .instanceArn(instanceArn)
                            .name("FlociDeleteAssignmentAdmins"))
                    .permissionSet().permissionSetArn();
            sso.createAccountAssignment(request -> request
                    .instanceArn(instanceArn)
                    .targetId("123456789012")
                    .targetType(TargetType.AWS_ACCOUNT)
                    .permissionSetArn(permissionSetArn)
                    .principalType(PrincipalType.GROUP)
                    .principalId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));

            var deleted = sso.deleteAccountAssignment(request -> request
                    .instanceArn(instanceArn)
                    .targetId("123456789012")
                    .targetType(TargetType.AWS_ACCOUNT)
                    .permissionSetArn(permissionSetArn)
                    .principalType(PrincipalType.GROUP)
                    .principalId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));

            assertThat(deleted.accountAssignmentDeletionStatus()).isNotNull();
            assertThat(deleted.accountAssignmentDeletionStatus().statusAsString()).isEqualTo("SUCCEEDED");
            assertThat(deleted.accountAssignmentDeletionStatus().requestId()).isNotBlank();
            var describedDeletion = sso.describeAccountAssignmentDeletionStatus(request -> request
                    .instanceArn(instanceArn)
                    .accountAssignmentDeletionRequestId(deleted.accountAssignmentDeletionStatus().requestId()));
            assertThat(describedDeletion.accountAssignmentDeletionStatus().requestId())
                    .isEqualTo(deleted.accountAssignmentDeletionStatus().requestId());
            assertThat(describedDeletion.accountAssignmentDeletionStatus().statusAsString()).isEqualTo("SUCCEEDED");

            var deletionStatuses = sso.listAccountAssignmentDeletionStatus(request -> request
                    .instanceArn(instanceArn)
                    .filter(filter -> filter.status("SUCCEEDED")));
            assertThat(deletionStatuses.accountAssignmentsDeletionStatus())
                    .anyMatch(operation -> deleted.accountAssignmentDeletionStatus().requestId().equals(operation.requestId()));

            assertThat(sso.listAccountAssignments(request -> request
                    .instanceArn(instanceArn)
                    .accountId("123456789012")
                    .permissionSetArn(permissionSetArn)).accountAssignments()).isEmpty();
        }
    }

    @Test
    @DisplayName("creates and describes account assignments through the AWS SDK")
    void accountAssignmentLifecycleUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Uses emulator-only account and principal identifiers");

        try (SsoAdminClient sso = TestFixtures.ssoAdminClient()) {
            String instanceArn = sso.listInstances(request -> {}).instances().get(0).instanceArn();
            String permissionSetArn = sso.createPermissionSet(request -> request
                            .instanceArn(instanceArn)
                            .name("FlociPlatformAdmins"))
                    .permissionSet()
                    .permissionSetArn();

            var created = sso.createAccountAssignment(request -> request
                    .instanceArn(instanceArn)
                    .targetId("123456789012")
                    .targetType(TargetType.AWS_ACCOUNT)
                    .permissionSetArn(permissionSetArn)
                    .principalType(PrincipalType.GROUP)
                    .principalId("11111111-2222-3333-4444-555555555555"));

            assertThat(created.accountAssignmentCreationStatus()).isNotNull();
            assertThat(created.accountAssignmentCreationStatus().requestId()).isNotBlank();

            var status = sso.describeAccountAssignmentCreationStatus(request -> request
                    .instanceArn(instanceArn)
                    .accountAssignmentCreationRequestId(created.accountAssignmentCreationStatus().requestId()));
            assertThat(status.accountAssignmentCreationStatus().statusAsString()).isEqualTo("SUCCEEDED");
            assertThat(status.accountAssignmentCreationStatus().createdDate()).isNotNull();

            var creationStatuses = sso.listAccountAssignmentCreationStatus(request -> request
                    .instanceArn(instanceArn)
                    .filter(filter -> filter.status("SUCCEEDED")));
            assertThat(creationStatuses.accountAssignmentsCreationStatus())
                    .anyMatch(operation -> created.accountAssignmentCreationStatus().requestId().equals(operation.requestId()));

            var assignments = sso.listAccountAssignments(request -> request
                    .instanceArn(instanceArn)
                    .accountId("123456789012")
                    .permissionSetArn(permissionSetArn));
            assertThat(assignments.accountAssignments())
                    .anySatisfy(assignment -> {
                        assertThat(assignment.principalType()).isEqualTo(PrincipalType.GROUP);
                        assertThat(assignment.principalId()).isEqualTo("11111111-2222-3333-4444-555555555555");
                    });

            for (String policy : new String[] {"ReadOnlyAccess", "SecurityAudit", "ViewOnlyAccess"}) {
                sso.attachManagedPolicyToPermissionSet(request -> request
                        .instanceArn(instanceArn)
                        .permissionSetArn(permissionSetArn)
                        .managedPolicyArn("arn:aws:iam::aws:policy/" + policy));
            }
            var firstPolicies = sso.listManagedPoliciesInPermissionSet(request -> request
                    .instanceArn(instanceArn)
                    .permissionSetArn(permissionSetArn)
                    .maxResults(1));
            assertThat(firstPolicies.attachedManagedPolicies()).hasSize(1);
            assertThat(firstPolicies.nextToken()).isNotBlank();
            var secondPolicies = sso.listManagedPoliciesInPermissionSet(request -> request
                    .instanceArn(instanceArn)
                    .permissionSetArn(permissionSetArn)
                    .maxResults(1)
                    .nextToken(firstPolicies.nextToken()));
            assertThat(secondPolicies.attachedManagedPolicies()).hasSize(1);
            assertThat(secondPolicies.attachedManagedPolicies().get(0).arn())
                    .isNotEqualTo(firstPolicies.attachedManagedPolicies().get(0).arn());

            assertThatThrownBy(() -> sso.listManagedPoliciesInPermissionSet(request -> request
                    .instanceArn(instanceArn)
                    .permissionSetArn(permissionSetArn)
                    .maxResults(101)))
                    .isInstanceOf(ValidationException.class);
        }
    }
}
