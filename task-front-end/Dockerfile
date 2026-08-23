# The front-end image (#24, ADR-0007): nginx serving the built Angular app and proxying to the
# back-end and to Keycloak, so the whole system is one origin. `cloudflared` publishes this
# container and nothing else.
#
# NODE IS NOT PINNED HERE, DELIBERATELY — same reasoning as task-back-end/Dockerfile. .nvmrc is the
# pin (docs/quality-bar.md §3) and ci.yml reads it; deploy/build-images.sh derives the build
# argument from the same file. No default, so a build that forgets it fails instead of guessing.
ARG NODE_VERSION

FROM node:${NODE_VERSION}-alpine AS build
WORKDIR /build

COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# `npm run build` stamps TASK_BUILT_AT from the shell (see package.json) — ADR-0009's front-end half
# of the build-date banner. It also runs the production configuration, so angular.json's bundle
# budgets apply here exactly as they do in CI.
RUN npm run build

FROM nginx:1.29.4-alpine AS runtime

# The allowlist, the SSE settings and the body cap. Everything internet-facing about this system is
# in that one file.
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist/task-front-end/browser /usr/share/nginx/html

EXPOSE 80
