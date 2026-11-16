module "network" {
  source     = "../modules/network"
  name       = "pagerewind"
  admin_cidr = var.admin_cidr
}

output "vpc_id" { value = module.network.vpc_id }
output "subnet_id" { value = module.network.subnet_id }
output "security_group_id" { value = module.network.security_group_id }