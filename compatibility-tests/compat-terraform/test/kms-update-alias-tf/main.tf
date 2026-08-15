# Verifies https://github.com/floci-io/floci/issues/2291
#
# The Terraform AWS provider treats target_key_id on aws_kms_alias as an
# in-place update, not a replacement. Flipping which key the alias points at
# must therefore call UpdateAlias, not CreateAlias/DeleteAlias.

resource "aws_kms_key" "a" {
  description = "floci-kms-update-alias-test key A"
}

resource "aws_kms_key" "b" {
  description = "floci-kms-update-alias-test key B"
}

variable "use_second_key" {
  type    = bool
  default = false
}

resource "aws_kms_alias" "test" {
  name          = "alias/floci-kms-update-alias-test"
  target_key_id = var.use_second_key ? aws_kms_key.b.key_id : aws_kms_key.a.key_id
}

output "key_a_id" {
  value = aws_kms_key.a.key_id
}

output "key_b_id" {
  value = aws_kms_key.b.key_id
}
