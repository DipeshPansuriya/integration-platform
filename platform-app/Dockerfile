# platform-app image (ADR-H-IPASS-01 Decision 4A). Build the jar on the build PC first:
#   JAVA_HOME=<jdk25> ./mvnw -B -pl platform-app -am package -DskipTests
#   docker build -f platform-app/Dockerfile -t ipass/platform-app:<short-commit> .      (context = repo root)
# No secret, no env value baked in: every setting arrives as an environment variable at run time (R07).
# Java 25 base pinned by digest (re-pin each release instead of Buildpacks' auto-patching).
FROM eclipse-temurin:25-jre-alpine@sha256:b7528962e65d43064d8a8b67ba175367896bc948363ea2d3fcd7f10d34e1f048 AS extract
WORKDIR /extract
COPY platform-app/target/platform-app-*-exec.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination layers

FROM eclipse-temurin:25-jre-alpine@sha256:b7528962e65d43064d8a8b67ba175367896bc948363ea2d3fcd7f10d34e1f048
RUN addgroup -g 10001 -S ipass && adduser -u 10001 -S -G ipass -H -h /app ipass
WORKDIR /app
COPY --from=extract --chown=10001:10001 /extract/layers/dependencies/ ./
COPY --from=extract --chown=10001:10001 /extract/layers/spring-boot-loader/ ./
COPY --from=extract --chown=10001:10001 /extract/layers/snapshot-dependencies/ ./
COPY --from=extract --chown=10001:10001 /extract/layers/application/ ./
USER 10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Duser.timezone=Etc/UTC"
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=5 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health/readiness | grep -q '"UP"' || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
