# Stage 1: build the React UI
FROM node:20-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

# Stage 2: compile the Java service (JDK only + the PostgreSQL JDBC driver)
FROM eclipse-temurin:21-jdk AS java
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
COPY scripts/ scripts/
COPY src/ src/
COPY bench/ bench/
RUN ./scripts/build.sh

# Stage 3: small runtime image
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=java /app/build/tally.jar build/tally.jar
COPY --from=java /app/lib/ lib/
COPY db/ db/
COPY --from=web /web/dist/ web/dist/
ENV PORT=8080 DB_POOL_SIZE=10
EXPOSE 8080
CMD ["java", "-XX:MaxRAMPercentage=75", "-jar", "build/tally.jar"]
