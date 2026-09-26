FROM eclipse-temurin:26-jdk AS build
WORKDIR /app
COPY . .
RUN ./mvnw -B -DskipTests package

FROM eclipse-temurin:26-jre
WORKDIR /app
RUN groupadd --gid 10001 agents && useradd --uid 10001 --gid agents agents && mkdir keys secrets && chown -R agents:agents /app
COPY --from=build --chown=agents:agents /app/bootstrap/target/bootstrap-0.1.0-SNAPSHOT.jar app.jar
USER agents
ENV SERVER_ADDRESS=0.0.0.0
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
