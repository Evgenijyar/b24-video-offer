UPDATE 020

Desktop Bitrix24 recording now supports:
- Camera + default system microphone
- Screen/window/tab capture
- Optional computer/system audio
- Optional microphone audio
- Simultaneous system audio + microphone mixing

No database migration and no Bitrix24 reinstall are required.

Install:
1. Extract the update archive into the project root with file replacement.
2. Run: .\mvnw.cmd clean test
3. git add . && git commit -m "Add desktop screen and audio recording" && git push
4. Server: cd /opt/b24-video-offer && python3 deploy.py

Test in desktop Bitrix24:
Create video offer -> Record video -> choose Camera or Screen.
For Screen, if "Computer sound" is enabled, also enable the browser's Share audio option in the native screen picker when it is offered.
