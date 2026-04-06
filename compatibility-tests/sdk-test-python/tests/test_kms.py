"""KMS integration tests."""

import pytest


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
        assert not any(a["AliasName"] == alias_name for a in response.get("Aliases", []))

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
