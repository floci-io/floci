from botocore.exceptions import ClientError
import json
import socket
import struct


def test_describe_endpoint(iot_client):
    response = iot_client.describe_endpoint(endpointType="iot:Data-ATS")

    assert response["endpointAddress"]


def test_thing_registry_crud(iot_client, unique_name):
    thing_name = f"{unique_name}-thing"
    other_thing_name = f"{unique_name}-other-thing"

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

    idempotent = iot_client.create_thing(
        thingName=thing_name,
        attributePayload={"attributes": {"env": "python"}},
    )
    assert idempotent["thingName"] == thing_name

    try:
        iot_client.create_thing(thingName=thing_name)
        raise AssertionError("duplicate create should fail")
    except ClientError as exc:
        assert exc.response["Error"]["Code"] == "ResourceAlreadyExistsException"

    described = iot_client.describe_thing(thingName=thing_name)
    assert described["attributes"]["env"] == "python"

    iot_client.create_thing(thingName=other_thing_name)

    listed = iot_client.list_things()
    assert any(thing["thingName"] == thing_name for thing in listed["things"])

    first_page = iot_client.list_things(maxResults=1)
    assert len(first_page["things"]) == 1
    assert first_page.get("nextToken")
    second_page = iot_client.list_things(maxResults=1, nextToken=first_page["nextToken"])
    assert len(second_page["things"]) == 1

    iot_client.update_thing(
        thingName=thing_name,
        attributePayload={"attributes": {"env": "updated", "owner": "iot"}},
    )
    iot_client.update_thing(
        thingName=thing_name,
        expectedVersion=2,
        attributePayload={"attributes": {"env": "versioned", "owner": "iot"}},
    )
    try:
        iot_client.update_thing(
            thingName=thing_name,
            expectedVersion=2,
            attributePayload={"attributes": {"env": "stale"}},
        )
        raise AssertionError("stale expectedVersion should fail")
    except ClientError as exc:
        assert exc.response["Error"]["Code"] == "VersionConflictException"

    updated = iot_client.describe_thing(thingName=thing_name)
    assert updated["attributes"] == {"env": "versioned", "owner": "iot"}

    iot_client.delete_thing(thingName=thing_name)
    iot_client.delete_thing(thingName=other_thing_name)
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
    principal_things = iot_client.list_principal_things(principal=cert_arn)
    assert thing_name in principal_things["things"]
    iot_client.detach_thing_principal(thingName=thing_name, principal=cert_arn)


