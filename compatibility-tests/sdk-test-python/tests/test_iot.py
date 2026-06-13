from botocore.exceptions import ClientError
import json
import socket
import struct


def test_describe_endpoint(iot_client):
    response = iot_client.describe_endpoint(endpointType="iot:Data-ATS")

    assert response["endpointAddress"]


def test_thing_registry_crud(iot_client, unique_name):
    thing_name = f"{unique_name}-thing"

    missing = False
    try:
        iot_client.describe_thing(thingName=thing_name)
    except ClientError as exc:
        missing = exc.response["Error"]["Code"] == "ResourceNotFoundException"
    assert missing

    created = iot_client.create_thing(
        thingName=thing_name,
        attributePayload={"attributes": {"env": "python"}},
    )
    assert created["thingName"] == thing_name
    assert created["thingArn"].endswith(f":thing/{thing_name}")

    try:
        iot_client.create_thing(thingName=thing_name)
        raise AssertionError("duplicate create should fail")
    except ClientError as exc:
        assert exc.response["Error"]["Code"] == "ResourceAlreadyExistsException"

    described = iot_client.describe_thing(thingName=thing_name)
    assert described["attributes"]["env"] == "python"

    listed = iot_client.list_things()
    assert any(thing["thingName"] == thing_name for thing in listed["things"])

    iot_client.update_thing(
        thingName=thing_name,
        attributePayload={"attributes": {"env": "updated", "owner": "iot"}},
    )
    updated = iot_client.describe_thing(thingName=thing_name)
    assert updated["attributes"] == {"env": "updated", "owner": "iot"}

    iot_client.delete_thing(thingName=thing_name)
    try:
        iot_client.describe_thing(thingName=thing_name)
        raise AssertionError("describe after delete should fail")
    except ClientError as exc:
        assert exc.response["Error"]["Code"] == "ResourceNotFoundException"


def test_thing_tags(iot_client, unique_name):
    thing_name = f"{unique_name}-tagged-thing"

    created = iot_client.create_thing(thingName=thing_name)
    thing_arn = created["thingArn"]

    listed = iot_client.list_tags_for_resource(resourceArn=thing_arn)
    assert listed["tags"] == []

    iot_client.tag_resource(
        resourceArn=thing_arn,
        tags=[{"Key": "env", "Value": "python"}, {"Key": "owner", "Value": "iot"}],
    )
    tags = iot_client.list_tags_for_resource(resourceArn=thing_arn)["tags"]
    assert {tag["Key"]: tag["Value"] for tag in tags} == {"env": "python", "owner": "iot"}

    iot_client.untag_resource(resourceArn=thing_arn, tagKeys=["env"])
    tags = iot_client.list_tags_for_resource(resourceArn=thing_arn)["tags"]
    assert {tag["Key"]: tag["Value"] for tag in tags} == {"owner": "iot"}

    try:
        iot_client.list_tags_for_resource(
            resourceArn="arn:aws:iot:us-east-1:000000000000:thing/missing-tagged-thing"
        )
        raise AssertionError("listing tags on a missing thing should fail")
    except ClientError as exc:
        assert exc.response["Error"]["Code"] == "ResourceNotFoundException"


def test_certificates_policies_and_attachments(iot_client, unique_name):
    created_cert = iot_client.create_keys_and_certificate(setAsActive=True)
    cert_id = created_cert["certificateId"]
    cert_arn = created_cert["certificateArn"]
    assert "BEGIN CERTIFICATE" in created_cert["certificatePem"]
    assert "PublicKey" in created_cert["keyPair"]
    assert "PrivateKey" in created_cert["keyPair"]

    described = iot_client.describe_certificate(certificateId=cert_id)
    assert described["certificateDescription"]["status"] == "ACTIVE"

    listed = iot_client.list_certificates()
    assert any(cert["certificateArn"] == cert_arn for cert in listed["certificates"])

    iot_client.update_certificate(certificateId=cert_id, newStatus="INACTIVE")
    described = iot_client.describe_certificate(certificateId=cert_id)
    assert described["certificateDescription"]["status"] == "INACTIVE"

    policy_name = f"{unique_name}-policy"
    policy_document = json.dumps({"Version": "2012-10-17", "Statement": []})
    created_policy = iot_client.create_policy(policyName=policy_name, policyDocument=policy_document)
    assert created_policy["policyName"] == policy_name

    got_policy = iot_client.get_policy(policyName=policy_name)
    assert json.loads(got_policy["policyDocument"])["Version"] == "2012-10-17"

    listed_policies = iot_client.list_policies()
    assert any(policy["policyName"] == policy_name for policy in listed_policies["policies"])

    iot_client.attach_policy(policyName=policy_name, target=cert_arn)
    iot_client.detach_policy(policyName=policy_name, target=cert_arn)

    thing_name = f"{unique_name}-principal-thing"
    iot_client.create_thing(thingName=thing_name)
    iot_client.attach_thing_principal(thingName=thing_name, principal=cert_arn)
    principals = iot_client.list_thing_principals(thingName=thing_name)
    assert cert_arn in principals["principals"]
    iot_client.detach_thing_principal(thingName=thing_name, principal=cert_arn)


