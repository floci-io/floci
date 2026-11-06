"""Subnet IPv6 CIDRs remain canonical across SDK create and describe calls."""

import ipaddress

import pytest


@pytest.mark.parametrize("host_bits", [0, 0xABCD])
def test_subnet_ipv6_cidr_is_canonical(ec2_client, host_bits):
    vpc = ec2_client.create_vpc(
        CidrBlock="10.94.0.0/16", AmazonProvidedIpv6CidrBlock=True)["Vpc"]
    subnet_id = None
    try:
        vpc_network = ipaddress.IPv6Network(
            vpc["Ipv6CidrBlockAssociationSet"][0]["Ipv6CidrBlock"])
        network = next(vpc_network.subnets(new_prefix=64))
        expanded = (network.network_address + host_bits).exploded.upper() + "/64"
        subnet = ec2_client.create_subnet(
            VpcId=vpc["VpcId"], CidrBlock="10.94.1.0/24",
            Ipv6CidrBlock=expanded)["Subnet"]
        subnet_id = subnet["SubnetId"]
        assert subnet["Ipv6CidrBlockAssociationSet"][0]["Ipv6CidrBlock"] == str(network)
        described = ec2_client.describe_subnets(SubnetIds=[subnet_id])["Subnets"]
        assert len(described) == 1
        assert described[0]["Ipv6CidrBlockAssociationSet"] == subnet["Ipv6CidrBlockAssociationSet"]
        assert described[0]["CidrBlock"] == "10.94.1.0/24"
    finally:
        if subnet_id is not None:
            ec2_client.delete_subnet(SubnetId=subnet_id)
        ec2_client.delete_vpc(VpcId=vpc["VpcId"])
