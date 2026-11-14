# Payment Cryptography

**Protocol:** JSON 1.0 (`X-Amz-Target: PaymentCryptographyControlPlane.*`)
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateKey` | Create a key with fresh material |
| `GetKey` | Get a key's attributes, state and check value |
| `ListKeys` | List the keys of a region |
| `DeleteKey` | Delete a key |
| `CreateAlias` | Point an alias name at a key |
| `GetAlias` | Get the key an alias name points at |
| `ListAliases` | List the aliases of a region |
| `UpdateAlias` | Point an alias name at another key |
| `DeleteAlias` | Remove an alias name |
| `ExportKey` | Export a key under a wrapping key, a certificate or an agreed secret |
| `ImportKey` | Import a key under a wrapping key, a certificate or an agreed secret |
| `GetPublicKeyCertificate` | The certificate of an asymmetric key |
| `GetParametersForImport` | The token and the wrapping key certificate an import quotes back |
| `GetParametersForExport` | The token and the signing key certificate an export quotes back |
| `TagResource` | Add tags to a key |
| `UntagResource` | Remove tags from a key |
| `ListTagsForResource` | List the tags of a key |
<!-- floci:actions:end -->

## Emulation Behavior

- **Real key material:** `CreateKey` generates the material the algorithm it names
  calls for: 32 bytes of AES-256 for an AES-256 key, 16 or 24 bytes for the two Triple
  DES ones, and a key pair for an RSA or EC one. The `KeyCheckValue` follows the
  algorithm: the leftmost three bytes of the CMAC of sixteen zero bytes under an AES
  key, and the ANSI X9.24 one for a Triple DES key. The material is held by the key
  while the emulator runs and is not written to the store.
- **Regional:** keys, aliases and tags all live in the region they were made in. The
  service is regional, and so is this.
- **Alias names are unique in a region, not in an account:** the same name may name a
  key in two regions, and the two are two aliases. A name that is already taken in
  the key's own region is a `ConflictException`.
- **Tags are the region's:** they are not part of the replication process, so a key in
  another region carries its own tag set, and tagging one leaves the others alone.
- **Deletion:** `DeleteKey` removes the key and the aliases that name it, and answers
  with the key, its state `DELETE_PENDING` and the moment its deletion takes effect.
  A key that is gone does not come back: a tagging request for it is refused. The
  service deletes a key the moment it is asked to, so the waiting period of
  `DeleteKeyInDays` is checked against the range the model states and is not waited for.
- **Creating a key:** `CreateKey` takes `Exportable`, `Enabled` and `Tags`, and a key
  created disabled or with tags comes back that way. The `KeyUsage` and the
  `KeyModesOfUse` of the key are required, as the reference requires them. The answer
  carries every mode of use the reference gives a key, the ones the key was not made
  with as false, so a caller reading them finds a boolean for each of them. The check
  value is a CMAC, which is the only algorithm this service computes, so a request
  naming another one is refused.
- **Which key a request may name:** `KeyIdentifier` takes the ARN of a key, the ARN of
  an alias or the name of an alias, while `KeyArn` and `ResourceArn` take the ARN of
  a key alone, as the reference states them. A request naming a key another way is
  refused, and the permission check reads the same member the request does.
- **Listing by state:** `ListKeys` takes a `KeyState` and answers with the keys in
  that state alone, and with every key when it names none. Each key in the list is the
  summary the model names: its identifier, its state, its attributes, its check value
  and its two flags.
- **The members a request may name that the service does not read:** `CreateKey` and
  `ImportKey` take `ReplicationRegions`, and `ImportKey` takes `RequesterComment`, and the
  service reads none of them. No replica is made and no approval is recorded, because the
  replication and the multi-party approval are not emulated.
- **An alias may name no key:** `UpdateAlias` names the key of an alias, and an update
  that names none removes the key the alias names and leaves the alias. `CreateAlias`
  refuses an alias without a key, as the reference does, though its model requires
  only the name.
- **Paging:** `ListKeys`, `ListAliases` and `ListTagsForResource` take `MaxResults`
  and `NextToken`, and answer with one page and the token that asks for the next.
- **The key exchange:** `ExportKey` and `ImportKey` take the form of the key material the
  request names, one form per request. A key leaves under a TR-31 block the service wraps
  with a key it holds, under an RSA-OAEP cryptogram the receiver's certificate protects, under
  a TR-34 key block the service signs and wraps for the receiver, or under a key the two
  parties agreed with ECDH; it comes back under the same protections, and a public key comes
  back under the certificate that carries it. A key that is not exportable
  does not leave, a key pair has no material a key block could carry, and a counterparty's
  certificate that the named authority did not sign is refused. An export may name an initial
  DUKPT key by its serial number, and the derived key leaves instead of the base derivation
  key. The base derivation key may not be exportable itself and still derive that initial key,
  which the export guide says the standards allow.
- **The keys a key exchange names:** a TR-31 block's wrapping key must be a key
  encryption key that may wrap, and of the family the block's own type names, so a TDES
  wrapping key carries a type B block and an AES one a type D block. A key agreement needs
  a key agreement key that may derive. The certificate a cryptogram or an agreement names
  must be one the named authority signed. A key that is disabled does no work, so it may
  not wrap, unwrap, agree or vouch for a certificate.
- **The strength a wrapping key must have:** a wrapping key must be at least as strong as
  the key it carries, by the matrix the guide publishes. A TR-31 block's wrapping key is
  therefore no weaker than the key it carries, an RSA cryptogram's is capped by its
  modulus, so RSA-2048 carries Triple DES but not AES-128, RSA-3072 and RSA-4096 carry
  AES-128, and no RSA size carries AES-192 or AES-256, and a key the two parties agreed
  is capped by the curve's strength, so P-256 carries no AES-192.
- **The check value of an export:** a key block carries the check value of the key it
  carries, and a cryptogram carries none, because it carries no key block at all.
- **The TR-34 key block:** a key leaves under a TR-31 block the service signs with its signing
  key and wraps for the receiver under the receiver's certificate, in the `X9_TR34_2012`
  format, and comes back the same way. The block carries the TR-31 header in the clear, the
  nonce the two passes agree on, and the digest of the envelope it wraps, all under the
  signature of the signing certificate, which has to chain to the named authority and be
  inside its validity window. The block names its recipient by the receiver's certificate,
  so the receiver can tell which of its keys the block is for. A block whose signature does
  not verify, whose digest does not match, whose nonce is not the one the request quotes,
  or that carries no header to say what it holds is refused.
- **The cipher of a TR-34 block:** the key block is enciphered with the cipher the key needs,
  as the reference does, so a key is never protected by something weaker than itself: a
  Triple DES key under Triple DES and an AES key under AES-128. The key a block may carry is
  therefore the one the guide's matrix allows for a receiver's certificate, which is a Triple
  DES key or an AES-128 key, and an AES-128 key needs a receiver whose certificate is
  RSA-3072 or RSA-4096.
- **The signing key of a TR-34 block:** the service signs the block with the key the export
  parameters issued, so the export has to name the export token they issued, and a request
  that names its own signing key is refused. The signing key is an RSA one, because the
  block's signature is an RSA signature, so `GetParametersForExport` takes `RSA_2048` and
  refuses the other algorithms.
- **The token of a TR-34 block:** the import has to name the import token the import
  parameters issued, and a request that names none, or that names a wrapping key instead,
  is refused with the reason rather than with a key that is not found.
- **The headers a TR-34 export names:** a request may narrow the modes of use and the
  exportability of the key, and the header the block carries says so, the same as it does
  for a TR-31 block. A TR-34 block's header names the key and carries nothing else, so a
  request that names optional blocks is refused.
- **The nonce of a TR-34 block:** the nonce binds the block to the exchange, and it is
  optional in the model, so a block made without one is read without the freshness it
  carries. A request that names no nonce expects a block that carries none, so a block that
  carries one may not come in under a request that names none.
- **The AS2805 form is not made.** A request that asks for it is refused with the error that
  says so, rather than answered with something of another kind, and the refusal names the
  forms the service serves.
- **The block headers a request names:** an export may name the modes of use, the
  exportability, the version and the optional blocks of the TR-31 block it produces, and
  the block carries them. An optional block is written with its length in hexadecimal, and
  the length covers the whole block, the identifier and the length included. The optional
  blocks take up a whole number of cipher blocks, so a block whose optional blocks are not
  aligned carries a padding block that fills the rest.
- **The block headers may only narrow the key.** A request that names modes of use or an
  exportability may not give the key a permission it does not have: a key that may only
  encrypt is refused an export that says it may decrypt, and a key that is already set to a
  stricter exportability is refused a looser one. A key encryption key wraps and unwraps
  where another key encrypts and decrypts, so for one the two spellings of a direction are
  the same permission.
- **The order of the exportabilities:** the exportability says what the receiver may do with
  the key once it has it. `SENSITIVE` is the loosest, for it lets the receiver export under
  a key block, a TR-34 block or a cryptogram, `EXPORTABLE` lets it export under a key block
  or a TR-34 block only, and `NON_EXPORTABLE` does not let it export at all.
- **The exportability of a key that comes in:** the exportability of the block says whether
  the key may leave the service again. An exportable or a sensitive key may, and only a
  non-exportable one may not.
- **The modes of use of a key that comes in:** the mode of use character of the block says
  what the key may be used for, and both spellings of a direction stand for it. A block
  that says `B` gives the key all four of `Encrypt`, `Decrypt`, `Wrap` and `Unwrap`,
  whichever use the key has.
- **The origin of a key that comes in:** a key that was imported says it came from outside
  the service, which is `EXTERNAL`, and a key the service made says `AWS_PAYMENT_CRYPTOGRAPHY`.
- **Only the type B and type D key blocks are read.** The standard also has the type A and
  the type C block, the key variant ones, and the reference reads them on import; this
  service refuses them and names the two it reads.
- **The certificate of a key:** `GetPublicKeyCertificate` answers with the certificate and
  its chain, and a certificate the service mints is valid for ninety days.
- **The import and export parameters:** `GetParametersForImport` answers with the token an
  import quotes back, the wrapping key's certificate and the chain, and the moment the
  parameters expire; `GetParametersForExport` answers with the same for an export, over a
  signing key. Each token is named for the operation that issued it, and a token is good
  until the parameters expire.
- **The data plane is a service of its own:** the operations that use a key are on
  [the data plane's page](payment-cryptography-data.md), which is reached at its own
  routes.
- **Not yet emulated:** the multi-region replication operations, the resource policy
  operations, the key usage operations, the key restore operation, the multi-party
  approval operations and the certificate signing request.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_PAYMENTCRYPTOGRAPHY_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a key
KEY_ARN=$(aws payment-cryptography create-key \
  --key-attributes 'KeyAlgorithm=AES_256,KeyClass=SYMMETRIC_KEY,KeyUsage=TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY,KeyModesOfUse={Encrypt=true,Decrypt=true}' \
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
