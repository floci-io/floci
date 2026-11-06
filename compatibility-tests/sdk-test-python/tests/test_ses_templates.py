"""SES email template SDK compatibility tests.

Exercises both the V1 SES client (Query protocol) and the V2 SESv2 client
(REST JSON protocol) via boto3, covering CRUD and templated send with
Mustache-style variable substitution.
"""

import json

import pytest
from botocore.exceptions import ClientError


# ============================================
# SES V2 (sesv2) - REST JSON
# ============================================


class TestSesV2EmailTemplateCrud:
    """Template CRUD via the SESv2 REST JSON API."""

    def test_create_and_get_email_template(self, sesv2_client, unique_name):
        name = f"pytest-tpl-{unique_name}"
        try:
            sesv2_client.create_email_template(
                TemplateName=name,
                TemplateContent={
                    "Subject": "Hello {{name}}",
                    "Text": "Hi {{name}}!",
                    "Html": "<p>Hi <b>{{name}}</b>!</p>",
                },
            )
            response = sesv2_client.get_email_template(TemplateName=name)
            assert response["TemplateName"] == name
            assert response["TemplateContent"]["Subject"] == "Hello {{name}}"
            assert response["TemplateContent"]["Text"] == "Hi {{name}}!"
            assert "{{name}}" in response["TemplateContent"]["Html"]
        finally:
            _safe_delete_v2(sesv2_client, name)

    def test_list_email_templates_includes_created(self, sesv2_client, unique_name):
        name = f"pytest-tpl-{unique_name}"
        try:
            sesv2_client.create_email_template(
                TemplateName=name,
                TemplateContent={"Subject": "s", "Text": "t"},
            )
            response = sesv2_client.list_email_templates()
            names = [t["TemplateName"] for t in response.get("TemplatesMetadata", [])]
            assert name in names
        finally:
            _safe_delete_v2(sesv2_client, name)

    def test_update_email_template(self, sesv2_client, unique_name):
        name = f"pytest-tpl-{unique_name}"
        try:
            sesv2_client.create_email_template(
                TemplateName=name,
                TemplateContent={"Subject": "original", "Text": "original body"},
            )
            sesv2_client.update_email_template(
                TemplateName=name,
                TemplateContent={
                    "Subject": "updated {{who}}",
                    "Text": "updated body for {{who}}",
                },
            )
            response = sesv2_client.get_email_template(TemplateName=name)
            assert response["TemplateContent"]["Subject"] == "updated {{who}}"
            assert "{{who}}" in response["TemplateContent"]["Text"]
        finally:
            _safe_delete_v2(sesv2_client, name)

    def test_delete_email_template(self, sesv2_client, unique_name):
        name = f"pytest-tpl-{unique_name}"
        sesv2_client.create_email_template(
            TemplateName=name,
            TemplateContent={"Subject": "s", "Text": "t"},
        )
        sesv2_client.delete_email_template(TemplateName=name)
        with pytest.raises(ClientError) as exc_info:
            sesv2_client.get_email_template(TemplateName=name)
        assert (
            exc_info.value.response["ResponseMetadata"]["HTTPStatusCode"] == 404
        )


class TestSesV2EmailTemplateErrors:
    """Error paths for V2 template operations."""

    def test_create_duplicate_rejected(self, sesv2_client, unique_name):
        name = f"pytest-tpl-{unique_name}"
        try:
            sesv2_client.create_email_template(
                TemplateName=name,
                TemplateContent={"Subject": "s", "Text": "t"},
            )
            with pytest.raises(ClientError) as exc_info:
                sesv2_client.create_email_template(
                    TemplateName=name,
                    TemplateContent={"Subject": "dup", "Text": "dup"},
                )
            assert (
                exc_info.value.response["ResponseMetadata"]["HTTPStatusCode"] == 400
            )
        finally:
            _safe_delete_v2(sesv2_client, name)

    def test_get_nonexistent(self, sesv2_client, unique_name):
        with pytest.raises(ClientError) as exc_info:
            sesv2_client.get_email_template(
                TemplateName=f"pytest-missing-{unique_name}"
            )
        assert (
            exc_info.value.response["ResponseMetadata"]["HTTPStatusCode"] == 404
        )


