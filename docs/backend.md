# MinAppStore 운영 안내

2026-10-22 현재 구성입니다. 이전 R2 설계는 폐기하고, R2 활성화·결제수단 등록·유료 플랜 전환 없이 배포했습니다.

## 서비스

| 구성 | 역할 |
| --- | --- |
| `minappstore-api` Worker | HTTPS API와 관리자 HTML |
| SQLite Durable Object `MinAppStoreState` | 카탈로그·연동 목록·비활성 상태·게시 잠금 |
| 공개 GitHub Releases | APK 원본 및 버전 기록 |
| 비공개 GitHub `minia1234/Min_AppStore` | 소스·검증·일일/수동 동기화 |

URL: `https://minappstore-api.eum-user.workers.dev`. 관리자 `/admin`, 공개 목록 `/api/catalog`, 앱 릴리스 `/api/apps/{package}/releases`, 다운로드 `/downloads/{package}/{versionCode}.apk`입니다. 다운로드는 검증된 공개 GitHub asset으로 redirect됩니다. APK·이미지를 Cloudflare에 저장하지 않습니다.

Workers Free의 SQLite-backed Durable Objects를 사용합니다. [공식 가격 정책](https://developers.cloudflare.com/durable-objects/platform/pricing/)에서 Free plan의 무료 할당량과 초과 시 제한을 확인할 수 있습니다. R2는 바인딩하지 않고 과금 서비스 활성화 API도 호출하지 않습니다. 이미 계정에서 가입한 다른 서비스의 요금까지 관리하는 도구는 아닙니다.

## 관리자

두 허용 계정 `minia1234`, `AIProject-k`의 **공개** 저장소만 목록으로 제공합니다. 비공개 원본은 GitHub 다운로드 fallback에 사용할 수 없으므로 제외합니다. 모든 관리자 API는 `Authorization: Bearer <관리자 키>`를 요구합니다. 브라우저는 키를 탭 메모리에만 유지하고 로그아웃/새로고침 시 지웁니다.

- 연동: 다음 동기화의 처리 대상으로 저장합니다. 유효한 APK/manifest 릴리스가 있어야 앱 목록에 나옵니다.
- 연동 해제: 공개 앱 목록에서 제거하고 동기화 대상에서 제외합니다.
- 비활성화: 연동 선택·검증된 서명·파일 정보를 유지하면서 두 기본 목록과 다운로드 API에서 숨깁니다.
- 비활성 항목 보기: 숨긴 항목을 확인하고 재활성화합니다.

관리자 변경과 동기화 게시에는 단일 writer 잠금과 조건부 JSON 쓰기를 사용합니다. 오래된 카탈로그/연동 ETag로 게시하면 409를 반환합니다. 읽기만 한 스냅샷으로 최신 사용자 설정을 덮어쓸 수 없습니다. 게시 결과가 불확실한 부분 실패는 복구 잠금을 유지합니다.

## 앱 등록

공개 릴리스에 **실제 서명 APK와 `store-manifest.json`**을 함께 올립니다. `scripts/store_manifest.py`가 `aapt`/`apksigner`로 실제 패키지·버전·최소 SDK·서명을 읽고 파일 크기·SHA-256을 생성합니다. 수작업으로 바이너리 정보를 꾸며 넣지 않습니다.

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
python scripts/store_manifest.py --apk path\signed.apk --metadata scripts/store-metadata.json --output store-manifest.json
```

앱 표시 정보는 각 앱에 맞는 metadata 파일로 작성합니다. 아이콘·스크린샷은 공개 HTTPS WebP URL을 사용하며 원본 URL을 그대로 제공합니다. draft/prerelease는 동기화하지 않습니다. 과거 버전의 APK 해시 교체, 패키지/서명 변경, versionCode 감소는 거부합니다.

## 동기화

`.github/workflows/sync-store.yml`은 매일 UTC18:00(한국03:00), 중앙 릴리스 게시, 수동 실행을 지원합니다. 무료 제공량을 아끼기 위해 빈번한 폴링은 하지 않습니다. 즉시 반영하려면 관리자 상단 **동기화 실행** 링크에서 GitHub `Run workflow`를 누릅니다.

저장소 변수 `STORE_ORIGIN`은 운영 Worker origin입니다. 저장소 secret `MINAPPSTORE_ADMIN_TOKEN`은 Worker `ADMIN_TOKEN`과 같습니다. 공개 GitHub 조회에는 job의 한시적 `GITHUB_TOKEN`을 사용하고 이를 Worker에 저장하지 않습니다. R2/S3 자격증명은 필요 없습니다.

동기화 스크립트 `scripts/sync_free.py`는 인증된 `/api/admin/sync`에서 내부 목록과 두 ETag를 읽고, APK 검사 후 후보를 PUT합니다. 서버가 현재 연동/비활성 설정과 패키지·서명 연속성을 다시 검사합니다. APK는 GitHub에 그대로 있고 Cloudflare로 전송하지 않습니다.

최대 연결100개, 앱당 릴리스100개, JSON2MB, APK512MiB, 작업당 GitHub4000요청/총8GB 전송으로 제한합니다. 한도를 넘으면 기존 목록을 보존하며 중단합니다. GitHub private 저장소의 포함 실행량을 사용하며 유료 spending limit을 늘리지 않습니다.

## 로컬 관리자 키 확인

이 PC에서 발급한 키는 `backend/.wrangler/minappstore-admin-token.dpapi`에 Windows 사용자 암호화로 보관했습니다. 이 파일은 Git에서 제외되며 다른 Windows 계정에서 복호화되지 않습니다. 본인 PC의 PowerShell7에서 아래 명령을 실행해 클립보드로 복사한 뒤 **운영 관리자 화면에만** 붙여넣습니다. 채팅·소스·GitHub issue에 붙여넣지 마세요.

```powershell
$encrypted = (Get-Content -LiteralPath 'D:\Indi\AppStore\backend\.wrangler\minappstore-admin-token.dpapi' -Raw).Trim()
$secure = ConvertTo-SecureString -String $encrypted
$credential = [PSCredential]::new('admin', $secure)
Set-Clipboard $credential.GetNetworkCredential().Password
```

다른 PC에서는 관리자가 별도 키를 발급해 `npx wrangler secret put ADMIN_TOKEN`과 `gh secret set MINAPPSTORE_ADMIN_TOKEN --repo minia1234/Min_AppStore`에 각각 안전하게 입력합니다. 키는 명령줄 인자나 커밋된 파일로 전달하지 않습니다.

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
