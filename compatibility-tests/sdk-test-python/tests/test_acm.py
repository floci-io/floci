"""ACM integration tests."""

import datetime
import re

import pytest
from botocore.exceptions import ClientError
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

FAKE_ARN = "arn:aws:acm:us-east-1:000000000000:certificate/00000000-0000-0000-0000-000000000000"


class TestACMCertificateLifecycle:
    """Test ACM certificate lifecycle operations."""

    def test_request_certificate(self, acm_client):
        """Test RequestCertificate creates a certificate and returns a valid ARN."""
        response = acm_client.request_certificate(DomainName="test.example.com")
        arn = response["CertificateArn"]

        try:
            assert arn
            assert re.match(r"arn:aws:acm:.*:.*:certificate/.*", arn)
        finally:
            acm_client.delete_certificate(CertificateArn=arn)

    def test_describe_certificate(self, acm_client):
        """Test DescribeCertificate returns certificate details."""
        response = acm_client.request_certificate(DomainName="describe.example.com")
        arn = response["CertificateArn"]

        try:
            response = acm_client.describe_certificate(CertificateArn=arn)
            cert = response["Certificate"]
            assert cert["DomainName"] == "describe.example.com"
            assert cert["Status"] == "ISSUED"
            assert cert["Type"] == "AMAZON_ISSUED"
        finally:
            acm_client.delete_certificate(CertificateArn=arn)

    def test_get_certificate(self, acm_client):
        """Test GetCertificate returns certificate body in PEM format."""
        response = acm_client.request_certificate(DomainName="get.example.com")
        arn = response["CertificateArn"]

        try:
            response = acm_client.get_certificate(CertificateArn=arn)
            assert response["Certificate"]
            assert "BEGIN CERTIFICATE" in response["Certificate"]
        finally:
            acm_client.delete_certificate(CertificateArn=arn)

    def test_list_certificates(self, acm_client):
        """Test ListCertificates includes created certificate."""
        response = acm_client.request_certificate(DomainName="list.example.com")
        arn = response["CertificateArn"]

        try:
            response = acm_client.list_certificates()
            arns = [
                c["CertificateArn"]
                for c in response["CertificateSummaryList"]
            ]
            assert arn in arns
        finally:
            acm_client.delete_certificate(CertificateArn=arn)

    def test_delete_certificate(self, acm_client):
        """Test DeleteCertificate removes certificate."""
        response = acm_client.request_certificate(DomainName="delete.example.com")
        arn = response["CertificateArn"]

        acm_client.delete_certificate(CertificateArn=arn)

        with pytest.raises(ClientError) as exc_info:
            acm_client.describe_certificate(CertificateArn=arn)
        assert "ResourceNotFoundException" in str(exc_info.value)


class TestACMImportExport:
    """Test ACM import and export operations."""

    @staticmethod
    def _generate_self_signed_cert():
        """Generate a self-signed certificate and private key.

        Returns:
            Tuple of (cert_pem_bytes, key_pem_bytes).
        """
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        subject = issuer = x509.Name(
            [x509.NameAttribute(NameOID.COMMON_NAME, "test.example.com")]
        )
        cert = (
            x509.CertificateBuilder()
            .subject_name(subject)
            .issuer_name(issuer)
            .public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(datetime.datetime.now(datetime.timezone.utc))
            .not_valid_after(
                datetime.datetime.now(datetime.timezone.utc)
                + datetime.timedelta(days=365)
            )
            .sign(key, hashes.SHA256())
        )
        cert_pem = cert.public_bytes(serialization.Encoding.PEM)
        key_pem = key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.TraditionalOpenSSL,
            serialization.NoEncryption(),
        )
        return cert_pem, key_pem

    def test_import_certificate(self, acm_client):
        """Test ImportCertificate with self-signed cert returns ARN."""
        cert_pem, key_pem = self._generate_self_signed_cert()

        response = acm_client.import_certificate(
            Certificate=cert_pem, PrivateKey=key_pem
        )
        arn = response["CertificateArn"]

        try:
            assert arn
            assert re.match(r"arn:aws:acm:.*:.*:certificate/.*", arn)
        finally:
            acm_client.delete_certificate(CertificateArn=arn)

    def test_import_certificate_with_chain(self, acm_client):
        """Test ImportCertificate with certificate chain returns ARN."""
        cert_pem, key_pem = self._generate_self_signed_cert()

        response = acm_client.import_certificate(
            Certificate=cert_pem,
            PrivateKey=key_pem,
            CertificateChain=cert_pem,
        )
        arn = response["CertificateArn"]

        try:
            assert arn
            assert re.match(r"arn:aws:acm:.*:.*:certificate/.*", arn)
        finally:
            acm_client.delete_certificate(CertificateArn=arn)

    def test_get_imported_certificate(self, acm_client):
        """Test GetCertificate on imported cert returns matching body."""
        cert_pem, key_pem = self._generate_self_signed_cert()

        import_response = acm_client.import_certificate(
            Certificate=cert_pem, PrivateKey=key_pem
        )
        arn = import_response["CertificateArn"]

        try:
            response = acm_client.get_certificate(CertificateArn=arn)
            assert response["Certificate"]
            assert "BEGIN CERTIFICATE" in response["Certificate"]
        finally:
            acm_client.delete_certificate(CertificateArn=arn)

    def test_export_certificate(self, acm_client):
        """Test ExportCertificate on imported cert returns cert and key."""
        cert_pem, key_pem = self._generate_self_signed_cert()

        import_response = acm_client.import_certificate(
            Certificate=cert_pem, PrivateKey=key_pem
        )
        arn = import_response["CertificateArn"]

        try:
            response = acm_client.export_certificate(
                CertificateArn=arn, Passphrase=b"test-passphrase"
            )
            assert response["Certificate"]
            assert response["PrivateKey"]
        finally:
            acm_client.delete_certificate(CertificateArn=arn)

    def test_export_requested_certificate_fails(self, acm_client):
        """Test ExportCertificate on a requested (non-imported) cert raises error."""
        response = acm_client.request_certificate(DomainName="export.example.com")
        arn = response["CertificateArn"]

        try:
            with pytest.raises(ClientError):
                acm_client.export_certificate(
                    CertificateArn=arn, Passphrase=b"test-passphrase"
                )
        finally:
            acm_client.delete_certificate(CertificateArn=arn)


