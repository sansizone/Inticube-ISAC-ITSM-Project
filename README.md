# 인티큐브 ISAC-PROJECT — 사내 설치형 업무 협업 보드 (v3.0.0)

monday.com 대체용으로 만든 사내 설치형 협업 보드입니다. 데이터와 첨부파일이 모두 사내 서버에 저장됩니다.

- **백엔드**: Spring Boot 3.3 / Java 17+ / Spring Security(세션) / MyBatis(XML 매퍼) / PostgreSQL
- **프론트엔드**: React 18 + Vite (외부 CDN 없음, 폐쇄망에서도 동작)
- **DB**: PostgreSQL
- **배포**: 실행 파일 하나 (`isac-project.jar`)

## 주요 기능

| 영역 | 내용 |
|---|---|
| 홈 | 내 업무(지연·이번 주·그 외), 공지사항, 최근 게시글, 워크스페이스 진행률 |
| 워크스페이스 | 생성/수정/삭제, 공개 범위·멤버, 그룹(색상·순서), 상태 라벨 편집(색상, 완료 여부), 보기 탭, 컬럼 편집, 연결 컬럼, 업무 이동 |
| 목록 뷰 | 업무 번호(#id)·상태 아이콘·우선순위 아이콘·마감일·담당자를 행에서 바로 편집, 행 드래그로 순서/그룹 이동 |
| 칸반 뷰 | 상태별 컬럼, 카드를 끌어서 상태 변경, 컬럼에서 바로 업무 추가 |
| 타임라인(간트) | 일/주 단위, 막대 드래그로 일정 이동, 양 끝 드래그로 기간 조정, 날짜 없는 업무는 클릭해서 일정 지정 |
| **담당업무** | 워크스페이스별 담당업무 트리(단계 제한 없음, 정/부 담당자). 업무에 지정하면 담당자가 비어 있을 때 정 담당자(없으면 상위의 정 담당자) 자동 배정. 담당업무 필터(하위 포함), 인사이트 '담당업무별 업무' |
| **엑셀** | 보드 화면 > 엑셀: 빈 양식 내려받기 / 현재 업무 내보내기 / 가져오기(미리보기 → 저장). 아래 '엑셀로 업무 일괄 등록' 참고 |
| 인사이트 | 보드별 완료율·상태별·담당자별·그룹별 진행률·마감 임박/지연 |
| 업무 상세 | 마크다운 설명, 댓글(`@아이디` 멘션), 파일 첨부(드래그 업로드, 50MB), 활동 기록(상태·담당자·마감일 변경 자동 기록) |
| **게시판** | 게시판(카테고리) 여러 개 — 기본: 공지사항(관리자 전용)·자유게시판·자료실. 마크다운 글쓰기(툴바·미리보기), 첨부파일, 댓글·답글, 상단 고정, 조회수, 제목·본문·작성자 검색, 페이지, 이전/다음 글 |
| 알림함 | 담당 지정, 댓글, 멘션, 완료 처리, 마감 임박(매일 08:30), 새 공지, 게시글 댓글·답글. 사이드바 배지 30초마다 갱신 |
| 검색 | `Ctrl+K` 명령 팔레트 — 업무·게시글·워크스페이스·게시판 검색 및 이동 |
| 관리자 설정 | 사용자(권한, 임시 비밀번호 발급, 비활성화, 비밀번호 초기화) / 게시판(추가·수정·순서·삭제, 관리자 전용 여부) |

화면은 관리자 대시보드형 레이아웃입니다 — 상단 고정 바(검색·알림 센터·사용자 메뉴), 라이트 사이드 메뉴(접기 가능), 페이지 제목 영역 + 카드, 왼쪽 컬러 보더 KPI 카드. 서체는 Pretendard(OFL, jar 에 포함되어 폐쇄망에서도 동일하게 표시). OS 설정에 따라 다크 모드가 자동 적용됩니다. 화면 폭이 1600px 이하(노트북)는 기본 크기, 그보다 넓으면 폭에 비례해 전체 UI가 커지고(QHD 약 1.12배, 4K 약 1.28배) 본문은 화면 전체 폭을 사용합니다.

## 엑셀로 업무 일괄 등록

1. 보드 화면 오른쪽 위 **엑셀 > 빈 양식 내려받기** (기존 업무를 고치려면 **현재 업무 내보내기**)
2. `업무` 시트에 한 줄에 업무 하나씩 작성 — 그룹·상태·담당자·우선순위는 드롭다운에서 선택, `작성 안내` 시트에 규칙 정리
3. **엑셀 > 엑셀 가져오기**로 올리면 먼저 미리보기(생성/수정/변경 없음/새 그룹/오류)를 보여 주고, 확인 후 저장

| 규칙 | 내용 |
|---|---|
| 번호 | 비어 있으면 새 업무 생성, 있으면 그 업무 수정 (다른 보드 번호는 오류) |
| 담당업무 | `개발 > 백엔드 > 인증` 경로(드롭다운) 또는 겹치지 않는 마지막 이름. 없는 담당업무는 오류(먼저 담당업무 탭에서 추가). 새 업무의 담당자가 비어 있으면 정 담당자 자동 지정 |
| 그룹 | 없는 이름이면 새 그룹 생성. 비우면 새 업무는 첫 그룹, 기존 업무는 그룹 유지 |
| 상태 | 보드의 상태 이름. 새 업무에서 비우면 첫 번째 상태 |
| 담당자 | `이름 (아이디)`, 아이디, 또는 (동명이인이 없으면) 이름 |
| 날짜 | 엑셀 날짜, `2026-11-05`, `2026.11.05`, `2026/11/05`, `20261105` |
| 빈 칸 | 기존 업무 행에서 비우면 값이 지워짐(그룹 제외). 엑셀에 없는 업무는 삭제되지 않음 |
| 오류 | 한 행이라도 오류면 **아무것도 저장하지 않음** (행·열·사유 표시) |
| 한도 | .xlsx, 10MB, 3,000행 |

가져온 업무에는 `엑셀 가져오기로 생성` 활동 기록이 남고, 수정은 일반 수정과 같이 변경 이력·담당자 알림이 남습니다.

## 엑셀로 워크스페이스 만들기 (원본 값 그대로)

사이드바 **워크스페이스 → 업로드 아이콘** (또는 `#/import/excel`).

1. 기존 업무 도구(monday.com 등)에서 보드를 **Excel 로 내보내기** (업데이트·하위 아이템 포함 선택)
2. 받은 .xlsx 를 올리면 미리보기에서 열 매핑, 상태, 사람 연결(기존 계정 연결 / 비활성 계정 새로 만들기 / 이름만 보존)을 확인
3. **새 워크스페이스로 가져오기** → 엑셀 파일 1개 = 워크스페이스 1개 (3,500건 + 업데이트 7,000건 기준 약 20초)

| monday.com | TeamBoard |
|---|---|
| 그룹 | 그룹 (순서 유지) |
| 아이템 / Name | 업무 / 업무명 |
| 상태 열 | 상태 (완료·진행중·막힘·대기와 같은 이름만 기본 상태로, 나머지 라벨은 원래 이름 그대로 새 상태) |
| 담당자 열 (예: 처리담당자) | 담당자 = 첫 번째 연결된 사람 + 원래 값(여러 명)은 사람 컬럼으로 보존 |
| 납기/마감 열 | 마감일 |
| 등록일 | 업무 등록일시 (+ 시작일, 타임라인 표시용) |
| 요청 내용 | 업무 설명 |
| 그 밖의 모든 열 | **워크스페이스 사용자 정의 컬럼** (텍스트·긴 텍스트·숫자·날짜·날짜+시간·사람·드롭다운·링크 자동 판별, 미리보기에서 변경 가능) |
| 하위 아이템 | 상위 업무 아래 **하위 업무** + 하위 업무 전용 컬럼 |
| Item ID | 업무의 외부 원본 ID 로 보관 (업데이트 연결용) |
| 업데이트 / 답글 | 댓글 / 원 댓글 아래 답글. 원래 작성자 이름·작성일시(UTC → 한국 시간) 보존 |

- 사용자 정의 컬럼은 보드 설정 › 컬럼에서 추가·이름 변경·유형 변경·순서·목록 표시·삭제할 수 있고, 업무 상세 '필드'에서 값을 수정합니다.
- **표** 보기에서 모든 컬럼을 한눈에 보고, 툴바의 **필드** 필터와 정렬에서 컬럼 값으로 거르고 정렬할 수 있습니다.
- 새로 만든 계정은 비활성(로그인 불가, 아이디 `m0001` 형식)입니다. 사용자 관리에서 아이디 변경 → 계정 사용 → 비밀번호 초기화.
- 먼데이 엑셀에는 첨부파일 자체가 없어 업데이트의 첨부파일은 옮겨지지 않습니다(본문은 이관). 엑셀 셀 한 칸은 최대 32,767자라 그보다 긴 업데이트는 먼데이가 내보낼 때 잘려 있을 수 있습니다.
- 가져오는 동안 알림은 보내지 않습니다.
- 엑셀 내보내기에는 사용자 정의 컬럼과 '상위 번호'가 함께 나갑니다(엑셀 가져오기는 기본 열만 반영).

## 워크스페이스 (v2.0)

monday.com 처럼 **워크스페이스(= 예전 '프로젝트 보드') › 그룹 › 업무 › 하위 업무** 구조입니다. 예: `고객사관리`, `요청사항`.

- **공개 범위**: 워크스페이스마다 '멤버만' 또는 '모든 사용자'. 멤버만이면 멤버가 아닌 사람에게는 사이드바·업무·댓글·첨부·검색·대시보드에 나오지 않고 API 도 403 입니다. 시스템 관리자는 전체를 봅니다.
- **멤버·관리자**: 만든 사람이 관리자(OWNER). 관리자는 설정(이름·상태)·컬럼·멤버를 바꿀 수 있습니다. 설정 › 멤버·공개.
- **보기 탭**: 목록·표·칸반·타임라인·인사이트·담당업무 보기를 탭으로 원하는 만큼 추가(＋), 이름 변경(더블클릭/▾), 복제, 순서 이동, 삭제.
  탭마다 필터·정렬·표시 컬럼을 저장합니다(바뀌면 '보기에 저장').
- **컬럼 편집**: 설정 › 컬럼에서 기본 컬럼(상태·담당자·마감일·시작일·우선순위·담당업무·그룹·등록일·수정일)과 사용자 정의 컬럼을 한 목록으로
  이름 변경·숨김·순서 변경. 표 보기 열 순서와 머리글이 그대로 따라갑니다.
- **연결 컬럼(RELATION)**: 다른 워크스페이스의 업무를 값으로 고릅니다. 예) `요청사항.고객사` → `고객사관리`, 표시 컬럼 `고객사명`.
  업무 상세에서 검색해 선택(여러 개 가능), 칩을 누르면 그 업무가 열립니다. 연결된 쪽(고객사) 상세에는 '이 업무를 연결한 항목'이 나옵니다.
