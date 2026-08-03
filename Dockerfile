FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml pom.xml
RUN mvn -q -DskipTests dependency:go-offline

COPY src src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/target/b24-video-offer-*.jar app.jar
RUN mkdir -p /app/data/videos
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
