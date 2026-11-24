# Infrastructure (Terraform, AWS)

The AWS side of the reference deployment. Users of PageRewind do not need any of this; it is how the
project's own demo cluster is built.

| Folder | What it creates | Why it is separate |
|---|---|---|
| `bootstrap/` | The S3 bucket that holds Terraform state (versioned, encrypted, locked) | It must exist before any other stack can store its state |
| `ip/` | The Elastic IP of the cluster | Rebuilding the cluster keeps the same IP, so DNS keeps working |
| `reference/` | VPC, subnet, firewall, two k3s nodes, IAM roles, automatic nightly stop, the backup bucket | The cluster itself |
| `modules/` | `state-backend`, `network`, `k3s-node`: the building blocks the stacks use | Reuse and smaller files |

## Use

```bash
aws login --profile pager
cp backend.hcl.example backend.hcl      # fill in the state bucket; never commit it
cd reference
terraform init -backend-config="../backend.hcl"
terraform plan -out plan.tfplan          # read it: nothing should be destroyed by surprise
terraform apply plan.tfplan
```

`scripts/pagerewind.ps1 up` and `down` run the stacks in the right order and install Argo CD.

## Notes

- **CI only checks Terraform** (format, validate); it never applies. The AWS account blocks GitHub's OIDC
  login, and no AWS keys are stored in CI (ADR 0007). Applies run from a laptop.
- The firewall opens 80 and 443 to everyone, and SSH and the Kubernetes API only to the admin's current IP.
- The node role can read only secrets named `pagerewind/*` and write (not delete) backups; the instance
  metadata hop limit is 1, so ordinary pods cannot use it.
- Each node reserves memory for k3s and evicts pods before it runs out (`modules/k3s-node/*.sh.tftpl`).
  The install scripts only run at first boot; changing them does not replace running nodes.
- Cost: both nodes stop 4 hours after `start` and every night at 04:00 (EventBridge Scheduler).