- **업무 이동**: 업무 ⋯ 메뉴 또는 상세 상단 이동 아이콘 → 다른 워크스페이스로. 하위 업무·댓글·첨부는 함께,
  상태·담당업무·컬럼 값은 **같은 이름이 있으면 유지**, 없으면 비웁니다(이동 전 미리 보여 줌).

## 유지보수 기능 (v3.0) — Jira · Redmine · OpenProject 에서 가져온 것들

| 기능 | 설명 |
|---|---|
| **업무 키** | 워크스페이스마다 접두어(예: `REQ`)를 두고 업무에 `REQ-12` 같은 키를 붙입니다. 검색창·Ctrl+K 에서 키로 바로 찾고, 상세에서 키를 눌러 복사. 설정 › 기본에서 접두어 변경 (워크스페이스 간 중복 불가) |
| **업무 유형 + SLA** | 요청·장애·변경·문의·정기작업(기본 5개, 설정 › 유형·SLA 에서 편집). 유형마다 **응답 목표**(첫 상태 변경·담당자 지정·등록자 외 댓글)·**처리 목표**(완료 상태) 시간을 두면 업무에 SLA 배지(남은 시간/초과/준수)가 붙고, 처리 목표 2시간 전·초과 시 담당자에게 알림(30분마다 점검). 영업시간(평일 09~18시) 기준 계산 옵션 |
| **업무 관계** | 차단함/차단됨 · 관련 · 중복 · 원인. 다른 워크스페이스 업무와도 연결. 미완료 업무에 막힌 업무는 목록·표에 ⚠ 표시, '차단됨' 필터 |
| **감시자** | 상세 상단 '감시'. 만든 사람·담당자·댓글 단 사람은 자동 감시. 상태·담당자·댓글 변경을 알림으로 받음. '감시 중' 필터 |
| **시간 기록** | 예상 시간, 작업한 시간 기록(날짜·내용). 업무·담당자·버전별 소요 집계, 인사이트의 예상 대비 소요 |
| **진척률** | 0~100% (완료 처리 시 100%) |
| **버전·마일스톤 / 로드맵** | 예: `2026.11 정기 패치`. 로드맵 보기에서 버전별 진행률·예상/소요·D-day, 닫기/다시 열기 |
| **위키** | 워크스페이스별 문서 트리(마크다운). 운영 절차, 장애 대응 매뉴얼, 고객사 환경 정보 등 |
| **반복 업무** | 매일/매주/2주/매월/분기/반기/매년. 완료 처리하면 다음 회차를 자동 생성(유형·담당자·예상시간·컬럼 값 복사) |
| **인사이트 확장** | SLA 초과·준수율·평균 응답/처리 시간, 월별 접수·처리 추이(12개월), 유형별 업무, SLA 확인 필요 목록, 예상 대비 소요 |
| **엑셀 내보내기** | 업무 키·유형·버전 열 추가 |

