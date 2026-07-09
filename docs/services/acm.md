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
<!-- floci:actions:end -->

## Emulation Behavior

- **Auto-Issuance:** All requested certificates are immediately issued with status `ISSUED` (no DNS/email validation required)
- **Real Cryptography:** Certificates are generated with real RSA/EC keys and valid X.509 structure
- **Key Algorithms:** Supports `RSA_2048`, `RSA_3072`, `RSA_4096`, `EC_prime256v1`, `EC_secp384r1`, `EC_secp521r1`
- **Certificate Types:** `AMAZON_ISSUED` (default) and `PRIVATE` (when `CertificateAuthorityArn` is provided)
- **Export:** Only `PRIVATE` type certificates can be exported with their private key

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
