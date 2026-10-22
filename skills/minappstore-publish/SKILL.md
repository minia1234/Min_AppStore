---
name: minappstore-publish
description: Android 프로젝트의 서명 APK와 store-manifest.json을 준비하고 GitHub Releases에서 MinAppStore로 등록하거나 업데이트할 때 사용한다. 다른 프로젝트의 앱스토어 연동, 릴리스 게시, 동기화 진단을 지원한다. 일반 서버 배포나 MoaLog 전용 빌드 스킬은 아니다.
---

# MinAppStore 앱 등록·업데이트

현재 프로젝트의 Android 앱을 기존 MinAppStore에 연결한다. 절차와 실행 명령은 [등록 가이드](references/publishing-guide.md)를 읽는다. APK 검사기는 [assets/store_manifest.py](assets/store_manifest.py), 표시 정보 예시는 [assets/store-metadata.json](assets/store-metadata.json)에 있다. 상대 경로는 이 SKILL.md가 있는 디렉터리를 기준으로 해석한다.

## 운영 계약

- 서버: `https://minappstore-api.eum-user.workers.dev`, 관리자 `/admin`, 공개 목록 `/api/catalog`.
- 허용 소유자: `minia1234`, `AIProject-k`. 두 계정의 공개 배포 저장소만 현재 지원한다.
- 동기화 실행기: 공개 `AIProject-k/ApplicationTest`의 `store-sync.yml`. 매일 03:00 KST, 그 저장소 자체의 릴리스 게시, 수동 실행에 동작한다. 다른 앱 저장소의 릴리스만 게시했다고 즉시 실행되지는 않는다.
- Cloudflare는 Workers Free + SQLite Durable Object 메타데이터만 사용한다. APK는 공개 GitHub Releases에 둔다. R2·유료 가입·지출 한도 변경은 하지 않는다.
- 관리자 토큰은 관리 화면/중앙 실행기의 기존 secret에만 필요하다. 각 앱 저장소에 복사하거나 채팅·URL·로그·소스에 출력하지 않는다.

## 진행 방법

1. 대상 프로젝트의 AGENTS.md, Git remote, 기본 브랜치, 빌드 설정과 기존 릴리스를 확인한다. 앱 소스 저장소와 공개 배포 저장소가 다르면 둘을 구분한다. 저장소나 앱의 대상이 불분명할 때만 질문한다.
2. 프로젝트의 실제 빌드 방식을 따른다. Gradle·Flutter·Unity·Godot 산출물 경로를 추측하지 않는다. 기존 packageName과 서명키를 유지하고 versionCode를 증가시킨다. 배포 키가 없으면 unsigned/debug APK를 정식 업데이트로 가장하지 말고 필요한 키 설정만 요청한다. 키 내용은 요구하지 않는다.
3. 실제 서명 APK와 앱 표시 metadata를 준비한다. SDK의 aapt/apksigner를 사용하는 동봉 검사기로 manifest를 생성한다. 패키지·서명·버전·해시·파일 크기를 직접 써넣지 않는다. 임의의 샘플 metadata를 게시하지 않는다.
4. 이미 등록된 앱이면 카탈로그와 같은 패키지·서명인지, 기존 versionCode의 APK 해시를 교체하지 않는지 확인한다. 기존 릴리스가 있으면 APK/manifest asset과 digest를 확인한다. 첫 등록에는 과거 릴리스가 없어도 된다. 검사 실패를 우회하지 않는다.
5. 게시가 요청 범위에 포함되면 지정한 공개 저장소의 해당 버전 릴리스에 APK와 정확히 `store-manifest.json`을 올린다. 일반 공개 Release만 반영되며 draft/prerelease는 제외된다. 준비/가이드 요청만으로 공개 게시하지 않는다. 이미 받은 게시 권한은 다시 묻지 않는다.
6. 관리 화면에서 대상 저장소를 연동하고 비활성 상태를 해제한다. 서버의 다른 연결·비활성 선택은 보존한다. 새 저장소가 캐시에 없으면 중앙 동기화를 먼저 한 번 실행해 목록을 갱신한 다음 연결한다.
7. 즉시 반영이 요청되면 기존 중앙 워크플로를 한 번 실행하고 결과를 확인한다. 실패하면 같은 작업을 무작정 반복하지 않는다. 409는 진행 중인 작업 종료 후 새 스냅샷으로 한 번 재시도하고, 서명/해시 검증 실패는 게시를 멈춰 원인을 보고한다. 잠금·핀·GitHub asset을 삭제해서 통과시키지 않는다.
8. 카탈로그에 대상 repository/packageName/versionCode가 표시되는지 확인한다. GitHub 업로드, Actions 성공, 카탈로그 반영, 실제 Android 업데이트 결과를 구분해 보고한다. 기기 검사는 기기가 연결되어 있고 사용자 요청 범위에 포함될 때 수행한다.

## 범위와 보존

- Git 작업은 저장소 기본 브랜치에서 한다. 별도 브랜치/작업 트리를 만들지 않으며 다른 사람의 미커밋 작업을 덮거나 일괄 stage하지 않는다.
- 비공개 원본 저장소를 공개로 바꾸지 않는다. 소스가 비공개라면 사용자가 지정한 공개 배포 저장소에 허용된 APK/manifest만 게시한다. 새 저장소 생성·허용 계정 확대·다른 앱 코드 복사는 별도 요청 없이는 하지 않는다.
- 중앙 동기화 워크플로와 관리자 토큰을 모든 앱 저장소에 배포하지 않는다. 기존 중앙 실행기를 재사용한다.
- keystore·비밀번호·환경 파일·DB·생성 APK를 Git에 추가하지 않는다. 버전 태그를 이동하거나 기존 릴리스 asset을 덮어쓰지 않는다.
- 허용 계정 외 저장소, 512 MiB를 넘는 APK, 서명키 교체가 필요하면 현 구성의 제한을 설명하고 그 변경을 별도 범위로 다룬다.

## 다른 프로젝트에서 부르는 예

`$minappstore-publish 이 프로젝트를 확인하고 AIProject-k/대상저장소에 APK와 manifest를 릴리스해서 MinAppStore에 연동해줘.`

`$minappstore-publish 이번에는 게시하지 말고 앱스토어 업데이트용 APK와 manifest만 준비해줘.`

`$minappstore-publish 릴리스를 올렸는데 앱이 안 보여. 변경 없이 원인부터 확인해줘.`
