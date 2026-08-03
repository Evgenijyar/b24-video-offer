# b24-video-offer

Spring Boot-приложение для создания персональных видеоофферов из записей Контур.Толка внутри Bitrix24 CRM.

## Поддерживаемые сущности CRM

- Сделка (`DEAL`)
- Лид (`LEAD`)
- Контакт (`CONTACT`)

## Конфигурация

Все настройки находятся в одном файле:

```text
src/main/resources/application.properties
```

Файлы `.env` и `application.yml` проекту не нужны.

## Первый локальный запуск в IntelliJ IDEA

1. Открыть корневую папку проекта, где находится `pom.xml`.
2. Выбрать JDK 21.
3. Дождаться загрузки Maven-зависимостей.
4. Запустить класс `ru.abs7.videooffer.B24VideoOfferApplication`.
5. Проверить:
   - `http://localhost:8080/`
   - `http://localhost:8080/api/health`

При первом запуске приложение подключится к уже созданной базе `b24_video_offer`, а Flyway автоматически создаст таблицы.

## Проверка через Maven

Windows PowerShell:

```powershell
.\mvnw.cmd test
```

Запуск без IntelliJ IDEA:

```powershell
.\mvnw.cmd spring-boot:run
```

## Git

Первичная публикация:

```bash
git init
git branch -M main
git remote add origin https://github.com/Evgenijyar/b24-video-offer.git
git add .
git commit -m "Initial b24 video offer project"
git push -u origin main
```

Обычные обновления:

```bash
git add .
git commit -m "Описание изменений"
git push
```

## Сервер

Приложение использует внешний PostgreSQL. Docker Compose поднимает только Java-приложение и постоянный volume для видео.

Обновление сервера:

```bash
python3 deploy.py
```
