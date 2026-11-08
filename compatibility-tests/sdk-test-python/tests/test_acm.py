"""ACM integration tests."""

import json
import re
import uuid
from contextlib import closing

import boto3
import pytest
from botocore.config import Config
from botocore.exceptions import ClientError
from cryptography import x509
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec, rsa

FAKE_ARN = "arn:aws:acm:us-east-1:000000000000:certificate/00000000-0000-0000-0000-000000000000"


class TestACMCertificateLifecycle:
    """Test ACM certificate lifecycle operations."""

    def test_request_certificate_rejects_empty_sans(self, aws_config, client_config):
        """Disable SDK validation so the empty list reaches the server."""
        with closing(boto3.client(
            "acm",
            config=client_config.merge(Config(parameter_validation=False)),
            **aws_config,
        )) as client:
            with pytest.raises(ClientError) as error:
                response = client.request_certificate(
                    DomainName="empty-sans.example.com", SubjectAlternativeNames=[]
                )
                client.delete_certificate(CertificateArn=response["CertificateArn"])
            assert error.value.response["ResponseMetadata"]["HTTPStatusCode"] == 400
            assert error.value.response["Error"]["Code"] == "ValidationException"

    @pytest.mark.parametrize("sans", [None, [], ["alt.san-probe.example.com"]])
    def test_cloudformation_certificate_sans(
        self, cloudformation_client, acm_client, sans
    ):
        stack = f"acm-sans-{uuid.uuid4().hex[:12]}"
        properties = {
            "DomainName": "san-probe.example.com",
            "ValidationMethod": "DNS",
        }
        if sans is not None:
            properties["SubjectAlternativeNames"] = sans
        template = {"Resources": {"Certificate": {
            "Type": "AWS::CertificateManager::Certificate",
            "Properties": properties,
        }}}
        cloudformation_client.create_stack(
            StackName=stack, TemplateBody=json.dumps(template)
        )
        try:
            cloudformation_client.get_waiter("stack_create_complete").wait(
                StackName=stack, WaiterConfig={"Delay": 1, "MaxAttempts": 60}
            )
            resource = cloudformation_client.describe_stack_resource(
                StackName=stack, LogicalResourceId="Certificate"
            )["StackResourceDetail"]
            certificate = acm_client.describe_certificate(
                CertificateArn=resource["PhysicalResourceId"]
            )["Certificate"]
            assert set(certificate["SubjectAlternativeNames"]) == {
                properties["DomainName"], *(sans or [])
            }
        finally:
            cloudformation_client.delete_stack(StackName=stack)

    def test_request_certificate(self, acm_client):
        """Test RequestCertificate creates a certificate and returns a valid ARN."""
        response = acm_client.request_certificate(DomainName="test.example.com")
        arn = response["CertificateArn"]

        try:
            assert arn
            assert re.match(r"arn:aws:acm:.*:.*:certificate/.*", arn)
        finally:
            acm_client.delete_certificate(CertificateArn=arn)

    @pytest.mark.parametrize(
        "key_algorithm,key_type",
        [("RSA_2048", rsa.RSAPublicKey), ("EC_prime256v1", ec.EllipticCurvePublicKey)],
    )
    def test_request_certificate_key_algorithms(
        self, acm_client, key_algorithm, key_type
    ):
        """Test RequestCertificate issues a parseable certificate for each key algorithm."""
        response = acm_client.request_certificate(
            DomainName=f"{key_algorithm.lower().replace('_', '-')}.example.com",
            KeyAlgorithm=key_algorithm,
        )
        arn = response["CertificateArn"]

        try:
            response = acm_client.get_certificate(CertificateArn=arn)
            certificate = x509.load_pem_x509_certificate(response["Certificate"].encode())
            assert isinstance(certificate.public_key(), key_type)
            # As on AWS, the certificate is signed by the issuing CA, returned first in
            # CertificateChain, not by its own key: check that signature, not just the PEM.
            issuer = x509.load_pem_x509_certificate(response["CertificateChain"].encode())
            assert certificate.issuer == issuer.subject
            certificate.verify_directly_issued_by(issuer)
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

    def test_import_certificate(self, acm_client, make_self_signed_certificate):
        """Test ImportCertificate with self-signed cert returns ARN."""
        cert_pem, key_pem = make_self_signed_certificate()

        response = acm_client.import_certificate(
            Certificate=cert_pem, PrivateKey=key_pem
        )
        arn = response["CertificateArn"]

        try:
            assert arn
            assert re.match(r"arn:aws:acm:.*:.*:certificate/.*", arn)
        finally:
            acm_client.delete_certificate(CertificateArn=arn)

    def test_import_certificate_with_chain(self, acm_client, make_self_signed_certificate):
        """Test ImportCertificate with certificate chain returns ARN."""
        cert_pem, key_pem = make_self_signed_certificate()

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

    def test_get_imported_certificate(self, acm_client, make_self_signed_certificate):
        """Test GetCertificate on imported cert returns matching body."""
        cert_pem, key_pem = make_self_signed_certificate()

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

    def test_export_certificate(self, acm_client, make_self_signed_certificate):
        """Test ExportCertificate returns the imported key, encrypted under the passphrase."""
        cert_pem, key_pem = make_self_signed_certificate()
        passphrase = b"test-passphrase"

        import_response = acm_client.import_certificate(
            Certificate=cert_pem, PrivateKey=key_pem
        )
        arn = import_response["CertificateArn"]

        try:
            response = acm_client.export_certificate(
                CertificateArn=arn, Passphrase=passphrase
            )
            assert response["Certificate"]
            assert response["PrivateKey"]

            # Asserting on the PEM header alone would pass for a key no client can
            # open. Decrypt it with the passphrase and check it is the key that was
            # imported, which is the whole point of the export.
            exported_key = serialization.load_pem_private_key(
                response["PrivateKey"].encode(), password=passphrase
            )
            imported_key = serialization.load_pem_private_key(key_pem, password=None)
            assert (
                exported_key.private_numbers() == imported_key.private_numbers()
            )

            # The passphrase has to actually be doing the work.
            with pytest.raises(ValueError):
                serialization.load_pem_private_key(
                    response["PrivateKey"].encode(), password=b"wrong-passphrase"
                )
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
