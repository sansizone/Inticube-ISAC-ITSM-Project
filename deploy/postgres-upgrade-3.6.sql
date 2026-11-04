-- ISAC-PROJECT 3.6 : 증적 보존 — 댓글·첨부·시간 기록 논리 삭제, 댓글 수정 이력, 첨부 해시
-- (서버 기동 시 SchemaPatcher 도 같은 변경을 없을 때만 적용한다) 여러 번 실행해도 결과가 같다.
SET search_path TO teamboard;

ALTER TABLE tb_item_update ADD COLUMN IF NOT EXISTS deleted_at timestamp;
ALTER TABLE tb_item_update ADD COLUMN IF NOT EXISTS deleted_by bigint;
ALTER TABLE tb_attachment  ADD COLUMN IF NOT EXISTS deleted_at timestamp;
ALTER TABLE tb_attachment  ADD COLUMN IF NOT EXISTS deleted_by bigint;
ALTER TABLE tb_attachment  ADD COLUMN IF NOT EXISTS file_hash  varchar(64);
ALTER TABLE tb_time_entry  ADD COLUMN IF NOT EXISTS deleted_at timestamp;
ALTER TABLE tb_time_entry  ADD COLUMN IF NOT EXISTS deleted_by bigint;

-- 댓글 수정 전 내용 보존
CREATE TABLE IF NOT EXISTS tb_item_update_rev (
    id         bigserial PRIMARY KEY,
    update_id  bigint    NOT NULL,
    content    text,
    edited_by  bigint,
    edited_at  timestamp NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_item_update_rev ON tb_item_update_rev (update_id);
CREATE INDEX IF NOT EXISTS ix_attachment_hash ON tb_attachment (file_hash);
