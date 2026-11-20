# TR-34 test vectors

Test vectors for the `Tr34` codec, from the informative samples in the ASC X9
TR-34-2019 standard, Annex B.

- `tr34-2019-annex-b-aes-key-block.der`
  - Annex B.2.2.2.4, sample AES KeyBlock.
  - SHA-256: `eceeedaa129dc583df2ac6a0d68d9b89247ea9e18b005eff4cdb617a5f0e7fc2`
  - Clear key `0123456789ABCDEFFEDCBA9876543210`, header `D0256K0AB00E0000`.

- `tr34-2019-annex-b-aes-enveloped-data.der`
  - Annex B.2.2.3.2, sample AES EnvelopedData.
  - SHA-256: `fb3e8b94bf22c4b456804d7145832315e4366937296c773f5cbc26dd39adbf71`
  - Ephemeral AES key `0123456789ABCDEFFEDCBA9876543210`; 144 bytes of
    AES-128-CBC ciphertext of a 133-byte key block. That key block carries the
    same clear key under the header `A0256K0TB00E0000`, which is not the header
    of the separately published clear key block.
  - The sample serializes the AES-CBC IV as the eight bytes
    `0123456789ABCDEF`; the 16-byte IV that produced the ciphertext is the
    zero-extended form `0123456789ABCDEF0000000000000000`, which is
    recoverable from the first CBC block.
  - The sample is a standard CMS EnvelopedData, a sequence of the version, the
    recipients and the encrypted content information. The reference writes the
    envelope of a TR-34 block as three bare objects instead, which is the shape
    `Tr34.recoverAndVerify` reads, so the sample is opened by the test rather
    than by the codec.

The vectors are the standard's published byte values, kept as-is. They are
compared against independent results recorded by the paysec project
(https://github.com/5n00py/paysec, `crates/paysec-tr34/tests/tr34_2019_aes_vectors.rs`),
which extracted the same samples.

The test checks the SHA-256 of each vector as it reads it, so a sample that was
altered or mangled in the tree is named as such rather than failing as a parse
error.
