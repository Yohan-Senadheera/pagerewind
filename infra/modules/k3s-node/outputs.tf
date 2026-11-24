output "server_public_ip" {
  value = var.public_ip
}

output "agent_private_ip" {
  value = aws_instance.agent.private_ip
}

output "server_instance_id" {
  value = aws_instance.server.id
}

output "agent_instance_id" {
  value = aws_instance.agent.id
}

output "ssh_command" {
  value = "ssh -i ~/.ssh/pagerewind ubuntu@${var.public_ip}"
}
output "node_role_name" {
  value = aws_iam_role.node.name
}
