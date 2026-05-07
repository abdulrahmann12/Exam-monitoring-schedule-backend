# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-23 AS build

WORKDIR /app

# Cache dependencies first
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Build the project
COPY src ./src
RUN mvn package -DskipTests -B

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:23-jre

WORKDIR /app

# Copy the fat JAR produced by Spring Boot
COPY --from=build /app/target/schedule-0.0.1-SNAPSHOT.jar app.jar

# Hugging Face Spaces routes external traffic to port 7860
EXPOSE 7860

ENTRYPOINT ["java", "-jar", "app.jar", "--server.port=7860"]