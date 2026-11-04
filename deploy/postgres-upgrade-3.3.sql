-- ISAC-PROJECT 3.3 : 상태 라벨 '진행중' → '처리중' 통일 (데이터만 변경, 테이블 구조 변경 없음)
-- 1) 같은 워크스페이스에 '처리중'이 이미 있으면 '진행중' 업무·자동화 규칙·양식 설정을 '처리중'으로 옮기고 '진행중' 삭제
-- 2) '처리중'이 없는 워크스페이스는 '진행중' 이름만 '처리중'으로 변경
-- 여러 번 실행해도 결과가 같음
SET search_path TO teamboard;

BEGIN;

CREATE TEMP TABLE tmp_status_pair ON COMMIT DROP AS
SELECT o.id AS old_id, n.id AS new_id, o.board_id
  FROM tb_board_status o
  JOIN tb_board_status n ON n.board_id = o.board_id
 WHERE replace(o.label, ' ', '') = '진행중'
   AND replace(n.label, ' ', '') = '처리중';

-- 업무 상태
UPDATE tb_item i SET status_id = p.new_id
  FROM tmp_status_pair p
 WHERE i.status_id = p.old_id;

-- 자동화 규칙 (tb_board.column_config 의 "statusId")
UPDATE tb_board b
   SET column_config = regexp_replace(b.column_config, '"statusId"\s*:\s*' || p.old_id || '([^0-9])', '"statusId":' || p.new_id || '\1', 'g')
  FROM tmp_status_pair p
 WHERE b.id = p.board_id AND b.column_config ~ ('"statusId"\s*:\s*' || p.old_id || '[^0-9]');

-- 양식 보기 설정 (tb_board_view.view_config 의 "statusId")
UPDATE tb_board_view v
   SET view_config = regexp_replace(v.view_config, '"statusId"\s*:\s*' || p.old_id || '([^0-9])', '"statusId":' || p.new_id || '\1', 'g')
  FROM tmp_status_pair p
 WHERE v.board_id = p.board_id AND v.view_config ~ ('"statusId"\s*:\s*' || p.old_id || '[^0-9]');

-- 보기 필터에 저장된 상태 ("status":"<id>")
UPDATE tb_board_view v
   SET view_config = replace(v.view_config, '"status":"' || p.old_id || '"', '"status":"' || p.new_id || '"')
  FROM tmp_status_pair p
 WHERE v.board_id = p.board_id AND v.view_config LIKE '%"status":"' || p.old_id || '"%';

DELETE FROM tb_board_status s USING tmp_status_pair p WHERE s.id = p.old_id;

-- 나머지는 이름만 변경
UPDATE tb_board_status SET label = '처리중' WHERE replace(label, ' ', '') = '진행중';

COMMIT;
