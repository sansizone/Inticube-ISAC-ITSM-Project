-- ISAC-PROJECT 3.5 : 업무 휴지통 (소프트 삭제)
-- 업무를 지우면 행을 지우지 않고 삭제 시각·삭제자·사유만 표시한다. 시스템 관리자가 휴지통에서 복원하거나 영구 삭제한다.
-- (서버 기동 시 SchemaPatcher 도 같은 컬럼을 없을 때만 추가한다) 여러 번 실행해도 결과가 같다.
SET search_path TO teamboard;

ALTER TABLE tb_item ADD COLUMN IF NOT EXISTS deleted_at    timestamp;
ALTER TABLE tb_item ADD COLUMN IF NOT EXISTS deleted_by    bigint;
ALTER TABLE tb_item ADD COLUMN IF NOT EXISTS delete_reason varchar(500);
COMMENT ON COLUMN tb_item.deleted_at    IS '휴지통 이동 시각 (NULL 이면 정상 업무)';
COMMENT ON COLUMN tb_item.deleted_by    IS '삭제한 사용자 ID';
COMMENT ON COLUMN tb_item.delete_reason IS '삭제 사유';

CREATE INDEX IF NOT EXISTS ix_item_deleted ON tb_item (deleted_at) WHERE deleted_at IS NOT NULL;
