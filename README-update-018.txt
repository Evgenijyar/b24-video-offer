UPDATE 018

Copy the archive contents into the project root and replace existing files.

Local verification:
  cd D:\Работа\b24-video-offer
  .\mvnw.cmd clean test

Git:
  git add .
  git commit -m "Speed up mobile video recording finalization"
  git push

Server:
  cd /opt/b24-video-offer && python3 deploy.py

No Bitrix24 reinstall and no application.properties changes are required.

Useful log filter after the first mobile test:
  docker logs --since 10m b24-video-offer 2>&1 | grep -Ei 'RECORDING_|chunk accepted|assembled|probe completed|fast remux|normalization|UPLOAD_READY|ERROR|WARN'