표의 기본 컬럼에 유형·SLA·버전·진척률·예상/소요 시간·처리 완료가 추가되었습니다(설정 › 컬럼에서 표시/숨김).

## 가져온 데이터 정리

- **텍스트 → 연결 컬럼 변환**: 설정 › 컬럼에서 텍스트·드롭다운 컬럼 옆 링크 아이콘. 대상 워크스페이스(예: 고객사관리)와 표시 컬럼을 고르면
  값을 이름으로 맞춰 연결합니다(대소문자·공백 무시, 쉼표 구분 값은 나눠서). 미리보기로 찾은/못 찾은 값을 확인하고,
  못 찾은 값은 대상에 새 항목으로 추가하거나 '(연결 안 된 값)' 숨김 컬럼에 보존합니다. 되돌리기는 없으니 엑셀 내보내기로 먼저 백업하세요.
- **비활성 계정 일괄 활성화**: 사용자 관리 › '비활성' 필터 › 체크 › '선택한 N명 활성화'에서 아이디·부서를 정하면
  한 번에 활성화하고 임시 비밀번호를 발급합니다('전체 복사'로 이름·아이디·임시 비밀번호를 엑셀에 붙여넣기 가능).

## 2단계 인증 (Google Authenticator 등 OTP 앱)

- 사용자 메뉴(오른쪽 위 아바타) › **2단계 인증 › 켜기**: QR 코드를 Google Authenticator·Microsoft Authenticator·Authy 등으로 찍고 6자리 코드를 넣으면 켜집니다.
  이때 **복구 코드 10개**를 한 번만 보여 주니 저장해 두세요(휴대폰 분실 시 사용, 코드당 1회).
