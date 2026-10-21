# MinAppStore 무료 운영 전환 — 2026-10-22

사용자가 `minia1234/Min_AppStore`를 중앙 저장소로 지정하고 표시명을 MinAppStore로 정했다. 해당 저장소는 비공개/빈 main이며 공개 전환하지 않는다. Android ID `dev.indi.appstore`와 기존 서명은 유지한다.

사용자는 R2의 결제수단 등록·초과 과금을 거부했다. R2 활성화/유료 플랜 전환을 하지 않는다. APK·이미지는 공개 GitHub Releases/원본 URL에 두고, Cloudflare Workers Free + SQLite-backed Durable Object Free에 카탈로그·연동·비활성 설정만 저장한다. 무료 한도를 초과하면 요청/작업이 실패하는 방식으로 운영한다.

## 변경 계획 및 검증

1. Android·관리 화면·표시 메타데이터를 MinAppStore로 변경한다. 내부 패키지/설정 키는 유지한다. 검증: 문자열 검색·Gradle build/lint.
2. Worker의 JSON 저장소를 SQLite Durable Object로 연결한다. 단일 writer 잠금과 조건부 게시를 유지한다. 검증: 저장 조건/관리 API/공개 다운로드 redirect 테스트 및 실제 배포 응답.
3. GitHub Actions가 공개 릴리스 APK를 내려받아 서명·해시를 검사하고, HTTPS 관리자 동기화 API로 카탈로그 후보를 게시한다. 조회 시점의 카탈로그/연동 ETag가 바뀌면 409로 거부하여 관리자 변경을 덮어쓰지 않는다. R2 자격증명은 사용하지 않는다. 검증: 후보 생성·동시 변경 거부·서명 검증 테스트.
4. 전체 기능을 검증한 후 main에 소스·운영 안내만 커밋/푸시한다. Worker 무료 저장소 배포와 실제 공개 목록/인증/연동/동기화를 확인한다. 비밀키·개인 기기 기록·로컬 SQLite·APK·테스트 인증서는 커밋하지 않는다.

실제 배포 URL: `https://minappstore-api.eum-user.workers.dev`. 관리자: `/admin`. 넓은 GitHub OAuth 자격증명을 Worker로 복사하지 않는다. 전용 관리자 secret만 새로 발급해 Worker와 지정한 비공개 저장소의 Actions secret으로 연결했다. 즉시 workflow dispatch용 세분화 토큰은 설정하지 않고, 매일 03:00 KST와 수동 실행을 사용한다. 관리자에 GitHub Actions 실행 링크를 제공한다.

GitHub private 저장소의 Actions 무료 사용량은 별도 제한이 있으므로 해당 저장소의 spending limit을 늘리지 않는다. 중앙 소스가 비공개인 점 때문에 자체 앱 APK를 그 저장소의 private release로 일반 사용자에게 직접 전달할 수는 없다. 일반 앱 배포는 기존 공개 ApplicationTest 같은 저장소가 담당한다. 자체 배포본은 별도 공개 배포 경로를 명시하기 전까지 비공개 소스의 릴리스 artifact로만 제공한다.
