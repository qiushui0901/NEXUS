FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -DskipTests dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system nexus \
    && useradd --system --gid nexus --home-dir /opt/nexus --create-home nexus

WORKDIR /opt/nexus
COPY --from=build /workspace/target/NEXUS-0.8.0-SNAPSHOT.jar app.jar
RUN mkdir -p /data/wiki /data/wiki-sources /data/wiki-drafts /data/version-manifests \
        /data/requirement-snapshots /workspace/repository \
    && chown -R nexus:nexus /opt/nexus /data /workspace/repository

USER nexus
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=5 \
    CMD curl --fail --silent http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/opt/nexus/app.jar"]
