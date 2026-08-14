FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml pom.xml
RUN mvn -q -DskipTests dependency:go-offline

COPY src src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app

ARG TARGETARCH
ARG YT_DLP_VERSION=2026.06.09
ARG DENO_VERSION=2.8.1

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg curl ca-certificates unzip \
    && case "${TARGETARCH:-amd64}" in \
         amd64) YTDLP_ASSET="yt-dlp_linux"; DENO_ASSET="deno-x86_64-unknown-linux-gnu.zip" ;; \
         arm64) YTDLP_ASSET="yt-dlp_linux_aarch64"; DENO_ASSET="deno-aarch64-unknown-linux-gnu.zip" ;; \
         *) echo "Unsupported Docker architecture: ${TARGETARCH}" >&2; exit 1 ;; \
       esac \
    && curl -fL --retry 4 --retry-delay 2 \
         -o /usr/local/bin/yt-dlp \
         "https://github.com/yt-dlp/yt-dlp/releases/download/${YT_DLP_VERSION}/${YTDLP_ASSET}" \
    && chmod 0755 /usr/local/bin/yt-dlp \
    && curl -fL --retry 4 --retry-delay 2 \
         -o /tmp/deno.zip \
         "https://github.com/denoland/deno/releases/download/v${DENO_VERSION}/${DENO_ASSET}" \
    && unzip -q /tmp/deno.zip -d /usr/local/bin \
    && chmod 0755 /usr/local/bin/deno \
    && command -v ffmpeg \
    && command -v ffprobe \
    && command -v yt-dlp \
    && command -v deno \
    && ffmpeg -version | head -n 1 \
    && ffprobe -version | head -n 1 \
    && yt-dlp --version \
    && deno --version | head -n 1 \
    && rm -f /tmp/deno.zip \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/target/b24-video-offer-*.jar app.jar
RUN mkdir -p /app/data/videos /app/data/mobile-uploads /app/data/external-imports /app/data/logs /app/data/page-builder/assets /app/data/page-builder/drafts /app/data/page-builder/attachments
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
