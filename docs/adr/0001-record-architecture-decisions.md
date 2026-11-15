# 1. Record architecture decisions

- Status: accepted
- Date: 2026-11-14

## Context

PageRewind is built by one person now and is meant to be open source later. Future contributors (and
future me) need to know why things are the way they are, not only what they are. Decisions made in
chat or in someone's head are lost.

## Decision

Record every significant decision as an Architecture Decision Record (ADR) in `docs/adr/`, using the
format of this file: context, decision, alternatives considered, consequences.

- One decision per file, numbered in order: `NNNN-short-title.md`.
- An ADR is never edited after it is accepted, except its status. A changed decision gets a new ADR
  that marks the old one as "superseded by NNNN".
- Write the ADR in the same pull request as the change it explains.

"Significant" means: hard to reverse, affects more than one service, or someone could reasonably have
chosen differently.

## Alternatives considered

- **No written decisions:** fastest now, but the reasons are lost and the same debates repeat.
- **A wiki:** separate from the code, so it drifts out of date and is not reviewed with changes.

## Consequences

- Each significant change takes a few extra minutes to write up.
- Reviewers and new contributors can read the reasons next to the code.
- ADRs appear on the docs site, which shows how the project was designed.
