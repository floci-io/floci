# Verifies aws_db_snapshot tags round-trip through CreateDBSnapshot/TagResource/DescribeDBSnapshots
# instead of TagResource rejecting the snapshot ARN outright ("not yet implemented by Floci").

resource "aws_db_instance" "source" {
  identifier          = "floci-rds-snapshot-tags-source"
  engine              = "postgres"
  engine_version      = "16"
  instance_class      = "db.t3.micro"
  allocated_storage   = 20
  username            = "app"
  password            = "changeme123!"
  skip_final_snapshot = true
}

variable "owner_tag" {
  type    = string
  default = "platform"
}

resource "aws_db_snapshot" "test" {
  db_instance_identifier = aws_db_instance.source.identifier
  db_snapshot_identifier = "floci-rds-snapshot-tags-snap"

  tags = {
    owner = var.owner_tag
  }
}

output "db_snapshot_arn" {
  value = aws_db_snapshot.test.db_snapshot_arn
}

output "owner_tag" {
  value = aws_db_snapshot.test.tags["owner"]
}