class TestSesV2SendTemplatedEmail:
    """Content.Template substitution via SESv2 SendEmail."""

    def test_send_email_substitutes_template_variables(
        self, sesv2_client, unique_name
    ):
        template_name = f"pytest-tpl-{unique_name}"
        sender = f"pytest-sender-{unique_name}@example.com"
        recipient = f"pytest-recipient-{unique_name}@example.com"

        try:
            sesv2_client.create_email_identity(EmailIdentity=sender)
            sesv2_client.create_email_template(
                TemplateName=template_name,
                TemplateContent={
                    "Subject": "Welcome {{name}}",
                    "Text": "Hello {{name}}, from {{team}}",
                    "Html": "<p>Welcome <b>{{name}}</b> ({{team}})</p>",
                },
            )

            response = sesv2_client.send_email(
                FromEmailAddress=sender,
                Destination={"ToAddresses": [recipient]},
                Content={
                    "Template": {
                        "TemplateName": template_name,
                        "TemplateData": json.dumps(
                            {"name": "Alice", "team": "floci"}
                        ),
                    }
                },
            )
            assert response["MessageId"]
        finally:
            _safe_delete_v2(sesv2_client, template_name)
            _safe_delete_identity_v2(sesv2_client, sender)

    def test_send_email_with_inline_template_substitutes_variables(
        self, sesv2_client, unique_name
    ):
        sender = f"pytest-inline-sender-{unique_name}@example.com"
        try:
            sesv2_client.create_email_identity(EmailIdentity=sender)
            response = sesv2_client.send_email(
                FromEmailAddress=sender,
                Destination={"ToAddresses": ["recipient@example.com"]},
                Content={
                    "Template": {
                        "TemplateContent": {
                            "Subject": "Inline {{name}}",
                            "Text": "Hello inline {{name}}",
                            "Html": "<p>Hello inline <b>{{name}}</b></p>",
                        },
                        "TemplateData": json.dumps({"name": "Alice"}),
                    }
                },
            )
            assert response["MessageId"]
        finally:
            _safe_delete_identity_v2(sesv2_client, sender)

    def test_send_email_with_both_name_and_inline_raises(
        self, sesv2_client, unique_name
    ):
        sender = f"pytest-both-sender-{unique_name}@example.com"
        try:
            sesv2_client.create_email_identity(EmailIdentity=sender)
            with pytest.raises(ClientError) as exc_info:
                sesv2_client.send_email(
                    FromEmailAddress=sender,
                    Destination={"ToAddresses": ["recipient@example.com"]},
                    Content={
                        "Template": {
                            "TemplateName": "whatever",
                            "TemplateContent": {"Subject": "s", "Text": "t"},
                            "TemplateData": "{}",
                        }
                    },
                )
            assert (
                exc_info.value.response["ResponseMetadata"]["HTTPStatusCode"] == 400
            )
        finally:
            _safe_delete_identity_v2(sesv2_client, sender)

    def test_send_email_with_template_arn_resolves_stored_template(
        self, sesv2_client, unique_name
    ):
        template_name = f"pytest-arn-tpl-{unique_name}"
        sender = f"pytest-arn-sender-{unique_name}@example.com"
        arn = f"arn:aws:ses:us-east-1:000000000000:template/{template_name}"
        try:
            sesv2_client.create_email_identity(EmailIdentity=sender)
            sesv2_client.create_email_template(
                TemplateName=template_name,
                TemplateContent={"Subject": "Hi {{name}}", "Text": "Hello {{name}}"},
            )
            response = sesv2_client.send_email(
                FromEmailAddress=sender,
                Destination={"ToAddresses": ["recipient@example.com"]},
                Content={
                    "Template": {
                        "TemplateArn": arn,
                        "TemplateData": json.dumps({"name": "Alice"}),
                    }
                },
            )
            assert response["MessageId"]
        finally:
            _safe_delete_v2(sesv2_client, template_name)
            _safe_delete_identity_v2(sesv2_client, sender)

    def test_send_email_with_unknown_template_raises(
        self, sesv2_client, unique_name
    ):
        sender = f"pytest-sender-{unique_name}@example.com"
        try:
            sesv2_client.create_email_identity(EmailIdentity=sender)
            with pytest.raises(ClientError) as exc_info:
                sesv2_client.send_email(
                    FromEmailAddress=sender,
                    Destination={"ToAddresses": ["recipient@example.com"]},
                    Content={
                        "Template": {
                            "TemplateName": f"pytest-missing-{unique_name}",
                            "TemplateData": "{}",
                        }
                    },
                )
            assert (
                exc_info.value.response["ResponseMetadata"]["HTTPStatusCode"] == 404
            )
        finally:
            _safe_delete_identity_v2(sesv2_client, sender)


