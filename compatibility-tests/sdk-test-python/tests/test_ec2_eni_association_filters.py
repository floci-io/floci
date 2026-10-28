"""Primary ENI filters and VPC-dependent EIP release verified against live EC2."""

import uuid
from contextlib import ExitStack

import pytest
from botocore.exceptions import ClientError


def terminate_and_wait(ec2_client, instance_ids):
    ec2_client.terminate_instances(InstanceIds=instance_ids)
    ec2_client.get_waiter("instance_terminated").wait(
        InstanceIds=instance_ids, WaiterConfig={"Delay": 1, "MaxAttempts": 120})


@pytest.mark.parametrize("filter_name", ["default-for-az", "defaultForAz"])
def test_default_subnet_filter_excludes_custom_subnets(ec2_client, filter_name):
    with ExitStack() as cleanup:
        vpc = ec2_client.create_vpc(CidrBlock="10.237.0.0/24")["Vpc"]["VpcId"]
        cleanup.callback(ec2_client.delete_vpc, VpcId=vpc)
        custom = ec2_client.create_subnet(
            VpcId=vpc, CidrBlock="10.237.0.0/26")["Subnet"]["SubnetId"]
        cleanup.callback(ec2_client.delete_subnet, SubnetId=custom)

        def selected(values, **kwargs):
            return ec2_client.describe_subnets(
                Filters=[{"Name": filter_name, "Values": values}], **kwargs)["Subnets"]

        defaults = selected(["true"])
        assert defaults and all(subnet["DefaultForAz"] for subnet in defaults)
        assert custom not in {subnet["SubnetId"] for subnet in defaults}
        nondefaults = selected(["false"])
        assert custom in {subnet["SubnetId"] for subnet in nondefaults}
        assert all(not subnet["DefaultForAz"] for subnet in nondefaults)
        assert selected(["true"], SubnetIds=[custom]) == []
        assert selected(["false"], SubnetIds=[defaults[0]["SubnetId"]]) == []
        assert {subnet["SubnetId"] for subnet in selected(
            ["true", "false"], SubnetIds=[custom, defaults[0]["SubnetId"]]
        )} == {custom, defaults[0]["SubnetId"]}


@pytest.mark.parametrize("supplied_interface", [False, True])
@pytest.mark.parametrize("default_vpc", [False, True])
def test_network_interface_association_filters(ec2_client, supplied_interface, default_vpc):
    with ExitStack() as cleanup:
        if default_vpc:
            subnet = ec2_client.describe_subnets(
                Filters=[{"Name": "default-for-az", "Values": ["true"]}])["Subnets"][0]["SubnetId"]
        else:
            vpc = ec2_client.create_vpc(CidrBlock="10.237.0.0/24")["Vpc"]["VpcId"]
            cleanup.callback(ec2_client.delete_vpc, VpcId=vpc)
            subnet = ec2_client.create_subnet(
                VpcId=vpc, CidrBlock="10.237.0.0/26")["Subnet"]["SubnetId"]
            cleanup.callback(ec2_client.delete_subnet, SubnetId=subnet)
        image = ec2_client.register_image(
            Name="eni-filter-" + uuid.uuid4().hex, Architecture="x86_64",
            RootDeviceName="/dev/sda1")["ImageId"]
        cleanup.callback(ec2_client.deregister_image, ImageId=image)
        placement = {"SubnetId": subnet}
        if supplied_interface:
            eni_id = ec2_client.create_network_interface(SubnetId=subnet)["NetworkInterface"]["NetworkInterfaceId"]
            cleanup.callback(ec2_client.delete_network_interface, NetworkInterfaceId=eni_id)
            placement = {"NetworkInterfaces": [{"NetworkInterfaceId": eni_id, "DeviceIndex": 0}]}
        instances = ec2_client.run_instances(
            ImageId=image, InstanceType="t3.nano", MinCount=1, MaxCount=1,
            **placement)["Instances"]
        instances += ec2_client.run_instances(
            ImageId=image, InstanceType="t3.nano", MinCount=1, MaxCount=1,
            SubnetId=subnet)["Instances"]
        cleanup.callback(terminate_and_wait, ec2_client,
                         [instance["InstanceId"] for instance in instances])
        eni_ids = [instance["NetworkInterfaces"][0]["NetworkInterfaceId"]
                   for instance in instances]
        allocation = ec2_client.allocate_address(Domain="vpc")["AllocationId"]
        allocation_cleanup = cleanup.enter_context(ExitStack())
        allocation_cleanup.callback(ec2_client.release_address, AllocationId=allocation)
        association = ec2_client.associate_address(
            AllocationId=allocation, InstanceId=instances[0]["InstanceId"])["AssociationId"]
        association_cleanup = cleanup.enter_context(ExitStack())
        association_cleanup.callback(ec2_client.disassociate_address, AssociationId=association)
        # Floci resolves a reachable address on association; read the actual association.
        interface = ec2_client.describe_network_interfaces(
            NetworkInterfaceIds=[eni_ids[0]])["NetworkInterfaces"][0]
        address = next(ip["Association"] for ip in interface["PrivateIpAddresses"] if ip["Primary"])
        assert interface["Association"] == address
        public_ip, owner = address["PublicIp"], address["IpOwnerId"]

        def matched(**filters):
            result = ec2_client.describe_network_interfaces(
                NetworkInterfaceIds=eni_ids,
                Filters=[{"Name": key, "Values": value} for key, value in filters.items()])
            return [item["NetworkInterfaceId"] for item in result["NetworkInterfaces"]]

        assert matched(**{"association.public-ip": [public_ip]}) == [eni_ids[0]]
        assert matched(**{"association.public-ip": ["192.0.2.1"]}) == []
        assert matched(**{"association.ip-owner-id": [owner]}) == [eni_ids[0]]
        assert matched(**{"association.ip-owner-id": ["wrong-owner"]}) == []
        assert matched(**{"association.public-ip": ["192.0.2.1", public_ip]}) == [eni_ids[0]]
        assert matched(**{"association.public-ip": ["*"],
                          "association.ip-owner-id": ["wrong-owner"]}) == []
        if not default_vpc:
            with pytest.raises(ClientError) as error:
                ec2_client.release_address(AllocationId=allocation)
            assert error.value.response["Error"]["Code"] == "InvalidIPAddress.InUse"
            assert error.value.response["ResponseMetadata"]["HTTPStatusCode"] == 400
            assert ec2_client.describe_addresses(
                AllocationIds=[allocation])["Addresses"][0]["AssociationId"] == association
            assert matched(**{"association.public-ip": [public_ip],
                              "association.ip-owner-id": [owner]}) == [eni_ids[0]]
            association_cleanup.close()
        ec2_client.release_address(AllocationId=allocation)
        association_cleanup.pop_all()
        allocation_cleanup.pop_all()
        assert matched(**{"association.public-ip": [public_ip]}) == []
        assert matched(**{"association.ip-owner-id": [owner]}) == []
        assert "Association" not in ec2_client.describe_network_interfaces(
            NetworkInterfaceIds=[eni_ids[0]])["NetworkInterfaces"][0]


