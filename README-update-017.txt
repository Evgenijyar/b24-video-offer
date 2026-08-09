B24 Video Offer — update 017

Apply this archive over the current project root and replace matching files.

Local check:
  cd D:\\Работа\\b24-video-offer
  .\\mvnw.cmd clean test

Git:
  git add .
  git commit -m "Fix mobile recorder permissions timer and ffmpeg deployment"
  git push

Server:
  cd /opt/b24-video-offer && python3 deploy.py

Expected during Docker build/runtime:
  ffmpeg version ...
  FFmpeg ready for mobile video processing: executable=/usr/bin/ffmpeg, ...

Quick verification:
  docker exec b24-video-offer /usr/bin/ffmpeg -version | head -n 1
  docker logs --since 10m b24-video-offer 2>&1 | grep -Ei 'FFmpeg ready|CAMERA_|PERMISSION_|RECORDING_STARTED|Mobile video upload|normalization|ERROR|WARN'

Bitrix24 app reinstallation is NOT required for this update.
No application.properties changes are required.