- 로그인은 비밀번호 → 인증 코드 2단계가 됩니다. 코드 5회 실패 시 10분 잠김. OTP 는 서버 시간 기준 ±30초를 허용하므로 서버 시각을 NTP 로 맞춰 두세요.
- 끄기: 2단계 인증 메뉴에서 비밀번호 + 인증 코드 입력. 휴대폰을 잃어버렸고 복구 코드도 없으면 **관리자가 사용자 관리 › 2단계 초기화**.
- 강제 정책: `TEAMBOARD_MFA_REQUIRED=true`(전체) 또는 `TEAMBOARD_MFA_REQUIRED_FOR_ADMIN=true`(관리자만). 켜면 설정 전까지 다른 화면을 쓸 수 없고, 정책 대상자는 스스로 끌 수 없습니다.
- 표준 TOTP(RFC 6238, SHA1·6자리·30초)라 외부 서비스 연동 없이 사내에서만 동작합니다. 비밀키는 DB 의 `TB_APP_USER.TOTP_SECRET` 에 있으니 DB 접근 권한을 관리하세요.

## 관리자 계정 잠금·비밀번호 분실

- 로그인 5회 실패 시 그 아이디가 **10분** 잠깁니다(서버 메모리 기준). 10분 기다리거나 **서버를 재시작**하면 바로 풀립니다.
- admin 비밀번호를 잊었으면 환경변수 `TEAMBOARD_RESET_ADMIN_PASSWORD=새비밀번호` 를 넣고 한 번 기동 → 그 비밀번호로 로그인하면 변경 화면이 나옵니다.
  변경 후 **환경변수를 지우고 다시 기동**하세요(남겨 두면 기동할 때마다 다시 초기화됩니다).

## v3.1 (MyBatis 전환) — DB 스키마는 SQL 스크립트로 관리

v3.1 부터 JPA 대신 **MyBatis** 를 쓰며, 기동 시 테이블·컬럼을 **자동으로 만들지 않습니다**.

- **새로 설치**: `deploy/postgres-teamboard-schema.sql` 을 먼저 실행(테이블 24개)한 뒤 기동.
- **기존 DB 업그레이드**: 아직 적용하지 않은 `deploy/postgres-upgrade-<버전>.sql` 을 순서대로(1.8 → 1.9 → 2.0 → 3.0 → 3.1) 실행한 뒤 jar 교체. 3.1 은 2단계 인증 컬럼(누락 대비)과 워크스페이스 즐겨찾기 테이블(`TB_BOARD_FAVORITE`)을 추가합니다. 테이블·컬럼 이름은 그대로라 데이터는 그대로 씁니다.
- 기동 시 데이터 이관(공개/멤버 설정, 키 접두어·업무 번호, 기본 유형, 보기 탭)은 예전처럼 자동으로 합니다.
- DAO 는 `dao/*.java`(`@Mapper`) + `resources/mapper/*.xml` 이며, 메서드 이름은 DAO `select/insert/update/delete/batch*`, 서비스 `get/set/insert/update/delete/batch*` 로 통일했습니다.

## v2.0 → v3.0 업그레이드

