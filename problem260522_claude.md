# 📌 백그라운드 촬영 안전성 보강 및 갤럭시 카메라 정합성 개선 과제 (problem260522_claude.md)

`problem260522.md` 1차 정의서를 기준으로, 코드 베이스 정밀 분석 후 **누락된 문제점 / 잘못된 정의 / 추가 발견 버그 / 갤럭시 카메라와의 정합성 결함 / 추가 구현 희망 기능** 까지 통합 정리한 확장 보고서입니다.

---

## 0. 용어 정정 — "빠른 녹화" 의 개념

`HomeFragment` 의 **빠른 녹화(Fast Record)** 기능의 본질은 "카메라 화면에 진입하지 않고도 즉시 녹화 파이프라인을 가동" 하는 데에 있습니다. 즉, **홈에서 → 카메라 UI 거치지 않고 → 백그라운드 녹화로 직진** 한다는 의미의 "빠름" 이지, 클릭부터 녹화 시작까지의 절대 시간이 짧다는 의미가 아닙니다.

따라서 `CameraFragment.onViewCreated()` 의 자동 시작 시점에 카메라 안정성을 위해 부여된 0.5초의 `postDelayed` 는 본 기능의 정의와 충돌하지 않습니다.

---

## 1. `problem260522.md` 1차 정의서의 보강·정정 사항

### 1-A. 문제 1 (`lensChipGroup` UI 제거) — **코드 미반영 상태**
- 문서에는 "제거 예정" 으로 적혀 있으나, 실제 코드에 아직 그대로 존재:
  - `app/src/main/res/layout/fragment_camera.xml:251-261` 의 `ChipGroup` 뷰
  - `app/src/main/java/com/example/camera2study/ui/CameraFragment.kt:231-271` 의 `populateLensChips()` / `updateLensIndicator()` 내부 칩 갱신 로직
- **조치**: XML 뷰 + 관련 코틀린 코드 + `com.google.android.material.chip.Chip` 임포트 모두 정리해야 함.

### 1-B. 문제 2-A (백그라운드 freeze) — **현재 해결책으로는 부족함**
1차 정의서가 제안한 해결책 = `detachPreview()` 에서 `if (isRecording())` 일 때 `setSurfaceProvider(null)` 건너뛰기는 이미 `CameraController.kt:143-148` 에 반영되어 있음. 그러나 동일 freeze 현상이 재현됨.

**근본 원인**:
- `fragment_camera.xml:10-17` 의 `PreviewView` 가 `implementationMode` 미지정 → CameraX 기본값 = **PERFORMANCE (SurfaceView 기반)**.
- SurfaceView 의 underlying `Surface` 는 윈도우가 사라지는 즉시 OS 가 destroy 함. `SurfaceProvider` 를 떼지 않아도 Surface 자체가 dead.
- Camera2 단일 `CaptureSession` 은 출력 타깃 중 하나라도 invalid 가 되면 전체 세션이 freeze → VideoCapture 측 MediaCodec 입력 surface 도 0fps.

**필수 추가 조치 (둘 중 하나라도)**:
1. `CameraFragment.onStop()` 시점에 녹화 중이면 `cameraProvider.unbind(previewUseCase)` 로 Preview UseCase 만 일시 분리, `onStart()` 복귀 시 재바인딩.
2. `PreviewView` 에 `app:implementationMode="compatible"` 추가 (TextureView 기반 → Surface 보존성 ↑).

### 1-C. 문제 2-B (Android 14 마이크 FGS 누락) — **이미 패치 완료 상태**
- `AndroidManifest.xml:8` 에 `FOREGROUND_SERVICE_MICROPHONE` 권한 존재.
- `AndroidManifest.xml:44` 에 `foregroundServiceType="camera|microphone"` 명시.
- **조치**: 1차 정의서에서 본 항목을 "(완료)" 표기 또는 제거.

---

## 2. 추가 발견된 코드 버그