def test_certificate_delete_csr_policy_versions_and_attachment_reads(iot_client, unique_name):
    active_cert = iot_client.create_keys_and_certificate(setAsActive=True)
    try:
        iot_client.delete_certificate(certificateId=active_cert["certificateId"])
        raise AssertionError("active certificate delete should fail")
    except ClientError as exc:
        assert exc.response["Error"]["Code"] == "InvalidRequestException"

    try:
        iot_client.update_certificate(
            certificateId=active_cert["certificateId"], newStatus="PENDING_TRANSFER"
        )
        raise AssertionError("unsupported certificate status should fail")
    except ClientError as exc:
        assert exc.response["Error"]["Code"] == "InvalidRequestException"

    iot_client.update_certificate(certificateId=active_cert["certificateId"], newStatus="INACTIVE")
    iot_client.delete_certificate(certificateId=active_cert["certificateId"])
    try:
        iot_client.describe_certificate(certificateId=active_cert["certificateId"])
        raise AssertionError("deleted certificate should be missing")
    except ClientError as exc:
        assert exc.response["Error"]["Code"] == "ResourceNotFoundException"

    csr_cert = iot_client.create_certificate_from_csr(
        certificateSigningRequest="-----BEGIN CERTIFICATE REQUEST-----\nfloci\n-----END CERTIFICATE REQUEST-----",
        setAsActive=False,
    )
    assert "BEGIN CERTIFICATE" in csr_cert["certificatePem"]

    policy_name = f"{unique_name}-versioned-policy"
    policy_document = json.dumps({"Version": "2012-10-17", "Statement": []})
    iot_client.create_policy(policyName=policy_name, policyDocument=policy_document)
    try:
        iot_client.create_policy(policyName=policy_name, policyDocument=policy_document)
        raise AssertionError("duplicate policy create should fail")
    except ClientError as exc:
        assert exc.response["Error"]["Code"] == "ResourceAlreadyExistsException"

    version = iot_client.create_policy_version(
        policyName=policy_name,
        policyDocument=json.dumps(
            {"Version": "2012-10-17", "Statement": [{"Effect": "Allow"}]}
        ),
        setAsDefault=True,
    )
    version_id = version["policyVersionId"]
    listed_versions = iot_client.list_policy_versions(policyName=policy_name)
    assert any(item["versionId"] == version_id for item in listed_versions["policyVersions"])
    got_version = iot_client.get_policy_version(policyName=policy_name, policyVersionId=version_id)
    assert "Allow" in got_version["policyDocument"]

    iot_client.set_default_policy_version(policyName=policy_name, policyVersionId="1")
    iot_client.delete_policy_version(policyName=policy_name, policyVersionId=version_id)

    iot_client.attach_policy(policyName=policy_name, target=csr_cert["certificateArn"])
    attached = iot_client.list_attached_policies(target=csr_cert["certificateArn"])
    assert any(policy["policyName"] == policy_name for policy in attached["policies"])
    targets = iot_client.list_targets_for_policy(policyName=policy_name)
    assert csr_cert["certificateArn"] in targets["targets"]
    try:
        iot_client.delete_policy(policyName=policy_name)
        raise AssertionError("attached policy delete should fail")
    except ClientError as exc:
        assert exc.response["Error"]["Code"] == "InvalidRequestException"

    iot_client.detach_policy(policyName=policy_name, target=csr_cert["certificateArn"])
    iot_client.delete_policy(policyName=policy_name)
    try:
        iot_client.get_policy(policyName=policy_name)
        raise AssertionError("deleted policy should be missing")
    except ClientError as exc:
        assert exc.response["Error"]["Code"] == "ResourceNotFoundException"
    iot_client.delete_certificate(certificateId=csr_cert["certificateId"])


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


def test_iot_data_retained_messages_and_shadow_conflicts(iot_data_client, unique_name):
    topic = f"devices/{unique_name}/retained"
    iot_data_client.publish(topic=topic, payload=b"retained-payload", retain=True, qos=1)
    retained = iot_data_client.get_retained_message(topic=topic)
    assert retained["topic"] == topic
    assert retained["payload"] == b"retained-payload"
    assert retained["qos"] == 1

    other_topic = f"devices/{unique_name}/retained-other"
    iot_data_client.publish(topic=other_topic, payload=b"other", retain=True)
    first_page = iot_data_client.list_retained_messages(maxResults=1)
    assert len(first_page["retainedTopics"]) == 1
    assert first_page.get("nextToken")
    second_page = iot_data_client.list_retained_messages(maxResults=1, nextToken=first_page["nextToken"])
    assert len(second_page["retainedTopics"]) == 1

    iot_data_client.publish(topic=topic, payload=b"", retain=True)
    try:
        iot_data_client.get_retained_message(topic=topic)
        raise AssertionError("empty retained publish should delete retained message")
    except ClientError as exc:
        assert exc.response["Error"]["Code"] == "ResourceNotFoundException"

    thing_name = f"{unique_name}-shadow-version"
    iot_data_client.update_thing_shadow(
        thingName=thing_name,
        payload=json.dumps({"state": {"desired": {"color": "blue", "mode": "auto"}}}).encode(),
    )
    updated = iot_data_client.update_thing_shadow(
        thingName=thing_name,
        payload=json.dumps({"version": 1, "state": {"desired": {"color": None}}}).encode(),
    )
    document = json.loads(updated["payload"].read())
    assert "color" not in document["state"]["desired"]
    assert document["state"]["desired"]["mode"] == "auto"
    try:
        iot_data_client.update_thing_shadow(
            thingName=thing_name,
            payload=json.dumps({"version": 1, "state": {"desired": {"mode": "manual"}}}).encode(),
        )
        raise AssertionError("stale shadow update should fail")
    except ClientError as exc:
        assert exc.response["Error"]["Code"] == "VersionConflictException"


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

        try:
            iot_client.create_topic_rule(
                ruleName=rule_name,
                topicRulePayload={"sql": f"SELECT * FROM 'devices/{unique_name}/rules'", "actions": []},
            )
            raise AssertionError("duplicate topic rule create should fail")
        except ClientError as exc:
            assert exc.response["Error"]["Code"] == "ResourceAlreadyExistsException"

        iot_client.replace_topic_rule(
            ruleName=rule_name,
            topicRulePayload={"sql": f"SELECT * FROM 'devices/{unique_name}/rules'", "actions": got["rule"]["actions"]},
        )

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


