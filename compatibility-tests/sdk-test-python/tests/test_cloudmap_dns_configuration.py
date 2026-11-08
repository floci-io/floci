"""Cloud Map DNS configuration and instance attributes through the AWS SDK."""

import time
import uuid

import boto3
import pytest
from botocore.exceptions import ClientError


def operation_target(client, operation_id):
    for _ in range(20):
        operation = client.get_operation(OperationId=operation_id)["Operation"]
        if operation["Status"] == "SUCCESS":
            return operation.get("Targets", {})
        assert operation["Status"] != "FAIL", operation
        time.sleep(0.1)
    pytest.fail("Cloud Map operation did not complete")


@pytest.mark.parametrize("record_type,attributes", [
    ("A", {"AWS_INSTANCE_IPV4": "192.0.2.1"}),
    ("AAAA", {"AWS_INSTANCE_IPV6": "2001:db8::1"}),
    ("CNAME", {"AWS_INSTANCE_CNAME": "backend.example.com"}),
    ("SRV", {"AWS_INSTANCE_PORT": "8080", "AWS_INSTANCE_IPV6": "2001:db8::2"}),
])
def test_dns_configuration_and_instance_attributes_round_trip(aws_config, record_type, attributes):
    client = boto3.client("servicediscovery", **aws_config)
    namespace_id = None
    service_id = None
    registered = False
    try:
        created = client.create_private_dns_namespace(Name=f"dns-{uuid.uuid4().hex}.internal", Vpc="vpc-dns")
        namespace_id = operation_target(client, created["OperationId"])["NAMESPACE"]
        configuration = {"RoutingPolicy": "WEIGHTED", "DnsRecords": [{"Type": record_type, "TTL": 45}]}
        service = client.create_service(Name="backend", NamespaceId=namespace_id,
                                        DnsConfig=configuration)["Service"]
        service_id = service["Id"]
        stored = client.get_service(Id=service_id)["Service"]["DnsConfig"]
        assert stored["RoutingPolicy"] == "WEIGHTED"
        assert stored["DnsRecords"] == configuration["DnsRecords"]
        result = client.register_instance(ServiceId=service_id, InstanceId="task-1", Attributes=attributes)
        registered = True
        operation_target(client, result["OperationId"])
        assert client.get_instance(ServiceId=service_id, InstanceId="task-1")["Instance"]["Attributes"] == attributes
    finally:
        if registered:
            result = client.deregister_instance(ServiceId=service_id, InstanceId="task-1")
            operation_target(client, result["OperationId"])
        if service_id:
            client.delete_service(Id=service_id)
        if namespace_id:
            client.delete_namespace(Id=namespace_id)
        client.close()


@pytest.mark.parametrize("identifier_length", [63, 64])
def test_srv_target_label_limit(aws_config, identifier_length):
    client = boto3.client("servicediscovery", **aws_config)
    namespace_id = None
    service_id = None
    registered = False
    instance_id = "a" * identifier_length
    try:
        created = client.create_private_dns_namespace(Name=f"srv-{uuid.uuid4().hex}.internal", Vpc="vpc-dns")
        namespace_id = operation_target(client, created["OperationId"])["NAMESPACE"]
        service_id = client.create_service(Name="backend", NamespaceId=namespace_id, DnsConfig={
            "RoutingPolicy": "MULTIVALUE", "DnsRecords": [{"Type": "SRV", "TTL": 45}],
        })["Service"]["Id"]
        arguments = {"ServiceId": service_id, "InstanceId": instance_id,
                     "Attributes": {"AWS_INSTANCE_PORT": "8080", "AWS_INSTANCE_IPV4": "192.0.2.1"}}
        if identifier_length == 64:
            with pytest.raises(ClientError) as error:
                client.register_instance(**arguments)
            assert error.value.response["Error"]["Code"] == "InvalidInput"
            assert client.list_instances(ServiceId=service_id)["Instances"] == []
        else:
            result = client.register_instance(**arguments)
            registered = True
            operation_target(client, result["OperationId"])
    finally:
        if registered:
            result = client.deregister_instance(ServiceId=service_id, InstanceId=instance_id)
            operation_target(client, result["OperationId"])
        if service_id:
            client.delete_service(Id=service_id)
        if namespace_id:
            client.delete_namespace(Id=namespace_id)
        client.close()
