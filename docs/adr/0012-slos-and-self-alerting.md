# 12. SLOs, burn-rate alerts and how PageRewind alerts about itself

- Status: accepted
- Date: 2026-11-24

## Context

An on-call tool that fails quietly is worse than none. We need to know when PageRewind itself is failing,
including when the part that would page us is the part that broke, without paging on every blip.

## Decision

- **SLOs on what users feel**, from metrics the services already export: page delivery (99% of Telegram and
  email deliveries succeed) and alert intake (99.5% of alert webhooks get no server error).
- **Multi-window burn-rate alerts** (Google SRE workbook): fast burn 14.4x over 1 hour and 5 minutes is
  critical, slow burn 6x over 6 hours and 30 minutes is a warning. The long window proves it is real; the
  short one stops the page once the problem is over.
- **Processing alerts** on the services' failure counters, because a healthy front door can hide a stalled
  pipeline (drill 1).
- **Routing:** SLO and critical alerts go into PageRewind's own ingest. Critical alerts about PageRewind
  also go to healthchecks.io `/fail`, which emails at once without PageRewind. An always-firing Watchdog
  pings healthchecks.io every minute; if the pings stop (Alertmanager or the cluster is down),
  healthchecks.io emails.

## Alternatives considered

- **Threshold alerts on error rates:** simple, but page on short spikes and miss slow, steady burns.
- **Only self-routing:** elegant, but cannot report the failure of its own pipeline (drill 1 proved it).
- **A second paging product:** more reliable, but another account and cost for a demo deployment;
  healthchecks.io covers the "whole system down" and "pipeline broken" cases by email.

## Consequences

- Proven on 24 Nov 2026: with email blocked, the slow burn fired and PageRewind paged on Telegram about its
  own failing email.
- Low traffic makes ratio SLOs jumpy; the two-window design keeps that from paging, but a quiet service can
  burn budget unnoticed for a while.
- Every self-alert needs an owner: a team must own the `pagerewind-*` services, or the incident pages nobody
  (found in the SLO test).
