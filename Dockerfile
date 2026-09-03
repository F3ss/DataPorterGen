# syntax=docker/dockerfile:1.7
FROM eclipse-temurin:21.0.12_8-jdk-jammy AS build
WORKDIR /workspace
COPY gradlew gradlew.bat settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies
COPY src ./src
RUN ./gradlew --no-daemon clean bootJar

FROM eclipse-temurin:21.0.12_8-jre-alpine-3.22 AS runtime
RUN addgroup -S dataportergen && adduser -S -G dataportergen -u 10001 dataportergen \
    && mkdir -p /app/reports && chown -R dataportergen:dataportergen /app
WORKDIR /app
COPY --from=build --chown=dataportergen:dataportergen /workspace/build/libs/DataPorterGen.jar /app/DataPorterGen.jar
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"
ENTRYPOINT ["java", "-jar", "/app/DataPorterGen.jar"]