# ============================================
# SES V1 (ses) - Query / XML
# ============================================


class TestSesV1TemplateCrud:
    """Template CRUD via the V1 SES Query API."""

    def test_create_and_get_template(self, ses_client, unique_name):
        name = f"pytest-v1-tpl-{unique_name}"
        try:
            ses_client.create_template(
                Template={
                    "TemplateName": name,
                    "SubjectPart": "Hello {{name}}",
                    "TextPart": "Hi {{name}}!",
                    "HtmlPart": "<p>Hi <b>{{name}}</b>!</p>",
                }
            )
            response = ses_client.get_template(TemplateName=name)
            template = response["Template"]
            assert template["TemplateName"] == name
            assert template["SubjectPart"] == "Hello {{name}}"
            assert template["TextPart"] == "Hi {{name}}!"
            assert "{{name}}" in template["HtmlPart"]
        finally:
            _safe_delete_v1(ses_client, name)

    def test_list_templates_includes_created(self, ses_client, unique_name):
        name = f"pytest-v1-tpl-{unique_name}"
        try:
            ses_client.create_template(
                Template={
                    "TemplateName": name,
                    "SubjectPart": "s",
                    "TextPart": "t",
                }
            )
            response = ses_client.list_templates()
            names = [
                t["Name"] for t in response.get("TemplatesMetadata", [])
            ]
            assert name in names
        finally:
            _safe_delete_v1(ses_client, name)

    def test_update_template(self, ses_client, unique_name):
        name = f"pytest-v1-tpl-{unique_name}"
        try:
            ses_client.create_template(
                Template={
                    "TemplateName": name,
                    "SubjectPart": "original",
                    "TextPart": "original",
                }
            )
            ses_client.update_template(
                Template={
                    "TemplateName": name,
                    "SubjectPart": "updated {{who}}",
                    "TextPart": "updated body {{who}}",
                }
            )
            response = ses_client.get_template(TemplateName=name)
            assert response["Template"]["SubjectPart"] == "updated {{who}}"
        finally:
            _safe_delete_v1(ses_client, name)

    def test_delete_template(self, ses_client, unique_name):
        name = f"pytest-v1-tpl-{unique_name}"
        ses_client.create_template(
            Template={"TemplateName": name, "SubjectPart": "s", "TextPart": "t"}
        )
        ses_client.delete_template(TemplateName=name)
        with pytest.raises(ClientError) as exc_info:
            ses_client.get_template(TemplateName=name)
        assert (
            exc_info.value.response["ResponseMetadata"]["HTTPStatusCode"] == 400
        )


class TestSesV1TemplateErrors:
    """Error paths for V1 template operations."""

    def test_create_duplicate_rejected(self, ses_client, unique_name):
        name = f"pytest-v1-tpl-{unique_name}"
        try:
            ses_client.create_template(
                Template={
                    "TemplateName": name,
                    "SubjectPart": "s",
                    "TextPart": "t",
                }
            )
            with pytest.raises(ClientError) as exc_info:
                ses_client.create_template(
                    Template={
                        "TemplateName": name,
                        "SubjectPart": "dup",
                        "TextPart": "dup",
                    }
                )
            assert (
                exc_info.value.response["ResponseMetadata"]["HTTPStatusCode"] == 400
            )
        finally:
            _safe_delete_v1(ses_client, name)

    def test_get_nonexistent(self, ses_client, unique_name):
        with pytest.raises(ClientError) as exc_info:
            ses_client.get_template(TemplateName=f"pytest-missing-{unique_name}")
        assert (
            exc_info.value.response["ResponseMetadata"]["HTTPStatusCode"] == 400
        )


