# MinAppStore 운영 안내

2026-10-22 현재 구성입니다. 이전 R2 설계는 폐기하고, R2 활성화·결제수단 등록·유료 플랜 전환 없이 배포했습니다.

## 서비스

| 구성 | 역할 |
| --- | --- |
| `minappstore-api` Worker | HTTPS API와 관리자 HTML |
| SQLite Durable Object `MinAppStoreState` | 카탈로그·연동 목록·비활성 상태·게시 잠금 |
| 공개 GitHub Releases | APK 원본 및 버전 기록 |
| 비공개 GitHub `minia1234/Min_AppStore` | 소스·검증·운영 문서 |
| 공개 GitHub `AIProject-k/ApplicationTest` | 무료 일일/수동 카탈로그 동기화 실행기 |

URL: `https://minappstore-api.eum-user.workers.dev`. 관리자 `/admin`, 공개 목록 `/api/catalog`, 앱 릴리스 `/api/apps/{package}/releases`, 다운로드 `/downloads/{package}/{versionCode}.apk`입니다. 다운로드는 검증된 공개 GitHub asset으로 redirect됩니다. APK·이미지를 Cloudflare에 저장하지 않습니다.

Workers Free의 SQLite-backed Durable Objects를 사용합니다. [공식 가격 정책](https://developers.cloudflare.com/durable-objects/platform/pricing/)에서 Free plan의 무료 할당량과 초과 시 제한을 확인할 수 있습니다. R2는 바인딩하지 않고 과금 서비스 활성화 API도 호출하지 않습니다. 이미 계정에서 가입한 다른 서비스의 요금까지 관리하는 도구는 아닙니다.

## 관리자

두 허용 계정 `minia1234`, `AIProject-k`의 **공개** 저장소만 목록으로 제공합니다. 비공개 원본은 GitHub 다운로드 fallback에 사용할 수 없으므로 제외합니다. 모든 관리자 API는 `Authorization: Bearer <관리자 키>`를 요구합니다. 브라우저는 키를 탭 메모리에만 유지하고 로그아웃/새로고침 시 지웁니다.

- 연동: 다음 동기화의 처리 대상으로 저장합니다. 유효한 APK/manifest 릴리스가 있어야 앱 목록에 나옵니다.
- 명시적 연동: 관리자가 선택한 공개 저장소만 동기화합니다. manifest 없는 APK는 실제 APK를 내려받아 패키지·버전·서명과 이름을 읽고, 12KB 이하 PNG/WebP 아이콘을 제공합니다.
- 연동 해제: 공개 앱 목록과 동기화 대상에서 제외합니다.
- 전환: 최신 동기화 스크립트를 먼저 실행기에 반영한 뒤 서버를 배포합니다. 이전 실행기가 비어 있지 않은 `autoLink`를 보내면 서버는 안전하게 거부합니다.
- 비활성화: 연동 선택·검증된 서명·파일 정보를 유지하면서 두 기본 목록과 다운로드 API에서 숨깁니다.
- 비활성 항목 보기: 숨긴 항목을 확인하고 재활성화합니다.

관리자 변경과 동기화 게시에는 단일 writer 잠금과 조건부 JSON 쓰기를 사용합니다. 오래된 카탈로그/연동 ETag로 게시하면 409를 반환합니다. 읽기만 한 스냅샷으로 최신 사용자 설정을 덮어쓸 수 없습니다. 게시 결과가 불확실한 부분 실패는 잠금을 남기며, 남은 잠금은 10분 뒤 다음 작업이 넘겨받습니다. 다음 작업도 최신 ETag로 다시 확인하므로 같은 변경을 재시도하면 상태가 맞춰집니다.

## 앱 등록

다른 프로젝트에서 사용하는 전체 절차는 [앱 등록·업데이트 가이드](../skills/minappstore-publish/references/publishing-guide.md), Codex 재사용 절차는 [minappstore-publish 스킬](../skills/minappstore-publish/SKILL.md)에 있습니다.

공개 릴리스에 **실제 서명 APK와 `store-manifest.json`**을 함께 올립니다. `scripts/store_manifest.py`가 `aapt`/`apksigner`로 실제 패키지·버전·최소 SDK·서명을 읽고 파일 크기·SHA-256을 생성합니다. 수작업으로 바이너리 정보를 꾸며 넣지 않습니다.

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
python scripts/store_manifest.py --apk path\signed.apk --metadata scripts/store-metadata.json --output store-manifest.json
```

앱 표시 정보는 각 앱에 맞는 metadata 파일로 작성합니다. 아이콘·스크린샷은 공개 HTTPS WebP URL을 사용하며 원본 URL을 그대로 제공합니다. draft/prerelease는 동기화하지 않습니다. 과거 버전의 APK 해시 교체, 패키지/서명 변경, versionCode 감소는 거부합니다.

## 동기화

공개 `AIProject-k/ApplicationTest`의 `.github/workflows/store-sync.yml`은 매일 UTC18:00(한국03:00), 해당 저장소 릴리스 게시, 수동 실행을 지원합니다. 즉시 반영하려면 관리자 상단 **동기화 실행** 링크에서 GitHub `Run workflow`를 누릅니다. 비공개 저장소는 결제/지출 제한으로 runner가 시작되지 않았으며, 지출 한도를 올리지 않고 공개 저장소의 표준 무료 Linux runner로 이동했습니다.

공개 실행 저장소의 변수 `STORE_ORIGIN`은 운영 Worker origin입니다. 해당 저장소 secret `MINAPPSTORE_ADMIN_TOKEN`은 Worker `ADMIN_TOKEN`과 같습니다. 공개 GitHub 조회에는 job의 한시적 `GITHUB_TOKEN`을 사용하고 이를 Worker에 저장하지 않습니다. 키는 공개 코드에 들어가지 않습니다. R2/S3 자격증명은 필요 없습니다.

### 동기화 토큰 전환

기존 설정에서는 동기화가 `ADMIN_TOKEN`을 사용합니다. 분리하려면 먼저 최신 실행기와 워크플로를 배포하고 새 고유 토큰을 실행 저장소의 `MINAPPSTORE_SYNC_TOKEN`에 설정합니다. 그다음 Worker secret `SYNC_TOKEN`에 같은 값을 설정합니다. `SYNC_TOKEN`이 설정된 서버는 동기화 API에 이 토큰만 받고, 관리자 API에는 `ADMIN_TOKEN`만 받습니다. 설정 전에는 기존 토큰을 사용합니다. 전환 후 Actions와 관리자 화면을 각각 확인하고 실행 저장소의 기존 관리자 토큰을 제거합니다. 실패 시 `SYNC_TOKEN` 설정을 제거하면 기존 방식으로 돌아갑니다. 토큰 값은 출력하거나 커밋하지 않습니다.

잠금은 시간 경과만으로 해제하지 않습니다. 중단 또는 불확실한 쓰기 후 잠금이 남으면 동기화와 관리자 변경이 차단됩니다. 운영자가 실행 종료와 카탈로그·연동 설정의 일관성을 확인해야 하며, 안전한 복구 API는 아직 구현되지 않았습니다.

공개 실행기는 매 실행마다 비공개 저장소 `main`의 `scripts/`를 읽기 전용 deploy key로 받아 `scripts/sync_free.py`를 실행합니다([워크플로 원본](../scripts/store-sync.yml)). 그래서 이 저장소 `main`에 push하면 다음 실행부터 자동으로 최신 동기화 코드가 쓰입니다. 새 Worker 기능이 필요한 동기화 변경은 **Worker를 먼저 배포한 뒤 push**합니다. 인증된 `/api/admin/sync`에서 내부 목록과 두 ETag를 읽고 APK 검사 후 후보를 PUT합니다. 서버가 현재 연동/비활성 설정과 서명 연속성을 다시 검사합니다. APK는 GitHub에 그대로 있고 Cloudflare로 전송하지 않습니다.

최대 연결100개, 앱당 릴리스100개, JSON2MB, APK512MiB, 작업당 GitHub4000요청/총8GB 전송으로 제한합니다. 한도를 넘으면 기존 목록을 보존하며 중단합니다. `sync_free.py`는 앱 하나의 검증·조회 실패 시 그 앱만 마지막 검증 항목을 유지하고 나머지 앱은 게시한 뒤, 실패한 저장소를 출력하고 실행을 실패로 끝냅니다. 공개 실행 저장소의 기본 무료 runner를 사용하며 유료 spending limit을 늘리지 않습니다.

### 공개 실행기 최초 1회 설정

1. 읽기 전용 deploy key를 만들고 비공개 저장소에 등록한 뒤, 개인키를 공개 실행 저장소 secret으로 넣습니다. 개인키는 표준 입력으로만 전달하고 끝나면 지웁니다.

   ```powershell
   ssh-keygen -t ed25519 -N '""' -C 'ApplicationTest store sync' -f minappstore-source
   gh repo deploy-key add minappstore-source.pub --repo minia1234/Min_AppStore --title 'ApplicationTest store sync'
   Get-Content minappstore-source -Raw | gh secret set MINAPPSTORE_SOURCE_KEY --repo AIProject-k/ApplicationTest
   Remove-Item minappstore-source, minappstore-source.pub
   ```

2. [scripts/store-sync.yml](../scripts/store-sync.yml)을 `AIProject-k/ApplicationTest`의 `.github/workflows/store-sync.yml`로 교체합니다. 이후 그 저장소의 `scripts/sync_catalog.py`는 쓰지 않으므로 지워도 됩니다.
3. Actions에서 **Run workflow**로 한 번 실행해 성공을 확인합니다.

deploy key는 이 저장소 하나만 읽을 수 있고 쓰기 권한이 없습니다. 공개 저장소의 Actions 로그는 누구나 볼 수 있으므로, 동기화 오류 출력에 비밀값을 넣지 않습니다. 실행이 깨지면 deploy key를 GitHub에서 삭제하는 것만으로 비공개 저장소 접근이 끊깁니다.

## 로컬 관리자 키 확인

이 PC에서 발급한 키는 `backend/.wrangler/minappstore-admin-token.dpapi`에 Windows 사용자 암호화로 보관했습니다. 이 파일은 Git에서 제외되며 다른 Windows 계정에서 복호화되지 않습니다. 본인 PC의 PowerShell7에서 아래 명령을 실행해 클립보드로 복사한 뒤 **운영 관리자 화면에만** 붙여넣습니다. 채팅·소스·GitHub issue에 붙여넣지 마세요.

```powershell
$encrypted = (Get-Content -LiteralPath 'D:\Indi\AppStore\backend\.wrangler\minappstore-admin-token.dpapi' -Raw).Trim()
$secure = ConvertTo-SecureString -String $encrypted
$credential = [PSCredential]::new('admin', $secure)
Set-Clipboard $credential.GetNetworkCredential().Password
```

다른 PC에서는 관리자가 별도 키를 발급해 `npx wrangler secret put ADMIN_TOKEN`과 `gh secret set MINAPPSTORE_ADMIN_TOKEN --repo AIProject-k/ApplicationTest`에 각각 안전하게 입력합니다. 키는 명령줄 인자나 커밋된 파일로 전달하지 않습니다.

## 배포

```powershell
Set-Location backend
npm ci
npm test
npm run typecheck
npm run dry-run
npx wrangler deploy
```

`wrangler.jsonc`에는 STATE Durable Object 바인딩과 SQLite migration만 있습니다. R2/D1/KV/유료 플랜 설정은 없습니다. 다른 Worker·DNS·도메인은 건드리지 않습니다. Android의 기본 URL은 운영 origin이며 패키지 ID는 `dev.indi.appstore`를 유지합니다.