`deploy/postgres-upgrade-3.0.sql` 을 실행해 테이블 6개(`TB_BOARD_TYPE`, `TB_BOARD_VERSION`, `TB_ITEM_LINK`, `TB_ITEM_WATCHER`, `TB_TIME_ENTRY`, `TB_WIKI_PAGE`)와
`TB_BOARD`·`TB_ITEM` 컬럼을 추가한 뒤 jar 를 교체하면 자동 이관합니다: 워크스페이스마다 키 접두어 생성(이름의 영문·숫자, 없으면 `TB+번호`) + 기존 업무에 번호 부여,
기본 유형 5개 생성(기존 업무는 '유형 없음'), 로드맵·위키 탭 추가.

## v1.9 → v2.0 업그레이드

`deploy/postgres-upgrade-2.0.sql` 로 `TB_BOARD_MEMBER`, `TB_BOARD_VIEW`, `TB_BOARD.IS_PUBLIC/COLUMN_CONFIG`, `TB_BOARD_COLUMN.REF_BOARD_ID/REF_COLUMN_ID` 를 추가한 뒤 jar 교체 후 재시작하면 자동 이관합니다.

- v1.9 '메인 워크스페이스'(기본)에 있던 보드 → **모든 사용자 공개** (지금처럼 모두에게 보임)
- 그 밖의 v1.9 워크스페이스에 있던 보드 → **멤버만**, 그 워크스페이스 멤버가 그대로 보드 멤버가 됨
- 만든 사람은 관리자, 보드마다 기본 보기 탭 6개 생성
- 구 테이블(`TB_WORKSPACE`, `TB_WORKSPACE_MEMBER`)은 남겨 두며 더 이상 쓰지 않습니다.

DB 계정에 DDL 권한이 없으면 `deploy/postgres-upgrade-2.0.sql` 을 DBA 가 먼저 실행하세요.

## v1.8 → v1.9 업그레이드

jar 교체 후 재시작하면 `TB_WORKSPACE`, `TB_WORKSPACE_MEMBER`, `TB_BOARD.WORKSPACE_ID` 가 추가되고,
'메인 워크스페이스'를 만들어 **기존 사용자 전원을 멤버로 넣고 기존 프로젝트를 모두 그 안으로** 옮깁니다(지금처럼 모두에게 보임).
DB 계정에 DDL 권한이 없으면 `deploy/postgres-upgrade-1.9.sql` 을 DBA 가 먼저 실행하세요.

## v1.7 → v1.8 업그레이드

jar 교체 후 재시작하면 새 테이블(`TB_BOARD_COLUMN`, `TB_ITEM_VALUE`)과 컬럼(업무 `PARENT_ID`·`EXTERNAL_ID`, 댓글 `PARENT_ID`·`AUTHOR_NAME`·`EXTERNAL_ID`)이 자동 추가됩니다.
DB 계정에 DDL 권한이 없으면 `deploy/postgres-upgrade-1.8.sql` 을 DBA 가 먼저 실행하세요.

## v1.0 → v1.1 업그레이드

jar 만 교체하고 재시작하면 됩니다. 기동 시 새 테이블(게시판 4개)과 컬럼(업무 설명, 알림의 게시글 ID)이 자동 추가되고,
v1.0 이 만든 열거형 CHECK 제약(새 알림 유형 저장을 막음)을 `SchemaPatcher` 가 찾아서 제거합니다(로그: `구버전 CHECK 제약 제거`).
Oracle 은 12.2 이상에서 자동 제거됩니다. 12.1 이하이거나 `DDL_AUTO=validate` 로 운영 중이면 아래를 DBA가 직접 실행하세요.

```sql
-- Oracle: TB_NOTIFICATION.NOTI_TYPE, TB_ITEM.PRIORITY, TB_APP_USER.USER_ROLE 의 IN(...) CHECK 제약 이름 확인 후 DROP
SELECT c.constraint_name, cc.table_name, cc.column_name FROM user_constraints c
  JOIN user_cons_columns cc ON c.constraint_name = cc.constraint_name
 WHERE c.constraint_type = 'C' AND cc.table_name IN ('TB_NOTIFICATION','TB_ITEM','TB_APP_USER');
ALTER TABLE TB_NOTIFICATION DROP CONSTRAINT <위에서 확인한 이름>;
```

## 실행

```bash
DB_PASSWORD='비밀번호' java -jar isac-project.jar
```

- PostgreSQL 전용입니다(H2 제거). 접속 기본값은 jar 안의 `application.yml` 에 있고, 환경변수 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `DB_SCHEMA` 또는 실행 폴더의 `config/application.yml` 로 덮어씁니다.
- IntelliJ: Run Configuration 의 Environment variables 에 `DB_PASSWORD=...` 만 넣으면 됩니다(프로파일 지정 불필요).

브라우저에서 `http://서버IP:8080` 접속 → `admin` / `admin1234!` 로 로그인 → **바로 비밀번호 변경 화면**이 나옵니다.