def test_iot_data_shadows_and_publish(iot_data_client, unique_name):
    thing_name = f"{unique_name}-shadow-thing"

    try:
        iot_data_client.get_thing_shadow(thingName=thing_name)
        raise AssertionError("missing shadow should fail")
    except ClientError as exc:
        assert exc.response["Error"]["Code"] == "ResourceNotFoundException"

    updated = iot_data_client.update_thing_shadow(
        thingName=thing_name,
        payload=json.dumps({"state": {"desired": {"color": "blue"}}}).encode(),
    )
    assert json.loads(updated["payload"].read())["version"] == 1

    iot_data_client.update_thing_shadow(
        thingName=thing_name,
        payload=json.dumps({"state": {"reported": {"color": "green"}}}).encode(),
    )
    got = json.loads(iot_data_client.get_thing_shadow(thingName=thing_name)["payload"].read())
    assert got["state"]["desired"]["color"] == "blue"
    assert got["state"]["reported"]["color"] == "green"

    iot_data_client.update_thing_shadow(
        thingName=thing_name,
        shadowName="settings",
        payload=json.dumps({"state": {"desired": {"mode": "auto"}}}).encode(),
    )
    named = iot_data_client.list_named_shadows_for_thing(thingName=thing_name)
    assert "settings" in named["results"]

    iot_data_client.publish(topic=f"devices/{thing_name}/events", payload=b"payload")
    iot_data_client.delete_thing_shadow(thingName=thing_name, shadowName="settings")
    iot_data_client.delete_thing_shadow(thingName=thing_name)


def test_topic_rule_crud_and_sqs_action(iot_client, iot_data_client, sqs_client, unique_name):
    rule_name = f"{unique_name}-rule"
    queue_name = f"{unique_name}-iot-rule-queue"
    queue_url = sqs_client.create_queue(QueueName=queue_name)["QueueUrl"]

    try:
        iot_client.create_topic_rule(
            ruleName=rule_name,
            topicRulePayload={
                "sql": f"SELECT * FROM 'devices/{unique_name}/rules'",
                "description": "python topic rule",
                "ruleDisabled": False,
                "actions": [
                    {
                        "sqs": {
                            "roleArn": "arn:aws:iam::000000000000:role/iot-rule-role",
                            "queueUrl": queue_url,
                            "useBase64": False,
                        }
                    }
                ],
            },
        )

        got = iot_client.get_topic_rule(ruleName=rule_name)
        assert got["rule"]["ruleName"] == rule_name
        assert got["rule"]["sql"] == f"SELECT * FROM 'devices/{unique_name}/rules'"
        assert got["rule"]["actions"][0]["sqs"]["queueUrl"] == queue_url

        iot_client.disable_topic_rule(ruleName=rule_name)
        listed = iot_client.list_topic_rules()
        assert any(rule["ruleName"] == rule_name and rule["ruleDisabled"] for rule in listed["rules"])

        iot_client.enable_topic_rule(ruleName=rule_name)
        iot_data_client.publish(topic=f"devices/{unique_name}/rules", payload=b"python-rule-payload")

        received = sqs_client.receive_message(QueueUrl=queue_url, MaxNumberOfMessages=1)
        assert received["Messages"][0]["Body"] == "python-rule-payload"
    finally:
        try:
            iot_client.delete_topic_rule(ruleName=rule_name)
        except ClientError:
            pass
        sqs_client.delete_queue(QueueUrl=queue_url)


def test_mqtt_connect_publish_subscribe(unique_name):
    topic = f"devices/{unique_name}/mqtt"
    payload = b"python-mqtt"

    with mqtt_connect(f"{unique_name}-sub") as subscriber:
        mqtt_subscribe(subscriber, topic)
        with mqtt_connect(f"{unique_name}-pub") as publisher:
            mqtt_publish(publisher, topic, payload)
        received_topic, received_payload = mqtt_read_publish(subscriber)

    assert received_topic == topic
    assert received_payload == payload


def test_mqtt5_connect(unique_name):
    with mqtt5_connect(f"{unique_name}-mqtt5") as client:
        assert client