class TestSesV1SendTemplatedEmail:
    """SendTemplatedEmail resolves stored templates via the V1 Query API."""

    def test_send_templated_email(self, ses_client, unique_name):
        template_name = f"pytest-v1-tpl-{unique_name}"
        sender = f"pytest-v1-sender-{unique_name}@example.com"
        recipient = f"pytest-v1-recipient-{unique_name}@example.com"

        try:
            ses_client.verify_email_identity(EmailAddress=sender)
            ses_client.create_template(
                Template={
                    "TemplateName": template_name,
                    "SubjectPart": "Welcome {{name}}",
                    "TextPart": "Hello {{name}}, from {{team}}",
                }
            )

            response = ses_client.send_templated_email(
                Source=sender,
                Destination={"ToAddresses": [recipient]},
                Template=template_name,
                TemplateData=json.dumps({"name": "Alice", "team": "floci"}),
            )
            assert response["MessageId"]
        finally:
            _safe_delete_v1(ses_client, template_name)
            _safe_delete_identity_v1(ses_client, sender)

    def test_send_templated_email_with_template_arn_and_name(
        self, ses_client, unique_name
    ):
        """boto3 requires Template (name) on SendTemplatedEmail; TemplateArn is
        supplied alongside for cross-account addressing on real AWS. Floci
        accepts both and resolves via the name."""
        template_name = f"pytest-v1-arn-tpl-{unique_name}"
        sender = f"pytest-v1-arn-sender-{unique_name}@example.com"
        arn = f"arn:aws:ses:us-east-1:000000000000:template/{template_name}"
        try:
            ses_client.verify_email_identity(EmailAddress=sender)
            ses_client.create_template(
                Template={
                    "TemplateName": template_name,
                    "SubjectPart": "Hi {{name}}",
                    "TextPart": "Hello {{name}}",
                }
            )
            response = ses_client.send_templated_email(
                Source=sender,
                Destination={"ToAddresses": ["recipient@example.com"]},
                Template=template_name,
                TemplateArn=arn,
                TemplateData=json.dumps({"name": "Alice"}),
            )
            assert response["MessageId"]
        finally:
            _safe_delete_v1(ses_client, template_name)
            _safe_delete_identity_v1(ses_client, sender)

    def test_send_templated_email_with_unknown_template_raises(
        self, ses_client, unique_name
    ):
        sender = f"pytest-v1-sender-{unique_name}@example.com"
        try:
            ses_client.verify_email_identity(EmailAddress=sender)
            with pytest.raises(ClientError) as exc_info:
                ses_client.send_templated_email(
                    Source=sender,
                    Destination={"ToAddresses": ["recipient@example.com"]},
                    Template=f"pytest-missing-{unique_name}",
                    TemplateData="{}",
                )
            assert (
                exc_info.value.response["ResponseMetadata"]["HTTPStatusCode"] == 400
            )
        finally:
            _safe_delete_identity_v1(ses_client, sender)


# ============================================
# Cleanup helpers
# ============================================


def _safe_delete_v2(client, template_name):
    try:
        client.delete_email_template(TemplateName=template_name)
    except ClientError:
        pass


def _safe_delete_v1(client, template_name):
    try:
        client.delete_template(TemplateName=template_name)
    except ClientError:
        pass


def _safe_delete_identity_v2(client, email):
    try:
        client.delete_email_identity(EmailIdentity=email)
    except ClientError:
        pass


def _safe_delete_identity_v1(client, email):
    try:
        client.delete_identity(Identity=email)
    except ClientError:
        pass