class TestACMTagging:
    """Test ACM tagging operations."""

    def test_add_tags(self, acm_client):
        """Test AddTagsToCertificate and ListTagsForCertificate."""
        response = acm_client.request_certificate(DomainName="tags.example.com")
        arn = response["CertificateArn"]

        try:
            acm_client.add_tags_to_certificate(
                CertificateArn=arn,
                Tags=[
                    {"Key": "Env", "Value": "test"},
                    {"Key": "Project", "Value": "floci"},
                ],
            )

            response = acm_client.list_tags_for_certificate(CertificateArn=arn)
            tags = response["Tags"]
            tag_map = {t["Key"]: t["Value"] for t in tags}
            assert tag_map["Env"] == "test"
            assert tag_map["Project"] == "floci"
        finally:
            acm_client.delete_certificate(CertificateArn=arn)

    def test_remove_tags(self, acm_client):
        """Test RemoveTagsFromCertificate removes specified tags."""
        response = acm_client.request_certificate(DomainName="rmtags.example.com")
        arn = response["CertificateArn"]

        try:
            acm_client.add_tags_to_certificate(
                CertificateArn=arn,
                Tags=[
                    {"Key": "Env", "Value": "test"},
                    {"Key": "Project", "Value": "floci"},
                ],
            )

            acm_client.remove_tags_from_certificate(
                CertificateArn=arn,
                Tags=[{"Key": "Env", "Value": "test"}],
            )

            response = acm_client.list_tags_for_certificate(CertificateArn=arn)
            tags = response.get("Tags", [])
            tag_keys = [t["Key"] for t in tags]
            assert "Env" not in tag_keys
            assert "Project" in tag_keys
        finally:
            acm_client.delete_certificate(CertificateArn=arn)

    def test_list_tags_empty(self, acm_client):
        """Test ListTagsForCertificate on fresh cert returns empty tags."""
        response = acm_client.request_certificate(DomainName="notags.example.com")
        arn = response["CertificateArn"]

        try:
            response = acm_client.list_tags_for_certificate(CertificateArn=arn)
            tags = response.get("Tags", [])
            assert len(tags) == 0
        finally:
            acm_client.delete_certificate(CertificateArn=arn)


class TestACMAccountConfiguration:
    """Test ACM account configuration operations."""

    def test_put_and_get_account_configuration(self, acm_client, unique_name):
        """Test PutAccountConfiguration and GetAccountConfiguration."""
        acm_client.put_account_configuration(
            ExpiryEvents={"DaysBeforeExpiry": 45},
            IdempotencyToken=unique_name,
        )

        response = acm_client.get_account_configuration()
        assert response["ExpiryEvents"]["DaysBeforeExpiry"] == 45


class TestACMErrorHandling:
    """Test ACM error handling."""

    def test_describe_nonexistent_certificate(self, acm_client):
        """Test DescribeCertificate with fake ARN raises error."""
        with pytest.raises(ClientError):
            acm_client.describe_certificate(CertificateArn=FAKE_ARN)

    def test_delete_nonexistent_certificate(self, acm_client):
        """Test DeleteCertificate with fake ARN raises error."""
        with pytest.raises(ClientError):
            acm_client.delete_certificate(CertificateArn=FAKE_ARN)

    def test_request_certificate_with_sans(self, acm_client):
        """Test RequestCertificate with SubjectAlternativeNames."""
        response = acm_client.request_certificate(
            DomainName="sans.example.com",
            SubjectAlternativeNames=[
                "sans.example.com",
                "www.sans.example.com",
                "api.sans.example.com",
            ],
        )
        arn = response["CertificateArn"]

        try:
            response = acm_client.describe_certificate(CertificateArn=arn)
            sans = response["Certificate"]["SubjectAlternativeNames"]
            assert "sans.example.com" in sans
            assert "www.sans.example.com" in sans
            assert "api.sans.example.com" in sans
        finally:
            acm_client.delete_certificate(CertificateArn=arn)

    def test_import_invalid_pem(self, acm_client):
        """Test ImportCertificate with invalid PEM raises error."""
        with pytest.raises(ClientError):
            acm_client.import_certificate(
                Certificate=b"not-a-valid-certificate",
                PrivateKey=b"not-a-valid-key",
            )
