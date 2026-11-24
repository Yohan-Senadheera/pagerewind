# Contributing

Thanks for your interest. Issues and pull requests are welcome; for larger changes, open an issue first
so we can agree on the approach. Ideas waiting for someone: [#75](https://github.com/Yohan-Senadheera/pagerewind/issues/75).

## Build and test

Needs Java 21 and Docker (the integration tests start PostgreSQL, RabbitMQ, Redis, Keycloak and k3s in
containers).

```bash
./mvnw verify                      # all unit and integration tests
./mvnw -pl services/incident verify  # one service
helm lint charts/pagerewind --strict
```

The Compose quickstart in the [README](README.md) is the quickest way to try a change end to end.

## Pull requests

- One topic per pull request, with tests for new behaviour.
- CI must pass: Java tests, the end-to-end test, the Helm chart install on kind, gitleaks.
- Write commit messages that say why, not only what.
- Design decisions that are hard to undo get an ADR in [docs/adr/](docs/adr/).

## Reporting security problems

See [SECURITY.md](SECURITY.md). Please do not open public issues for vulnerabilities.
