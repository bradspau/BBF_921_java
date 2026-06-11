# Stage 1 — build
FROM maven:3.9-eclipse-temurin-21-jammy AS build
WORKDIR /app
# Cache dependencies separately from source
COPY pom.xml .
RUN mvn dependency:resolve -q 2>&1 | tail -3 || true
COPY src/ src/
RUN mvn package -DskipTests -q

# Stage 2 — runtime
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

# Ontology TTL files (loaded by SchemaInit on startup)
COPY ontology/ ontology/

# BBF access domain resource inventory (used by access profile)
COPY BBF_access/ BBF_access/

# Seed data expressions
COPY seed_data/ seed_data/

# Default: standalone profile on port 8000
ENV SPRING_PROFILES_ACTIVE=standalone
EXPOSE 8000 8001

ENTRYPOINT ["java", "-jar", "app.jar"]
