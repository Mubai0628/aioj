FROM maven:3.9.16-eclipse-temurin-17@sha256:1a352420f7aba21f5ad08df31bab55f74c013fb491f1ae8ab1dd7ff9ed698584 AS build
ARG SERVICE_MODULE
WORKDIR /workspace
COPY backend/ ./
# BuildKit keeps the Maven repository between builds, so each image no longer downloads every dependency.
RUN --mount=type=cache,target=/root/.m2 mvn -pl ${SERVICE_MODULE} -am -DskipTests package

FROM eclipse-temurin:17-jre@sha256:e04fa72be13b257a59fdec6dc14af78c8f80f035d3c1ed4e03e45552a231d4c8
ARG SERVICE_MODULE
ENV JAVA_OPTS="-XX:InitialRAMPercentage=15 -XX:MaxRAMPercentage=50 -XX:+ExitOnOutOfMemoryError"
WORKDIR /app
RUN apt-get update -o Acquire::Retries=3 \
    && apt-get install -y --no-install-recommends -o Acquire::Retries=3 curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /workspace/${SERVICE_MODULE}/target/*.jar /app/app.jar
EXPOSE 8080
# The shell expands JAVA_OPTS, then exec hands PID 1 to Java so `docker stop`'s SIGTERM reaches it.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
