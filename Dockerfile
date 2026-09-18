# syntax=docker/dockerfile:1

# ---- React UI
FROM node:24-alpine AS ui
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY frontend/ ./
# vite writes to ../target/classes/static
RUN npm run build

# ---- Spring Boot jar (with the UI inside)
FROM maven:3.9-eclipse-temurin-25 AS app
WORKDIR /build
COPY pom.xml ./
COPY src src
COPY --from=ui /build/target/classes/static target/classes/static
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipFrontend=true -DskipTests package

# ---- runtime
FROM eclipse-temurin:25-jre
COPY --from=app /build/target/kbmd-*.jar /app/kbmd.jar
# writable home for any user id passed with --user
ENV HOME=/tmp
VOLUME /vault
EXPOSE 8787
ENTRYPOINT ["java", "-jar", "/app/kbmd.jar", "--kbmd.vault=/vault", "--server.address=0.0.0.0"]
