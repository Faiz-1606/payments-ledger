# Deploying to Render (core + demo profiles)

This guide puts the ledger on [Render](https://render.com) with a managed PostgreSQL database
and **no Kafka**: profiles `core,demo`. Transfers still write their outbox events (nothing
publishes them), and the race demo is at `/demo.html`.

There are two ways: a Blueprint (one click, from `render.yaml`) or manual setup. Both build the
repository's `Dockerfile`.

## Before you start
- Push this repository to GitHub (or GitLab) and create a Render account.
- Choose an admin email and a strong admin password. The admin can make deposits and run
  reconciliation, so treat the password like a production credential.

## Option A: Blueprint (`render.yaml`)
1. In Render: **New → Blueprint**, then select the repository. Render reads `render.yaml` and
   proposes a web service `payments-ledger` and a database `ledger-db`.
2. It prompts for the two `sync: false` values: `ADMIN_EMAIL` and `ADMIN_PASSWORD`.
   `JWT_SECRET` is generated for you, and the `DB_*` variables are wired from the database.
3. Click **Apply**. The first deploy builds the Docker image (a few minutes) and runs the Flyway
   migrations on the empty database at startup.
4. Open `https://<service-name>.onrender.com/demo.html`.

## Option B: manual
1. **Database:** New → PostgreSQL. Name `ledger-db`, database `ledger`, user `ledger`, same region
   as the web service. After creation, open it and note **Hostname** (internal), **Port**,
   **Database**, **Username** and **Password** from the *Internal* connection info.
2. **Web service:** New → Web Service → your repo → Runtime **Docker**.
   Health check path: `/actuator/health/readiness`.
3. **Environment variables** (Settings → Environment):

   | Key | Value |
   |---|---|
   | `SPRING_PROFILES_ACTIVE` | `core,demo` |
   | `DB_HOST` | internal hostname of the database |
   | `DB_PORT` | `5432` |
   | `DB_NAME` | `ledger` |
   | `DB_USER` | database username |
   | `DB_PASSWORD` | database password |
   | `JWT_SECRET` | at least 32 random bytes, e.g. the output of `openssl rand -base64 48` |
   | `ADMIN_EMAIL` | your admin login |
   | `ADMIN_PASSWORD` | a strong password |
   | `LEDGER_LOCKING` | `pessimistic` (default; safest under hot-account contention) |
   | `JAVA_OPTS` | `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -XX:+UseSerialGC` |

   Render's database page also shows a `postgresql://user:pass@host/db` URL. Spring's JDBC
   driver needs `jdbc:postgresql://host:port/db` with the user and password separate, which is
   why the app reads `DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USER`/`DB_PASSWORD` (see
   `spring.datasource` in `application.yml`). Alternatively set `SPRING_DATASOURCE_URL`,
   `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD` directly.
4. Deploy.

## How the app adapts to Render
- **Port:** Render tells the service which port to listen on through `PORT`.
  `server.port: ${PORT:8080}` in `application.yml` uses it, falling back to 8080 locally.
- **Memory:** the free instance has 512 MB. `-XX:MaxRAMPercentage=75` sizes the heap from the
  container limit, and SerialGC has the smallest footprint for a single small CPU.
- **Migrations:** Flyway runs at startup. Schema changes ship with the code: add a new
  `V<n>__*.sql`, never edit an applied one.
- **Health:** `/actuator/health/readiness` returns 200 only after startup (including migrations)
  completes, so Render doesn't route traffic to a half-started instance.

## Check the deployment
```bash
BASE=https://<service-name>.onrender.com
curl -s $BASE/actuator/health
curl -s -X POST $BASE/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"<ADMIN_EMAIL>","password":"<ADMIN_PASSWORD>"}'
# then, with the token:
curl -s -X POST $BASE/api/admin/reconciliation/runs -H "Authorization: Bearer <token>"
```
After you run the naive race on the demo page, reconciliation reports drift on the DEMO
accounts. That is expected: naive mode exists to show exactly that.

## Things to know
- **Free instances sleep** after a period without traffic. The first request after that takes
  a while (container start plus JVM start).
- **Free databases expire** after a limited period on Render's free tier. Check Render's current
  limits, and use a paid plan for anything you want to keep.
- **Kafka is not deployed.** To run the full outbox pipeline, use `docker compose up` locally,
  or attach a managed Kafka and drop the `core` profile, setting `KAFKA_BOOTSTRAP_SERVERS`.
- **Secrets** (JWT secret, admin password, DB password) live only in Render's environment
  settings, never in the repository. The values in `docker-compose*.yml` are for local use.
