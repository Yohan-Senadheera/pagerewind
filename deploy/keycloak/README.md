# Keycloak realm

`pagerewind-realm.json` is the `pagerewind` realm as code: the roles, the clients and the service
accounts. It holds no secrets and no people. Keycloak fills in these placeholders from its environment
when it imports the file.

The same file is used by the Compose quickstart (mounted into Keycloak), the Helm chart (a copy in
`charts/pagerewind/files/`, CI checks it matches) and the reference cluster (`scripts/render-realm-import.py`
turns it into `platform/keycloak/realm-import.yaml`, CI checks that too).

Placeholders:

| Placeholder | What it is |
|---|---|
| `PAGEREWIND_URL` | Public address of the gateway, for example `https://pagerewind.example.com` (no trailing slash) |
| `PAGEREWIND_WEB_CLIENT_SECRET` | Secret of `pagerewind-web`, the client the gateway signs people in with |
| `NOTIFY_CLIENT_SECRET` | Secret of `notify-svc`, which acknowledges and resolves for the person who was paged |
| `AUTOMATION_CLIENT_SECRET` | Secret of `pagerewind-automation`, used by scripts and CI (admin role) |

## Roles

| Role | Can |
|---|---|
| `viewer` | Read incidents and who is on call |
| `responder` | Everything a viewer can, plus acknowledge, resolve and add notes; see contact details |
| `admin` | Everything a responder can, plus change people, teams, rotations and escalation policies |

The services apply the order admin > responder > viewer themselves, so give each person one role.

## Tokens

Every access token for PageRewind carries the audience `pagerewind-api`, and the services reject tokens
without it. Access tokens live 5 minutes.
