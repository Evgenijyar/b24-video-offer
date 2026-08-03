# b24-video-offer

Spring Boot application for creating personal video offers from Kontur.Talk recordings inside Bitrix24 CRM.

## Supported CRM entities

- Deal (`DEAL`)
- Lead (`LEAD`)
- Contact (`CONTACT`)

## Local start in IntelliJ IDEA

1. Copy `.env.example` to `.env` and fill values.
2. Configure IntelliJ run environment variables from `.env`.
3. Run `B24VideoOfferApplication`.
4. Open `http://localhost:8080` and `http://localhost:8080/api/health`.

The PostgreSQL database must already exist on the external PostgreSQL server. Flyway creates tables automatically.

## Create database on PostgreSQL server

Run as a PostgreSQL administrator:

```sql
CREATE USER b24_video_offer_user WITH PASSWORD 'CHANGE_ME';
CREATE DATABASE b24_video_offer OWNER b24_video_offer_user ENCODING 'UTF8';
GRANT ALL PRIVILEGES ON DATABASE b24_video_offer TO b24_video_offer_user;
```

## Git workflow

```bash
git add .
git commit -m "Describe changes"
git push
```

## Server update

```bash
python3 deploy.py
```
