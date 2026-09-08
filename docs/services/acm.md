# ACM

**Protocol:** JSON 1.1 (`X-Amz-Target: CertificateManager.*`)
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `RequestCertificate` | Request a new certificate (auto-issued for emulation) |
| `DescribeCertificate` | Get certificate details and validation status |
| `GetCertificate` | Retrieve the certificate and chain in PEM format |
| `ListCertificates` | List all certificates with optional status filtering |
| `DeleteCertificate` | Delete a certificate |
| `ImportCertificate` | - |
| `ExportCertificate` | Export certificate with encrypted private key (PRIVATE type only) |
| `AddTagsToCertificate` | Add tags to a certificate |
| `ListTagsForCertificate` | List tags for a certificate |
| `RemoveTagsFromCertificate` | Remove tags from a certificate |
| `GetAccountConfiguration` | Get account-level ACM settings |
| `PutAccountConfiguration` | Update account-level ACM settings |
| `UpdateCertificateOptions` | - |
| `RevokeCertificate` | - |
| `RenewCertificate` | - |
| `ResendValidationEmail` | - |
<!-- floci:actions:end -->

## Emulation Behavior

- **Auto-Issuance:** All requested certificates are immediately issued with status `ISSUED`, and every entry in `DomainValidationOptions` reports `ValidationStatus: SUCCESS` (no DNS/email validation required). With a validation wait configured, both stay pending until the wait has passed.
- **Real Cryptography:** Certificates are generated with real RSA/EC keys and valid X.509 structure
- **One Local CA:** Every issued certificate (`AMAZON_ISSUED` and `PRIVATE`) is signed by Floci's local root CA, and `GetCertificate` returns that CA as `CertificateChain`. Trust it once (`GET /_floci/ca.pem`, see [TLS](../configuration/tls.md)) and both Floci's HTTPS endpoint and every ACM certificate validate. `DescribeCertificate` reports `Issuer` as the CA's name, `CN=Floci Local CA`, where AWS reports `Amazon`. `ImportCertificate` keeps the chain you upload.
- **CA on First Use:** The CA is created under `{persistent-path}/tls/` the first time a certificate is issued, also with TLS off and in `memory` storage mode, so that directory must be writable. A certificate keeps the chain it was issued with; after a CA regeneration, delete and re-request it.
- **Key Algorithms:** `RequestCertificate` accepts `RSA_2048`, `EC_prime256v1`, and `EC_secp384r1` and rejects the rest with a `ValidationException`, matching real ACM; the wider list (`RSA_1024`, `RSA_3072`, `RSA_4096`, `EC_secp521r1`) remains valid for `ImportCertificate`
- **Certificate Types:** `AMAZON_ISSUED` (default) and `PRIVATE` (when `CertificateAuthorityArn` is provided)
- **Export:** Only `PRIVATE` type certificates can be exported with their private key
- **In use:** `DescribeCertificate` lists under `InUseBy` the CloudFront distribution of each Cognito custom domain that uses the certificate, and `DeleteCertificate` refuses with `ResourceInUseException` while that list is not empty. Other consumers, such as load balancers and API Gateway domain names, are not tracked yet.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_ACM_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_ACM_VALIDATION_WAIT_SECONDS` | `0` | Seconds to wait before transitioning a certificate from `PENDING_VALIDATION` to `ISSUED` (0 = immediate) |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Request a certificate
CERT_ARN=$(aws acm request-certificate \
  --domain-name "example.com" \
  --subject-alternative-names "www.example.com" "*.example.com" \
  --validation-method DNS \
  --query CertificateArn --output text \
  --endpoint-url $AWS_ENDPOINT_URL)

# Describe the certificate
aws acm describe-certificate \
  --certificate-arn $CERT_ARN \
  --endpoint-url $AWS_ENDPOINT_URL

# Get certificate in PEM format
aws acm get-certificate \
  --certificate-arn $CERT_ARN \
  --endpoint-url $AWS_ENDPOINT_URL

# List all certificates
aws acm list-certificates \
  --endpoint-url $AWS_ENDPOINT_URL

# List only issued certificates
aws acm list-certificates \
  --certificate-statuses ISSUED \
  --endpoint-url $AWS_ENDPOINT_URL

# Add tags
aws acm add-tags-to-certificate \
  --certificate-arn $CERT_ARN \
  --tags Key=Environment,Value=Production Key=Project,Value=Demo \
  --endpoint-url $AWS_ENDPOINT_URL

# List tags
aws acm list-tags-for-certificate \
  --certificate-arn $CERT_ARN \
  --endpoint-url $AWS_ENDPOINT_URL

# Request a private certificate (exportable)
PRIVATE_ARN=$(aws acm request-certificate \
  --domain-name "internal.example.com" \
  --certificate-authority-arn "arn:aws:acm-pca:us-east-1:123456789012:certificate-authority/12345678-1234-1234-1234-123456789012" \
  --query CertificateArn --output text \
  --endpoint-url $AWS_ENDPOINT_URL)

# Export private certificate (passphrase must be base64-encoded, min 4 chars)
PASSPHRASE=$(echo -n "mypassphrase123" | base64)
aws acm export-certificate \
  --certificate-arn $PRIVATE_ARN \
  --passphrase $PASSPHRASE \
  --endpoint-url $AWS_ENDPOINT_URL

# Delete a certificate
aws acm delete-certificate \
  --certificate-arn $CERT_ARN \
  --endpoint-url $AWS_ENDPOINT_URL
```

## SDK Example (Java)

```java
AcmClient acm = AcmClient.builder()
    .endpointOverride(URI.create("http://localhost:4566"))
    .region(Region.US_EAST_1)
    .credentialsProvider(StaticCredentialsProvider.create(
        AwsBasicCredentials.create("test", "test")))
    .build();

// Request a certificate
RequestCertificateResponse response = acm.requestCertificate(req -> req
    .domainName("example.com")
    .subjectAlternativeNames("www.example.com", "*.example.com")
    .validationMethod(ValidationMethod.DNS));

String certArn = response.certificateArn();

// Describe the certificate
DescribeCertificateResponse desc = acm.describeCertificate(req -> req
    .certificateArn(certArn));

System.out.println("Status: " + desc.certificate().status());
```
