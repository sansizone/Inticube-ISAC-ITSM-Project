-- =====================================================================
-- TeamBoard 용 PostgreSQL 계정·DB 생성 (DBA 가 superuser 로 1회 실행)
-- 비밀번호는 반드시 바꾸세요. PostgreSQL 12 이상 지원(16에서 테스트).
-- =====================================================================

-- [방법 A] 전용 DB 를 만드는 경우 (권장)
CREATE USER teamboard WITH PASSWORD '여기에_강력한_비밀번호';
CREATE DATABASE teamboard OWNER teamboard ENCODING 'UTF8' TEMPLATE template0;
-- → url: jdbc:postgresql://서버:5432/teamboard

-- [방법 B] 기존 DB 안에 스키마만 만드는 경우 (기존 DB 에 접속한 상태에서 실행)
-- CREATE USER teamboard WITH PASSWORD '여기에_강력한_비밀번호';
-- CREATE SCHEMA teamboard AUTHORIZATION teamboard;
-- → url: jdbc:postgresql://서버:5432/기존DB?currentSchema=teamboard

-- 테이블은 TeamBoard 최초 기동 시 자동 생성됩니다(TB_ 로 시작하는 13개).
-- 서버의 pg_hba.conf 에 TeamBoard 서버 IP 의 접속 허용이 필요할 수 있습니다.
