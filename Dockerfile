# Single image: Angular bundle served by the Spring Boot backend.
# Stage 1 - build the frontend
FROM node:20-alpine AS frontend
WORKDIR /app
COPY frontend/package*.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npx ng build --configuration production

# Stage 2 - build the backend, embedding the frontend as static resources
FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /build
COPY backend/pom.xml .
RUN mvn -B -q dependency:go-offline
COPY backend/src ./src
COPY --from=frontend /app/dist/frontend/browser ./src/main/resources/static
RUN mvn -B -q clean package -DskipTests

# Stage 3 - runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=backend /build/target/*.jar app.jar
# The fixture is the estate this deployment describes; it ships with the image
# and is mounted read-only by the application.
COPY demo-estate/ /app/demo-estate/
ENV CODEATLAS_SOURCE_ROOT=/app/demo-estate
ENV CODEATLAS_PROFILE=demo
EXPOSE 8080
# Render supplies $PORT; default to 8080 for local runs.
CMD ["sh","-c","java -Dserver.port=${PORT:-8080} -Xmx400m -jar app.jar"]
