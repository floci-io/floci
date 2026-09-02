"""KMS integration tests."""

import hashlib

import pytest
from botocore.exceptions import ClientError
from cryptography.hazmat.primitives import serialization


class TestKMSKey:
    """Test KMS key operations."""

    def test_create_key(self, kms_client):
        """Test CreateKey creates a key."""
        response = kms_client.create_key(Description="pytest-test-key")
        key_id = response["KeyMetadata"]["KeyId"]
        assert key_id

        # Cleanup
        kms_client.schedule_key_deletion(KeyId=key_id, PendingWindowInDays=7)

    def test_describe_key(self, kms_client):
        """Test DescribeKey returns key metadata."""
        response = kms_client.create_key(Description="pytest-test-key")
        key_id = response["KeyMetadata"]["KeyId"]

        try:
            response = kms_client.describe_key(KeyId=key_id)
            assert response["KeyMetadata"]["KeyId"] == key_id
        finally:
            kms_client.schedule_key_deletion(KeyId=key_id, PendingWindowInDays=7)

    def test_schedule_key_deletion(self, kms_client):
        """Test ScheduleKeyDeletion marks key for deletion."""
        response = kms_client.create_key(Description="pytest-test-key")
        key_id = response["KeyMetadata"]["KeyId"]

        kms_client.schedule_key_deletion(KeyId=key_id, PendingWindowInDays=7)

        response = kms_client.describe_key(KeyId=key_id)
        assert response["KeyMetadata"]["KeyState"] == "PendingDeletion"


class TestKMSGrants:
    """Test KMS grant operations."""

    def test_list_grants(self, kms_client):
        """Test ListGrants returns an empty grant list for a new key."""
        response = kms_client.create_key(Description="pytest-grant-test-key")
        key_id = response["KeyMetadata"]["KeyId"]

        try:
            response = kms_client.list_grants(KeyId=key_id)
            assert response["Grants"] == []
        finally:
            kms_client.schedule_key_deletion(KeyId=key_id, PendingWindowInDays=7)

    def test_list_grants_paginator(self, kms_client):
        """Test ListGrants paginator returns pages with Grants."""
        response = kms_client.create_key(Description="pytest-grant-test-key")
        key_id = response["KeyMetadata"]["KeyId"]

        try:
            paginator = kms_client.get_paginator("list_grants")
            pages = list(paginator.paginate(KeyId=key_id))

            assert pages
            assert all("Grants" in page for page in pages)
            assert [grant for page in pages for grant in page["Grants"]] == []
        finally:
            kms_client.schedule_key_deletion(KeyId=key_id, PendingWindowInDays=7)


class TestKMSAlias:
    """Test KMS alias operations."""

    def test_create_alias(self, kms_client, unique_name):
        """Test CreateAlias creates an alias."""
        response = kms_client.create_key(Description="pytest-test-key")
        key_id = response["KeyMetadata"]["KeyId"]
        alias_name = f"alias/pytest-key-{unique_name}"

        try:
            kms_client.create_alias(AliasName=alias_name, TargetKeyId=key_id)
            # If no exception, test passes
        finally:
            kms_client.delete_alias(AliasName=alias_name)
            kms_client.schedule_key_deletion(KeyId=key_id, PendingWindowInDays=7)

    def test_list_aliases(self, kms_client, unique_name):
        """Test ListAliases returns aliases."""
        response = kms_client.create_key(Description="pytest-test-key")
        key_id = response["KeyMetadata"]["KeyId"]
        alias_name = f"alias/pytest-key-{unique_name}"

        kms_client.create_alias(AliasName=alias_name, TargetKeyId=key_id)

        try:
            response = kms_client.list_aliases()
            assert any(a["AliasName"] == alias_name for a in response["Aliases"])
        finally:
            kms_client.delete_alias(AliasName=alias_name)
            kms_client.schedule_key_deletion(KeyId=key_id, PendingWindowInDays=7)

    def test_delete_alias(self, kms_client, unique_name):
        """Test DeleteAlias removes alias."""
        response = kms_client.create_key(Description="pytest-test-key")
        key_id = response["KeyMetadata"]["KeyId"]
        alias_name = f"alias/pytest-key-{unique_name}"

        kms_client.create_alias(AliasName=alias_name, TargetKeyId=key_id)
        kms_client.delete_alias(AliasName=alias_name)

        response = kms_client.list_aliases()
        assert not any(
            a["AliasName"] == alias_name for a in response.get("Aliases", [])
        )

        # Cleanup
        kms_client.schedule_key_deletion(KeyId=key_id, PendingWindowInDays=7)


