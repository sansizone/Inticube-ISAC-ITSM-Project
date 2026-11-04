# ISAC-PROJECT — AI 작업 규칙

사내 설치형 업무 협업 보드. Spring Boot 3.3 / Java 17 / MyBatis(XML) / PostgreSQL(스키마 `teamboard`) + React 18 / Vite.
새 기능·수정 작업은 아래 규칙을 **예외 없이** 지킨다. 규칙과 요청이 부딪히면 구현하지 말고 먼저 사람에게 묻는다.

## 반드시 지킬 규칙

1. **모든 변경 API는 조회 권한이 아니라 그 동작의 권한을 검사한다.**
   - `AccessGuard` 를 쓴다. `getBoardChecked`·`getItemChecked` 는 "볼 수 있는가"만 확인하므로 변경 API 의 권한 검사로 충분하지 않다.
   - 설정·멤버·컬럼처럼 관리 동작이면 `setManageChecked`, 업무를 없애는 동작(삭제·이동·그룹 삭제)이면 `setRemoveItemChecked`, 시스템 관리 기능이면 `/api/admin/**` 아래에 둔다.
   - 본인 것만 고칠 수 있는 동작(댓글·시간 기록 등)은 서비스에서 작성자와 `me.id()` 를 비교한다.
   - 컨트롤러에서 guard 를 부르지 않는다면 `backend/src/test/resources/permission-allowlist.txt` 에 "어디서 검사하는지"를 적는다. 근거 없이 목록에 넣지 않는다 (`PermissionCoverageTest` 가 검사).
2. **모든 데이터 변경은 감사 로그에 남긴다.**
   - 업무 변경은 `ItemService.insertSystemLog`/`insertChangeLog` 를 거친다. 이 경로가 업무 활동 기록과 감사 로그(`AuditService`) 둘 다에 남긴다.
   - 워크스페이스·사용자·그룹 등은 `audit.record(액션, ...)` 를 직접 부르고, 새 액션 코드는 `AuditService.LABELS` 에 한글 이름을 추가한다.
   - 이력을 합치거나, 건너뛰거나, 지우지 않는다. 감사 로그 테이블은 DB 트리거로 수정·삭제가 막혀 있다.
3. **업무는 영구 삭제하지 않는다.**
   - 삭제는 `deleted_at` 으로 표시만 하고(휴지통), 사유를 받는다.
   - 댓글·첨부·시간 기록도 논리 삭제한다. 댓글은 수정 전 내용을 `tb_item_update_rev` 에 보존한다.
   - 목록·집계 쿼리에는 `deleted_at IS NULL` 을 넣는다.
   - 현재 예외: 휴지통의 관리자 영구 삭제, 워크스페이스 삭제. 둘 다 정책 결정 대기 중이니 새로 늘리지 않는다.
4. **날짜·상태는 서버가 검증한다.** 화면 검사는 보조일 뿐이다.
   - 날짜 형식·범위(미래 금지 등), 상태 전환 조건, 자동 계산 값(처리완료일·등록 지연·장애등급·작업시간)은 서버에서 정하고 검사한다.
   - 클라이언트가 보낸 값을 그대로 믿지 않는다. SR 통제는 `SrPolicyService`.

## 작업 순서

1. **테스트부터 만든다.** 버그·점검 항목은 재현 절차를 `backend/src/test/java/.../api/*Test.java` 의 API 테스트로 옮기고, 실패하는 것을 먼저 확인한다. 그다음 고쳐서 통과시킨다.
   - 공통 기반은 `ApiTestBase`(서버를 실제로 띄움, `user()`·`board()`·`item()` 픽스처)와 `Api`(로그인·CSRF 처리 HTTP 클라이언트).
2. **한 MR 에는 한 항목만** 담는다. MR 설명에는 완료 기준과 테스트 결과를 붙인다(`.gitlab/merge_request_templates/Default.md`).
3. **기능을 바꾸면 README 도 같이 고친다.** 대상: 주요 기능 표, 권한 표, 아직 없는 기능, 검증 범위, 버전.
4. **DB 변경**은 두 곳에 같이 반영한다: `deploy/postgres-upgrade-<버전>.sql`(여러 번 실행해도 결과가 같게) + `config/SchemaPatcher`(기동 시 자동 적용). 운영 DB 에 직접 실행하지 않는다.
5. `ItemService`·`AccessGuard` 처럼 넓게 바뀌는 공통 기반은 한 사람이 끝낸 뒤 다음 사람이 이어 받는다.

## 명령

- 테스트: `cd backend && mvn test` (내장 PostgreSQL 자동 기동, root 계정 불가. 기존 DB 를 쓰려면 `TEST_DB_URL`/`TEST_DB_USER`/`TEST_DB_PASSWORD`)
- 전체 빌드 + 테스트(CI 와 같음): `cd frontend && npm run build && cd ../backend && mvn clean package`
- 빠른 빌드(테스트 생략): `./build.sh`

## 하지 말 것

- 비밀번호·키·운영 DB 접속 정보를 코드·로그·커밋에 넣지 않는다.
- 전화번호 등 개인정보는 응답에서 마스킹한다(`PhoneFormat.mask`). 원문 열람은 관리자 + 사유 + 기록.
- 업무 행 전체를 오래된 객체로 다시 저장(`itemDao.update`)하지 않는다. 같은 업무를 고치는 요청은 `itemDao.lockById` 로 잠근 뒤 최신 값을 읽어 처리한다. 부속 작업은 `updateTouch` 를 쓴다.
