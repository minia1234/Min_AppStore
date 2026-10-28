# MinAppStore

개인 Android 앱을 모아 설치·업데이트하는 앱스토어입니다. 소스 저장소는 공개 `minia1234/Min_AppStore`이며, Android 패키지 ID는 기존 설치와 호환되는 `dev.indi.appstore`를 유지합니다.

- [운영 API](https://minappstore-api.eum-user.workers.dev/api/catalog)
- [관리자 화면](https://minappstore-api.eum-user.workers.dev/admin)
- [무료 동기화 실행](https://github.com/AIProject-k/ApplicationTest/actions/workflows/store-sync.yml)
- [다른 Android 앱 등록·업데이트 가이드](skills/minappstore-publish/references/publishing-guide.md) · [재사용 스킬](skills/minappstore-publish/SKILL.md)
- [English publishing guide](skills/minappstore-publish/references/publishing-guide.en.md)

## 무료 운영 구조

**R2·유료 플랜·결제수단 등록을 사용하지 않습니다.** APK와 이미지는 공개 GitHub Releases/원본 URL에 두고, Cloudflare Workers + SQLite Durable Object에는 앱 목록과 연동·비활성 설정만 저장합니다. Workers Free의 한도를 넘으면 서비스가 제한됩니다. 계정 플랜을 유료로 전환하는 코드는 없습니다. 자동 동기화는 공개 저장소의 기본 무료 Linux 실행기를 사용하며, 유료 예산을 설정하거나 늘리지 않습니다.

관리자가 `minia1234`, `AIProject-k` 두 계정의 공개 저장소를 조회해 직접 연동합니다. 연동한 저장소만 동기화합니다. `store-manifest.json`이 없고 APK가 하나이면 이름·아이콘은 APK에서, 설명은 저장소 설명에서 가져옵니다. **비활성화**는 연동을 유지하며 관리자 기본 목록·스토어 목록과 직접 다운로드 경로에서 숨깁니다. **비활성 항목 보기**에서 복원할 수 있습니다. GitHub 원본은 삭제하지 않습니다.

저장된 연동은 공개 저장소 `AIProject-k/ApplicationTest`의 무료 GitHub Actions에서 매일 **03:00(KST)** 동기화합니다. 해당 저장소의 릴리스 게시 시에도 실행합니다. 지금 반영하려면 관리자 화면의 **동기화 실행** 링크에서 `Run workflow`를 실행합니다. 넓은 GitHub 계정 토큰을 Cloudflare에 저장하지 않습니다.

중앙 동기화는 현재 `ApplicationTest` 저장소의 워크플로를 사용합니다. 소스 저장소 공개 이후의 Actions 실행 및 설정은 별도 검증이 필요합니다.

## Android

Android 8.0 이상, Kotlin/Compose/Room/WorkManager/Retrofit/Coil을 사용합니다. 홈·검색·카테고리·상세·이전 버전·내 앱·다운로드·업데이트·알림을 제공합니다. 기본 서버는 위 운영 API이며 설정에서 변경할 수 있습니다.

APK는 파일 크기·SHA-256·패키지·버전·서명을 검증한 뒤 Android 설치 승인 화면으로 전달합니다. 일괄 업데이트도 실제 설치는 개별 승인해야 합니다. 동일 키와 증가한 versionCode가 업데이트 조건입니다.

```powershell
Set-Location android
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

산출물: `android/app/build/outputs/apk/debug/app-debug.apk`. debug APK는 개발용 인증서입니다. 정식 키로 바꾸면 기존 debug 설치본을 그대로 업데이트할 수 없습니다.

앱·관리자 웹·배포 스킬은 한국어와 영어를 지원합니다. 앱은 기기 언어, 관리자 웹은 브라우저 선호 언어를 따릅니다. [언어 설정과 검증 범위 / Language support](docs/localization.md)를 참고하세요. 게시자가 작성한 앱 설명과 릴리스 노트는 원문을 유지합니다.

## 서버와 자동화 검증

```powershell
Set-Location backend
npm ci
npm test
npm run typecheck
npm run dry-run
Set-Location ..
$env:PYTHONPATH = 'scripts'
python -m unittest discover -s scripts/tests -v
```

동기화는 실제 공개 APK를 내려받아 Android SDK로 검증합니다. 오래된 스냅샷이나 관리자 연동 상태가 바뀌면 서버가 게시를 거부해 최신 설정을 보호합니다. 이미 검증한 앱의 패키지·서명·기존 APK 해시 변경도 차단합니다.

별도 테스트 앱 [ApplicationTest](https://github.com/AIProject-k/ApplicationTest)의 v1.0.0 → v1.0.1 업데이트는 Pixel 4에서 확인했습니다. 운영 환경 확인 결과는 [운영 안내](docs/backend.md)에 구분합니다.

실제 Cloudflare·무료 GitHub Actions·Pixel4 운영 연결 결과는 [배포 검증 기록](docs/cloud-deployment-verification.md)에 있습니다.

## 비밀키·배포

운영 관리자 키는 Worker secret과 공개 실행 저장소 `AIProject-k/ApplicationTest`의 Actions secret `MINAPPSTORE_ADMIN_TOKEN`에 설정합니다. Android·카탈로그·소스·URL에 키를 넣지 않습니다. 비공개 중앙 저장소에는 불필요한 관리자 secret을 남기지 않았습니다. [운영 안내](docs/backend.md)의 절차를 따릅니다.

현재 소스 저장소가 비공개라 그 private release의 APK를 일반 사용자에게 직접 배포하지 않습니다. 일반 앱 배포에는 공개 릴리스를 사용합니다. 앱스토어 자체 공개 배포는 별도의 공개 배포 저장소를 정한 뒤 연결해야 합니다.
