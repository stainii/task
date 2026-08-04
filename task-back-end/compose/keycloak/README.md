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
