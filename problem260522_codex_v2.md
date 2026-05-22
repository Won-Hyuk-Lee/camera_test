# problem260522_codex_v2.md

## 목적

`problem260522.md`, `problem260522_codex.md`, `problem260522_claude.md`를 현재 소스 기준으로 다시 대조한 종합 의견이다. 특히 사용자가 실제로 겪은 "앱을 종료했는데도 백그라운드에 남아 발열이 심해진 문제"를 최우선 장애로 추가한다.

이 문서는 구현 지시서가 아니라 문제 정의와 우선순위 정리 문서다. 다음 작업자는 이 문서를 기준으로 수정 범위를 나누고, 실제 코드 수정 후 실기기 검증을 수행해야 한다.

## 결론

현재 가장 위험한 문제는 백그라운드 녹화 freeze보다 먼저, **녹화 중이 아닌 상태에서도 `CameraForegroundService`가 계속 살아남을 수 있는 서비스 생명주기 결함**이다. 이 문제는 발열, 배터리 소모, 카메라/센서 점유, 사용자 불신으로 바로 이어지므로 P0로 처리해야 한다.

Claude 문서에는 유효한 지적도 많지만, 현재 체크아웃 기준으로 틀린 판단도 있다.

- `detachPreview()` 녹화 중 패치가 이미 반영됐다는 판단은 틀렸다.
- Android 14 microphone foreground service 설정이 이미 완료됐다는 판단도 틀렸다.
- `withAudioEnabled()` 반환값을 버려서 오디오가 무조건 빠진다는 판단은 CameraX 1.3.4 API 구현 기준으로 과장 또는 오판이다.
- 0.6x 줌/초광각 전환, 파일 크기 최적화 데드코드, 비율 0-size 위험, PrivateVault 메인 스레드 IO 등은 유효한 지적이다.

## P0. 앱 종료 후 서비스 잔존 및 발열 문제

### 현재 상태

`CameraFragment`는 카메라 화면에 진입할 때 항상 foreground service를 시작한다.

- `app/src/main/java/com/example/camera2study/ui/CameraFragment.kt:127`
  - `CameraForegroundService.start(ctx)`

이 서비스는 생성 즉시 `CameraController`를 만들고 CameraX 초기화를 시작한다.

- `app/src/main/java/com/example/camera2study/CameraForegroundService.kt:97-102`
  - `controller = CameraController(applicationContext)`
  - `controller.init(this)`

동시에 볼륨 리시버와 가속도 센서 리스너도 등록한다.

- `app/src/main/java/com/example/camera2study/CameraForegroundService.kt:112-121`

하지만 `CameraFragment.onDestroyView()`에서는 preview만 detach하고 service bind만 해제한다.

- `app/src/main/java/com/example/camera2study/ui/CameraFragment.kt:637-650`
  - `controller.detachPreview()`
  - `unbindService(serviceConnection)`

여기서 `CameraForegroundService.stop(ctx)` 또는 `stopSelf()`는 호출되지 않는다. 즉, 카메라 화면을 떠나거나 앱을 닫아도 service가 계속 살아남을 수 있다.

또한 service는 `START_STICKY`를 반환한다.

- `app/src/main/java/com/example/camera2study/CameraForegroundService.kt:139`

이는 시스템이 service를 다시 살릴 수 있는 정책이다. 녹화 중에는 의미가 있을 수 있지만, 비녹화 대기 상태에서는 발열/배터리 소모를 키우는 위험한 선택이다.

### 문제점

- 녹화하지 않는 상태에서도 foreground service가 남을 수 있다.
- service가 남으면 `CameraController`, CameraX provider, 센서 리스너, 볼륨 리시버가 함께 살아남을 수 있다.
- 사용자는 앱을 종료했다고 생각하지만 실제로는 카메라 관련 service가 계속 남는다.
- 장시간 방치 시 발열과 배터리 급소모가 발생할 수 있다.

### 필수 수정 방향

1. 녹화 중이 아닌 상태에서 카메라 화면을 떠나면 service를 반드시 종료한다.
   - `onDestroyView()` 또는 더 적절한 lifecycle 지점에서 `if (!controller.isRecording()) CameraForegroundService.stop(ctx)` 처리
   - service 종료 시 `controller.release()`가 호출되어 `cameraProvider.unbindAll()`까지 가야 한다.

