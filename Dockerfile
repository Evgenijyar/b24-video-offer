FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY pom.xml .
COPY src src
RUN ./mvnw -q -DskipTests package 2>/dev/null || mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/target/b24-video-offer-*.jar app.jar
RUN mkdir -p /app/data/videos
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
