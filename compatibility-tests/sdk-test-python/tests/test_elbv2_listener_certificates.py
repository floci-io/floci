"""ELBv2 listener certificate tests.

CreateListener, ModifyListener and AddListenerCertificates resolve every certificate they are
given against IAM and ACM, and answer CertificateNotFound when one of them does not exist.
Those three are the only operations the reference models that error on.
"""

import logging

import pytest
from botocore.exceptions import ClientError

logger = logging.getLogger(__name__)

MISSING_IAM_CERTIFICATE = "arn:aws:iam::000000000000:server-certificate/never-uploaded"
MISSING_ACM_CERTIFICATE = (
    "arn:aws:acm:us-east-1:000000000000:certificate/00000000-0000-0000-0000-000000000000"
)


class ListenerFixtures:
    """A load balancer to hang listeners on, with IAM server certificates on demand."""

    def __init__(self, elbv2_client, iam_client, ec2_client, make_certificate, name):
        self._elbv2_client = elbv2_client
        self._iam_client = iam_client
        self._make_certificate = make_certificate
        self._name = name
        self._certificate_names = []
        self._next_port = 443
        # A load balancer needs subnets in two availability zones and at most one in each, so
        # pick by zone rather than taking the first two the account happens to list.
        zones = {}
        for subnet in ec2_client.describe_subnets()["Subnets"]:
            zones.setdefault(subnet["AvailabilityZone"], subnet["SubnetId"])
        subnets = [zones[zone] for zone in sorted(zones)[:2]]
        response = elbv2_client.create_load_balancer(
            Name=name, Type="application", Subnets=subnets
        )
        self.load_balancer_arn = response["LoadBalancers"][0]["LoadBalancerArn"]

    def upload_certificate(self):
        """Upload a self-signed certificate to IAM and return its ARN."""
        certificate_name = f"{self._name}-{len(self._certificate_names)}"
        certificate_pem, key_pem = self._make_certificate(f"{certificate_name}.example.com")
        response = self._iam_client.upload_server_certificate(
            ServerCertificateName=certificate_name,
            CertificateBody=certificate_pem.decode(),
            PrivateKey=key_pem.decode(),
        )
        self._certificate_names.append(certificate_name)
        return response["ServerCertificateMetadata"]["Arn"]

    def create_listener(self, certificate_arn=None, protocol="HTTPS"):
        """CreateListener on the load balancer, each on a port of its own."""
        request = {
            "LoadBalancerArn": self.load_balancer_arn,
            "Protocol": protocol,
            "Port": self._next_port,
            "DefaultActions": [
                {"Type": "fixed-response", "FixedResponseConfig": {"StatusCode": "200"}}
            ],
        }
        self._next_port += 1
        if certificate_arn is not None:
            request["Certificates"] = [{"CertificateArn": certificate_arn}]
        return self._elbv2_client.create_listener(**request)["Listeners"][0]["ListenerArn"]

    def certificates_on(self, listener_arn):
        """The certificate ARNs the listener is serving."""
        response = self._elbv2_client.describe_listener_certificates(ListenerArn=listener_arn)
        return {certificate["CertificateArn"] for certificate in response["Certificates"]}

    def clean_up(self):
        """Take everything back out, load balancer first.

        Deleting it cascades to its listeners, which is what frees the certificates: one a
        listener still holds answers DeleteConflict, and the account's quota is 20 for the
        whole run.
        """
        try:
            self._elbv2_client.delete_load_balancer(LoadBalancerArn=self.load_balancer_arn)
        except ClientError as e:
            logger.warning("Failed to clean up load balancer %s: %s", self.load_balancer_arn, e)
        for certificate_name in self._certificate_names:
            try:
                self._iam_client.delete_server_certificate(
                    ServerCertificateName=certificate_name
                )
            except ClientError as e:
                logger.warning(
                    "Failed to clean up server certificate %s: %s", certificate_name, e
                )


@pytest.fixture
def listeners(
    elbv2_client, iam_client, ec2_client, make_self_signed_certificate, unique_name
):
    """Create and clean up one load balancer and the certificates a test puts on it."""
    fixtures = ListenerFixtures(
        elbv2_client, iam_client, ec2_client, make_self_signed_certificate, unique_name
    )

    yield fixtures

    fixtures.clean_up()


