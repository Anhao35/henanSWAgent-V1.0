FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
RUN useradd --system --uid 10001 --create-home app
WORKDIR /app
COPY --from=build /workspace/target/henan-sec-agent-server-1.0.0-SNAPSHOT.jar app.jar
USER 10001
EXPOSE 8088
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
