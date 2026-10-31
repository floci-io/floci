# Payment Cryptography

**Protocol:** JSON 1.0 (`X-Amz-Target: PaymentCryptographyControlPlane.*`)
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateKey` | Create a key with fresh AES-256 material |
| `GetKey` | Get a key's attributes, state and check value |
| `ListKeys` | List the keys of a region |
| `DeleteKey` | Delete a key |
| `CreateAlias` | Point an alias name at a key |
| `GetAlias` | Get the key an alias name points at |
| `ListAliases` | List the aliases of a region |
| `UpdateAlias` | Point an alias name at another key |
| `DeleteAlias` | Remove an alias name |
| `TagResource` | Add tags to a key |
| `UntagResource` | Remove tags from a key |
| `ListTagsForResource` | List the tags of a key |
<!-- floci:actions:end -->

## Emulation Behavior

- **Real key material:** `CreateKey` generates 32 bytes of AES-256 material, and the
  `KeyCheckValue` is the leftmost three bytes of the CMAC of sixteen zero bytes under
  it, which is what the service computes for a `CMAC` check value.
- **Regional:** keys, aliases and tags all live in the region they were made in. The
  service is regional, and so is this.
- **Alias names are unique in a region, not in an account:** the same name may name a
  key in two regions, and the two are two aliases. A name that is already taken in
  the key's own region is a `ConflictException`.
- **Tags are the region's:** they are not part of the replication process, so a key in
  another region carries its own tag set, and tagging one leaves the others alone.
- **Deletion:** `DeleteKey` removes the key and the aliases that name it. A key that
  is gone does not come back: a tagging request for it is refused.
- **Creating a key:** `CreateKey` takes `Exportable`, `Enabled` and `Tags`, and a key
  created disabled or with tags comes back that way.
- **Paging:** `ListKeys`, `ListAliases` and `ListTagsForResource` take `MaxResults`
  and `NextToken`, and answer with one page and the token that asks for the next.
- **Not yet emulated:** the data plane (`POST /encrypt` and the rest), the import and
  export formats, and the multi-region replication operations.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_PAYMENTCRYPTOGRAPHY_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a key
KEY_ARN=$(aws payment-cryptography create-key \
  --key-attributes KeyAlgorithm=AES_256,KeyClass=SYMMETRIC_KEY,KeyUsage=TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY \
  --exportable \
  --query Key.KeyArn --output text)

# Name it
aws payment-cryptography create-alias \
  --alias-name alias/team/card-key --key-arn "$KEY_ARN"

# Tag it
aws payment-cryptography tag-resource \
  --resource-arn "$KEY_ARN" \
  --tags Key=Origin,Value=PRIMARY

# Read it back
aws payment-cryptography get-key --key-identifier "$KEY_ARN"
```
