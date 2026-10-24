# platform-app image (ADR-H-IPASS-01 Decision 4A). Build the jar on the build PC first:
#   JAVA_HOME=<jdk25> ./mvnw -B -pl platform-app -am package -DskipTests
#   docker build -f platform-app/Dockerfile -t ipass/platform-app:<short-commit> .      (context = repo root)
# No secret, no env value baked in: every setting arrives as an environment variable at run time (R07).
# Java 25 base pinned by digest (re-pin each release instead of Buildpacks' auto-patching). glibc (noble), NOT alpine:
# the rule engine (zen-engine) loads a glibc native library - on musl the context fails (measured in the local smoke).
FROM eclipse-temurin:25-jre-noble@sha256:3f2223fe04b750aa5a8179e29b5c9a1ddf7665fc9bb08bfe11ad01d143bc13a5 AS extract
WORKDIR /extract
COPY platform-app/target/platform-app-*-exec.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination layers

FROM eclipse-temurin:25-jre-noble@sha256:3f2223fe04b750aa5a8179e29b5c9a1ddf7665fc9bb08bfe11ad01d143bc13a5
RUN groupadd -g 10001 ipass && useradd -u 10001 -g ipass -M -d /app -s /usr/sbin/nologin ipass
WORKDIR /app
COPY --from=extract --chown=10001:10001 /extract/layers/dependencies/ ./
COPY --from=extract --chown=10001:10001 /extract/layers/spring-boot-loader/ ./
COPY --from=extract --chown=10001:10001 /extract/layers/snapshot-dependencies/ ./
COPY --from=extract --chown=10001:10001 /extract/layers/application/ ./
USER 10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Duser.timezone=Etc/UTC"
# no curl/wget in the noble JRE image: bash /dev/tcp (readiness includes KEK + scheduler checks)
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=5   CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /actuator/health/readiness HTTP/1.0\r\n\r\n' >&3 && grep -q '\"UP\"' <&3"]
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