class TestELBv2ListenerCertificates:
    """Test that a certificate named on a listener has to exist."""

    def test_create_listener_rejects_missing_iam_certificate(self, listeners):
        """Test CreateListener with an unknown IAM certificate raises CertificateNotFound."""
        with pytest.raises(ClientError) as exc_info:
            listeners.create_listener(MISSING_IAM_CERTIFICATE)

        response = exc_info.value.response
        assert response["Error"]["Code"] == "CertificateNotFound"
        assert response["Error"]["Message"] == "The specified certificate does not exist."
        assert response["ResponseMetadata"]["HTTPStatusCode"] == 400

    def test_create_listener_rejects_missing_acm_certificate(self, listeners):
        """Test CreateListener with an unknown ACM certificate raises CertificateNotFound."""
        with pytest.raises(ClientError) as exc_info:
            listeners.create_listener(MISSING_ACM_CERTIFICATE)

        assert exc_info.value.response["Error"]["Code"] == "CertificateNotFound"

    def test_create_listener_accepts_uploaded_iam_certificate(self, listeners):
        """Test CreateListener accepts a certificate the account has uploaded to IAM."""
        certificate_arn = listeners.upload_certificate()

        listener_arn = listeners.create_listener(certificate_arn)

        assert listeners.certificates_on(listener_arn) == {certificate_arn}

    def test_create_listener_requires_an_https_default(self, listeners, elbv2_client):
        """A rejected HTTPS creation publishes nothing and can retry with a certificate."""
        with pytest.raises(ClientError) as exc_info:
            listeners.create_listener()
        assert exc_info.value.response["Error"]["Code"] == "InvalidConfigurationRequest"
        assert exc_info.value.response["ResponseMetadata"]["HTTPStatusCode"] == 400
        assert elbv2_client.describe_listeners(
            LoadBalancerArn=listeners.load_balancer_arn
        )["Listeners"] == []
        certificate_arn = listeners.upload_certificate()
        listener_arn = listeners.create_listener(certificate_arn)
        assert listeners.certificates_on(listener_arn) == {certificate_arn}

    def test_http_listener_is_left_alone(self, listeners):
        """Test the check covers the protocols the Certificates member is bracketed for.

        The reference brackets it for HTTPS and TLS listeners, so a certificate on an HTTP
        listener is a configuration it does not describe. This pins the scope of the check
        rather than endorsing the request.
        """
        assert listeners.create_listener(MISSING_IAM_CERTIFICATE, protocol="HTTP")

    def test_modify_listener_rejects_missing_certificate(self, listeners, elbv2_client):
        """Test ModifyListener refuses an unknown certificate and keeps the one it had."""
        certificate_arn = listeners.upload_certificate()
        listener_arn = listeners.create_listener(certificate_arn)

        with pytest.raises(ClientError) as exc_info:
            elbv2_client.modify_listener(
                ListenerArn=listener_arn,
                Certificates=[{"CertificateArn": MISSING_IAM_CERTIFICATE}],
            )

        assert exc_info.value.response["Error"]["Code"] == "CertificateNotFound"
        # The refusal has to land before the write, or the listener is left serving a
        # certificate the account does not have.
        assert listeners.certificates_on(listener_arn) == {certificate_arn}

    def test_add_listener_certificates_rejects_missing_certificate(
        self, listeners, elbv2_client
    ):
        """Test AddListenerCertificates refuses an unknown certificate and adds nothing."""
        certificate_arn = listeners.upload_certificate()
        listener_arn = listeners.create_listener(certificate_arn)

        with pytest.raises(ClientError) as exc_info:
            elbv2_client.add_listener_certificates(
                ListenerArn=listener_arn,
                Certificates=[{"CertificateArn": MISSING_IAM_CERTIFICATE}],
            )

        assert exc_info.value.response["Error"]["Code"] == "CertificateNotFound"
        assert listeners.certificates_on(listener_arn) == {certificate_arn}

    def test_add_listener_certificates_accepts_second_certificate(
        self, listeners, elbv2_client
    ):
        """Test AddListenerCertificates puts a second uploaded certificate on the listener."""
        first = listeners.upload_certificate()
        second = listeners.upload_certificate()
        listener_arn = listeners.create_listener(first)

        elbv2_client.add_listener_certificates(
            ListenerArn=listener_arn, Certificates=[{"CertificateArn": second}]
        )

        assert listeners.certificates_on(listener_arn) == {first, second}
