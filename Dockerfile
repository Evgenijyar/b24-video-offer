FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml pom.xml
RUN mvn -q -DskipTests dependency:go-offline

COPY src src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && command -v ffmpeg \
    && command -v ffprobe \
    && ffmpeg -version | head -n 1 \
    && ffprobe -version | head -n 1 \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /workspace/target/b24-video-offer-*.jar app.jar
RUN mkdir -p /app/data/videos /app/data/mobile-uploads /app/data/logs
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
