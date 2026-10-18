FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build
RUN apk add --no-cache curl
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src/ src/
# Unit tests run here; PostgreSQL integration tests run in the mandatory CI verify job.
RUN ./mvnw --batch-mode --no-transfer-progress package

FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S app && adduser -S -G app -u 10001 app
WORKDIR /app
COPY --from=build --chown=app:app /build/target/operationsflow-0.1.0-SNAPSHOT.jar app.jar
USER 10001
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=12 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