2. service 시작 시점을 재검토한다.
   - 현재는 카메라 화면 진입만 해도 foreground service가 시작된다.
   - 더 안전한 구조는 "녹화 시작 시 foreground service 시작"이다.
   - 단순 preview 상태는 Activity/Fragment lifecycle에 묶고, 녹화가 시작될 때만 service로 승격하는 구조가 발열 면에서 더 안전하다.

3. `START_STICKY`는 녹화 중에만 허용하는 정책으로 바꾼다.
   - 비녹화 상태의 service는 `START_NOT_STICKY` 또는 self-stop이 맞다.
   - service가 재시작됐는데 `recording == null`이면 즉시 `stopSelf()`해야 한다.

4. 최근 앱에서 제거/뒤로가기/홈 이동 정책을 명확히 정한다.
   - 녹화 중: 사용자가 명시적으로 백그라운드 녹화를 원한 경우에만 유지
   - 녹화 중 아님: 즉시 release + service stop
   - 녹화 중 앱 종료: "녹화 계속"과 "녹화 종료" 정책을 UI/알림으로 명확히 제공

### 완료 조건

- 앱에서 카메라 화면을 열었다가 녹화하지 않고 나가면 foreground service 알림이 사라진다.
- `adb shell dumpsys activity services com.example.camera2study`에서 비녹화 상태 service가 남지 않는다.
- 비녹화 상태에서 화면을 끄고 10분 이상 두어도 발열이 재현되지 않는다.
- 녹화 중에는 service가 유지되고, 녹화 종료 후에는 service가 자동 종료된다.

## P0. 백그라운드 녹화 freeze 문제

### 현재 소스 기준

Claude 문서는 `detachPreview()`에 녹화 중 skip 분기가 이미 반영됐다고 했지만 현재 소스는 아니다.

- `app/src/main/java/com/example/camera2study/CameraController.kt:143-145`
  - `previewUseCase?.setSurfaceProvider(null)`
  - `previewView = null`

따라서 `problem260522.md`와 `problem260522_codex.md`의 기존 판단이 현재 소스와 더 맞다.

### 추가로 고려할 점

Claude 문서의 "SurfaceView 기반 PreviewView가 윈도우 종료 시 Surface를 잃고 세션을 흔들 수 있다"는 분석은 타당하다. 단순히 `setSurfaceProvider(null)`을 건너뛰는 것만으로 모든 단말에서 해결된다고 단정하면 안 된다.

### 수정 방향

- 1차 조치: 녹화 중 `detachPreview()`에서 `setSurfaceProvider(null)`을 호출하지 않도록 한다.
- 2차 조치: 녹화 중 앱 백그라운드/화면 OFF 전환 시 Preview use case를 안전하게 분리하고 VideoCapture만 유지할 수 있는 구조를 검토한다.
- 3차 조치: `PreviewView`의 `implementationMode="compatible"` 적용 여부를 실기기에서 비교한다.
- 4차 조치: `VideoRecordEvent.Status` 또는 파일 분석으로 실제 프레임 증가 여부를 검증한다.

## P0. Android 14+ camera/microphone foreground service 설정 누락

### 현재 소스 기준

Claude 문서는 microphone foreground service가 이미 패치됐다고 했지만 현재 소스에는 없다.

- `app/src/main/AndroidManifest.xml`
  - `FOREGROUND_SERVICE_CAMERA`는 있음
  - `FOREGROUND_SERVICE_MICROPHONE`은 없음
  - service type은 `android:foregroundServiceType="camera"`만 있음

- `app/src/main/java/com/example/camera2study/CameraForegroundService.kt:106-107`
  - Android 14+에서 `ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA`만 넘김

### 수정 방향

- manifest에 `android.permission.FOREGROUND_SERVICE_MICROPHONE` 추가
- service type을 `camera|microphone`으로 변경
- Android 14+ `startForeground()` 호출에서 camera와 microphone type을 함께 전달
- 마이크 mute 상태에서는 microphone type이 필요한지 별도 검토하되, 기본 녹화가 오디오 포함이라면 camera/microphone 병렬 선언이 안전하다.

## P0/P1. S25 Ultra 줌/렌즈 정상화

