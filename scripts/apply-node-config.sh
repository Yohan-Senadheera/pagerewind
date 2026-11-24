#!/usr/bin/env bash
# Writes /etc/rancher/k3s/config.yaml on the running nodes (the same content the install scripts in
# infra/modules/k3s-node write on a fresh node) and restarts k3s only where the file changed.
# Running containers keep running during a k3s restart.
#   scripts/apply-node-config.sh <server public IP> <agent private IP>
set -euo pipefail

server="${1:?usage: apply-node-config.sh <server public IP> <agent private IP>}"
agent="${2:?usage: apply-node-config.sh <server public IP> <agent private IP>}"
key="${SSH_KEY:-$HOME/.ssh/pagerewind}"
ssh_opts=(-i "$key" -o ConnectTimeout=15 -o BatchMode=yes -o StrictHostKeyChecking=accept-new)

server_config='kubelet-arg:
  - "kube-reserved=memory=2Gi"
  - "system-reserved=memory=512Mi"
  - "eviction-hard=memory.available<300Mi"'
agent_config='kubelet-arg:
  - "system-reserved=memory=768Mi"
  - "eviction-hard=memory.available<300Mi"'

apply() { # $1 = service name, $2 = config, rest = ssh target arguments
  local service=$1 config=$2; shift 2
  # shellcheck disable=SC2029 # expanded on purpose: the config text is sent to the node
  ssh "${ssh_opts[@]}" "$@" "
    sudo mkdir -p /etc/rancher/k3s
    if [ \"\$(sudo cat /etc/rancher/k3s/config.yaml 2>/dev/null)\" = '$config' ]; then
      echo \"$service: already up to date\"
    else
      echo '$config' | sudo tee /etc/rancher/k3s/config.yaml > /dev/null
      sudo systemctl restart $service
      echo \"$service: config written, restarted\"
    fi"
}

apply k3s "$server_config" "ubuntu@$server"
# The agent has no public address; reach it through the server.
apply k3s-agent "$agent_config" -o "ProxyCommand=ssh ${ssh_opts[*]} -W %h:%p ubuntu@$server" "ubuntu@$agent"
