# Task 3 report: Cognito federated OAuth routes

## Changed files

- `src/main/java/io/github/hectorvent/floci/services/cognito/CognitoOAuthController.java`
  - Added hosted-UI compatible authorize and identity-provider callback routes.
  - Added authorization-code redemption, including one-time code consumption, expiry through the state store, client and redirect URI binding, custom-domain pool binding, and OAuth token response conversion.
  - Preserved client-credentials handling and its Basic/form-client authentication checks.
- `src/main/java/io/github/hectorvent/floci/services/cognito/CognitoFederationService.java`
  - Added a package-private completion overload that accepts an already-consumed transaction. This lets the controller retain the transaction redirect URI without double-consuming federation state; the existing public method remains unchanged.
- `src/main/java/io/github/hectorvent/floci/services/cognito/CognitoCustomDomainFilter.java`
  - Documented that the existing `/oauth2/` rewrite and pool pinning also cover authorize and IdP callback requests.
- `src/main/java/io/github/hectorvent/floci/services/cognito/CognitoWellKnownController.java`
  - Advertises `code` responses and the `authorization_code` grant while retaining `client_credentials` metadata.
  - Replaced the existing `var` and inline qualified `Arrays` reference with explicit imported types.
- `src/test/java/io/github/hectorvent/floci/services/cognito/CognitoOAuthControllerTest.java`
  - Added controller coverage for valid authorization redirects, invalid client and callback URI, unsupported response type, invalid callback state, IdP errors, callback code redirects, redemption, replay, and Basic/form client authentication mismatch.
- `src/test/java/io/github/hectorvent/floci/services/cognito/CognitoCustomDomainOAuthContractIntegrationTest.java`
  - Updated prior assertions that treated authorization-code grant and its routes as unsupported.

## Test execution

### Red phase

Command:

```text
./mvnw -B -Dtest=CognitoOAuthControllerTest test
```

Output after a deliberate removal of the authorize behavior:

```text
Tests run: 1, Failures: 1, Errors: 0
```

The failing `authorizeRedirectsToConfiguredIdentityProvider` test proved the route test detects the missing authorize implementation. The behavior was restored immediately afterward.

### Final focused verification

Command:

```text
./mvnw -B -Dtest=CognitoOAuthControllerTest test
```

Output:

```text
Tests run: 10, Failures: 0, Errors: 0
BUILD SUCCESS
```

Additional focused unit verification:

```text
./mvnw -B -Dtest=CognitoOAuthControllerTest,CognitoCustomDomainFilterTest test
Tests run: 13, Failures: 0, Errors: 0
BUILD SUCCESS
```

Affected integration verification:

```text
./mvnw -B -Dtest=CognitoOAuthTokenIntegrationTest,CognitoCustomDomainOAuthContractIntegrationTest test
CognitoOAuthTokenIntegrationTest: Tests run: 21, Failures: 0, Errors: 0
CognitoCustomDomainOAuthContractIntegrationTest: Tests run: 11, Failures: 0, Errors: 0
BUILD SUCCESS
```

Maven emitted existing Quarkus `argLine`, deprecated configuration, split-package, and dynamically-loaded Mockito agent warnings. No compilation or test failures remained.

## Self-review

- Authorize validates the configured client, code flow, callback URI, and custom-domain owning pool before beginning federation.
- Callback state and authorization codes are consumed exactly once through `CognitoFederationStateStore`; expiration remains enforced by that store.
- Callback success and provider-error redirects preserve deterministic query parameter ordering.
- Authorization-code redemption checks the client, redirect URI, code pool, and confidential-client secret before issuing the existing Cognito user token set in OAuth JSON form.
- The custom-domain integration test was updated because authorization-code is now supported; a request missing its required redirect URI correctly returns `invalid_request` rather than `unsupported_grant_type`.
- `git diff --check` completed without whitespace errors.

## Concerns

None. This task intentionally leaves full end-to-end OIDC provider flow coverage to the following task; Task 3 validates its controller seams and affected existing OAuth integration contracts.

## Fix round 1

### Changes

- Preserved the relying-party `state` value exactly in the federation transaction and round-tripped it to the registered callback for both authorization-code and provider-error redirects.
- Added a non-destructive authorization-code lookup. Client lookup, client-secret validation, and code binding checks for client, redirect URI, and custom-domain pool now complete before atomic one-time consumption.
- Added controller regression coverage proving invalid client, redirect URI, custom-domain pool, and client-secret redemption requests do not burn a valid code.
- Added a custom-domain integration assertion that `/oauth2/authorize` reaches the Cognito OAuth controller instead of returning an unrouted 404.

### Final focused verification

Command:

```text
./mvnw -B -Dtest=CognitoOAuthControllerTest,CognitoFederationStateStoreTest,CognitoFederationServiceTest,CognitoCustomDomainOAuthContractIntegrationTest test
```

Output:

```text
CognitoCustomDomainOAuthContractIntegrationTest: Tests run: 12, Failures: 0, Errors: 0
CognitoFederationServiceTest: Tests run: 15, Failures: 0, Errors: 0
CognitoFederationStateStoreTest: Tests run: 7, Failures: 0, Errors: 0
CognitoOAuthControllerTest: Tests run: 14, Failures: 0, Errors: 0
Tests run: 48, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Maven emitted the repository's existing Quarkus deprecated-configuration and split-package warnings, plus the dynamically-loaded Mockito agent warning. No compilation or test failures remained.
