data "aws_availability_zones" "available" {
  state = "available"
}

resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = { Name = "${var.name}-vpc" }
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id
  tags   = { Name = "${var.name}-igw" }
}

resource "aws_subnet" "public" {
  vpc_id                  = aws_vpc.this.id
  cidr_block              = var.public_subnet_cidr
  availability_zone       = data.aws_availability_zones.available.names[0]
  map_public_ip_on_launch = true
  tags                    = { Name = "${var.name}-public" }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.this.id
  }

  tags = { Name = "${var.name}-public" }
}

resource "aws_route_table_association" "public" {
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public.id
}

# Firewall for the nodes
resource "aws_security_group" "nodes" {
  name        = "${var.name}-nodes"
  description = "PageRewind k3s nodes"
  vpc_id      = aws_vpc.this.id
}

resource "aws_vpc_security_group_ingress_rule" "ssh" {
  security_group_id = aws_security_group.nodes.id
  description       = "SSH from admin"
  cidr_ipv4         = var.admin_cidr
  ip_protocol       = "tcp"
  from_port         = 22
  to_port           = 22
}

resource "aws_vpc_security_group_ingress_rule" "kube_api" {
  security_group_id = aws_security_group.nodes.id
  description       = "Kubernetes API from admin"
  cidr_ipv4         = var.admin_cidr
  ip_protocol       = "tcp"
  from_port         = 6443
  to_port           = 6443
}

resource "aws_vpc_security_group_ingress_rule" "http" {
  security_group_id = aws_security_group.nodes.id
  description       = "HTTP (Lets Encrypt checks and redirect to HTTPS)"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
}

resource "aws_vpc_security_group_ingress_rule" "https" {
  security_group_id = aws_security_group.nodes.id
  description       = "HTTPS"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

resource "aws_vpc_security_group_egress_rule" "all" {
  security_group_id = aws_security_group.nodes.id
  description       = "All outbound"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

# Node-to-node traffic for k3s
resource "aws_vpc_security_group_ingress_rule" "k3s_api_internal" {
  security_group_id            = aws_security_group.nodes.id
  description                  = "k3s API between nodes"
  referenced_security_group_id = aws_security_group.nodes.id
  ip_protocol                  = "tcp"
  from_port                    = 6443
  to_port                      = 6443
}

resource "aws_vpc_security_group_ingress_rule" "kubelet_internal" {
  security_group_id            = aws_security_group.nodes.id
  description                  = "kubelet between nodes"
  referenced_security_group_id = aws_security_group.nodes.id
  ip_protocol                  = "tcp"
  from_port                    = 10250
  to_port                      = 10250
}

resource "aws_vpc_security_group_ingress_rule" "flannel_internal" {
  security_group_id            = aws_security_group.nodes.id
  description                  = "flannel VXLAN pod network between nodes"
  referenced_security_group_id = aws_security_group.nodes.id
  ip_protocol                  = "udp"
  from_port                    = 8472
  to_port                      = 8472
}