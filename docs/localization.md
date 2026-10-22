# 한국어·영어 지원

## 사용 방법

- Android: 기기 언어를 따릅니다. Android 13 이상에서는 시스템의 앱별 언어 설정에서 MinAppStore의 한국어/English를 선택할 수도 있습니다. 지원하지 않는 언어는 영어로 표시합니다.
- 관리자 웹: 브라우저의 첫 번째 선호 언어가 한국어이면 한국어, 그 외에는 영어로 표시합니다. 언어 설정을 바꾼 뒤 페이지를 새로고침하세요. 새로고침하면 관리자 토큰을 다시 입력해야 합니다.
- 스킬: `$minappstore-publish`에 한국어나 영어로 요청하면 같은 언어로 안내합니다. [한국어 가이드](../skills/minappstore-publish/references/publishing-guide.md), [English guide](../skills/minappstore-publish/references/publishing-guide.en.md)를 제공합니다.

앱 화면·상태·접근성 안내와 앱 자체 오류/알림 문구를 Android 리소스로 분리했습니다. 한국어/영어 문자열 116개와 업데이트 알림 복수형을 제공합니다. 검색의 전체 분류와 정렬 상태는 번역된 문구가 아닌 언어 독립 값으로 유지합니다.

관리자 웹은 정적 문구·동적 상태·버튼·접근성 레이블을 번역하며, API 요청에 선택 언어를 전달합니다. API의 인증·검증·동기화 오류 응답도 해당 언어로 반환합니다. 관리자 토큰 처리·연동·비활성·서명 검증 정책은 유지합니다.

## English

The Android app follows the device language. On Android 13+, select Korean or English in the system's per-app language settings for MinAppStore. Other languages fall back to English.

The admin page uses Korean when the browser's first preferred language is Korean; otherwise it uses English. Reload after changing browser language. Reloading also clears the in-memory admin token.

Use `$minappstore-publish` in English or Korean. The skill follows the request language and includes both publishing guides and metadata examples.

## 2026-10-22 검증

- Android assembleDebug, assembleDebugAndroidTest, 단위 테스트 8개, lintDebug 성공.
- 실제 Pixel 4의 LocalizationDeviceTest 3개 성공: 두 언어 문자열/서식, 알림 단수·복수, 검증 오류, 미지원 언어의 영어 fallback.
- 실제 Pixel 4에서 앱별 언어 en-US/ko-KR로 홈을 열고 화면 XML과 캡처를 확인했습니다. 확인 후 기존 앱별 언어(시스템 따름)를 복원했습니다. 데이터 삭제 없이 APK를 교체 설치했고 다른 기기의 설정은 변경하지 않았습니다.
- 서버 테스트 38개와 TypeScript 검사 성공. 관리자 스크립트의 정적 문구·입력 힌트·접근성 레이블·로딩 메시지는 Node VM/DOM 대역으로 확인했습니다.
- Python 34개 성공. 한국어·영어 리소스 이름과 서식 인자의 일치도 검사했습니다.
- Cloudflare 배포 `8be9cbb4-a582-4be1-936d-8ad896f5da34`: 운영 관리자 HTML200, 실제 script/style CSP 해시, 영어/한국어 미인증 API401 메시지를 확인했습니다.
- 스킬 구조 검증 성공. 설치된 스킬을 갱신했고 영어 metadata 예시로 실제 ApplicationTest APK의 manifest 생성에 성공했습니다.

## 범위

게시자가 작성한 앱 이름·설명·분류·태그·릴리스 노트, 저장소 설명은 자동 번역하지 않습니다. 이전 언어로 이미 저장된 다운로드 오류 기록과 외부 시스템/라이브러리의 오류 메시지도 원문이 표시될 수 있습니다.

관리자 웹의 실제 브라우저 배치/클릭 검사는 브라우저 자동화가 비활성 상태라 수행하지 않았습니다. 이번 변경에서는 앱의 설치·업데이트 전체 시나리오를 재실행하지 않았으며, 새 APK는 기존 개발용 서명을 유지합니다. 언어 지원을 위해 새 릴리스나 유료 서비스를 만들지 않았습니다.
