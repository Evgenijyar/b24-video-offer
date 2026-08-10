B24 VIDEO OFFER — UPDATE 021

Install over the current project root. Do not replace application.properties with anything from this package; update 021 does not contain application.properties.

LOCAL:
  cd D:\Работа\b24-video-offer
  .\mvnw.cmd clean test

GIT:
  git add .
  git commit -m "Add universal video sources and screen recording"
  git push

SERVER:
  cd /opt/b24-video-offer && python3 deploy.py

No Bitrix24 reinstall is required. Existing application URLs, OAuth scopes and CRM placements are unchanged.

The first Docker build after this update is larger because it downloads yt-dlp and Deno into the runtime image.

VERIFY TOOLS:
  docker exec b24-video-offer /usr/local/bin/yt-dlp --version
  docker exec b24-video-offer /usr/local/bin/deno --version | head -n 1
  docker exec b24-video-offer /usr/bin/ffmpeg -version | head -n 1
  docker exec b24-video-offer /usr/bin/ffprobe -version | head -n 1

VERIFY STARTUP/MIGRATION:
  docker logs --since 10m b24-video-offer 2>&1 | grep -Ei 'V5|yt-dlp|Deno|External video tools|Mobile media tools|ERROR|WARN'

DESKTOP TESTS:
1) URL: existing Kontur.Talk URL.
2) URL: one public YouTube/Rutube/VK/Instagram/TikTok URL.
3) File: MP4 under 100 MiB.
4) Camera + microphone.
5) Screen: select Screen -> helper popup -> "Выбрать экран" -> choose "Весь экран" in the browser chooser -> Record.
6) During one recording: Camera -> Screen -> Camera -> Stop; verify a single final preview and one offer.

MOBILE TESTS:
1) Select a lead/contact/deal.
2) Link source -> public URL -> create offer.
3) Camera source -> record -> create offer.
4) File source -> choose a video under 100 MiB -> create offer.

IMPORTANT SCREEN-CAPTURE LIMITS:
- Browsers are required to show a display chooser; a web app cannot silently force the whole monitor.
- The compact helper is a normal browser popup. It can be moved, but cannot be guaranteed always-on-top or excluded from a full-monitor capture.
- Bitrix Android WebView does not expose getDisplayMedia, so mobile screen recording is not included. It would require native Android MediaProjection support in the host app or a separate native companion app.
