#!/usr/bin/env bash
# 프론트엔드 빌드 → backend static → jar 패키징 → dist/ 에 실행 파일 모음
set -euo pipefail
cd "$(dirname "$0")"
(cd frontend && npm ci && npm run build)
(cd backend && mvn -q clean package -DskipTests)
mkdir -p dist/config
cp backend/target/isac-project.jar dist/
cp deploy/start.bat deploy/start.sh deploy/postgres-init.sql dist/
# 이미 수정한 DB 설정은 덮어쓰지 않음
[ -f dist/config/application.yml ] || cp deploy/config/application.yml dist/config/
echo "완료: dist/isac-project.jar (실행: dist/start.sh)"