class TestKMSEncryption:
    """Test KMS encryption operations."""

    def test_encrypt_decrypt(self, kms_client):
        """Test Encrypt and Decrypt roundtrip."""
        response = kms_client.create_key(Description="pytest-test-key")
        key_id = response["KeyMetadata"]["KeyId"]
        plaintext = b"secret data"

        try:
            encrypt_response = kms_client.encrypt(KeyId=key_id, Plaintext=plaintext)
            ciphertext = encrypt_response["CiphertextBlob"]
            assert ciphertext

            decrypt_response = kms_client.decrypt(CiphertextBlob=ciphertext)
            assert decrypt_response["Plaintext"] == plaintext
        finally:
            kms_client.schedule_key_deletion(KeyId=key_id, PendingWindowInDays=7)

    def test_encrypt_using_alias(self, kms_client, unique_name):
        """Test Encrypt using alias."""
        response = kms_client.create_key(Description="pytest-test-key")
        key_id = response["KeyMetadata"]["KeyId"]
        alias_name = f"alias/pytest-key-{unique_name}"

        kms_client.create_alias(AliasName=alias_name, TargetKeyId=key_id)

        try:
            response = kms_client.encrypt(KeyId=alias_name, Plaintext=b"alias data")
            assert response.get("CiphertextBlob")
        finally:
            kms_client.delete_alias(AliasName=alias_name)
            kms_client.schedule_key_deletion(KeyId=key_id, PendingWindowInDays=7)

    def test_generate_data_key(self, kms_client):
        """Test GenerateDataKey generates plaintext and ciphertext."""
        response = kms_client.create_key(Description="pytest-test-key")
        key_id = response["KeyMetadata"]["KeyId"]

        try:
            response = kms_client.generate_data_key(KeyId=key_id, KeySpec="AES_256")
            assert response.get("Plaintext")
            assert response.get("CiphertextBlob")
        finally:
            kms_client.schedule_key_deletion(KeyId=key_id, PendingWindowInDays=7)

    def test_generate_data_key_without_plaintext(self, kms_client):
        """Test GenerateDataKeyWithoutPlaintext returns only ciphertext."""
        response = kms_client.create_key(Description="pytest-test-key")
        key_id = response["KeyMetadata"]["KeyId"]

        try:
            response = kms_client.generate_data_key_without_plaintext(
                KeyId=key_id, KeySpec="AES_256"
            )
            assert response.get("CiphertextBlob")
        finally:
            kms_client.schedule_key_deletion(KeyId=key_id, PendingWindowInDays=7)

    def test_re_encrypt(self, kms_client):
        """Test ReEncrypt re-encrypts data with different key."""
        response1 = kms_client.create_key(Description="pytest-test-key-1")
        key_id1 = response1["KeyMetadata"]["KeyId"]
        response2 = kms_client.create_key(Description="pytest-test-key-2")
        key_id2 = response2["KeyMetadata"]["KeyId"]

        plaintext = b"secret data"
        encrypt_response = kms_client.encrypt(KeyId=key_id1, Plaintext=plaintext)
        ciphertext = encrypt_response["CiphertextBlob"]

        try:
            reencrypt_response = kms_client.re_encrypt(
                CiphertextBlob=ciphertext, DestinationKeyId=key_id2
            )
            new_ciphertext = reencrypt_response["CiphertextBlob"]
            assert new_ciphertext

            decrypt_response = kms_client.decrypt(CiphertextBlob=new_ciphertext)
            assert decrypt_response["Plaintext"] == plaintext
        finally:
            kms_client.schedule_key_deletion(KeyId=key_id1, PendingWindowInDays=7)
            kms_client.schedule_key_deletion(KeyId=key_id2, PendingWindowInDays=7)


