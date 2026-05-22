# notice.md

To: Claude

`task260522.md`를 다시 점검했습니다. 큰 틀은 현재 합의사항을 잘 반영하고 있으므로, 작업 정의서는 `task260522.md`를 source of truth로 보고 진행하면 됩니다. 아래는 작업 중 특히 놓치지 말아야 할 보충 지시입니다.

## 우선순위

1. P0부터 끝내세요.
   - 앱 종료 후 service/camera/sensor가 남는 발열 문제
   - 백그라운드/화면 꺼짐 녹화 freeze
   - Android 14+ camera/microphone foreground service 타입
   - 볼륨 업/다운 2초 길게 누르기 종료
   - 촬영 시작/종료 알림 최소화

2. P0가 빌드와 수동 검증까지 통과하기 전에는 줌/UI/프로모드 같은 P1 작업을 크게 벌리지 않는 편이 안전합니다.

## 현재 소스에서 재확인한 사실

- `app/src/main/res/values/strings.xml`의 `app_name`은 현재 `Camera2Study`입니다. 사용자는 앱 표시 이름을 `test`로 바꾸라고 했습니다.
- `AndroidManifest.xml`은 `android:label="@string/app_name"`을 사용합니다.
- 런처 아이콘은 `@mipmap/ic_launcher`를 사용하고, adaptive icon은 `ic_launcher_background` / `ic_launcher_foreground`를 참조합니다.
- 사용자는 별도 커스텀 아이콘을 원하지 않습니다. 새 아이콘을 만들지 말고 기본 아이콘 기준으로 정리하세요.
- `AndroidManifest.xml`에는 현재 `FOREGROUND_SERVICE_MICROPHONE`이 없습니다.
- service type은 현재 `android:foregroundServiceType="camera"`만 지정되어 있습니다.
- `CameraForegroundService.startForeground()`는 현재 `FOREGROUND_SERVICE_TYPE_CAMERA`만 전달합니다.
- `CameraForegroundService.onStartCommand()`는 현재 `START_STICKY`를 반환합니다.
- `CameraForegroundService`는 현재 `SensorEventListener`를 구현하고 센서 기반 stop 로직을 가지고 있습니다. 사용자는 흔들기 종료를 원하지 않습니다.
- `CameraController.detachPreview()`는 현재 preview surface provider를 null로 만드는 경로가 있습니다. 녹화 중 freeze 의심 지점입니다.
- `lensChipGroup`은 XML과 `CameraFragment` 코드에 아직 남아 있습니다.
- `isFileSizeOptimizationEnabled`는 현재 변수와 UI 연결만 있고 실제 recorder 정책과 연결되지 않습니다. 사용자는 이 기능 제거를 선택했습니다.

## 사용자가 확정한 선택

- 셔터 무음 우회는 신경 쓰지 마세요. 강제 무음 구현 금지.
- 핀치 줌 구현 금지. 줌은 다이얼/프리셋 방식으로 정리.
- 흔들어서 녹화 종료 금지. 백그라운드 종료는 볼륨 업 또는 다운 2초 이상 길게 누르기.
- foreground service 알림 문구:
  - 제목: `동기화 중`
  - 내용: `백그라운드 작업이 진행 중입니다`
  - 액션: `작업 종료`
- 촬영 시작/종료 Toast, snackbar, popup성 알림은 가능한 한 제거.
- 위치 태그는 설정으로 넣고 기본값 OFF.
- 빠른 녹화의 0.5초 지연 자체는 버그로 보지 않음.

## GitHub/커밋 주의

- 코드 수정 전에 GitHub 이슈를 먼저 생성하세요.
- 모든 커밋 본문에 `Issue: #번호`를 넣으세요.
- `git add .` 사용 금지. 파일을 개별 지정해서 stage.
- 현재 브랜치에서 작업하세요. 새 브랜치를 만들지 마세요.
- `Co-Authored-By` 금지.
- 한 번에 몰아서 커밋하지 말고 P0/P1/P2 단위로 작게 커밋하세요.

## 구현 시 주의

- S25 Ultra 줌 프리셋은 `0.6x/1x/2x/3x/5x/10x`가 목표지만, 단말/CameraX가 지원하지 않는 조합은 숨기거나 비활성화하세요. unsupported 값을 억지로 hardcode하지 마세요.
- 해상도/FPS/HDR도 마찬가지로 지원 여부 확인 후 노출하세요.
- `withAudioEnabled()` 반환값 문제는 "모든 오디오가 무조건 빠짐"으로 단정하지 마세요. 다만 가독성을 위해 반환값을 변수에 다시 받는 정리는 가능합니다.
- P0 lifecycle 수정에서는 "녹화 중 service 유지"와 "비녹화 상태 service 종료"를 명확히 분리하세요.
- service 종료는 `Recording.Finalize` 이후 정리되도록 맞추세요. stop 직후 바로 `stopSelf()`해서 finalize와 race가 나지 않게 하세요.
- 이전 문제 문서들은 일부 과장 또는 정정된 주장이 섞여 있습니다. 충돌하면 `task260522.md`와 이 `notice.md`를 우선하세요.

## 검증 기준

- 최소 `.\gradlew.bat assembleDebug`와 `.\gradlew.bat lintDebug`를 실행하세요.
- 가능하면 Galaxy S25 Ultra 실기기로 아래를 직접 확인하세요.
  - 비녹화 상태 앱 종료 후 service가 남지 않는지
  - 홈 이동/화면 꺼짐 녹화 파일이 정지 프레임이 아닌지
  - 볼륨 업/다운 2초 길게 누르기로 백그라운드 녹화가 종료되는지
  - 흔들기로는 종료되지 않는지
  - 알림 문구가 `동기화 중`으로 표시되는지
  - 앱 이름이 `test`로 보이는지
  - 아이콘이 별도 커스텀 아이콘이 아닌 기본 아이콘 기준인지

