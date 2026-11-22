data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

# Latest Ubuntu 24.04 LTS image from Canonical
data "aws_ami" "ubuntu" {
  most_recent = true
  owners      = ["099720109477"]

  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-amd64-server-*"]
  }

  filter {
    name   = "architecture"
    values = ["x86_64"]
  }
}

# Shared join password for the cluster
resource "random_password" "k3s_token" {
  length  = 48
  special = false
}

resource "aws_key_pair" "admin" {
  key_name   = "${var.name}-admin"
  public_key = var.ssh_public_key
}

# IAM role the nodes use (no AWS keys stored on the servers)
data "aws_iam_policy_document" "assume_ec2" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "node" {
  name               = "${var.name}-node"
  assume_role_policy = data.aws_iam_policy_document.assume_ec2.json
}

data "aws_iam_policy_document" "read_secrets" {
  statement {
    actions = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
    resources = [
      "arn:aws:secretsmanager:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:secret:${var.name}/*"
    ]
  }
}

resource "aws_iam_role_policy" "read_secrets" {
  name   = "read-secrets"
  role   = aws_iam_role.node.id
  policy = data.aws_iam_policy_document.read_secrets.json
}

resource "aws_iam_instance_profile" "node" {
  name = "${var.name}-node"
  role = aws_iam_role.node.name
}

resource "aws_instance" "server" {
  ami                         = data.aws_ami.ubuntu.id
  instance_type               = var.instance_type
  subnet_id                   = var.subnet_id
  private_ip                  = var.server_private_ip
  vpc_security_group_ids      = [var.security_group_id]
  key_name                    = aws_key_pair.admin.key_name
  iam_instance_profile        = aws_iam_instance_profile.node.name
  user_data_replace_on_change = true

  user_data = templatefile("${path.module}/server.sh.tftpl", {
    token     = random_password.k3s_token.result
    public_ip = var.public_ip
  })

  root_block_device {
    volume_type = "gp3"
    volume_size = var.root_volume_gb
    encrypted   = true
  }

  metadata_options {
    http_tokens = "required"
    # 1 hop: only processes on the node itself (and pods with hostNetwork, such as External Secrets)
    # can get the node role's credentials. Ordinary pods are one hop further and are refused.
    # The Ubuntu image defaults to 2, which let every pod use the node role.
    http_put_response_hop_limit = 1
  }

  lifecycle {
    ignore_changes = [ami]
  }

  tags = { Name = "${var.name}-server" }
}

resource "aws_eip_association" "server" {
  instance_id   = aws_instance.server.id
  allocation_id = var.eip_allocation_id
}

resource "aws_instance" "agent" {
  ami                         = data.aws_ami.ubuntu.id
  instance_type               = var.instance_type
  subnet_id                   = var.subnet_id
  vpc_security_group_ids      = [var.security_group_id]
  key_name                    = aws_key_pair.admin.key_name
  iam_instance_profile        = aws_iam_instance_profile.node.name
  user_data_replace_on_change = true

  user_data = templatefile("${path.module}/agent.sh.tftpl", {
    token     = random_password.k3s_token.result
    server_ip = var.server_private_ip
  })

  root_block_device {
    volume_type = "gp3"
    volume_size = var.root_volume_gb
    encrypted   = true
  }

  metadata_options {
    http_tokens = "required"
    # 1 hop: only processes on the node itself (and pods with hostNetwork, such as External Secrets)
    # can get the node role's credentials. Ordinary pods are one hop further and are refused.
    # The Ubuntu image defaults to 2, which let every pod use the node role.
    http_put_response_hop_limit = 1
  }

  lifecycle {
    ignore_changes = [ami]
  }

  depends_on = [aws_instance.server]

  tags = { Name = "${var.name}-agent" }
}