### 2-A. 마이크 mute 토글 영구 무력화 🔴 P0
**파일**: `app/src/main/java/com/example/camera2study/CameraController.kt:473-475`
```kotlin
if (!isAudioMuted) {
    prep.withAudioEnabled()   // ← 반환값을 버림
}
```
- `PendingRecording.withAudioEnabled()` 는 **새 객체를 반환하는 빌더**. 반환값을 받지 않아 적용되지 않음.
- **결과**: 마이크 mute 여부와 무관하게 **모든 녹화 영상이 오디오 없이 저장됨**.
- **수정**: `prep = prep.withAudioEnabled()`

### 2-B. `isFileSizeOptimizationEnabled` 데드 코드 🟠 P1
- `CameraController.kt:87` 에 변수만 선언.
- `SettingsBottomSheet.kt:24, onFileSizeOptimization(...)` 콜백 / UI 까지 노출.
- 그러나 `Recorder.Builder()` 어디에서도 `setTargetVideoEncodingBitRate()` 등으로 반영하지 않음.
- **결과**: 사용자에게는 동작하는 척, 실제 효과 0. **UX 거짓 정보**.
- **수정**: 실제 구현하거나 UI 와 변수 모두 제거.

### 2-C. `stopRecording()` 의 의미 어긋난 `muteSystemSound()` 호출 🟡 P2
**파일**: `CameraController.kt:524-530`
```kotlin
fun stopRecording() {
    muteSystemSound()       // 종료 직전 무음 진입 시도 - 의도 불명
    recording?.stop()
    ...
}
```
- 시작 시점 무음은 이미 `startRecording()` 에서 처리됨. 종료 시점 무음의 효과는 사실상 종료 효과음 차단이 목적으로 추정되지만 함수명과 위치가 혼동.
- **수정**: `suppressStopSound()` 등으로 의도를 명확히 분리하거나 주석 추가.

### 2-D. `updateRatioSelection()` 초기 호출 시 0-size 버그 🟠 P1
**파일**: `CameraFragment.kt:367-384`
```kotlin
params.height = (binding.previewView.width * 4) / 3
```
- 최초 호출 경로: `restoreCameraSettings()` 내부 → 이 시점에 PreviewView 가 아직 측정 전 → `width == 0` → **height = 0** → 프리뷰 미표시.
- **수정**: `binding.previewView.post { updateRatioSelection(...) }` 로 보내거나 ConstraintLayout `app:layout_constraintDimensionRatio` 사용.

### 2-E. PrivateVault 의 메인 스레드 IO/디코딩 🟠 P1
**파일**: `PrivateVaultFragment.kt:70-89, 238-262`
- `loadMediaFiles()` 의 `listFiles()` + sort 가 메인 스레드.
- `onBindViewHolder` 에서 `BitmapFactory.decodeFile`, `ThumbnailUtils.createVideoThumbnail` 도 메인 스레드.
- 영상 썸네일 디코딩은 매우 무거워 보관 파일 누적 시 **ANR 위험**.
- **수정**: Coil/Glide 또는 코루틴(`Dispatchers.IO`) + LruCache 전환.

### 2-F. VideoView 다이얼로그 리소스 누수 🟡 P2
**파일**: `PrivateVaultFragment.kt:117-134`
- 다이얼로그 dismiss 시 `videoView.stopPlayback()` 호출 누락 → MediaPlayer / 오디오 포커스 누수.
- **수정**: `dialog.setOnDismissListener { videoView.stopPlayback() }` 추가.

### 2-G. WakeLock 해제 안전핀 보강 🟢 P3
**파일**: `CameraController.kt:440-446`
- 30분 타임아웃이 안전핀이지만, `Finalize` 미호출 케이스에서 release 누락 가능.
- **수정**: `wakeLock?.acquire(...)` 직전에 `if (wakeLock?.isHeld == true) wakeLock?.release()` 보강.

### 2-H. `switchFacing()` 후 설정 미저장 🟢 P3
**파일**: `CameraFragment.kt:131-141`
- 전후면 전환 클릭 핸들러가 `saveCameraSettings()` 미호출.
- 앱 재시작 시 사용자의 마지막 전/후면 상태 복원 불가.
- **수정**: 전환 직후 `saveCameraSettings()` 호출 + SharedPreferences 에 `last_lens_facing` 저장.

