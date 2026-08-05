# Keycloak realm: dev fixture only

`realm-export.json` is a **development fixture**. It is imported by `compose.yaml`
(`start-dev --import-realm`) and mirrored in `src/test/resources/keycloak/` for the
integration tests.

**It is never the source of the production realm, and must never be imported into it.**

Two reasons, both decided in [#31](https://github.com/stainii/task/issues/31):

1. **This repo is public.** The fixture is safe to commit precisely because everything
   in it is worthless: user `stijnhooft@hotmail.com` with password `test`, and every
   client is a *public* client, so no client secret exists to leak. The moment this file
   is treated as production truth it has to hold real credentials — which is the one rule
   this repo does not break.
2. **Keycloak is shared infrastructure, not `task`'s** ([#15](https://github.com/stainii/task/issues/15)).
   The production realm accumulates state this repo cannot know about — other apps'
   clients and users. Importing this file over it would clobber them.

The production realm is **live state**, owned by the Keycloak deployment: created once,
configured out-of-band, and backed up with that server's own files. Nothing in this repo
reflects it, so [#29](https://github.com/stainii/task/issues/29) documents how it was
built and [#26](https://github.com/stainii/task/issues/26) covers restoring it.

What differs between dev and production is the issuer URI and the redirect URIs. Those
are environment values, not realm contents.

## What the fixture now resembles

[ADR-0010](../../../docs/adr/0010-a-tunnel-an-allowlist-and-a-role.md) rewrote it so the
resemblance is worth something — a wrong-by-default fixture teaches the wrong defaults:

- realm **`stijnhooft-realm`**, named after its owner because Keycloak is shared
  infrastructure; **`task` is one client in it**, not the realm itself;
- the `task` client is public with **PKCE S256**, **real redirect URIs** (no wildcard —
  a wildcard on a public client is account takeover, and PKCE does not prevent it),
  **empty `webOrigins`** (everything is same-origin), and **no direct access grants**;
- **`bruteForceProtected`** on, and **30-day** SSO sessions with 5-minute access tokens;
- a realm role **`task-user`**, which every `/api` request requires. Membership of this
  shared realm is deliberately not sufficient.

`portal-client` and `dummy-client` are gone.

The **test** realm in `src/test/resources/keycloak/` keeps `directAccessGrantsEnabled`,
because the integration tests mint tokens with the password grant inside a container that
never leaves the machine. It gains the `task-user` role for the same reason production has
it: without it every secured endpoint answers 403.

Anything changed here must be applied to the live realm **by hand** — see
[#29](https://github.com/stainii/task/issues/29). This file changing is not that realm
changing.
