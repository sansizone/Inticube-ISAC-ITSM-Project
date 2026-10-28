#!/usr/bin/env bash
# ISAC-PROJECT 배포 서버 재시작 스크립트 (GitLab CI 의 deploy 단계가 SSH 로 실행)
#
#   사용법: server-restart.sh <설치경로>
#   동작  : isac-project.jar.new 가 있으면 기존 jar 를 backup/ 으로 옮기고 교체
#           → 실행 중인 프로세스 종료(pid 파일 → 포트 기준) → nohup 으로 기동
#           → 60초 안에 HTTP 응답이 없으면 백업 jar 로 되돌리고 실패 종료
#
#   환경변수: APP_PORT(기본 8080), JAVA_OPTS(기본 -Xms256m -Xmx1g)
#   DB 접속 정보는 서버의 config/application.yml 에 직접 적어 둔다 (CI 로 비밀번호를 넘기지 않음)
#   손대지 않는 것: config/application.yml, data/, logs/
set -euo pipefail

APP_DIR="${1:-$(cd "$(dirname "$0")" && pwd)}"
APP_PORT="${APP_PORT:-8080}"
JAVA_OPTS="${JAVA_OPTS:--Xms256m -Xmx1g}"
JAR="$APP_DIR/isac-project.jar"
PID_FILE="$APP_DIR/isac-project.pid"
LOG="$APP_DIR/logs/app.log"
cd "$APP_DIR"
mkdir -p logs backup config data

if [ ! -f config/application.yml ]; then
  echo "!! config/application.yml 이 없습니다. config/application.yml.example 을 복사해 DB 설정을 채운 뒤 다시 배포하세요."
  exit 1
fi

# 1) jar 교체 (새 파일이 올라온 경우만)
if [ -f "$JAR.new" ]; then
  if [ -f "$JAR" ]; then
    cp -p "$JAR" "backup/isac-project-$(date +%Y%m%d-%H%M%S).jar"
    ls -1t backup/isac-project-*.jar 2>/dev/null | tail -n +6 | xargs -r rm -f   # 백업은 최근 5개만
  fi
  mv -f "$JAR.new" "$JAR"
  echo "jar 교체 완료 ($(stat -c %s "$JAR") bytes)"
fi

# 2) 기존 프로세스 종료
stop_app() {
  local pid=""
  [ -f "$PID_FILE" ] && pid="$(cat "$PID_FILE" 2>/dev/null || true)"
  if [ -z "$pid" ] || ! kill -0 "$pid" 2>/dev/null; then
    pid="$(pgrep -f "isac-project.jar" || true)"
  fi
  if [ -n "$pid" ]; then
    echo "기존 프로세스 종료: $pid"
    kill $pid 2>/dev/null || true
    for _ in $(seq 1 30); do kill -0 $pid 2>/dev/null || break; sleep 1; done
    kill -0 $pid 2>/dev/null && { echo "강제 종료"; kill -9 $pid 2>/dev/null || true; sleep 1; }
  fi
  rm -f "$PID_FILE"
}

# 3) 기동 + 확인
start_app() {
  echo "기동: java $JAVA_OPTS -jar $JAR (port $APP_PORT)"
  nohup java $JAVA_OPTS -Dserver.port="$APP_PORT" -jar "$JAR" >> "$LOG" 2>&1 &
  echo $! > "$PID_FILE"
  for i in $(seq 1 60); do
    if curl -sf -o /dev/null "http://127.0.0.1:$APP_PORT/"; then
      echo "기동 확인 (${i}s) — pid $(cat "$PID_FILE")"
      return 0
    fi
    kill -0 "$(cat "$PID_FILE")" 2>/dev/null || { echo "프로세스가 바로 종료됨"; break; }
    sleep 1
  done
  echo "!! 60초 안에 응답이 없습니다. 최근 로그:"; tail -n 40 "$LOG" || true
  return 1
}

stop_app
if ! start_app; then
  last="$(ls -1t backup/isac-project-*.jar 2>/dev/null | head -1 || true)"
  if [ -n "$last" ]; then
    echo "!! 이전 버전으로 되돌립니다: $last"
    stop_app; cp -p "$last" "$JAR"
    start_app && { echo "롤백 기동 성공 — 새 버전 배포는 실패로 처리"; exit 1; }
  fi
  exit 1
fi
