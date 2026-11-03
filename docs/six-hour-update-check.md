# 6시간 주기 변경 — 2026-11-03

## 동작

- 서버: 공개 `AIProject-k/ApplicationTest`의 실제 운영 워크플로 예약을 `0 */6 * * *`로 변경했다. UTC 00:00·06:00·12:00·18:00, 한국 시간 03:00·09:00·15:00·21:00에 실행을 예약한다. 기존 수동/릴리스 트리거는 유지한다.
- 앱: `update-check`를 6시간 주기로 등록한다. `KEEP` 대신 `UPDATE`를 사용해 기존 설치본의 24시간 예약도 앱 실행 시 갱신한다. 네트워크 연결 조건과 앱을 새로 실행할 때의 즉시 목록 확인은 유지한다.
- 앱 버전: 0.2.1/code3. 새 APK 설치 후 앱을 한 번 실행해야 새 주기가 적용된다. 주기 변경만으로 기존 폰의 APK가 자동 교체되지는 않는다.
- 관리자 안내, README, 운영 문서와 한·영 배포 스킬/설치본도 6시간 기준으로 맞췄다.

Android는 실행 중 작업을 취소하지 않고 기존 예약을 갱신하는 [WorkManager UPDATE 정책](https://developer.android.com/reference/androidx/work/ExistingPeriodicWorkPolicy)을 사용한다. 배터리·네트워크·OS 상태에 따라 실제 실행은 늦어질 수 있다. [GitHub 예약 실행](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule)도 정확한 정시 실행을 보장하지 않으므로 예약 설정과 실제 실행 증거를 구분한다.

## 검증

- 실제 공개 워크플로 main 커밋 `292875e120f763d9336853c5fcb5bbf1fff5d9f0`. GitHub에서 다시 읽어 예약식 한 줄 외에는 기존 파일과 동일함을 확인했다. 기존 실행기·토큰·권한 설정을 변경하지 않았다.
- 변경 후 [수동 동기화 41607617930](https://github.com/AIProject-k/ApplicationTest/actions/runs/41607617930) 성공. 이는 실제 동기화 성공 증거이며, 6시간 동안 기다려 예약 실행까지 관찰한 결과는 아니다.
- Android assembleDebug, testDebugUnitTest 8개, lintDebug와 assembleDebugAndroidTest 성공. 기존 예약의 6시간/네트워크 조건을 확인하는 `UpdateScheduleDeviceTest`를 추가했다. 현재 기기가 연결되지 않아 기기 테스트와 24시간→6시간 실기기 전환은 실행하지 않았다.
- 현재 main의 서버 테스트 43개·TypeScript 검사, Python 39개와 스킬 구조 검증 통과.
- Cloudflare는 마지막 실제 배포 소스(`15ed3fe`)에 관리자 안내 문구만 바꾼 스냅샷을 사용했다. 아직 운영에 배포되지 않은 main의 다른 서버 변경을 함께 반영하지 않았다. 이 배포 스냅샷의 서버 테스트 38개와 TypeScript 검사도 통과했다.
- Worker 배포 `4916a565-b69d-4b83-a64c-2a717b61d722` 성공. 운영 HTML200, 한·영 6시간 안내, 실제 script/style CSP 해시와 기존 관리자 키의 조회/동기화 API 인증200을 확인했다. 사용자 연동·비활성 선택과 카탈로그를 재설정하지 않았다.

APK는 `artifacts/MinAppStore-0.2.1-debug.apk`이며 개발용 서명이다. APK·실행 스냅샷·토큰은 Git에 추가하지 않는다. 2026-10-22의 이전 배포 기록에 남은 하루 1회 설명은 당시 검증 결과로 보존한다.
