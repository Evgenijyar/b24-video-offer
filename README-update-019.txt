Install into project root (D:\\Работа\\b24-video-offer), replacing files.

Check locally:
  .\\mvnw.cmd clean test

Git:
  git add .
  git commit -m "Add desktop camera recording"
  git push

Deploy:
  cd /opt/b24-video-offer && python3 deploy.py

No Bitrix24 reinstallation is required.