### 2-I. `Preview.Builder()` 에 `setTargetRotation()` 미설정 🟢 P3
**파일**: `CameraController.kt:165-167`
- 일부 단말에서 프리뷰/녹화 회전 어긋남 가능. Activity 가 portrait 고정이라 영향은 작지만 명시 권장.

### 2-J. 서비스 강제 종료 시 콜백 race 🟢 P3
**파일**: `CameraForegroundService.kt:69-76, 180-186`
- `stopBackgroundRecording()` 직후 즉시 `stopSelf()` → `onDestroy` 의 `controller.release()` 가 발동.
- `Recording.Finalize` 콜백이 서비스 destroy 이후 실행될 수 있어 dead reference 위험.
- **수정**: `Finalize` 콜백 안에서 `stopSelf()` 호출하도록 정렬.

---

## 3. 갤럭시 기본 카메라 대비 잘못 구현된 항목

### 3-A. 0.6x 줌 버튼이 초광각 렌즈로 전환 안 됨 🔴 P0
**파일**: `CameraFragment.kt:260-263`, `CameraController.kt:266-273`
```kotlin
binding.btnZoom06.setOnClickListener {
    controller.setZoomRatio(0.6f)   // 이게 전부
    saveCameraSettings()
}
```
```kotlin
fun setZoomRatio(ratio: Float) {
    ...
    val clamped = ratio.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
    cam.cameraControl.setZoomRatio(clamped)
}
```
- 현재 활성 렌즈가 일반 광각(1.0x~) 이면 `minZoomRatio == 1.0` → `0.6` 이 `1.0` 으로 clamp.
- **결과: 0.6x 버튼을 눌러도 실제로는 1.0x 유지**.
- 갤럭시의 0.6x 는 **초광각 물리 렌즈로 카메라 ID 전환** (디지털 줌 아님).
- **수정**: `backLenses` 에서 `isWide==true` 인 카메라 ID 로 `selectBackLens()` 호출 후 zoom = 1.0 적용.
- 같은 이유로 `CameraFragment.kt:279` 의 하이라이트 조건 `current <= 0.7f` 도 영원히 false 가능.

### 3-B. 2.0x 망원 버튼도 실제 망원 렌즈 전환이 아님 🟠 P1
- 3-A 와 동일 원리. 갤럭시 S25 Ultra 의 3x/5x 망원 모듈을 활용하지 못함.
- 현재는 광각 센서를 디지털 2배 줌 → **화질 저하 + S25 Ultra 하드웨어 강점 무력화**.
- **수정**: 망원 렌즈 후보를 `backLenses` 에서 식별 (focalLength 기준) → 해당 카메라 ID 로 전환.

### 3-C. 줌 슬라이더 초기 `valueFrom=1.0` 하드코딩 🟡 P2
**파일**: `fragment_camera.xml:243`
```xml
android:valueFrom="1.0"
```
- 0.6x 버튼이 있는데 슬라이더 하한이 1.0. `setupZoomListeners` 가 동적으로 갱신해 주지만 인플레이션 직후 잠시 모순 상태 → 화면 깜빡임.
- **수정**: 동적 적용 전 잠정 `valueFrom="0.6"` 으로 표기하거나 `slider.isVisible = false` 후 갱신 완료 시 노출.

### 3-D. 셔터 무음(`isMuteSound`) 한국 단말 미작동 🟡 P2
**파일**: `CameraController.kt:366-378`
- `RINGER_MODE_SILENT` 만 변경하는 우회 처리.
- 한국 출시 단말(갤럭시 포함) 은 카메라 셔터음이 시스템 **ENFORCED** 사운드이므로 ringer mode 와 무관하게 발생.
- 갤럭시 기본 카메라는 자체 사운드 처리라 OS 강제음과 무관.
- **수정**: 셔터 사운드 자체를 자체 처리하도록 검토하거나, 한국 단말에서는 비공식적으로 `AudioManager.STREAM_SYSTEM_ENFORCED` 음소거 시도 (또는 사용자에게 한계 고지).

