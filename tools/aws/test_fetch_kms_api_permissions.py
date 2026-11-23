#!/usr/bin/env python3
"""Tests for fetch_kms_api_permissions.py, on pages in the AWS KMS developer guide's and API reference's markup."""
from __future__ import annotations

import json

import pytest

import fetch_kms_api_permissions as f

API = "https://docs.aws.amazon.com/kms/latest/APIReference/"
HEAD = ('<tr><th>Actions and permissions</th><th>Policy type</th><th>Cross-account use</th>'
        '<th>Resources (for IAM policies)</th><th>AWS KMS condition keys</th></tr>')
DECRYPT = ('<tr><td tabindex="-1"><p><a href="' + API + 'API_Decrypt.html">Decrypt</a></p>'
           '<p><code class="code">kms:Decrypt</code></p></td><td tabindex="-1"><p>Key policy</p></td>'
           '<td tabindex="-1"><p>Yes</p></td><td tabindex="-1"><p>KMS key</p></td><td tabindex="-1">'
           '<p>kms:EncryptionAlgorithm</p></td></tr>')
RE_ENCRYPT = ('<tr><td tabindex="-1"><p><a href="' + API + 'API_ReEncrypt.html">ReEncrypt</a></p>'
              '<p><code class="code">kms:ReEncryptFrom</code></p><p><code class="code">kms:ReEncryptTo</code></p>'
              '<p>To use this operation, the caller needs permission on two KMS keys:</p><div class="itemizedlist">'
              '<ul class="itemizedlist"><li class="listitem"><p><code class="code">kms:ReEncryptFrom</code> on the KMS '
              'key used to decrypt</p></li><li class="listitem"><p><code class="code">kms:ReEncryptTo</code> on the '
              'KMS key used to encrypt</p></li></ul></div></td><td tabindex="-1"><p>Key policy</p></td>'
              '<td tabindex="-1"><p>Yes</p></td><td tabindex="-1"><p>KMS key</p></td><td tabindex="-1"><p>None</p>'
              '</td></tr>')
# An operation whose first cell spans two rows, the second giving the key policy's type and resources.
CREATE_ALIAS = ('<tr><td rowspan="2" tabindex="-1"><a href="' + API + 'API_CreateAlias.html">CreateAlias</a>'
                '<p><code class="code">kms:CreateAlias</code></p><p>To use this operation, the caller needs '
                '<code class="code">kms:CreateAlias</code> permission on two resources:</p><div class="itemizedlist">'
                '<ul class="itemizedlist"><li class="listitem"><p>The alias (in an IAM policy)</p></li>'
                '<li class="listitem"><p>The KMS key (in a key policy)</p></li></ul></div></td>'
                '<td tabindex="-1"><p>IAM policy (for the alias)</p></td><td tabindex="-1"><p>No</p></td>'
                '<td tabindex="-1"><p>Alias</p></td><td tabindex="-1"><p>None</p></td></tr>'
                '<tr><td tabindex="-1"><p>Key policy (for the KMS key)</p></td><td tabindex="-1"><p>No</p></td>'
                '<td tabindex="-1"><p>KMS key</p></td><td tabindex="-1"><p>kms:CallerAccount</p></td></tr>')
KEY_ID = ('<dt id="KMS-Decrypt-request-KeyId"><span class="term"><b><a href="#">KeyId</a></b></span></dt><dd>'
          '<p>Specifies the KMS key that AWS KMS uses to decrypt the ciphertext.</p><p>This parameter is required only '
          'when the ciphertext was encrypted under an asymmetric KMS key. If you used a symmetric encryption KMS key, '
          'AWS KMS can get the KMS key from metadata that it adds to the symmetric ciphertext blob. However, it is '
          'always recommended as a best practice.</p></dd>')
BLOB = ('<dt id="KMS-Decrypt-request-CiphertextBlob"><span class="term"><b>CiphertextBlob</b></span></dt><dd>'
        '<p>Ciphertext to be decrypted. The blob includes metadata.</p></dd>')
CONCEPTS = ('<dl><dt id="key-id-alias-ARN"><b><span class="term">Alias ARN</span></b></dt><dd><p>The alias ARN is the '
            'Amazon Resource Name (ARN) of an AWS KMS alias.</p><p>The format of an alias ARN is as follows:</p>'
            '<pre class="programlisting"><div class="code-btn-container"><div class="btn-copy-code" title="Copy">'
            '</div></div><code class="nohighlight">arn:<code class="replaceable">&lt;partition&gt;</code>:kms:'
            '<code class="replaceable">&lt;region&gt;</code>:<code class="replaceable">&lt;account-id&gt;</code>:alias/'
            '<code class="replaceable">&lt;alias-name&gt;</code></code></pre></dd>'
            '<dt id="key-id-key-id"><b><span class="term">Key ID</span></b></dt><dd><p>A key ID uniquely identifies '
            'a KMS key.</p></dd>'
            '<dt id="key-id-alias-name"><b><span class="term">Alias name</span></b></dt><dd><p>In the AWS KMS API, '
            'alias names always begin with <code class="code">alias/</code>.</p><p>The format of an alias name is as '
            'follows:</p><pre class="programlisting"><div class="code-btn-container"><div class="btn-copy-code" '
            'title="Copy"></div></div><code class="nohighlight">alias/<code class="replaceable">&lt;alias-name&gt;'
            '</code></code></pre></dd></dl>')


