-- ISAC-PROJECT 3.4 : 감사 로그 (TB_AUDIT_LOG)
-- 업무 생성·변경·삭제, 댓글·첨부 삭제, 개인정보 열람, 워크스페이스·그룹·사용자 관리, 로그인 기록을 영구 보관한다.
-- 기록은 수정·삭제할 수 없도록 트리거로 막는다. 여러 번 실행해도 결과가 같다.
SET search_path TO teamboard;

CREATE TABLE IF NOT EXISTS tb_audit_log (
    id           bigserial    PRIMARY KEY,
    created_at   timestamp    NOT NULL DEFAULT now(),
    actor_id     bigint,
    actor_name   varchar(100),
    action       varchar(40)  NOT NULL,
    board_id     bigint,
    board_name   varchar(200),
    target_type  varchar(30),
    target_id    bigint,
    target_name  varchar(300),
    detail       text,
    client_ip    varchar(64)
);
COMMENT ON TABLE tb_audit_log IS '감사 로그 (수정·삭제 불가)';

CREATE INDEX IF NOT EXISTS ix_audit_log_created ON tb_audit_log (created_at);
CREATE INDEX IF NOT EXISTS ix_audit_log_actor   ON tb_audit_log (actor_id, created_at);
CREATE INDEX IF NOT EXISTS ix_audit_log_action  ON tb_audit_log (action, created_at);

CREATE OR REPLACE FUNCTION fn_audit_log_readonly() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '감사 로그는 수정하거나 삭제할 수 없습니다.';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS tr_audit_log_readonly ON tb_audit_log;
CREATE TRIGGER tr_audit_log_readonly BEFORE UPDATE OR DELETE ON tb_audit_log
    FOR EACH ROW EXECUTE FUNCTION fn_audit_log_readonly();
