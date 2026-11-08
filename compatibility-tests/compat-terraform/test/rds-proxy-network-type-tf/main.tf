# Verifies aws_db_proxy's endpoint_network_type/target_connection_network_type round-trip
# through CreateDBProxy/DescribeDBProxies instead of being rejected outright. AWS requires both
# the VPC and every selected subnet to carry an IPv6 CIDR block for IPV6/DUAL (see
# https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/rds-proxy-network-prereqs.html), so this
# VPC and its subnets are genuinely dual-stack; see rds_proxy_network_type.bats for the companion
# negative case against an IPv4-only VPC.

resource "aws_vpc" "proxy" {
  cidr_block                       = "10.80.0.0/16"
  assign_generated_ipv6_cidr_block = true

  tags = {
    Name = "floci-rds-proxy-network-type"
  }
}

resource "aws_subnet" "a" {
  vpc_id            = aws_vpc.proxy.id
  cidr_block        = "10.80.1.0/24"
  ipv6_cidr_block   = cidrsubnet(aws_vpc.proxy.ipv6_cidr_block, 8, 0)
  availability_zone = "us-east-1a"
}

resource "aws_subnet" "b" {
  vpc_id            = aws_vpc.proxy.id
  cidr_block        = "10.80.2.0/24"
  ipv6_cidr_block   = cidrsubnet(aws_vpc.proxy.ipv6_cidr_block, 8, 1)
  availability_zone = "us-east-1b"
}

resource "aws_security_group" "proxy" {
  name   = "floci-rds-proxy-network-type"
  vpc_id = aws_vpc.proxy.id
}

resource "aws_secretsmanager_secret" "db" {
  name = "floci-rds-proxy-network-type-secret"
}

resource "aws_secretsmanager_secret_version" "db" {
  secret_id     = aws_secretsmanager_secret.db.id
  secret_string = jsonencode({ username = "app", password = "changeme123!" })
}

resource "aws_iam_role" "proxy" {
  name = "floci-rds-proxy-network-type-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "rds.amazonaws.com" }
    }]
  })
}

# IPv4-only sibling VPC/subnets: the negative case in rds_proxy_network_type.bats asserts a
# direct CreateDBProxy against these with EndpointNetworkType=DUAL is rejected, since AWS
# requires the VPC to have an IPv6 CIDR block for that.
resource "aws_vpc" "ipv4_only" {
  cidr_block = "10.90.0.0/16"

  tags = {
    Name = "floci-rds-proxy-network-type-ipv4-only"
  }
}

resource "aws_subnet" "ipv4_only_a" {
  vpc_id            = aws_vpc.ipv4_only.id
  cidr_block        = "10.90.1.0/24"
  availability_zone = "us-east-1a"
}

resource "aws_subnet" "ipv4_only_b" {
  vpc_id            = aws_vpc.ipv4_only.id
  cidr_block        = "10.90.2.0/24"
  availability_zone = "us-east-1b"
}

# Dual-stack VPC whose subnets never got an IPv6 CIDR block: the negative case in
# rds_proxy_network_type.bats asserts a direct CreateDBProxy against these with
# EndpointNetworkType=DUAL is still rejected, since AWS requires every selected subnet -- not
# just the VPC -- to carry an IPv6 CIDR block.
resource "aws_vpc" "ipv6_subnets_missing" {
  cidr_block                       = "10.100.0.0/16"
  assign_generated_ipv6_cidr_block = true

  tags = {
    Name = "floci-rds-proxy-network-type-ipv6-subnets-missing"
  }
}

resource "aws_subnet" "ipv6_subnets_missing_a" {
  vpc_id            = aws_vpc.ipv6_subnets_missing.id
  cidr_block        = "10.100.1.0/24"
  availability_zone = "us-east-1a"
}

resource "aws_subnet" "ipv6_subnets_missing_b" {
  vpc_id            = aws_vpc.ipv6_subnets_missing.id
  cidr_block        = "10.100.2.0/24"
  availability_zone = "us-east-1b"
}

resource "aws_db_proxy" "test" {
  name                           = "floci-rds-proxy-network-type"
  engine_family                  = "MYSQL"
  role_arn                       = aws_iam_role.proxy.arn
  vpc_subnet_ids                 = [aws_subnet.a.id, aws_subnet.b.id]
  vpc_security_group_ids         = [aws_security_group.proxy.id]
  endpoint_network_type          = "DUAL"
  target_connection_network_type = "IPV6"

  auth {
    auth_scheme = "SECRETS"
    iam_auth    = "DISABLED"
    secret_arn  = aws_secretsmanager_secret.db.arn
  }
}

output "proxy_arn" {
  value = aws_db_proxy.test.arn
}

output "endpoint_network_type" {
  value = aws_db_proxy.test.endpoint_network_type
}

output "target_connection_network_type" {
  value = aws_db_proxy.test.target_connection_network_type
}

output "role_arn" {
  value = aws_iam_role.proxy.arn
}

output "secret_arn" {
  value = aws_secretsmanager_secret.db.arn
}

output "ipv4_only_subnet_ids" {
  value = [aws_subnet.ipv4_only_a.id, aws_subnet.ipv4_only_b.id]
}

output "ipv6_subnets_missing_subnet_ids" {
  value = [aws_subnet.ipv6_subnets_missing_a.id, aws_subnet.ipv6_subnets_missing_b.id]
}