### 유효한 지적

Claude 문서의 0.6x 지적은 맞다. 현재 `0.6x` 버튼은 초광각 물리 렌즈 전환이 아니라 현재 camera의 zoom ratio 설정일 뿐이다.

- `app/src/main/java/com/example/camera2study/ui/CameraFragment.kt:287-289`
  - `controller.setZoomRatio(0.6f)`

현재 활성 camera의 `minZoomRatio`가 1.0이면 0.6은 1.0으로 clamp된다.

- `app/src/main/java/com/example/camera2study/CameraController.kt:264-269`

### 수정 방향

- mm 단위 `lensChipGroup` 제거
- S25 Ultra 기준 프리셋을 `0.6x`, `1x`, `2x`, `3x`, `5x`, `10x`로 정리
- 0.6x는 초광각, 3x/5x는 망원 계열을 사용하도록 logical camera zoom 또는 physical camera 전환 전략 검증
- 단말 지원 범위를 벗어나는 프리셋은 숨김 또는 비활성화

## P1. 자동모드/프로모드 분리

사용자 결정사항 기준으로 전체 갤럭시 카메라 복제는 목표가 아니다. 핵심은 자동모드 품질이다.

### 자동모드 요구

- 연속 AF
- 자동 AE
- 자동 AWB
- 지원 가능한 경우 자동 HDR
- 지원 가능한 경우 얼굴/장면 기반 자동 보정
- 수동 설정이 자동모드에 남지 않도록 reset

### 프로모드 요구

프로모드는 필요할 때만 사용하는 별도 모드로 둔다. 프로모드라고 부르려면 최소한 ISO, 셔터, WB, EV, focus를 분리해서 다뤄야 한다. 현재 상세설정은 일부 수동 파라미터만 섞여 있어 "프로모드"라고 부르기에는 부족하다.

## P1. 화면비와 동영상 설정 정상화

### 화면비

현재 `Full`은 실제 출력 비율이 아니라 preview layout을 늘리는 성격이 강하다.

- `CameraController`는 `RATIO_FULL`도 사실상 4:3 target ratio로 처리한다.
- `CameraFragment`는 `PreviewView` height만 `MATCH_PARENT`로 바꾼다.

수정 시 프리뷰 비율과 저장 파일 비율이 일치해야 한다.

### 동영상 설정

현재는 CameraX `Quality.UHD/FHD/HD/SD` 정도만 제공한다. S25 Ultra 기준으로 설정에서 해상도와 FPS를 분리해야 한다.

필요 항목:

- 해상도: 8K/UHD/FHD/HD 중 지원 항목
- FPS: 24/30/60/120/240 중 지원 항목
- HDR/10-bit: 지원 시 토글
- Log Video: 지원 시 프로모드 또는 고급 영상 설정으로 분리
- 영상 안정화: 지원 시 토글
- 파일 크기 최적화: 실제 HEVC/H.265 또는 bitrate 정책과 연결

## P1. 데드코드와 UX 거짓 정보

### 파일 크기 최적화

`isFileSizeOptimizationEnabled`는 설정 UI와 변수만 있고 실제 recorder 설정에 쓰이지 않는다.

- `app/src/main/java/com/example/camera2study/CameraController.kt:87`
- `app/src/main/java/com/example/camera2study/ui/SettingsBottomSheet.kt:238-240`

구현하거나 UI에서 제거해야 한다.

### README 과장

README는 현재 구현보다 완성도가 높은 것처럼 설명한다. 구현 전까지는 "현재 구현"과 "예정"을 나눠야 한다.

## Claude 문서 항목별 판단

### 채택

- `lensChipGroup` 제거 필요
- 백그라운드 freeze 해결은 단순 preview detach skip만으로 부족할 수 있음
- `isFileSizeOptimizationEnabled` 데드코드
- `updateRatioSelection()` 초기 width 0 위험
- PrivateVault 메인 스레드 IO/디코딩 위험
- VideoView dismiss 시 stopPlayback 필요
- 0.6x/2.0x 줌 정합성 문제
- 줌 슬라이더 초기값과 실제 지원 범위 불일치
- 핀치 줌, 수평선, HDR 자동 인식, 자동 AF/AE 개선은 선택 구현 후보

### 정정 필요