@pytest.mark.parametrize("supplied_interface", [False, True])
@pytest.mark.parametrize("default_vpc", [False, True])
def test_termination_preserves_only_retained_interface_associations(
    ec2_client, supplied_interface, default_vpc
):
    with ExitStack() as cleanup:
        if default_vpc:
            subnet = ec2_client.describe_subnets(
                Filters=[{"Name": "default-for-az", "Values": ["true"]}])["Subnets"][0]["SubnetId"]
        else:
            vpc = ec2_client.create_vpc(CidrBlock="10.237.0.0/24")["Vpc"]["VpcId"]
            cleanup.callback(ec2_client.delete_vpc, VpcId=vpc)
            subnet = ec2_client.create_subnet(VpcId=vpc, CidrBlock="10.237.0.0/26")["Subnet"]["SubnetId"]
            cleanup.callback(ec2_client.delete_subnet, SubnetId=subnet)
        image = ec2_client.register_image(
            Name="eni-termination-" + uuid.uuid4().hex, Architecture="x86_64",
            RootDeviceName="/dev/sda1")["ImageId"]
        cleanup.callback(ec2_client.deregister_image, ImageId=image)
        placement = {"SubnetId": subnet}
        interface_cleanup = cleanup.enter_context(ExitStack())
        if supplied_interface:
            eni = ec2_client.create_network_interface(SubnetId=subnet)["NetworkInterface"]["NetworkInterfaceId"]
            interface_cleanup.callback(ec2_client.delete_network_interface, NetworkInterfaceId=eni)
            placement = {"NetworkInterfaces": [{"NetworkInterfaceId": eni, "DeviceIndex": 0}]}
        instance = ec2_client.run_instances(
            ImageId=image, InstanceType="t3.nano", MinCount=1, MaxCount=1, **placement)["Instances"][0]
        termination_cleanup = cleanup.enter_context(ExitStack())
        termination_cleanup.callback(terminate_and_wait, ec2_client, [instance["InstanceId"]])
        allocation = ec2_client.allocate_address(Domain="vpc")["AllocationId"]
        allocation_cleanup = cleanup.enter_context(ExitStack())
        allocation_cleanup.callback(ec2_client.release_address, AllocationId=allocation)
        association = ec2_client.associate_address(
            AllocationId=allocation, InstanceId=instance["InstanceId"])["AssociationId"]
        association_cleanup = cleanup.enter_context(ExitStack())
        association_cleanup.callback(ec2_client.disassociate_address, AssociationId=association)
        termination_cleanup.close()
        address = ec2_client.describe_addresses(AllocationIds=[allocation])["Addresses"][0]
        assert not address.get("InstanceId")
        if supplied_interface:
            assert address["AssociationId"] == association
            interface = ec2_client.describe_network_interfaces(NetworkInterfaceIds=[eni])["NetworkInterfaces"][0]
            assert interface["Status"] == "available"
            assert not interface.get("Attachment")
            assert interface["Association"]["AssociationId"] == association
            if not default_vpc:
                with pytest.raises(ClientError) as error:
                    ec2_client.release_address(AllocationId=allocation)
                assert error.value.response["Error"]["Code"] == "InvalidIPAddress.InUse"
                interface_cleanup.close()
                address = ec2_client.describe_addresses(AllocationIds=[allocation])["Addresses"][0]
                assert not address.get("AssociationId")
                assert not address.get("NetworkInterfaceId")
        else:
            assert not address.get("AssociationId")
            assert not address.get("NetworkInterfaceId")
        ec2_client.release_address(AllocationId=allocation)
        association_cleanup.pop_all()
        allocation_cleanup.pop_all()