class TestKMSSigning:
    """Test KMS signing operations."""

    def test_sign_and_verify(self, kms_client):
        """Test Sign and Verify with RSA key."""
        # Create an asymmetric signing key
        response = kms_client.create_key(
            Description="pytest-signing-key",
            KeyUsage="SIGN_VERIFY",
            KeySpec="RSA_2048",
        )
        key_id = response["KeyMetadata"]["KeyId"]
        message = b"message to sign"

        try:
            # Sign the message
            sign_response = kms_client.sign(
                KeyId=key_id,
                Message=message,
                SigningAlgorithm="RSASSA_PSS_SHA_256",
            )
            signature = sign_response["Signature"]
            assert signature

            # Verify the signature
            verify_response = kms_client.verify(
                KeyId=key_id,
                Message=message,
                Signature=signature,
                SigningAlgorithm="RSASSA_PSS_SHA_256",
            )
            assert verify_response["SignatureValid"]
        finally:
            kms_client.schedule_key_deletion(KeyId=key_id, PendingWindowInDays=7)


    def test_ed25519_sign_and_verify(self, kms_client):
        """Test Ed25519 signing, checked against an outside library and real KMS rules."""
        key_id = kms_client.create_key(
            Description="pytest-ed25519-key",
            KeyUsage="SIGN_VERIFY",
            KeySpec="ECC_NIST_EDWARDS25519",
        )["KeyMetadata"]["KeyId"]
        message = b"message to sign"
        digest = hashlib.sha512(message).digest()

        try:
            # Real KMS returns a 44 byte SubjectPublicKeyInfo holding a 32 byte Ed25519
            # point. A NIST P-521 key, which this key spec used to produce, is far larger.
            public_key = serialization.load_der_public_key(
                kms_client.get_public_key(KeyId=key_id)["PublicKey"]
            )
            assert type(public_key).__name__ == "Ed25519PublicKey"

            # ED25519_SHA_512 is pure Ed25519 over the message, so an outside library
            # verifies it directly.
            signature = kms_client.sign(
                KeyId=key_id,
                Message=message,
                MessageType="RAW",
                SigningAlgorithm="ED25519_SHA_512",
            )["Signature"]
            assert len(signature) == 64
            public_key.verify(signature, message)
            assert kms_client.verify(
                KeyId=key_id,
                Message=message,
                MessageType="RAW",
                Signature=signature,
                SigningAlgorithm="ED25519_SHA_512",
            )["SignatureValid"]

            # ED25519_PH_SHA_512 requires DIGEST and pre-hashes the bytes it is given,
            # so it is a different signature over the same input.
            prehash_signature = kms_client.sign(
                KeyId=key_id,
                Message=digest,
                MessageType="DIGEST",
                SigningAlgorithm="ED25519_PH_SHA_512",
            )["Signature"]
            assert len(prehash_signature) == 64
            assert prehash_signature != signature
            assert kms_client.verify(
                KeyId=key_id,
                Message=digest,
                MessageType="DIGEST",
                Signature=prehash_signature,
                SigningAlgorithm="ED25519_PH_SHA_512",
            )["SignatureValid"]

            # Each algorithm takes one message type, the way real KMS enforces it.
            for algorithm, message_type in [
                ("ED25519_SHA_512", "DIGEST"),
                ("ED25519_PH_SHA_512", "RAW"),
            ]:
                with pytest.raises(ClientError) as excinfo:
                    kms_client.sign(
                        KeyId=key_id,
                        Message=digest if message_type == "DIGEST" else message,
                        MessageType=message_type,
                        SigningAlgorithm=algorithm,
                    )
                assert excinfo.value.response["Error"]["Code"] == "ValidationException"

            # A DIGEST for the pre-hash algorithm has to be one SHA-512 digest. Real KMS
            # checks the length on Sign and on Verify.
            with pytest.raises(ClientError) as excinfo:
                kms_client.sign(
                    KeyId=key_id,
                    Message=b"not a sha-512 digest",
                    MessageType="DIGEST",
                    SigningAlgorithm="ED25519_PH_SHA_512",
                )
            assert "Digest is invalid length" in excinfo.value.response["Error"]["Message"]
            with pytest.raises(ClientError) as excinfo:
                kms_client.verify(
                    KeyId=key_id,
                    Message=b"not a sha-512 digest",
                    MessageType="DIGEST",
                    Signature=prehash_signature,
                    SigningAlgorithm="ED25519_PH_SHA_512",
                )
            assert "Digest is invalid length" in excinfo.value.response["Error"]["Message"]
        finally:
            kms_client.schedule_key_deletion(KeyId=key_id, PendingWindowInDays=7)


class TestKMSTagging:
    """Test KMS tagging operations."""

    def test_tag_resource(self, kms_client):
        """Test TagResource and ListResourceTags."""
        response = kms_client.create_key(Description="pytest-test-key")
        key_id = response["KeyMetadata"]["KeyId"]

        try:
            kms_client.tag_resource(
                KeyId=key_id, Tags=[{"TagKey": "Project", "TagValue": "Floci"}]
            )

            response = kms_client.list_resource_tags(KeyId=key_id)
            assert any(
                t["TagKey"] == "Project" and t["TagValue"] == "Floci"
                for t in response["Tags"]
            )
        finally:
            kms_client.schedule_key_deletion(KeyId=key_id, PendingWindowInDays=7)


class TestKMSGenerateRandom:
    """Test KMS GenerateRandom operation."""

    def test_generate_random(self, kms_client):
        """Test GenerateRandom returns random bytes."""
        response = kms_client.generate_random(NumberOfBytes=32)
        plaintext = response["Plaintext"]
        assert plaintext
        assert len(plaintext) == 32
