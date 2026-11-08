"""Classic listener defaults survive public readback and identical retries."""

from contextlib import closing

import boto3
import pytest


@pytest.mark.parametrize(
    "protocol,default", [("HTTP", "HTTP"), ("HTTPS", "HTTP"), ("TCP", "TCP"), ("SSL", "TCP")]
)
def test_listener_instance_protocol_defaults(
    aws_config, client_config, ec2_client, iam_client, make_self_signed_certificate, unique_name,
    protocol, default,
):
    certificate = None
    created = False
    with closing(boto3.client("elb", config=client_config, **aws_config)) as elb:
        try:
            tls = {}
            if protocol in ("HTTPS", "SSL"):
                cert, key = make_self_signed_certificate(unique_name + ".example.test")
                certificate = iam_client.upload_server_certificate(
                    ServerCertificateName=unique_name, CertificateBody=cert.decode(),
                    PrivateKey=key.decode(),
                )["ServerCertificateMetadata"]
                tls["SSLCertificateId"] = certificate["Arn"]
            listener = {
                "Protocol": protocol, "LoadBalancerPort": 8443, "InstancePort": 9000, **tls
            }
            subnet = ec2_client.describe_subnets()["Subnets"][0]["SubnetId"]
            elb.create_load_balancer(
                LoadBalancerName=unique_name, Subnets=[subnet], Listeners=[listener]
            )
            created = True
            added = {**listener, "LoadBalancerPort": 8444, "InstancePort": 9001}
            explicit = {
                **listener, "LoadBalancerPort": 8445, "InstancePort": 9002,
                "InstanceProtocol": "HTTPS" if default == "HTTP" else "SSL",
            }
            elb.create_load_balancer_listeners(
                LoadBalancerName=unique_name, Listeners=[added, explicit]
            )
            elb.create_load_balancer_listeners(
                LoadBalancerName=unique_name, Listeners=[{**added, "InstanceProtocol": default}]
            )
            described = elb.describe_load_balancers(LoadBalancerNames=[unique_name])[
                "LoadBalancerDescriptions"
            ][0]["ListenerDescriptions"]
            actual = {row["Listener"]["LoadBalancerPort"]: row["Listener"] for row in described}
            assert actual == {
                8443: {**listener, "InstanceProtocol": default},
                8444: {**added, "InstanceProtocol": default},
                8445: explicit,
            }
        finally:
            try:
                if created:
                    elb.delete_load_balancer(LoadBalancerName=unique_name)
            finally:
                if certificate is not None:
                    iam_client.delete_server_certificate(ServerCertificateName=unique_name)