def permissions_page(*rows: str, times: int = 1) -> str:
    return "<html><body><table>" + HEAD + "".join(rows) * times + "</table></body></html>"


def test_each_operation_of_the_table_is_copied_with_its_actions_list_and_policies():
    rows = f.read_permissions(permissions_page(DECRYPT, RE_ENCRYPT, CREATE_ALIAS))
    assert rows == [
        {"operation": "Decrypt", "apiReference": API + "API_Decrypt.html", "actions": ["kms:Decrypt"],
         "description": [], "items": [], "policies": [{"policyType": "Key policy", "resources": "KMS key"}]},
        {"operation": "ReEncrypt", "apiReference": API + "API_ReEncrypt.html",
         "actions": ["kms:ReEncryptFrom", "kms:ReEncryptTo"],
         "description": ["To use this operation, the caller needs permission on two KMS keys:"],
         "items": ["kms:ReEncryptFrom on the KMS key used to decrypt",
                   "kms:ReEncryptTo on the KMS key used to encrypt"],
         "policies": [{"policyType": "Key policy", "resources": "KMS key"}]},
        {"operation": "CreateAlias", "apiReference": API + "API_CreateAlias.html", "actions": ["kms:CreateAlias"],
         "description": ["To use this operation, the caller needs kms:CreateAlias permission on two resources:"],
         "items": ["The alias (in an IAM policy)", "The KMS key (in a key policy)"],
         "policies": [{"policyType": "IAM policy (for the alias)", "resources": "Alias"},
                      {"policyType": "Key policy (for the KMS key)", "resources": "KMS key"}]},
    ]


def test_a_page_whose_table_changed_its_layout_is_refused():
    with pytest.raises(ValueError, match="tables"):
        f.read_permissions("<html><body></body></html>")
    with pytest.raises(ValueError, match="headed"):
        f.read_permissions("<html><body><table><tr><th>Operation</th></tr>" + DECRYPT + "</table></body></html>")
    with pytest.raises(ValueError, match="naming no operation"):
        f.read_permissions(permissions_page(DECRYPT.replace('<a href="' + API + 'API_Decrypt.html">Decrypt</a>',
                                                            "Decrypt")))


def test_a_parameter_saying_kms_reads_its_resource_from_metadata_is_copied_with_that_sentence():
    assert f.read_from_metadata("<dl>" + BLOB + KEY_ID + "</dl>") == [
        {"operation": "Decrypt", "parameter": "KeyId",
         "sentence": "If you used a symmetric encryption KMS key, AWS KMS can get the KMS key from metadata that it "
                     "adds to the symmetric ciphertext blob."}]


def test_each_key_identifier_giving_a_format_is_copied_with_its_variables_marked():
    assert f.read_identifiers(CONCEPTS) == [
        {"identifier": "Alias ARN", "sentence": "The format of an alias ARN is as follows:",
         "format": "arn:{partition}:kms:{region}:{account-id}:alias/{alias-name}"},
        {"identifier": "Alias name", "sentence": "The format of an alias name is as follows:",
         "format": "alias/{alias-name}"}]
    with pytest.raises(ValueError, match="alias name and alias ARN"):
        f.read_identifiers("<dl></dl>")


def test_the_file_is_stale_when_a_page_says_something_else(monkeypatch):
    monkeypatch.setattr(f, "MINIMUM_ROWS", 1)
    pages = (permissions_page(DECRYPT), {API + "API_Decrypt.html": "<dl>" + KEY_ID + "</dl>"}, CONCEPTS)
    vendored = f.build(*pages)
    assert vendored["fromMetadata"][0]["parameter"] == "KeyId"
    assert not f.stale(json.loads(json.dumps(vendored)), *pages)
    assert f.stale(vendored, pages[0], {API + "API_Decrypt.html": "<dl>" + BLOB + "</dl>"}, CONCEPTS)
    with pytest.raises(ValueError, match="rows"):
        monkeypatch.setattr(f, "MINIMUM_ROWS", 40)
        f.build(*pages)
