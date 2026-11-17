# Long-lived public IP for the k3s server. Kept in its own stack so that rebuilding the cluster
# (scripts/pagerewind.ps1 down, then up) keeps the same address and DNS records stay valid.
# An Elastic IP that is not attached to a running instance still costs a little per hour.
resource "aws_eip" "server" {
  domain = "vpc"
  tags   = { Name = "pagerewind-server" }
}

output "allocation_id" {
  value = aws_eip.server.id
}

output "public_ip" {
  value = aws_eip.server.public_ip
}
