module "network" {
  source     = "../modules/network"
  name       = "pagerewind"
  admin_cidr = var.admin_cidr
}

output "vpc_id" { value = module.network.vpc_id }
output "subnet_id" { value = module.network.subnet_id }
output "security_group_id" { value = module.network.security_group_id }

module "k3s" {
  source            = "../modules/k3s-node"
  name              = "pagerewind"
  subnet_id         = module.network.subnet_id
  security_group_id = module.network.security_group_id
  ssh_public_key    = file(pathexpand("~/.ssh/pagerewind.pub"))
}

output "server_public_ip" { value = module.k3s.server_public_ip }
output "ssh_command" { value = module.k3s.ssh_command }
output "server_instance_id" { value = module.k3s.server_instance_id }
output "agent_instance_id" { value = module.k3s.agent_instance_id }