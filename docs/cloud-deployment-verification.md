# MinAppStore 운영 배포 검증 — 2026-10-22

## 실제 구성

- 비공개 소스: `https://github.com/minia1234/Min_AppStore`, 기본 브랜치 main.
- 운영 Worker: `https://minappstore-api.eum-user.workers.dev`, 관리자 `/admin`.
- Cloudflare STATE는 SQLite Durable Object 한 개이며, APK/이미지/R2 바인딩은 없다. R2 활성화·결제수단 등록·유료 플랜 전환을 수행하지 않았다.
- Worker secret은 전용 `ADMIN_TOKEN`이다. GitHub 계정의 OAuth 토큰은 Worker로 복사하지 않았다.
- 자동화: 사용자가 승인한 두 파일만 공개 `AIProject-k/ApplicationTest`에 추가했다. Android/UI 소스와 기기 기록은 비공개/로컬로 유지했다.

## 확인 결과

| 확인 | 실제 결과 |
| --- | --- |
| 공개 카탈로그 | HTTPS 200, 실제 ApplicationTest 1.0.1/code2와 1.0.0/code1 |
| 다운로드 | 운영 Worker 302 → 공개 GitHub APK URL |
| 관리자 HTML | HTTPS 200, MinAppStore 표시명, script/style CSP 해시 |
| 관리자 인증 | 인증된 저장소 조회 200; 두 계정의 공개 저장소 목록 |
| 카탈로그 게시 | 실제 공개 APK를 SDK로 검사한 뒤 운영 동기화 API의 published:true 응답 |
| 공개 무료 Actions | [실행 39756281216](https://github.com/AIProject-k/ApplicationTest/actions/runs/39756281216) success; APK검사 도구와 운영 카탈로그·저장소 목록 캐시 동기화 성공 |
| 기본 자동 실행 | 매일03:00KST·ApplicationTest 릴리스 게시·수동 실행 |
| Pixel4 | 기존 데이터 삭제 없이 MinAppStore0.2.0/code2 설치, 운영 origin으로 변경, USB reverse 제거 후 MinAppStore 앱 목록과 설치된 테스트 앱 ‘열기’ 표시 확인 |

비공개 저장소의 Actions는 GitHub가 계정 결제 실패/지출 제한 메시지로 runner 시작을 차단했다. 지출을 늘리지 않고 공개 기본 Linux runner로 전환했다. 첫 공개 실행에서는 setup-android 기본값의 폐기된 `tools` 패키지 때문에 실패하여, 필요한 `build-tools;36.0.0`을 명시한 다음 실행에서 성공했다.

## 로컬 검증

Node36개, Python33개, Android단위8개 성공. TypeScript 검사와 실제 배포 성공. Android assembleDebug와 lintDebug 성공(오류0개). APK 표시명 MinAppStore, 패키지 dev.indi.appstore, 버전0.2.0/code2. 기존 Pixel4 설치와 같은 인증서를 유지했다.

GitHub 인증 없는 조회의 403 제한을 피하기 위해 공개 동기화 실행기가 허용 계정의 공개 저장소 목록을 서버에 캐시한다. 관리자 조회와 기존 캐시 항목의 연결·활성화는 이 목록을 사용하고, 실제 카탈로그 게시 시 공개 여부·APK 검증을 다시 수행한다. 캐시는 링크·비활성·서명 정보를 대체하지 않는다. 최신 실행 후 인증 조회200, 비활성 앱의 카탈로그 숨김과 다운로드404, 활성화 후 복원을 운영 서버에서 확인했다.

블루 라이트·민트 다크 디자인의 변경 및 실기기/웹 검증 범위는 [디자인 기록](design-blue-mint.md)에 구분했다.

## 경계

무료 플랜의 한도와 서비스 가용성은 공급자 정책을 따른다. 다른 계정 서비스의 기존 청구 내역은 현재 OAuth 권한으로 조회할 수 없었고 변경하지 않았다. 이 작업에서 유료 가입·추가 결제·지출 한도 증액은 하지 않았다. Free 플랜의 Durable Object는 무료 한도 초과 시 해당 작업을 오류로 제한한다([정책](https://developers.cloudflare.com/durable-objects/platform/pricing/)).

앱스토어 소스 저장소가 비공개이므로 자체 APK를 그 private release에서 일반 사용자에게 자동 배포하지 않는다. 현재 제공 APK는 개발용 인증서이며, 별도 공개 앱스토어 배포 저장소 지정은 후속 운영 결정이다. 관리자 키는 로컬 DPAPI 암호화와 Worker/GitHub repository secrets로 보관하고 소스·로그에 공개하지 않았다.
