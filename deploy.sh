#!/bin/bash
# 一键构建并部署到阿里云 ECS Docker 容器

set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

# ── 配置 ──────────────────────────────────────────
ECS_HOST="root@8.129.129.59"
ECS_PEM="${DEPLOY_PEM:-/Users/huangzhanghong/Downloads/aliyun/cx.pem}"
CONTAINER="fqnovel-unidbg"
REMOTE_DIR="/opt/fqnovel"
JAR_NAME="unidbg-boot-server-0.0.1-SNAPSHOT.jar"
SSH_OPTS="-i $ECS_PEM -o IdentitiesOnly=yes -o StrictHostKeyChecking=no"
# ─────────────────────────────────────────────────

log() { echo "[$(date '+%H:%M:%S')] $*"; }

# 步骤1：本地构建
log "构建 JAR（跳过测试）..."
chmod 600 "$ECS_PEM"

if command -v /usr/libexec/java_home >/dev/null 2>&1; then
  JAVA17=$(/usr/libexec/java_home -v 17 2>/dev/null || true)
  [ -n "$JAVA17" ] && export JAVA_HOME="$JAVA17" && export PATH="$JAVA_HOME/bin:$PATH"
fi
MVN_CMD="./mvnw"
[ ! -x "$MVN_CMD" ] && MVN_CMD="mvn"

$MVN_CMD package -DskipTests -q
log "构建完成：target/$JAR_NAME"

# 步骤2：上传 JAR 到 ECS
log "上传 JAR 到 $ECS_HOST:$REMOTE_DIR ..."
scp $SSH_OPTS "target/$JAR_NAME" "$ECS_HOST:$REMOTE_DIR/$JAR_NAME"
log "上传完成"

# 步骤3：将 JAR 复制进容器 + 重启
log "更新容器内 JAR 并重启..."
ssh $SSH_OPTS "$ECS_HOST" bash <<REMOTE
  set -e
  docker cp $REMOTE_DIR/$JAR_NAME $CONTAINER:/app/app.jar
  docker restart $CONTAINER
  echo "容器已重启，等待启动..."
  sleep 8
  STATUS=\$(docker inspect -f '{{.State.Status}}' $CONTAINER)
  echo "容器状态: \$STATUS"
  if [ "\$STATUS" != "running" ]; then
    echo "容器未正常运行，最近日志："
    docker logs $CONTAINER --tail 30
    exit 1
  fi
REMOTE

# 步骤4：打印最新日志确认启动
log "部署成功！最近启动日志："
ssh $SSH_OPTS "$ECS_HOST" "docker logs $CONTAINER --tail 20 2>&1"