- 초기 관리자 비밀번호는 환경변수로 바꿀 수 있습니다: `TEAMBOARD_ADMIN_PASSWORD='...' java -jar isac-project.jar` (DB에 사용자가 하나도 없을 때만 적용)
- 실행한 폴더 아래에 `data/uploads`(첨부파일), `logs/`가 만들어집니다.

## 운영 DB 연결 (PostgreSQL)

`dist/` 폴더 구성:

```
dist/
├─ isac-project.jar
├─ start.bat / start.sh              ← PostgreSQL 로 실행 (비밀번호는 실행 시 입력)
├─ postgres-init.sql                  ← DBA 용 계정·DB 생성 SQL
└─ config/application.yml            ← DB 주소·계정 (필요 시 수정)
```

1. DBA 에게 `postgres-init.sql` 실행 요청(전용 DB 방식 권장, 기존 DB 에 스키마만 만드는 방식도 포함). PostgreSQL 12 이상.
2. `config/application.yml` 의 `url`, `username` 확인/수정 — 예: `jdbc:postgresql://10.10.1.20:5432/teamboard`
3. `start.bat`(Windows) 또는 `./start.sh`(Linux) 실행 → 비밀번호 입력(화면에 표시되지 않음)
4. `postgres-teamboard-schema.sql` 로 테이블(TB_*)을 만듭니다(자동 생성되지 않음). 로그에 `DB 연결 확인: PostgreSQL 16.x / jdbc:postgresql://… / 사용자 teamboard` 가 나오면 성공.

- 서비스로 띄울 때는 비밀번호를 환경변수 `DB_PASSWORD` 로 넘기세요(아래 systemd 예시의 `teamboard.env`).
- 기존 DB 안의 별도 스키마를 쓰려면 url 끝에 `?currentSchema=teamboard` 를 붙이세요. 테이블은 `deploy/postgres-teamboard-schema.sql` 로 만듭니다(스크립트의 스키마 이름을 맞춰 수정).
- 접속이 안 되면: DB 서버 방화벽 5432 포트, `pg_hba.conf` 의 TeamBoard 서버 IP 허용 여부를 확인하세요.

### Oracle

v3.1(MyBatis 전환)부터 SQL 이 PostgreSQL 전용(`fetch first`, `boolean`, `text` 등)이라 **Oracle 은 지원하지 않습니다.**
Oracle 이 꼭 필요하면 `resources/mapper/*.xml` 과 `deploy/postgres-teamboard-schema.sql` 을 Oracle 문법으로 옮겨야 합니다.

## 서버 등록 (Linux systemd 예시)

`/etc/systemd/system/teamboard.service`

```ini
[Unit]
Description=TeamBoard
After=network.target

[Service]
User=teamboard
WorkingDirectory=/opt/teamboard
EnvironmentFile=/opt/teamboard/teamboard.env
ExecStart=/usr/bin/java -Xms256m -Xmx1g -jar /opt/teamboard/isac-project.jar --spring.profiles.active=oracle
SuccessExitStatus=143
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

`teamboard.env`에 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`를 넣고 권한을 `600`으로 설정하세요.

```bash
sudo systemctl daemon-reload && sudo systemctl enable --now teamboard
```

## 설정값 (application.yml / 실행 인자로 덮어쓰기)

| 키 | 기본값 | 설명 |
|---|---|---|
| `server.port` | 8080 | 포트 |
| `server.servlet.session.timeout` | 60m | 세션 만료 시간 |
| `teamboard.upload-dir` | ./data/uploads | 첨부파일 저장 경로 (백업 대상) |
| `spring.servlet.multipart.max-file-size` | 50MB | 파일 크기 제한 |
| `teamboard.denied-extensions` | jsp, exe, sh 등 | 업로드 금지 확장자 |
| `teamboard.max-login-failures` / `lock-minutes` | 5 / 10 | 로그인 실패 잠금 |
| `teamboard.due-reminder-cron` | `0 30 8 * * *` | 마감 임박 알림 시각 |
| 환경변수 `TEAMBOARD_TZ` | Asia/Seoul | 업무 시간대 |

예: `java -jar isac-project.jar --server.port=9090 --teamboard.upload-dir=/data/teamboard/files`

## 보안 사항

- 세션 쿠키(HttpOnly, SameSite) + CSRF 토큰(모든 변경 요청), 로그인 시 세션 ID 교체
- BCrypt 비밀번호 해시, 비밀번호 정책(8~64자, 영문·숫자·특수문자, 아이디 포함 금지)
- 최초/초기화 비밀번호는 변경 전까지 다른 API 사용 불가
- 로그인 5회 실패 시 10분 잠금 (서버 메모리 기준 — 다중 서버 구성 시 서버별로 계산됨)
- 첨부파일은 UUID 이름으로 저장, 다운로드는 항상 `attachment` + `nosniff`
- CSP, X-Frame-Options(DENY) 등 보안 헤더 적용
- 운영 시 앞단에 Apache/Nginx로 **HTTPS**를 적용하세요. HTTPS 적용 후 `server.servlet.session.cookie.secure=true` 를 추가하면 쿠키가 HTTPS 에서만 전송됩니다.

