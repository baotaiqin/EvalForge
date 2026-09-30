# QATools Backend

Java 17 / Spring Boot service for the QATools evaluation application.

## Requirements

- JDK 17 or newer
- Maven 3.9 or newer
- MySQL 8.x
- MinIO is needed for object storage and file previews

## Local run

Create the application database first:

```sql
CREATE DATABASE qa_tools CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

The service applies `init.sql` at startup by default. It creates the QATools-owned tables if they are missing. Set `SPRING_SQL_INIT_MODE=never` if the database account must not run DDL; in that case, apply `init.sql` with a database administrator before starting the service.

PowerShell example (replace local connection values as needed):

```powershell
$env:DB_URL = 'jdbc:mysql://127.0.0.1:3306/qa_tools?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai'
$env:DB_USER = 'qatools'
$env:DB_PASSWORD = 'set-a-local-password'
$env:MINIO_ENDPOINT = 'http://127.0.0.1:9000'
$env:MINIO_ACCESS_KEY = 'set-a-local-access-key'
$env:MINIO_SECRET_KEY = 'set-a-local-secret-key'
mvn spring-boot:run
```

The API listens on port `8081`. For the Vue app, set `VITE_USE_MOCK=false` and `VITE_API_BASE_URL=http://localhost:8081` in its local environment file.

Build an executable jar:

```sh
mvn --batch-mode -DskipTests package
java -jar target/qatools-backend-1.0.0-SNAPSHOT.jar
```

## Docker image

From this directory:

```sh
docker build -t qatools-backend .
```

Run the image with MySQL and MinIO addresses reachable from the container. Supply `DB_URL` (or `DB_HOST`, `DB_PORT`, and `DB_NAME` with the `docker` Spring profile), `DB_USER`, `DB_PASSWORD`, and the `MINIO_*` variables. `PUBLIC_BASE_URL` controls links returned to the browser. The configured MinIO endpoint is also used to form browser-facing object URLs, so it must be reachable by both the backend and the browser.

## Configuration

Application properties support environment-variable overrides. The checked-in defaults are local examples and contain no working service credentials. Configure these groups for integrations you use:

- `DB_URL`, `DB_USER`, `DB_PASSWORD`: QATools application database.
- `MINIO_ENDPOINT`, `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY`, `MINIO_BUCKET`: object storage.
- `ENV_DB_DEV_*`, `ENV_DB_SIT_*`, `ENV_DB_PROD_*`: external Agent platform database/API connections.
- `SEMICLAW_*`: SemiClaw service account and WebSocket settings.
- `EVAL_*`: test-account settings used when evaluating configured environments.
- `PUBLIC_BASE_URL`: public address used in generated file links.

Never commit real passwords, API keys, authorization headers, cookies, tenant credentials, or internal service URLs.

## Database schema recovery note

The original `init.sql` supplied with the workspace was an empty TODO placeholder. This version reconstructs the application-owned tables and columns from the current Java SQL statements and row mappings. It is a usable development baseline, not a recovered production dump. External platform tables queried by `EnvAgentService` (such as `user`, `ai_bot`, `user_canvas`, and `obj_tenant`) belong to those remote systems and are intentionally not created here.
