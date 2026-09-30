FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace

COPY pom.xml ./
COPY init.sql ./init.sql
COPY src ./src
RUN mvn --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

RUN groupadd --system --gid 10001 qatools \
    && useradd --system --uid 10001 --gid qatools --home-dir /app qatools

COPY --from=build --chown=10001:10001 /workspace/target/qatools-backend-1.0.0-SNAPSHOT.jar /app/qatools-backend.jar

USER 10001:10001
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/qatools-backend.jar"]
