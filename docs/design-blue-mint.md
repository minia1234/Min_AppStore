# MinAppStore Blue / Mint 디자인

사용자가 승인한 A(Blue Minimal)를 기본 라이트 모드, B(Mint Dark)를 다크 모드로 적용했다.

- Android는 시스템 다크 모드, 관리자 웹은 브라우저의 `prefers-color-scheme` 설정을 따른다.
- 라이트: 블루 `#0959E8`, 배경 `#F6F9FF`, 흰 카드, 네이비 본문.
- 다크: 민트 `#7DF4D0`, 배경 `#101719`, 차콜 카드, 밝은 본문.
- 앱 홈은 짧은 소개, 검색, 분류, 실제 업데이트 카드, 앱 목록 순서다. 업데이트가 있는 경우에만 업데이트 카드를 표시한다.
- 설치·열기 버튼은 앱 목록에서 바로 사용할 수 있고, 카드를 누르면 기존 상세 화면을 연다.
- 기존 네 개 탭(스토어·내 앱·다운로드·설정), 연동·해제·비활성화, 설치 검증, 패키지와 서명은 유지했다.
- 시안의 가상 앱·버전과 예시 메뉴는 구현 데이터에 추가하지 않았다.

## 2026-10-22 검증

- Android assembleDebug, testDebugUnitTest(8개), lintDebug 성공, lint 오류 0개.
- 양쪽 테마의 본문·버튼·주요 카드 텍스트 대비 4.5:1 이상 검사 통과.
- 실제 Pixel 4에 기존 데이터 삭제 없이 APK 교체 설치 성공. 두 모드의 홈 화면을 확인한 뒤 시스템 야간 모드는 원래 값(no)으로 복원했다.
- 로컬 캡처: `artifacts/pixel4/design/minappstore-blue-light.png`, `minappstore-mint-dark.png`. 캡처와 APK는 Git에 올리지 않는다.
- 관리자 웹 운영 배포: `https://minappstore-api.eum-user.workers.dev/admin`, HTML 200과 양쪽 테마 CSS, 실제 script/style CSP 해시 확인.
- 서버 테스트 36개, TypeScript 검사, 동기화 Python 테스트 33개 통과.
- 무료 공개 동기화 실행 [39756281216](https://github.com/AIProject-k/ApplicationTest/actions/runs/39756281216) 성공. 저장소 목록 캐시 반영 후 인증 조회와 비활성화/복원 API를 실제 운영에서 확인했다.

브라우저 자동화가 비활성 상태라 관리자 웹의 실제 브라우저 화면 배치와 클릭 흐름은 이번에 확인하지 않았다. Android 설치·업데이트 전체 시나리오도 이번 디자인 변경에서 재실행하지 않았다. APK는 기존 개발용 서명이다.
