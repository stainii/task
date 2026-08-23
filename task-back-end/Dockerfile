# The back-end image (#24, ADR-0007).
#
# Self-contained on purpose: it builds the jar itself rather than copying one a previous step left
# in target/. ADR-0008's pass criterion is "starting from nothing, on a machine that is not the
# server" — an image recipe that only works inside one CI workflow is not that.
#
# THE JDK IS NOT PINNED HERE, DELIBERATELY. It is pinned in .sdkmanrc and, for the bytecode target,
# in pom.xml — ToolchainPinsTest fails the build when those two disagree, and ci.yml reads the major
# from .sdkmanrc at run time rather than repeating it. A third copy in this file would be a third
# thing to move, and the failure of a half-moved JDK is silent (see that test). So the version
# arrives as a build argument with no default, and deploy/build-images.sh derives it the same way
# ci.yml does. `docker build` without the argument fails loudly rather than picking a version.
ARG JAVA_VERSION

FROM eclipse-temurin:${JAVA_VERSION}-jdk AS build
WORKDIR /build

# .mvn carries jvm.config, whose --add-exports flags Error Prone needs. Maven only reads that file
# when it is invoked with the project as its base directory (ci.yml guards the same trap), which is
# what WORKDIR gives us here.
COPY .mvn .mvn
COPY mvnw pom.xml lombok.config ./
COPY src src

# Tests are not run here. The `green` check in ci.yml is what stands between a commit and this
# image — ADR-0007 wires every green push to `main` straight at the box, so the gate is the
# workflow's, and running the suite a second time inside the image build would test the same
# commit twice and publish nothing extra.
#
# `package` runs the build-info goal, which ClientConfigController refuses to start without: it is
# the back-end build date ADR-0009's second banner compares against. Dropping it would produce an
# image that boots, answers 200, and quietly disables the monitor.
RUN ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:${JAVA_VERSION}-jre AS runtime

# curl is here for one reason: compose's healthcheck hits /actuator/health (ADR-0009), and a
# healthcheck that cannot run is a container that never reports unhealthy.
RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home --uid 1001 task

WORKDIR /app
COPY --from=build --chown=task:task /build/target/task-back-end-*.jar /app/task-back-end.jar

# ADR-0009: logs outlive container recreation. compose mounts a volume here; without one a nightly
# deploy would erase the week.
ENV LOG_DIR=/logs
RUN mkdir /logs && chown task:task /logs

# spring-boot-docker-compose is on the runtime classpath for development, where it derives the
# datasource from compose.yaml. In a container there is no compose file and no Docker socket, and
# its absence is reported as a startup failure rather than a shrug. Off, explicitly.
ENV SPRING_DOCKER_COMPOSE_ENABLED=false

USER task
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/task-back-end.jar"]
