# test (camera_test)

Galaxy 계열 단말에서 자동/프로 모드 카메라와 백그라운드 녹화, 비공개 보관함을 시험하기 위한 학습용 안드로이드 앱입니다. Galaxy S25 Ultra 기준으로 UX와 카메라 컨트롤을 정리합니다.

## 주요 기능

### 카메라

- **자동 모드 기본**: 진입 시 자동 모드로 시작하며 AF(사진 `CONTINUOUS_PICTURE`/영상 `CONTINUOUS_VIDEO`), AE(`AE_MODE_ON`, `AE_ANTIBANDING_AUTO`), AWB(`AWB_MODE_AUTO`)가 동작합니다.
- **프로 모드**: 설정 시트의 "프로 모드" 스위치를 켜면 AWB 모드, 색온도, 셔터 스피드, 조리개 수동 컨트롤이 노출됩니다. OFF로 전환하면 누적된 수동 값이 모두 초기화됩니다.
- **줌 다이얼**: `0.6x / 1x / 2x / 3x / 5x / 10x` 프리셋 + 슬라이더. 단말이 지원하지 않는 배율은 자동으로 숨겨집니다. 핀치 줌은 사용하지 않습니다.
- **화면 비율**: `3:4`, `16:9`, `Full`. Full은 16:9 sensor 출력 기준 디스플레이 비율로 채웁니다.
- **영상 설정**: 해상도(UHD/FHD/HD/SD), FPS(30/60), HDR 10-bit(지원 단말 한정), 최대 녹화 시간, 반복 녹화 횟수, 위치 태그(기본 OFF).

### 백그라운드 녹화

- 녹화 시작 시점에만 `CameraForegroundService`가 foreground로 격상됩니다. 비녹화 상태로 카메라 화면을 닫으면 service가 즉시 정리됩니다.
- foreground service 알림은 `동기화 중 / 백그라운드 작업이 진행 중입니다 / 작업 종료`로 일반화되어 있습니다.
- 백그라운드 상태에서 볼륨 업 또는 볼륨 다운을 2초 이상 길게 누르면 녹화가 종료됩니다. foreground에서는 앱 내 녹화 종료 버튼을 사용합니다.
- 흔들기 종료는 사용하지 않습니다.
- 화면 OFF / 백그라운드 전환 시 `detachPreview()`가 녹화 중이면 `setSurfaceProvider(null)`을 건너뛰어 정지 프레임 저장을 방지합니다. `PreviewView`는 `implementationMode="compatible"`로 보강되어 있습니다.

### 비공개 보관함

- 촬영 결과는 `getExternalFilesDir(null)/private_vault`에 저장하고 `.nomedia`로 미디어 스캔을 차단합니다.
- 보관함 화면에서 인앱 뷰어로 이미지/영상을 재생하고, 길게 눌러 공개 갤러리(`DCIM/StudyVault`, `Movies/StudyVault`)로 내보내거나 삭제할 수 있습니다.

## Android 14+ 권한 / FGS

`AndroidManifest.xml`은 `FOREGROUND_SERVICE_CAMERA`와 `FOREGROUND_SERVICE_MICROPHONE`을 함께 선언하고, service의 `foregroundServiceType`을 `camera|microphone`으로 둡니다. `startForeground()` 호출 시 `FOREGROUND_SERVICE_TYPE_CAMERA or FOREGROUND_SERVICE_TYPE_MICROPHONE`을 전달합니다.

## 동작 범위 / 의도적으로 하지 않는 것

- 강제 셔터 무음 우회: 하지 않습니다. 셔터음 처리는 Galaxy 설정의 사용자 선택에 맡깁니다.
- 핀치 줌: 구현하지 않습니다.
- 흔들기 종료: 사용자 요구에 따라 제거되었습니다.
- 파일 크기 최적화 옵션: 실제 recorder 정책과 연결되지 않아 제거되었습니다.
- Android 시스템의 카메라/마이크 사용 표시 숨김: 시도하지 않습니다.

## 작업 정의서

세부 작업 정의는 [`task260522.md`](task260522.md)와 [`notice.md`](notice.md)를 참고하세요.