def test_mqtt_shadow_reserved_topics(unique_name):
    thing_name = f"{unique_name}-shadow"
    with mqtt_connect(f"{unique_name}-shadow-sub") as subscriber:
        mqtt_subscribe(subscriber, f"$aws/things/{thing_name}/shadow/update/accepted")
        mqtt_subscribe(subscriber, f"$aws/things/{thing_name}/shadow/get/accepted")
        mqtt_subscribe(subscriber, f"$aws/things/{thing_name}/shadow/delete/accepted")

        with mqtt_connect(f"{unique_name}-shadow-pub") as publisher:
            mqtt_publish(
                publisher,
                f"$aws/things/{thing_name}/shadow/update",
                json.dumps({"state": {"desired": {"color": "blue"}}, "clientToken": "update-token"}).encode(),
            )
            topic, payload = mqtt_read_publish(subscriber)
            assert topic == f"$aws/things/{thing_name}/shadow/update/accepted"
            accepted = json.loads(payload)
            assert accepted["state"]["desired"]["color"] == "blue"
            assert accepted["clientToken"] == "update-token"

            mqtt_publish(publisher, f"$aws/things/{thing_name}/shadow/get", b'{"clientToken":"get-token"}')
            topic, payload = mqtt_read_publish(subscriber)
            assert topic == f"$aws/things/{thing_name}/shadow/get/accepted"
            got = json.loads(payload)
            assert got["state"]["desired"]["color"] == "blue"
            assert got["clientToken"] == "get-token"

            mqtt_publish(publisher, f"$aws/things/{thing_name}/shadow/delete", b'{"clientToken":"delete-token"}')
            topic, payload = mqtt_read_publish(subscriber)
            assert topic == f"$aws/things/{thing_name}/shadow/delete/accepted"
            deleted = json.loads(payload)
            assert deleted["state"]["desired"]["color"] == "blue"
            assert deleted["clientToken"] == "delete-token"


def mqtt_connect(client_id):
    client = socket.create_connection(("floci", 1883), timeout=5)
    client.settimeout(5)
    body = mqtt_utf8("MQTT") + bytes([4, 2, 0, 60]) + mqtt_utf8(client_id)
    client.sendall(bytes([0x10]) + mqtt_remaining_length(len(body)) + body)
    assert mqtt_read_packet(client) == b"\x20\x02\x00\x00"
    return client


def mqtt5_connect(client_id):
    client = socket.create_connection(("floci", 1883), timeout=5)
    client.settimeout(5)
    body = mqtt_utf8("MQTT") + bytes([5, 2, 0, 60, 0]) + mqtt_utf8(client_id)
    client.sendall(bytes([0x10]) + mqtt_remaining_length(len(body)) + body)
    mqtt_assert_v5_connack(mqtt_read_packet(client))
    return client


def mqtt_assert_v5_connack(packet):
    assert packet[0] == 0x20
    index = 1
    while packet[index] & 0x80:
        index += 1
    index += 1
    assert packet[index] == 0x00
    assert packet[index + 1] == 0x00


def mqtt_subscribe(client, topic):
    body = b"\x00\x01" + mqtt_utf8(topic) + b"\x00"
    client.sendall(bytes([0x82]) + mqtt_remaining_length(len(body)) + body)
    assert mqtt_read_packet(client) == b"\x90\x03\x00\x01\x00"


def mqtt_publish(client, topic, payload):
    body = mqtt_utf8(topic) + payload
    client.sendall(bytes([0x30]) + mqtt_remaining_length(len(body)) + body)


def mqtt_read_publish(client):
    packet = mqtt_read_packet(client)
    assert packet[0] & 0xF0 == 0x30
    index = 1
    while packet[index] & 0x80:
        index += 1
    index += 1
    topic_length = struct.unpack("!H", packet[index:index + 2])[0]
    index += 2
    topic = packet[index:index + topic_length].decode()
    index += topic_length
    return topic, packet[index:]


def mqtt_read_packet(client):
    first = client.recv(1)
    assert first
    remaining = 0
    multiplier = 1
    encoded_bytes = bytearray()
    while True:
        encoded = client.recv(1)
        assert encoded
        encoded_bytes.extend(encoded)
        remaining += (encoded[0] & 127) * multiplier
        multiplier *= 128
        if encoded[0] & 128 == 0:
            break
    body = client.recv(remaining)
    assert len(body) == remaining
    return first + bytes(encoded_bytes) + body


def mqtt_utf8(value):
    encoded = value.encode()
    return struct.pack("!H", len(encoded)) + encoded


def mqtt_remaining_length(length):
    encoded = bytearray()
    value = length
    while True:
        byte = value % 128
        value //= 128
        if value:
            byte |= 128
        encoded.append(byte)
        if not value:
            return bytes(encoded)
