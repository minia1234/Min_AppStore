# 다른 Android 앱을 MinAppStore에 연동하기

[English guide](publishing-guide.en.md) · 스킬은 요청한 언어로 안내합니다. 영문 metadata 예시는 `../assets/store-metadata.en.json`에 있습니다.

## 무엇을 올리면 되나요?

**공개 GitHub Release에 서명된 APK와 `store-manifest.json`을 함께 올리고, 관리자에서 해당 저장소를 연동**하면 됩니다. 앱 소스를 앱스토어 저장소로 복사할 필요는 없습니다. 서버를 앱마다 새로 배포할 필요도 없습니다.

앱 소스가 비공개라면 그대로 두고 APK 배포용 공개 저장소를 따로 지정합니다. 이 절차는 그 저장소를 자동 생성하거나 소스를 공개로 전환하지 않습니다. 공개 APK는 누구나 내려받을 수 있으므로 비밀키나 내부 설정을 APK에 넣지 않습니다.

| 구분 | 현재 값 |
| --- | --- |
| 관리자 | https://minappstore-api.eum-user.workers.dev/admin |
| 앱 목록 | https://minappstore-api.eum-user.workers.dev/api/catalog |
| 허용 배포 저장소 소유자 | minia1234, AIProject-k |
| 중앙 동기화 실행 | https://github.com/AIProject-k/ApplicationTest/actions/workflows/store-sync.yml |
| 자동 반영 | 매일 03:00 KST, GitHub 실행 지연 가능 |
| 즉시 반영 | 중앙 워크플로의 Run workflow → main |

다른 계정 소유의 저장소는 현재 허용하지 않습니다. 각 앱에 Cloudflare 키나 관리자 토큰을 등록하지 않습니다. 공개 저장소의 기존 중앙 실행기에서 동기화합니다.

## 1. 프로젝트와 서명을 확인합니다

프로젝트 채팅에서 다음처럼 요청하면 설치된 스킬이 앱의 실제 빌드 설정부터 확인합니다.

```text
$minappstore-publish 이 프로젝트의 Android 앱을 확인하고,
AIProject-k/대상저장소에 APK와 store-manifest.json을 릴리스한 뒤
MinAppStore에 연동해줘. 기존 패키지와 서명키는 유지해줘.
```

대상 저장소를 실제 이름으로 바꿉니다. 스킬 목록에 바로 보이지 않으면 새 채팅에서 사용하거나 Codex를 다시 엽니다. 재시작 없이 전달하려면 이 가이드 옆 `../SKILL.md`를 채팅에 첨부해 그 절차대로 진행해 달라고 요청해도 됩니다.

다른 PC로 옮길 때는 저장소의 `skills/minappstore-publish` **폴더 전체**를 그 PC의 `.codex/skills/minappstore-publish`에 복사합니다. references/assets도 함께 필요합니다. 스킬에는 관리자 토큰·서명키가 없으며, GitHub 로그인·Android SDK·앱 서명 설정은 그 PC에 별도로 준비합니다.

직접 준비하려면 다음 조건을 지킵니다.

- Python 3.11 이상, Android SDK Build-tools의 `aapt`와 `apksigner`, GitHub CLI 로그인/해당 저장소 쓰기 권한이 필요합니다.
- 프로젝트의 기존 방식으로 **서명된 단일 APK**를 빌드합니다. AAB만으로는 등록되지 않습니다.
- Android Gradle 프로젝트라면 예를 들어 `android` 또는 프로젝트 루트의 올바른 위치에서 `.\gradlew.bat :app:assembleRelease`를 사용합니다. 서명 설정이 없다면 이 명령만으로 배포 가능한 서명 APK가 생기지 않습니다.
- Flutter·Unity·Godot는 해당 프로젝트의 Android APK 빌드 방식을 사용합니다.
- 업데이트는 동일 `packageName`, 동일 서명 인증서, 더 큰 `versionCode`가 필요합니다. 화면에 보이는 `versionName`만 바꾸면 업데이트되지 않습니다.
- APK 크기는 최대 512 MiB입니다. 파일명은 영문·숫자·밑줄·하이픈·마침표로 된 `.apk`를 사용합니다.