- `detachPreview()` 패치 완료: 현재 소스 기준 틀림
- microphone FGS 완료: 현재 소스 기준 틀림
- `withAudioEnabled()` 반환값 미할당으로 오디오가 항상 빠짐: CameraX 1.3.4의 `PendingRecording.withAudioEnabled()`는 내부 `mAudioEnabled`를 true로 바꾸고 `this`를 반환하므로, 현재 코드가 무조건 오디오 없는 녹화를 만든다는 판단은 틀릴 가능성이 높다. 다만 명확성을 위해 반환값을 받아 쓰는 스타일로 바꾸는 것은 가능하다.

### 누락

- 앱 종료 후 비녹화 상태 service 잔존 및 발열 문제
- 비녹화 상태에서도 foreground service가 시작되는 구조
- `START_STICKY`가 비녹화 상태에 부적절한 문제
- 녹화 중/비녹화 중 lifecycle 정책 분리

## 최종 우선순위

| 우선순위 | 항목 | 이유 |
|---|---|---|
| P0 | 앱 종료 후 비녹화 service 잔존/발열 | 실제 사용자 위험, 배터리/발열/카메라 점유 |
| P0 | 백그라운드 녹화 freeze | 핵심 기능 실패 |
| P0 | Android 14+ camera/microphone FGS 정리 | 최신 OS 정책 위반 가능 |
| P0 | 녹화 종료 후 service 자동 종료 정책 | 발열 문제와 직접 연결 |
| P1 | S25 Ultra 줌 프리셋/렌즈 정상화 | 공식 기준 정합성 |
| P1 | 자동모드/프로모드 분리 | 사용자 결정사항 반영 |
| P1 | 화면비 실제 출력 정상화 | 프리뷰/결과물 불일치 방지 |
| P1 | 동영상 해상도/FPS/HDR 설정 분리 | S25 Ultra 설정 정합성 |
| P1 | 파일 크기 최적화 데드코드 제거 또는 구현 | UX 거짓 정보 제거 |
| P1 | PrivateVault 메인 스레드 IO 개선 | ANR/발열 위험 |
| P2 | 핀치 줌, 수평선, pause/resume, HDR indicator | 사용성 개선 |
| P2 | README 현재 구현/예정 분리 | 문서 신뢰도 |

## 권장 구현 순서

1. 비녹화 상태 service 잔존 문제부터 수정한다.
   - 카메라 화면 이탈 시 녹화 중 아니면 service stop
   - service 재시작 시 recording 없으면 self-stop
   - START_STICKY 정책 재검토

2. 녹화 안정성 수정
   - `detachPreview()` freeze 방지
   - Preview use case 분리 전략 검증
   - VideoRecordEvent.Status로 프레임/바이트 증가 확인

3. Android 14+ FGS 타입 수정
   - manifest permission/type
   - `startForeground()` type mask

4. S25 Ultra 줌/비율/영상 설정 정상화
   - 줌 프리셋
   - Full 비율 실제 출력
   - 해상도/FPS/HDR 설정

5. 자동모드/프로모드 분리
   - 자동모드 reset 정책
   - 프로모드 manual request 범위

6. 성능/문서 정리
   - PrivateVault IO
   - README 정정
   - lintDebug 통과

## 실기기 검증 체크리스트

- 카메라 화면 진입 후 녹화하지 않고 앱 종료: foreground service 알림이 사라지는지 확인
- 카메라 화면 진입 후 녹화하지 않고 화면 OFF 10분: 발열 재현 여부 확인
- 녹화 시작 후 홈 이동: 녹화 유지 확인
- 녹화 종료 후 service 자동 종료 확인
- 최근 앱에서 제거 시 녹화 중/비녹화 중 정책 확인
- `adb shell dumpsys activity services com.example.camera2study`로 service 잔존 확인
- `adb shell dumpsys media.camera` 또는 logcat으로 camera 점유 해제 확인
- 화면 OFF 녹화에서 실제 프레임 증가 확인
- 오디오 켬/끔 녹화 파일 각각 확인
- 0.6x/1x/2x/3x/5x/10x 프리셋 실기기 확인
- 선택한 화면비와 저장 파일 비율 비교
- `./gradlew.bat assembleDebug`
- `./gradlew.bat lintDebug`
