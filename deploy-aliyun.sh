#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

KEY_PATH="${ALIYUN_ECS_KEY_PATH:-/Users/huangzh16/Downloads/cx.pem}"
HOST="${ALIYUN_ECS_HOST:-8.129.129.59}"
USER_NAME="${ALIYUN_ECS_USER:-root}"
KNOWN_HOSTS_FILE="${ALIYUN_ECS_KNOWN_HOSTS_FILE:-/tmp/aliyun-ecs-known_hosts}"
REMOTE_DEPLOY_DIR="${ALIYUN_ECS_DEPLOY_DIR:-/opt/fqnovel-unidbg}"
REMOTE_APP_DIR="${ALIYUN_ECS_APP_DIR:-/opt/fqnovel}"
CONTAINER_NAME="${ALIYUN_ECS_CONTAINER_NAME:-fqnovel-unidbg}"
IMAGE_NAME="${ALIYUN_ECS_IMAGE_NAME:-fqnovel-unidbg:latest}"
LEGACY_SERVICE_NAME="${ALIYUN_ECS_LEGACY_SERVICE_NAME:-fqnovel.service}"
BOOKSOURCE_BASE_URL="${BOOKSOURCE_BASE_URL:-http://8.129.129.59:8899}"
JAR_PATH="$PROJECT_DIR/target/unidbg-boot-server-0.0.1-SNAPSHOT.jar"
MAIN_BOOKSOURCE="$PROJECT_DIR/src/main/resources/legado/fqnovel.json"
BATCH_BOOKSOURCE="$PROJECT_DIR/src/main/resources/static/legado/fqnovel-batch-booksource.json"
REMOTE_TARGET="$USER_NAME@$HOST"

if [[ ! -f "$KEY_PATH" ]]; then
  echo "SSH key not found: $KEY_PATH" >&2
  exit 1
fi

ssh_remote() {
  ssh \
    -i "$KEY_PATH" \
    -o StrictHostKeyChecking=accept-new \
    -o UserKnownHostsFile="$KNOWN_HOSTS_FILE" \
    "$REMOTE_TARGET" \
    "$@"
}

scp_remote() {
  scp \
    -i "$KEY_PATH" \
    -o StrictHostKeyChecking=accept-new \
    -o UserKnownHostsFile="$KNOWN_HOSTS_FILE" \
    "$@"
}

if command -v /usr/libexec/java_home >/dev/null 2>&1; then
  JAVA17_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || true)
  if [[ -n "$JAVA17_HOME" ]]; then
    export JAVA_HOME="$JAVA17_HOME"
    export PATH="$JAVA_HOME/bin:$PATH"
  fi
fi

if [[ -f "./mvnw" && -x "./mvnw" ]]; then
  MVN_CMD="./mvnw"
else
  MVN_CMD="mvn"
fi

if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
  echo "[$(date)] Building application jar..."
  "$MVN_CMD" clean package -DskipTests
fi

if [[ ! -f "$JAR_PATH" ]]; then
  echo "Jar not found: $JAR_PATH" >&2
  exit 1
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

cat > "$TMP_DIR/Dockerfile" <<'EOF'
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY app.jar /app/app.jar
EXPOSE 9999
ENTRYPOINT ["java","-Djava.security.egd=file:/dev/./urandom","-jar","/app/app.jar","--spring.config.additional-location=file:/app/config/application.yml"]
EOF

TEMP_MAIN_BOOKSOURCE="$TMP_DIR/fqnovel.json"
TEMP_BATCH_BOOKSOURCE="$TMP_DIR/fqnovel-batch-booksource.json"

if [[ -f "$MAIN_BOOKSOURCE" ]]; then
  cp "$MAIN_BOOKSOURCE" "$TEMP_MAIN_BOOKSOURCE"
  perl -0pi -e 's#__BOOKSOURCE_BASE_URL__#$ENV{BOOKSOURCE_BASE_URL}#g; s#http://127\.0\.0\.1:9999#$ENV{BOOKSOURCE_BASE_URL}#g; s#http://替换为搭建的IP:9999#$ENV{BOOKSOURCE_BASE_URL}#g; s#https://fq\.huangzh\.cn#$ENV{BOOKSOURCE_BASE_URL}#g' "$TEMP_MAIN_BOOKSOURCE"
fi

if [[ -f "$BATCH_BOOKSOURCE" ]]; then
  cp "$BATCH_BOOKSOURCE" "$TEMP_BATCH_BOOKSOURCE"
  perl -0pi -e 's#__BOOKSOURCE_BASE_URL__#$ENV{BOOKSOURCE_BASE_URL}#g; s#http://127\.0\.0\.1:9999#$ENV{BOOKSOURCE_BASE_URL}#g; s#http://替换为搭建的IP:9999#$ENV{BOOKSOURCE_BASE_URL}#g; s#https://fq\.huangzh\.cn#$ENV{BOOKSOURCE_BASE_URL}#g' "$TEMP_BATCH_BOOKSOURCE"
fi

echo "[$(date)] Preparing remote directories..."
ssh_remote "mkdir -p '$REMOTE_DEPLOY_DIR' '$REMOTE_APP_DIR/legado'"

echo "[$(date)] Uploading deployment files..."
scp_remote "$JAR_PATH" "$REMOTE_TARGET:$REMOTE_DEPLOY_DIR/app.jar"
scp_remote "$TMP_DIR/Dockerfile" "$REMOTE_TARGET:$REMOTE_DEPLOY_DIR/Dockerfile"
if [[ -f "$MAIN_BOOKSOURCE" ]]; then
  scp_remote "$TEMP_MAIN_BOOKSOURCE" "$REMOTE_TARGET:$REMOTE_APP_DIR/legado/fqnovel.json"
  scp_remote "$TEMP_MAIN_BOOKSOURCE" "$REMOTE_TARGET:$REMOTE_APP_DIR/fqnovel_legado.json"
fi
if [[ -f "$BATCH_BOOKSOURCE" ]]; then
  scp_remote "$TEMP_BATCH_BOOKSOURCE" "$REMOTE_TARGET:$REMOTE_APP_DIR/legado/fqnovel-batch-booksource.json"
fi

echo "[$(date)] Replacing remote deployment..."
ssh_remote "set -euo pipefail
systemctl stop '$LEGACY_SERVICE_NAME' >/dev/null 2>&1 || true
systemctl disable '$LEGACY_SERVICE_NAME' >/dev/null 2>&1 || true
systemctl enable --now redis >/dev/null 2>&1 || true
pkill -f '/opt/fqnovel/unidbg-boot-server-0.0.1-SNAPSHOT.jar' >/dev/null 2>&1 || true
docker rm -f '$CONTAINER_NAME' >/dev/null 2>&1 || true
cd '$REMOTE_DEPLOY_DIR'
docker build -t '$IMAGE_NAME' .
docker run -d \
  --name '$CONTAINER_NAME' \
  --restart unless-stopped \
  --network host \
  -v '$REMOTE_APP_DIR/application.yml:/app/config/application.yml:ro' \
  '$IMAGE_NAME' >/dev/null
sleep 8
curl -fsS http://127.0.0.1:9999/api/fq-signature/health >/dev/null
"

echo
echo "Deployment complete."
echo "Health URL: http://$HOST:8899/api/fq-signature/health"
echo "Booksource URL: http://$HOST:8899/fqnovel_legado.json"
echo "Batch booksource URL: http://$HOST:8899/legado/fqnovel-batch-booksource.json"