### 3-E. 모드 전환 시 마이크 버튼이 alpha 처리만 됨 🟢 P3
**파일**: `CameraFragment.kt:213-214, 225-226`
- 사진 모드에서 마이크 버튼이 alpha 0.3 + disabled 로 잔존.
- 갤럭시는 영상 전용 컨트롤을 **visibility GONE** 으로 숨김.
- **수정**: `visibility = View.GONE / VISIBLE` 토글로 변경.

### 3-F. 셔터 변환 애니메이션이 갤럭시 시그니처와 미일치 🟡 P2
**파일**: `CameraFragment.kt:494-507`
- README 35번 항목은 *"중앙 빨간 원이 사각형(■)으로 변하는 애니메이션"* 약속.
- 실제 구현은 단순 `ScaleAnimation` (60% 축소) 만. 사각형 변환 없음.
- **수정**: `shutter_center_video` 배경 drawable 을 `shutter_center_recording` (둥근 사각형) 으로 교체하면서 `cornerRadius` interpolator 로 부드럽게 변형.

### 3-G. 볼륨 버튼 셔터는 의도적 비구현 — 유지
- 갤럭시는 볼륨 버튼 = 셔터 / 줌 옵션이 있으나, 본 앱은 백그라운드 종료 트리거로 단독 사용함이 설계 의도. **현 정책 유지**.

---

## 4. 추가 구현 희망 기능 (있으면 좋은 기능)

갤럭시 기본 카메라 대비 누락된 항목 중, **사용자 판단으로 가치가 있다고 선별된 기능** 만 정리.

### 4-A. 영상 일시정지 / 재개 (Pause / Resume) 🟠 P1
- 갤럭시 기본 영상 모드 표준 컨트롤.
- `androidx.camera.video.Recording.pause()` / `resume()` API 사용.
- 셔터 옆 또는 셔터 위에 일시정지 버튼 추가, 녹화 중 상태에서만 노출.
- 분할 반복 녹화(`maxRepeatCount`) 와 충돌하지 않도록 pause 중에는 auto-stop 타이머도 일시 중단해야 함.

### 4-B. 핀치 줌 (Pinch-to-Zoom)
- `PreviewView` 에 `ScaleGestureDetector` 부착.
- `onScale` 콜백에서 `currentZoom * scaleFactor` → `controller.setZoomRatio(...)` 호출.
- 기존 tap-to-focus 터치 리스너와 충돌 회피 처리 필요 (제스처 우선순위 정의).

### 4-C. 수평선 / 레벨 가이드
- 격자(3x3) 와 별개로, 가속도 센서 기반 수평선 표시.
- `GridOverlayView` 또는 별도 `LevelOverlayView` 에서 단말의 pitch/roll 각도를 시각화.
- 갤럭시 표준: **단말이 수평일 때 노란선이 흰색으로 전환**.
- 본 앱은 이미 `CameraForegroundService` 에서 가속도 센서 리스너를 운영 중이므로 센서 재활용 가능.

### 4-D. 위치 정보 태그 (Geo-tagging)
- 사진 EXIF / 영상 메타데이터에 GPS 좌표 삽입.
- `ImageCapture.Metadata.location` + `Recorder.Builder().setLocation()` (가능 시).
- `ACCESS_FINE_LOCATION` 권한 추가 및 `PermissionHelper` 갱신.
- 비공개 보관함의 성격상 **기본값 OFF**, 설정에서 명시적 ON 시에만 태깅.

### 4-E. HDR 자동 인식 / 적용
- CameraX `ImageCapture.Builder().setDynamicRange(DynamicRange.HDR_UNSPECIFIED_10_BIT)` 또는 Camera2 `CONTROL_SCENE_MODE_HDR` 사용.
- 가능 시 자동 감지 후 적용, 미지원 단말에서는 비활성화.
- 셔터 영역 또는 상단에 **HDR 표시 인디케이터** 노출.

