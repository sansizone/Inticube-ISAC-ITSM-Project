#!/usr/bin/env bash
# TeamBoard 실행 (PostgreSQL). DB 설정: config/application.yml 또는 환경변수 DB_URL/DB_USERNAME/DB_PASSWORD
cd "$(dirname "$0")"
if [ -z "${DB_PASSWORD:-}" ] && { [ ! -f config/application.yml ] || grep -q '\${DB_PASSWORD' config/application.yml; }; then
  read -r -s -p "PostgreSQL 비밀번호: " DB_PASSWORD; echo
  export DB_PASSWORD
fi
exec java -Xms256m -Xmx1g -jar isac-project.jar