def test_thing_types_groups_and_jobs(iot_client, iot_jobs_data_client, unique_name):
    thing_type = f"{unique_name}-type"
    thing_name = f"{unique_name}-typed-thing"
    group_name = f"{unique_name}-group"
    job_id = f"{unique_name}-job"

    endpoint = iot_client.describe_endpoint(endpointType="iot:Jobs")
    assert endpoint["endpointAddress"]

    created_type = iot_client.create_thing_type(
        thingTypeName=thing_type,
        thingTypeProperties={"thingTypeDescription": "python type", "searchableAttributes": ["model"]},
    )
    assert created_type["thingTypeName"] == thing_type
    described_type = iot_client.describe_thing_type(thingTypeName=thing_type)
    assert described_type["thingTypeProperties"]["thingTypeDescription"] == "python type"
    assert "model" in described_type["thingTypeProperties"]["searchableAttributes"]
    assert any(item["thingTypeName"] == thing_type for item in iot_client.list_thing_types()["thingTypes"])

    iot_client.update_thing_type(
        thingTypeName=thing_type,
        thingTypeProperties={"thingTypeDescription": "python type updated", "searchableAttributes": ["model", "fw"]},
    )
    thing = iot_client.create_thing(
        thingName=thing_name,
        thingTypeName=thing_type,
        attributePayload={"attributes": {"model": "p1"}},
    )
    thing_arn = thing["thingArn"]
    assert iot_client.describe_thing(thingName=thing_name)["thingTypeName"] == thing_type

    group = iot_client.create_thing_group(
        thingGroupName=group_name,
        thingGroupProperties={
            "thingGroupDescription": "python group",
            "attributePayload": {"attributes": {"fleet": "python"}},
        },
    )
    assert group["thingGroupName"] == group_name
    described_group = iot_client.describe_thing_group(thingGroupName=group_name)
    assert described_group["thingGroupProperties"]["attributePayload"]["attributes"]["fleet"] == "python"
    iot_client.add_thing_to_thing_group(thingGroupName=group_name, thingName=thing_name)
    assert thing_name in iot_client.list_things_in_thing_group(thingGroupName=group_name)["things"]
    assert any(item["groupName"] == group_name for item in iot_client.list_thing_groups_for_thing(thingName=thing_name)["thingGroups"])

    created_job = iot_client.create_job(
        jobId=job_id,
        targets=[thing_arn],
        document=json.dumps({"operation": "reboot"}),
        description="python job",
    )
    assert created_job["jobId"] == job_id
    assert iot_client.describe_job(jobId=job_id)["job"]["status"] == "IN_PROGRESS"
    assert any(job["jobId"] == job_id for job in iot_client.list_jobs()["jobs"])
    assert any(item["jobId"] == job_id for item in iot_client.list_job_executions_for_thing(thingName=thing_name)["executionSummaries"])

    pending = iot_jobs_data_client.get_pending_job_executions(thingName=thing_name)
    assert any(job["jobId"] == job_id for job in pending["queuedJobs"])
    started = iot_jobs_data_client.start_next_pending_job_execution(
        thingName=thing_name,
        statusDetails={"phase": "download"},
    )
    assert started["execution"]["status"] == "IN_PROGRESS"
    updated = iot_jobs_data_client.update_job_execution(
        thingName=thing_name,
        jobId=job_id,
        status="SUCCEEDED",
        expectedVersion=2,
        includeJobExecutionState=True,
        includeJobDocument=True,
    )
    assert updated["executionState"]["status"] == "SUCCEEDED"
    assert json.loads(updated["jobDocument"])["operation"] == "reboot"

    iot_client.remove_thing_from_thing_group(thingGroupName=group_name, thingName=thing_name)
    iot_client.delete_thing_group(thingGroupName=group_name)
    iot_client.delete_thing(thingName=thing_name)
    iot_client.deprecate_thing_type(thingTypeName=thing_type)
    iot_client.delete_thing_type(thingTypeName=thing_type)


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