### 4-F. 씬(Scene) 자동 인식
- 단말의 씬 감지 능력 활용 (`CaptureRequest.CONTROL_SCENE_MODE`).
- 야간/역광/풍경/문서 등 자동 인식 후 모드 자동 전환.
- 인식 결과를 상단 인디케이터에 짧게 표시 (선택).

### 4-G. 자동 포커스 / 자동 노출 정확도 개선
- 현재 `applyOptionsLive()` 에서 `CONTROL_AF_MODE_CONTINUOUS_VIDEO` 만 강제 주입 (`CameraController.kt:256-260`).
- 추가 개선 방향:
  1. 사진 모드에서는 `CONTROL_AF_MODE_CONTINUOUS_PICTURE` 로 분기.
  2. AE 모드를 자동 노출(`CONTROL_AE_MODE_ON_AUTO_FLASH` 등) 로 명시 + 안티밴딩(`CONTROL_AE_ANTIBANDING_MODE_AUTO`) 활성화.
  3. 주기적인 AF trigger (`CONTROL_AF_TRIGGER_START`) 로 포커스가 어긋난 상태에서 안정화 시간 단축.
  4. 노출 측정 영역을 화면 중앙 weighted 로 가중 (현재는 단순 평균).
  5. 단말이 흔들리지 않는 상태에서는 자동으로 AF 잠금하여 미세 헌팅(focus hunting) 방지.

---

## 5. 우선순위 통합 정리

| 우선순위 | 항목 | 출처 |
|---|---|---|
| 🔴 P0 | 마이크 mute 영구 무력화 (2-A) | 추가 발견 |
| 🔴 P0 | 백그라운드 freeze 해결책 보강 (1-B) | 1차 정의서 보강 |
| 🔴 P0 | 0.6x 초광각 렌즈 전환 (3-A) | 갤럭시 정합성 |
| 🟠 P1 | `lensChipGroup` 실제 제거 (1-A) | 1차 정의서 정정 |
| 🟠 P1 | 2.0x 망원 렌즈 전환 (3-B) | 갤럭시 정합성 |
| 🟠 P1 | `isFileSizeOptimizationEnabled` 데드코드 (2-B) | 추가 발견 |
| 🟠 P1 | 비율 복원 0-size 버그 (2-D) | 추가 발견 |
| 🟠 P1 | PrivateVault 메인 스레드 IO (2-E) | 추가 발견 |
| 🟠 P1 | 영상 일시정지/재개 (4-A) | 추가 구현 희망 |
| 🟠 P1 | 자동 포커스/노출 개선 (4-G) | 추가 구현 희망 |
| 🟠 P1 | HDR 자동 인식 (4-E) | 추가 구현 희망 |
| 🟡 P2 | 줌 슬라이더 초기값 모순 (3-C) | 갤럭시 정합성 |
| 🟡 P2 | 셔터 무음 한국 단말 (3-D) | 갤럭시 정합성 |
| 🟡 P2 | 셔터 변환 애니메이션 (3-F) | 갤럭시 정합성 |
| 🟡 P2 | `stopRecording()` `muteSystemSound` 의미 (2-C) | 추가 발견 |
| 🟡 P2 | VideoView 다이얼로그 누수 (2-F) | 추가 발견 |
| 🟡 P2 | 핀치 줌 (4-B) | 추가 구현 희망 |
| 🟡 P2 | 수평선 가이드 (4-C) | 추가 구현 희망 |
| 🟡 P2 | 위치 정보 태그 (4-D) | 추가 구현 희망 |
| 🟡 P2 | 씬 자동 인식 (4-F) | 추가 구현 희망 |
| 🟢 P3 | 모드 전환 마이크 버튼 visibility (3-E) | 갤럭시 정합성 |
| 🟢 P3 | WakeLock 해제 보강 (2-G) | 추가 발견 |
| 🟢 P3 | `switchFacing` 설정 미저장 (2-H) | 추가 발견 |
| 🟢 P3 | Preview targetRotation 미설정 (2-I) | 추가 발견 |
| 🟢 P3 | 서비스 종료 race (2-J) | 추가 발견 |
| ✅ 완료 | Manifest 마이크 FGS (1-C) | 1차 정의서 정정 |
