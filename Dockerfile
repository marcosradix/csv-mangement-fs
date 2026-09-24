# syntax=docker/dockerfile:1

# Stage 1: Build application with Java 25 & Maven
FROM eclipse-temurin:25-jdk-noble AS builder
WORKDIR /build

# Install Maven
RUN apt-get update && apt-get install -y --no-install-recommends maven && rm -rf /var/lib/apt/lists/*

# Copy pom and source, then package jar
COPY pom.xml .
COPY src src
RUN mvn clean package -DskipTests -B

# Stage 2: Minimal runtime with Java 25 JRE
FROM eclipse-temurin:25-jre-noble AS runner
WORKDIR /app

# Install curl for Actuator health checks and create unprivileged user
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && groupadd -r appgroup && useradd -r -g appgroup appuser

COPY --from=builder /build/target/*.jar /app/app.jar

RUN chown -R appuser:appgroup /app
USER appuser

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=5s --start-period=20s --retries=5 \
  CMD curl -f http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