## 2. 앱 표시 정보를 작성합니다

설치된 스킬의 `assets/store-metadata.json`을 프로젝트의 `store-metadata.json`으로 복사하고 이름·요약·설명·분류·개발자·태그를 실제 앱에 맞게 수정합니다. 기본 설치 위치는 `C:\Users\user\.codex\skills\minappstore-publish`입니다. `CODEX_HOME`을 따로 설정한 경우 그 아래 `skills/minappstore-publish`를 사용합니다.

```json
{
  "name": "나의 앱",
  "summary": "내 앱의 주요 기능",
  "description": "앱의 기능과 사용 방법을 설명합니다.",
  "category": "도구",
  "author": "AIProject-k",
  "tags": ["도구"],
  "iconUrl": "",
  "screenshots": []
}
```

아이콘과 스크린샷은 없어도 됩니다. 넣는다면 공개 HTTPS **WebP 파일 URL**을 사용합니다. 이름은 80자, 요약 200자, 설명 10,000자, 분류 40자, 개발자 80자 이내입니다. 태그는 각 40자/최대 12개, 스크린샷은 최대 8개입니다. 나머지 바이너리 검증 정보는 다음 단계에서 자동 생성합니다.

## 3. 실제 APK에서 manifest를 생성합니다

아래는 **앱 프로젝트 루트에서 실행하는 PowerShell 예시**입니다. 빌드 산출물 경로와 파일명은 실제 프로젝트에 맞춥니다.

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$skillRoot = Join-Path $env:USERPROFILE '.codex/skills/minappstore-publish'
if ($env:CODEX_HOME) { $skillRoot = Join-Path $env:CODEX_HOME 'skills/minappstore-publish' }
New-Item -ItemType Directory -Path release -Force | Out-Null
$apk = 'release/MyApp-1.0.0.apk'
Copy-Item -LiteralPath 'app/build/outputs/apk/release/app-release.apk' -Destination $apk
python "$skillRoot/assets/store_manifest.py" --apk $apk --metadata store-metadata.json --output release/store-manifest.json
if ($LASTEXITCODE -ne 0) { throw 'APK/manifest 검증 실패: 게시를 중단하세요.' }
```

검사기는 실제 패키지·버전·최소 SDK·서명 지문·SHA-256·파일 크기를 읽습니다. 생성 후 APK 파일을 수정하거나 이름을 바꿨다면 다시 생성해야 합니다. 최종 릴리스에 올라갈 APK 이름과 manifest의 `asset`이 같아야 합니다. keystore/비밀번호/`.env`/APK는 Git 커밋에 포함하지 않습니다.

## 4. 공개 Release에 두 파일을 올립니다

GitHub 저장소의 **Releases → Draft a new release**에서 대상 커밋/새 버전 태그를 확인하고 두 파일을 첨부합니다.

```text
MyApp-1.0.0.apk
store-manifest.json
```

파일명은 정확히 `store-manifest.json`이어야 합니다. 저장만 한 Draft나 Prerelease는 수집하지 않습니다. 내용·서명·저장소를 확인한 뒤 일반 Release로 **Publish release**합니다. 이미 게시한 APK를 같은 버전으로 덮지 말고 새 versionCode/태그로 배포합니다.

CLI로 게시할 경우 아래 명령은 **실제로 공개합니다**. `$repo`, `$tag`, `$apk`, 릴리스 노트 파일을 실제 값으로 준비하고, 해당 태그가 올바른 커밋으로 원격에 존재하는지 먼저 확인합니다. 앱 소스가 비공개인 경우 태그/커밋은 소스 저장소가 아닌 지정한 **공개 배포 저장소**의 것을 사용합니다.

```powershell
$repo = 'AIProject-k/MyAndroidApp' # 실제 공개 배포 저장소로 변경
$tag = 'v1.0.0' # 해당 저장소에 먼저 준비한 태그
gh release create $tag $apk release/store-manifest.json --repo $repo --verify-tag --title $tag --notes-file release/release-notes.md
if ($LASTEXITCODE -ne 0) { throw '릴리스 게시 실패: 원인을 확인하세요.' }
gh release view $tag --repo $repo --json url,isDraft,isPrerelease,assets
```

앱스토어는 GitHub의 실제 asset 크기와 `sha256:` digest, 내려받은 APK 내용까지 검사합니다. manifest를 손으로 조작해서 통과시키지 않습니다.

## 5. 관리자에서 연동합니다

1. 관리자 주소에 접속해 기존 관리자 토큰으로 로그인합니다. 토큰을 다른 앱 프로젝트나 공개 파일에 넣지 않습니다.
2. 앱의 공개 배포 저장소를 검색하고 **연동**을 누릅니다.
3. 비활성 상태라면 **비활성 항목 보기**를 체크하고 **활성화**합니다.
4. **동기화 실행** 링크를 눌러 GitHub에서 **Run workflow → main → Run workflow**를 실행합니다. Actions 실행 권한이 없으면 관리자에게 요청하거나 일일 실행을 기다립니다.

새로 만든 저장소가 검색되지 않는다면 서버가 저장한 공개 저장소 목록이 아직 갱신되지 않았을 수 있습니다. 중앙 동기화를 먼저 한 번 실행 → 관리자 **목록 새로고침** → 해당 저장소 **연동** → 중앙 동기화 재실행 순서로 진행합니다. 중앙 실행 한 번으로 목록 캐시가 갱신되더라도 미연동 저장소가 자동 등록되지는 않습니다.

**다른 앱 저장소의 Release 게시 이벤트가 중앙 실행기를 바로 깨우지는 않습니다.** 현재 `ApplicationTest` 자체 릴리스 이벤트, 매일 03:00 KST, 수동 실행만 연결돼 있습니다. 앱마다 관리자 토큰이나 추가 워크플로를 복제할 필요는 없습니다.

## 6. 반영을 확인합니다

- 중앙 Actions 실행이 `success`인지 확인합니다.
- 공개 카탈로그에서 해당 `repository`·`packageName`·최신 `versionCode`가 맞는지 확인합니다. 가장 큰 versionCode가 최신입니다.
- 앱스토어 앱에서 새로고침해 설치/업데이트 버튼을 확인합니다.
- Android 설치는 사용자의 설치 승인 화면을 거칩니다. 자동 동기화 성공과 실제 기기 업데이트 성공은 별개입니다.

이후 업데이트는 같은 서명키로 versionCode를 올려 빌드 → manifest 재생성 → 새 Release 게시 → 중앙 동기화 순서입니다. 저장소 연동은 최초 한 번이면 됩니다. 중앙 동기화는 APK를 검사·등록하며 앱 소스를 대신 빌드하지 않습니다.

## 문제가 생기면

| 증상 | 확인할 것 |
| --- | --- |
| 관리자 목록에 저장소가 없음 | 허용된 두 계정의 공개 저장소인지, 목록 캐시를 중앙 동기화로 갱신했는지 |
| 연동했는데 앱이 없음 | 활성 상태인지, 일반 공개 Release에 APK와 정확한 manifest 이름이 있는지, 중앙 동기화가 실행됐는지 |
| 릴리스를 올렸는데 바로 반영 안 됨 | 다른 저장소의 게시만으로 즉시 실행되지 않음. 중앙 Run workflow 실행 |
| 서명/해시 검증 실패 | 같은 키인지, manifest 생성 후 APK를 바꿨는지, 과거 asset을 교체했는지 |
| 업데이트 버튼이 안 뜸 | 실제 versionCode가 설치 버전보다 큰지, 패키지가 같은지 |
| HTTP 409 | 동기화/관리자 작업 진행 여부 확인 후 최신 상태에서 재시도. 잠금 강제 삭제 금지 |
| 여러 앱 중 하나 때문에 동기화 실패 | 실패한 앱의 메타데이터를 고친 후 재실행. 기존 카탈로그를 비우지 않음 |

이 문서는 기존 무료 운영 구조의 사용 가이드입니다. 새 저장소 공개·토큰 발급·실제 릴리스 게시·허용 계정 확대는 문서를 만드는 것만으로 실행하지 않습니다.