> 알아둘 점: 관리자가 계정을 비활성화해도 이미 로그인된 세션은 세션 만료(기본 60분)까지 유지됩니다. 즉시 차단이 필요하면 서버를 재시작하거나 세션 시간을 줄이세요.

### Apache(mod_proxy) 앞단 예시

```apache
ProxyPreserveHost On
ProxyPass        / http://127.0.0.1:8080/
ProxyPassReverse / http://127.0.0.1:8080/
RequestHeader set X-Forwarded-Proto "https"
```

`--server.forward-headers-strategy=framework` 옵션을 함께 주세요.

## 권한

| 동작 | 일반 사용자 | 보드 소유자 | 관리자 |
|---|---|---|---|
| 모든 보드 조회, 업무·그룹 추가/수정/삭제, 댓글, 파일 | ✓ | ✓ | ✓ |
| 보드 이름·상태 라벨 변경, 보드 삭제 | | ✓ | ✓ |
| 게시글 작성(관리자 전용 게시판 제외), 본인 글 수정·삭제 | ✓ | ✓ | ✓ (타인 글 포함) |
| 관리자 전용 게시판 글쓰기, 상단 고정 | | | ✓ |
| 담당업무 구성(추가·이름·정/부 담당자·이동·삭제) — 조회와 업무 지정은 모두 가능 | | ✓ | ✓ |
| 본인 댓글/파일 삭제 | ✓ | ✓ | ✓ (타인 것 포함) |
| 사용자 관리 | | | ✓ |

팀 단위 사용을 전제로 **모든 사용자가 모든 보드를 볼 수 있습니다.** 보드별 비공개/멤버 제한이 필요하면 추가 개발이 필요합니다.

## 백업

- Oracle/PostgreSQL: DB 백업 + `teamboard.upload-dir` 폴더 백업 (첨부파일은 DB가 아닌 디스크에 저장)

## 소스에서 빌드

필요: JDK 17+, Maven 3.9+, Node.js 18+

```bash
./build.sh          # Windows: build.bat
# 결과: dist/ (isac-project.jar + 실행 스크립트 + DB 설정 템플릿, 기존 DB 설정은 유지)
```

폐쇄망 빌드 시 사내 Nexus/npm 미러를 사용하세요(`~/.m2/settings.xml`, `.npmrc`).

개발 모드(핫 리로드):

```bash
cd backend && mvn spring-boot:run        # :8080
cd frontend && npm install && npm run dev # :5173 (API는 8080으로 프록시)
```

## 구조

```
backend/src/main/java/com/inticube/teamboard
├─ config/   보안(세션·CSRF), 초기 관리자 생성, 설정값
├─ domain/   테이블 매핑 POJO (TB_APP_USER, TB_BOARD, TB_BOARD_STATUS, TB_BOARD_GROUP, TB_ITEM,
│            TB_ITEM_UPDATE, TB_ATTACHMENT, TB_NOTIFICATION, TB_BOARD_DUTY, TB_BOARD_TYPE, TB_BOARD_VERSION,
│            TB_ITEM_LINK, TB_ITEM_WATCHER, TB_TIME_ENTRY, TB_WIKI_PAGE, TB_POST_* …)
├─ dao/      MyBatis @Mapper 인터페이스 (SQL 은 resources/mapper/*.xml)
├─ service/  보드·업무·사용자·알림·파일 저장 로직
└─ web/      REST 컨트롤러(/api/**), DTO, 예외 처리
frontend/src
├─ App.jsx, api.js, util.js, styles.css
└─ components/ 홈·목록·칸반·타임라인·인사이트·업무 상세·게시판(목록/보기/작성)·알림함·명령 팔레트·관리자 화면
               icons.jsx(상태·우선순위 아이콘), markdown.jsx(의존성 없는 안전한 마크다운 렌더러/에디터)
```

## 검증 범위

PostgreSQL 16에서 로그인·권한·CSRF·보드/업무 CRUD·드래그 이동·댓글/멘션 알림·파일 업로드/다운로드·검색·대시보드·게시판(권한, 검색, 조회수, 댓글/답글, 첨부)과 v1.0 → v1.1, v1.2 → v1.3 업그레이드, 엑셀 내보내기→수정→가져오기 왕복(생성·수정·오류 시 미저장), 담당업무 트리(이동·순환 방지·하위 삭제·정 담당자 자동 배정)를 자동 테스트로 확인했습니다. **Oracle은 이 환경에서 직접 기동 테스트를 하지 못했으므로** 운영 적용 전 개발 DB에서 한 번 기동해 테이블 생성과 기본 동작을 확인하세요.

