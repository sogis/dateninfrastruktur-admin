# syntax=docker/dockerfile:1
ARG JDK_IMAGE=eclipse-temurin:25-jdk-jammy@sha256:e4f029273ed9e4444697df30bb6098dbe51477291598aebc54dd0b48d95d6ae7
ARG JRE_IMAGE=eclipse-temurin:25-jre-jammy@sha256:659b528cca74a0edc5c25315ec5819f35bce40bbcec645c4c534987e4c6be7c1
ARG PG_TOOL_IMAGE=postgres_tools
FROM ${JDK_IMAGE} AS jvm-build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY src ./src
COPY db ./db
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar

FROM ${JRE_IMAGE} AS jvm-runtime
WORKDIR /opt/admin
RUN groupadd --gid 10001 admin && useradd --uid 10001 --gid 10001 --no-create-home admin
COPY --from=jvm-build --chown=10001:10001 /workspace/build/libs/dateninfrastruktur-admin.jar /opt/admin/app.jar
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65.0" TZ=UTC
EXPOSE 8080
ENTRYPOINT ["java","-jar","/opt/admin/app.jar"]

FROM rclone/rclone:1.75.2 AS rclone-binary
FROM ${PG_TOOL_IMAGE} AS tooling
USER root
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-boto3 libasound2 libfontconfig1 libfreetype6 libx11-6 libxext6 libxi6 libxrender1 libxtst6 && rm -rf /var/lib/apt/lists/*
COPY --from=jvm-build /opt/java/openjdk /opt/java/openjdk
COPY --from=rclone-binary /usr/local/bin/rclone /usr/local/bin/rclone
ENV JAVA_HOME=/opt/java/openjdk PATH=/opt/java/openjdk/bin:$PATH DI_DATABASE_TARGET=postgresql DI_TOOL_RUNTIME=/runtime/tools
WORKDIR /opt/admin
COPY --from=jvm-build /workspace/build/libs/dateninfrastruktur-admin.jar /opt/admin/app.jar
COPY --from=stack_source services/migration /opt/stack-tools/migration
COPY --from=stack_source services/garage/setup-compose.py /opt/stack-tools/setup_garage.py
COPY --from=stack_source services/postgres/initialize-compose.py /opt/stack-tools/initialize_postgres.py
COPY scripts ./scripts
COPY db ./db
COPY models ./models
COPY src/main/resources/cayenne ./src/main/resources/cayenne
RUN mkdir -p /runtime/tools && python3 -c "import sys;sys.path.insert(0,'scripts');from interlis_tools import ensure;ensure()"
ENTRYPOINT ["python3","/opt/admin/scripts/setup_explicit.py"]

FROM jvm-build AS test-build
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon composeTestRuntime
FROM tooling AS test-tools
COPY --from=test-build /workspace/build/compose-test /opt/compose-test
ENTRYPOINT ["java","-cp","/opt/compose-test/classes:/opt/compose-test/lib/*","ch.so.agi.dateninfrastruktur.admin.ComposeFixtureCommand"]
