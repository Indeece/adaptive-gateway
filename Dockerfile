FROM eclipse-temurin:21.0.9_10-jre-jammy@sha256:cc11c035bb25cc709d7b1e3f43cbff15d69e06e2dba23eec431b64627d27e705
WORKDIR /app
ARG JAR_FILE
COPY --chown=10001:10001 ${JAR_FILE} /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