참고: 게시글 검색은 제목·작성자 전체와 **본문 앞부분(약 1,000자)** 을 대상으로 합니다(Oracle CLOB 직접 LIKE 검색을 피하기 위함).

## 아직 없는 기능 (필요 시 추가 개발)

- 보드별 접근 권한(비공개 보드), 사내 SSO/LDAP 연동
- 자동화 규칙(“상태가 완료되면 ~”), 반복 업무, 하위 업무
- 이메일/메신저 알림, 실시간 동시 편집(현재는 30초 주기 갱신)
- 엑셀 가져오기/내보내기, 엑셀로 워크스페이스 만들기
- 게시글 본문 이미지 붙여넣기(현재는 첨부파일로 업로드)

## 화면 개발: 빌드 없이 바로 보기 (Vite 개발 서버)

화면(프론트엔드)만 고칠 때는 jar 를 다시 만들 필요 없이 개발 서버로 띄우면 **파일을 저장하는 즉시 브라우저에 반영**됩니다.

- 처음 한 번 Node.js LTS(20 이상)를 설치합니다.
- 저장소 루트의 `run-dev.bat` 실행 → 처음엔 `npm ci` 로 패키지를 받고, `http://127.0.0.1:5173` 이 열립니다.
  - 기본: 개발 서버 백엔드(`http://10.1.17.116:30000`)의 API·DB 를 그대로 씁니다. 로그인도 그 서버 계정으로.
  - `run-dev.bat local`: 내 PC 에서 `java -jar` 로 띄운 백엔드(`http://localhost:8080`)에 붙습니다.
- 백엔드 주소를 바꾸려면 `frontend/.env.remote` 의 `API_TARGET` 을 고치면 됩니다.
- 백엔드(Java·매퍼) 변경은 개발 서버에 반영되지 않으니, 그건 dev 에 푸시해서 CI 배포 후 확인하세요.

## GitLab CI/CD 배포 (10.1.17.116)

`.gitlab-ci.yml` 이 **build → deploy** 두 단계로 돌아갑니다. `dev` 브랜치에 머지(푸시)되면 자동 빌드·배포되고, 그 외 브랜치(pilot 등) 푸시나 MR 생성 시에는 파이프라인이 돌지 않습니다. 배포는 jar 와 스크립트·SQL 만 올리고, 서버의 `config/application.yml`·`data/`·`logs/` 는 건드리지 않습니다.

**1. Runner (shell executor) 준비** — Runner 머신에 JDK 17, Node 20, Maven, git, ssh/scp 가 있어야 합니다. 프로젝트 › Settings › CI/CD › Runners 에서 등록하고 `Run untagged jobs` 를 켜거나 job 에 tags 를 맞추세요.

**2. 배포 서버(10.1.17.116) 준비** — 배포 계정(`isacstat`)으로 JDK 17 설치, `/app/isac/isac-project/` 에 `config/application.yml` (DB 접속 정보와 `server.port: 30000` — CI 로는 넘기지 않고 서버에만 둡니다) 을 만들어 둡니다. 처음 한 번은 `sql/postgres-teamboard-schema.sql` 로 테이블을 만들고, 이후 버전 업은 `sql/postgres-upgrade-<버전>.sql` 을 jar 교체 전에 실행합니다. CI 전용 SSH 키를 만들어 공개키를 `~/.ssh/authorized_keys` 에 넣습니다 (`ssh-keygen -t ed25519 -f isac-ci -N ""`).

**3. GitLab 변수** (Settings › CI/CD › Variables, Masked + Protected)

| 변수 | 값 |
|---|---|
| `DEPLOY_SSH_KEY` | 개인키 내용 (**Type: File**) |
| `DEPLOY_USER` | 배포 계정 (기본 `isacstat`, 다를 때만 등록) |
| `DEPLOY_HOST` | `10.1.17.116` (기본값 있음) |
| `DEPLOY_DIR` | 설치 경로 (기본 `/app/isac/isac-project`, 배포 계정에 쓰기 권한 필요) |

**4. 서버에서 하는 일** — `server-restart.sh` 가 새 jar 를 `backup/` 에 이전 버전을 남기고 교체 → 기존 프로세스 종료 → `nohup java -jar` 기동 → 60초 안에 `http://127.0.0.1:30000/` 응답 확인. 응답이 없으면 이전 jar 로 자동 롤백하고 파이프라인을 실패로 표시합니다. 로그는 `logs/app.log`, pid 는 `isac-project.pid`. 수동 재시작도 같은 스크립트로: `./server-restart.sh ~/isac-project`.
