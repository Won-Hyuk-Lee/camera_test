# 📌 백그라운드 촬영 안전성·발열 대응·갤럭시 정합성 통합 정의서 (problem260522_claude_v2.md)

`problem260522.md` (1차 정의서), `problem260522_claude.md` (v1 보강), `problem260522_codex.md` (외부 분석) 세 문서를 코드 베이스 정밀 검증과 함께 통합한 최종 정의서입니다.

**v2 신규 항목**:
- 🔥 **5장 — 발열 / 백그라운드 서비스 영구 잔존** (1차/v1/codex 모두 누락한 P0 결함)
- 🆕 6장 자동/프로 모드 분리, 7장 줌 6단계, 8장 Full 비율 출력 일치, 9장 동영상 설정 분리, 10장 린트 (codex 통합)

---

## 0. 용어 정정 — "빠른 녹화" 의 개념

`HomeFragment` 의 **빠른 녹화(Fast Record)** 기능의 본질은 "카메라 화면에 진입하지 않고도 즉시 녹화 파이프라인을 가동" 하는 데에 있습니다. 즉 **홈에서 → 카메라 UI 거치지 않고 → 백그라운드 녹화로 직진** 한다는 의미의 "빠름" 이지, 클릭부터 녹화 시작까지의 절대 시간이 짧다는 의미가 아닙니다.

따라서 `CameraFragment.onViewCreated()` 의 자동 시작 시점에 카메라 안정성을 위해 부여된 0.5초의 `postDelayed` 는 본 기능의 정의와 충돌하지 않습니다.

---

## 1. `problem260522.md` 1차 정의서의 보강·정정 사항

### 1-A. 문제 1 (`lensChipGroup` UI 제거) — **코드 미반영 상태**
- 문서에는 "제거 예정" 으로 적혀 있으나, 실제 코드에 아직 그대로 존재:
  - `app/src/main/res/layout/fragment_camera.xml:251-261` 의 `ChipGroup` 뷰
  - `app/src/main/java/com/example/camera2study/ui/CameraFragment.kt:231-271` 의 `populateLensChips()` / `updateLensIndicator()` 내부 칩 갱신 로직
- **조치**: XML 뷰 + 관련 코틀린 코드 + `com.google.android.material.chip.Chip` 임포트 모두 정리. 7장의 줌 6단계 재구성과 함께 진행.

### 1-B. 문제 2-A (백그라운드 freeze) — **현재 해결책으로는 부족함**
1차 정의서가 제안한 해결책 = `detachPreview()` 에서 `if (isRecording())` 일 때 `setSurfaceProvider(null)` 건너뛰기는 이미 `CameraController.kt:143-148` 에 반영되어 있음. 그러나 동일 freeze 현상이 재현됨.

**근본 원인**:
- `fragment_camera.xml:10-17` 의 `PreviewView` 가 `implementationMode` 미지정 → CameraX 기본값 = **PERFORMANCE (SurfaceView 기반)**.
- SurfaceView 의 underlying `Surface` 는 윈도우가 사라지는 즉시 OS 가 destroy 함. `SurfaceProvider` 를 떼지 않아도 Surface 자체가 dead.
- Camera2 단일 `CaptureSession` 은 출력 타깃 중 하나라도 invalid 가 되면 전체 세션이 freeze → VideoCapture 측 MediaCodec 입력 surface 도 0fps.

**필수 추가 조치 (둘 중 하나라도)**:
1. `CameraFragment.onStop()` 시점에 녹화 중이면 `cameraProvider.unbind(previewUseCase)` 로 Preview UseCase 만 일시 분리, `onStart()` 복귀 시 재바인딩.
2. `PreviewView` 에 `app:implementationMode="compatible"` 추가 (TextureView 기반 → Surface 보존성 ↑).

### 1-C. 문제 2-B (Android 14 마이크 FGS 누락) — **부분 패치 상태**
- `AndroidManifest.xml:8` 에 `FOREGROUND_SERVICE_MICROPHONE` 권한 존재. ✅
- `AndroidManifest.xml:44` 에 `foregroundServiceType="camera|microphone"` 명시. ✅
- 그러나 `CameraForegroundService.kt:107` 의 `startForeground()` 호출은 여전히 `FOREGROUND_SERVICE_TYPE_CAMERA` 만 전달:
  ```kotlin
  startForeground(NOTI_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
  ```
- **조치**: `FOREGROUND_SERVICE_TYPE_CAMERA or FOREGROUND_SERVICE_TYPE_MICROPHONE` 로 비트 OR 결합 호출. (codex 9장 발견 정확)

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
- **결과**: 사용자에게는 동작하는 척, 실제 효과 0. UX 거짓 정보.
- **수정**: 9장의 동영상 설정 재구성과 함께 실제 HEVC/H.265 또는 bitrate 정책으로 연결.

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
- **수정**: `binding.previewView.post { updateRatioSelection(...) }` 로 보내거나 ConstraintLayout `app:layout_constraintDimensionRatio` 사용. 8장의 비율 출력 정상화와 함께 처리.

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
- **수정**: `wakeLock?.acquire(...)` 직전에 `if (wakeLock?.isHeld == true) wakeLock?.release()` 보강. 5장의 발열 대책과 함께 진행.

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

## 3. 갤럭시 기본 카메라 대비 잘못 구현된 항목 (자동모드 정상화 전제)

> 7장 (줌 6단계) 과 6장 (자동/프로 분리) 와 연계해 처리해야 효과가 있음.

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
- **수정**: `backLenses` 에서 `isWide==true` 인 카메라 ID 로 `selectBackLens()` 호출 후 zoom = 1.0 적용. 단, CameraX logical camera 가 자동 전환을 제공하는지 먼저 검증 (codex 1장 권고).
- 같은 이유로 `CameraFragment.kt:279` 의 하이라이트 조건 `current <= 0.7f` 도 영원히 false 가능.

### 3-B. 2.0x 망원 버튼도 실제 망원 렌즈 전환이 아님 🟠 P1
- 3-A 와 동일 원리. 갤럭시 S25 Ultra 의 3x/5x 망원 모듈을 활용하지 못함.
- 현재는 광각 센서를 디지털 2배 줌 → **화질 저하 + S25 Ultra 하드웨어 강점 무력화**.
- **수정**: 7장의 줌 6단계 (`0.6x/1x/2x/3x/5x/10x`) 재구성과 함께 망원 렌즈 후보를 `backLenses` 에서 focalLength 기준으로 식별 → 해당 카메라 ID 로 전환.

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

### 4-B. 핀치 줌 (Pinch-to-Zoom) 🟡 P2
- `PreviewView` 에 `ScaleGestureDetector` 부착.
- `onScale` 콜백에서 `currentZoom * scaleFactor` → `controller.setZoomRatio(...)` 호출.
- 기존 tap-to-focus 터치 리스너와 충돌 회피 처리 필요 (제스처 우선순위 정의).

### 4-C. 수평선 / 레벨 가이드 🟡 P2
- 격자(3x3) 와 별개로, 가속도 센서 기반 수평선 표시.
- `GridOverlayView` 또는 별도 `LevelOverlayView` 에서 단말의 pitch/roll 각도를 시각화.
- 갤럭시 표준: **단말이 수평일 때 노란선이 흰색으로 전환**.
- 본 앱은 이미 `CameraForegroundService` 에서 가속도 센서 리스너를 운영 중이지만, 5장 발열 대책에서 센서를 녹화 중에만 등록하도록 변경할 예정이므로 별도 리스너 운영 필요.

### 4-D. 위치 정보 태그 (Geo-tagging) 🟡 P2
- 사진 EXIF / 영상 메타데이터에 GPS 좌표 삽입.
- `ImageCapture.Metadata.location` + `Recorder.Builder().setLocation()` (가능 시).
- `ACCESS_FINE_LOCATION` 권한 추가 및 `PermissionHelper` 갱신.
- 비공개 보관함의 성격상 **기본값 OFF**, 설정에서 명시적 ON 시에만 태깅.

### 4-E. HDR 자동 인식 / 적용 🟠 P1 (자동모드 핵심)
- CameraX `ImageCapture.Builder().setDynamicRange(DynamicRange.HDR_UNSPECIFIED_10_BIT)` 또는 Camera2 `CONTROL_SCENE_MODE_HDR` 사용.
- CameraX Extensions 의 HDR 지원 여부를 먼저 확인하고, 지원 단말에서만 활성화.
- 셔터 영역 또는 상단에 **HDR 표시 인디케이터** 노출.
- 6장 자동모드의 기본 정책으로 통합.

### 4-F. 씬(Scene) 자동 인식 🟠 P1 (자동모드 핵심)
- 단말의 씬 감지 능력 활용 (`CaptureRequest.CONTROL_SCENE_MODE`).
- 야간/역광/풍경/문서 등 자동 인식 후 모드 자동 전환.
- 가능한 범위는 얼굴 감지, AF/AE metering, HDR/Night extension 정도로 제한 (codex 8장 권고).
- *"AI ProVisual Engine 과 동일"* 같은 과장 표현 금지.

### 4-G. 자동 포커스 / 자동 노출 정확도 개선 🟠 P1 (자동모드 핵심)
- 현재 `applyOptionsLive()` 에서 `CONTROL_AF_MODE_CONTINUOUS_VIDEO` 만 강제 주입 (`CameraController.kt:256-260`).
- 추가 개선 방향:
  1. 사진 모드에서는 `CONTROL_AF_MODE_CONTINUOUS_PICTURE` 로 분기.
  2. AE 모드를 자동 노출(`CONTROL_AE_MODE_ON` 등) 로 명시 + 안티밴딩(`CONTROL_AE_ANTIBANDING_MODE_AUTO`) 활성화.
  3. 주기적인 AF trigger (`CONTROL_AF_TRIGGER_START`) 로 포커스 안정화 시간 단축.
  4. 노출 측정 영역을 화면 중앙 weighted 로 가중.
  5. 단말 정지 시 자동 AF 잠금하여 미세 헌팅 방지.

---

## 5. 🔥 발열 / 백그라운드 서비스 영구 잔존 — 최우선 결함 🔴 P0

> **1차 정의서, v1 보강, codex 모두 이 결함을 놓침. 사용자가 "어플 종료했는데도 백그라운드에 남아 폰 발열이 위험 수준" 이라 보고한 정확한 시나리오의 근본 원인이며, 즉시 P0 최우선 처리 대상.**

### 5-1. 발열 시나리오 재구성

```
1. 사용자가 카메라 화면 진입
   → 서비스 시작 + 카메라 센서 ON + 센서 리스너 등록 + 포그라운드 알림
2. 사용자가 백 키 / 홈 / 최근 앱 스와이프로 앱을 닫음
   → 서비스는 살아남음 (START_STICKY + startService 조합)
   → Fragment 만 unbind, 알림은 계속 표시
3. 시스템이 메모리 압박으로 서비스 죽임
   → START_STICKY 가 즉시 재시작 → 카메라 자원 재점유
4. 사용자는 알림창 자세히 안 보면 서비스 가동 사실 모름
   → 카메라 센서 + 가속도 센서 + 포그라운드 노티 영구 운영
   → 디바이스 발열 → 위험 수준
```

### 5-2. 코드 검증된 5가지 구조적 원인

#### 원인 ① — 서비스가 자동 종료되지 않는 설계
**파일**: `CameraForegroundService.kt:130-140`
```kotlin
override fun onStartCommand(...): Int {
    ...
    return START_STICKY   // ← 시스템이 죽여도 자동 재시작
}
```
`stopSelf()` 가 호출되는 경로는 단 3개:
1. 알림창 "녹화 종료" 버튼
2. 볼륨 버튼 2초 long-press
3. 격렬한 흔들기 감지

→ **사용자가 앱을 백 키 / 홈 키 / 최근 앱 스와이프로 닫아도 서비스는 영구 생존**. `START_STICKY` 라 시스템이 강제로 죽여도 자동 재시작.

#### 원인 ② — 카메라 화면 진입만 해도 서비스가 시작됨
**파일**: `CameraFragment.kt:127-129`
```kotlin
CameraForegroundService.start(ctx)   // 무조건 startForegroundService 호출
val intent = android.content.Intent(ctx, CameraForegroundService::class.java)
ctx.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
```
`start()` + `bind()` 둘 다 호출. Fragment 가 unbind 해도 `start()` 로 시작된 서비스는 살아남음. **카메라 화면을 단 한 번이라도 열면 서비스가 영구 잔존**.

#### 원인 ③ — 서비스 생성 즉시 카메라 자원 점유
**파일**: `CameraForegroundService.kt:97-123`
```kotlin
override fun onCreate() {
    super.onCreate()
    controller = CameraController(applicationContext)
    controller.init(this)   // ← 즉시 bindUseCases() — Preview/ImageCapture/VideoCapture 모두 바인딩 → 카메라 센서 ON
    ...
}
```
→ 서비스가 생성되는 즉시 `bindToLifecycle(...)` 실행되어 카메라 센서가 켜짐. **녹화 중이 아니어도 카메라가 영구 가동** = 가장 큰 발열원.

#### 원인 ④ — 가속도 센서 영구 등록
**파일**: `CameraForegroundService.kt:118-122`
```kotlin
sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
```
녹화 중이 아닐 때도 가속도 센서가 계속 콜백 → CPU wake 지속.

#### 원인 ⑤ — WakeLock 해제 누락 가능성
**파일**: `CameraController.kt:440-446`
- 30분 timeout 안전핀이 있지만, 반복 분할 녹화 시 새 acquire 가 누적될 수 있음.
- 그 사이 CPU 가 슬립 못 함.

### 5-3. 해결 방향 (모두 P0)

#### F-1. 카메라 자원 lazy 점유
- `CameraForegroundService.onCreate()` 에서 `controller.init()` 즉시 호출 금지.
- 사용자가 실제로 녹화 / 프리뷰 요청한 시점에만 init 호출.
- 또는 init 은 하되 use case 바인딩(`bindUseCases()`)은 deferred 시키고 첫 프리뷰 attach 시점에 수행.

#### F-2. 녹화 중이 아닐 때 서비스 자동 정지
**옵션 A (권장)**: `startService` 를 호출하지 않고 `bindService` 만 사용 → Fragment unbind 시 서비스 자동 종료. 단, 녹화 시작 시점에 `startForegroundService` 로 승격하여 백그라운드 생존 보장. 녹화 종료 시 다시 일반 서비스로 강등하거나 stopSelf.

옵션 B: `START_STICKY` → `START_NOT_STICKY` 변경 + 녹화 종료 후 5초 타임아웃 뒤 `stopSelf()`.

옵션 C: Activity `onDestroy` 또는 `onTaskRemoved` 콜백에서 녹화 중이 아니면 명시적 `stopService()`.

→ **옵션 A 가 가장 안전**. 갤럭시 표준 카메라도 화면 닫으면 카메라 자원 즉시 해제.

#### F-3. 센서 / 리시버는 녹화 중에만 등록
- `startRecording()` 직전에 `volumeReceiver` 와 `accelerometer` 리스너 등록.
- `stopRecording()` / `Finalize` 콜백에서 즉시 unregister.
- 녹화 중이 아닐 때는 CPU wake 0.

#### F-4. `onTaskRemoved()` 핸들러 추가
```kotlin
override fun onTaskRemoved(rootIntent: Intent?) {
    if (!controller.isRecording()) {
        stopSelf()
    }
    super.onTaskRemoved(rootIntent)
}
```
앱이 최근 앱 목록에서 스와이프되었을 때 녹화 중이 아니면 즉시 종료.

#### F-5. WakeLock 정책 보강
- 녹화 시작 시점 acquire 직전에 `if (isHeld) release()` 로 누적 방지.
- 반복 분할 녹화에서는 첫 acquire 만 유지, 분할 전환 시 재취득 금지.

#### F-6. 사용자 인지 가능한 종료 UX
- 현 알림 액션 "녹화 종료" 외에 **"앱 완전 종료" 액션 추가**.
- 백그라운드 가동 사실을 사용자가 명확히 인지하도록.

### 5-4. 완료 검증 체크리스트
- [ ] 카메라 화면 진입 후 즉시 앱 종료 → 서비스 종료 확인 (`adb shell dumpsys activity services com.example.camera2study`)
- [ ] 녹화 중 앱 종료 → 서비스 유지 + 녹화 정상 지속 확인
- [ ] 녹화 종료 후 일정 시간 내 서비스 자동 정지 확인
- [ ] 알림창 "녹화 종료" 누르면 서비스 즉시 정지 + 추가 "앱 완전 종료" 액션 동작 확인
- [ ] 최근 앱에서 스와이프 → 비녹화 시 서비스 정지 / 녹화 중일 때 유지 확인
- [ ] 가속도/볼륨 센서 콜백이 녹화 중에만 발생하는지 로그로 검증

---

## 6. 자동모드 / 프로모드 분리 🟠 P1 (codex 통합)

### 6-1. 현재 구조의 문제
- 기본 카메라 바인딩 후 `CONTROL_AF_MODE_CONTINUOUS_VIDEO` 는 강제됨.
- AE/AWB 는 기본 자동이지만, `SettingsBottomSheet` 에서 수동 노출/WB 를 건드리면 **자동과 수동의 경계가 무너짐**.
- 수동 설정 적용 후 자동모드로 완전 복구되는 기준이 없음.
- HDR/장면 인식 등 자동 보정 기능 부재 (→ 4-E, 4-F, 4-G).

### 6-2. 개선 방향
- 촬영 모드를 `자동모드` 와 `프로모드` 로 분리.
- **자동모드 진입 시 항상 다음 상태 보장**:
  - 연속 AF 활성화 (사진/영상 모드에 따라 `CONTINUOUS_PICTURE` / `CONTINUOUS_VIDEO`)
  - AE 자동
  - AWB 자동
  - 사용 가능한 경우 자동 HDR / CameraX HDR Extension 활성화
  - 사용 가능한 경우 얼굴/장면 자동 보정 UI 표시
- 자동모드에서는 수동 노출, 수동 WB, 수동 조리개, 수동 ISO 설정이 캡처 요청에 남으면 안 됨 → `applyOptionsLive()` 에서 분기 처리.
- 단말 미지원 자동 기능은 조용히 비활성화하고, 설정 UI 에는 *"지원 안 함"* 으로 표시.

### 6-3. 프로모드 재정의
- 프로모드는 **선택 모드** 로만 둠.
- 최소 항목: **ISO**, **셔터 스피드**, **WB**, **EV**, **수동 초점 또는 AF 모드 선택**.
- 조리개는 가변 조리개 지원 단말에서만 노출.
- 자동모드로 돌아오면 **모든 manual capture request 를 clear/reset**.
- 현재 코드는 `SENSOR_EXPOSURE_TIME` 만 제어하고 `SENSOR_SENSITIVITY (ISO)` 미제어 → 프로모드라 부르기 부족.

### 6-4. UI 구조 예시
- 상단 또는 설정: `자동 | 프로`
- 하단: `사진 | 동영상`
- 두 축이 직교하도록 분리.

### 6-5. 완료 조건
- 앱 시작 기본값은 자동모드.
- 자동모드 전환 시 이전 프로 설정이 캡처 요청에 남지 않음.
- HDR/장면 기능은 런타임 감지 기반.
- 프로모드 설정이 자동모드 캡처에 영향을 주지 않음.

---

## 7. S25 Ultra 줌 6단계 정상화 🟠 P1 (codex 통합)

### 7-1. 개선 방향
- 줌 프리셋: **`0.6x` / `1x` / `2x` / `3x` / `5x` / `10x`** 우선 제공.
- 슬라이더는 `zoomState.maxZoomRatio` 까지 동적 확장, 10x 초과는 **Space Zoom 영역** 으로 시각적 구분.
- 우선 CameraX logical camera zoom 으로 자연 전환되는지 검증, 미흡하면 physical camera id 매핑을 보조 전략으로.
- 프리셋이 단말 미지원 시 버튼을 숨기거나 비활성화.

### 7-2. 연계 작업
- 3-A (0.6x 초광각 전환), 3-B (2x 망원 전환) 와 함께 처리.
- 1-A (`lensChipGroup` 제거) 와 함께 처리.

### 7-3. 완료 조건
- 사진/동영상 모두 같은 줌 UX.
- mm 칩 없이도 S25 Ultra 기준 주요 줌 단계 조작 가능.
- 3x/5x 프리셋 추가, 단말 지원 범위 밖에서는 안전하게 clamp 또는 비활성화.

---

## 8. 화면비 `Full` 의 실제 출력 정상화 🟠 P1 (codex 통합)

### 8-1. 현재 문제
- `RATIO_FULL` 을 선택해도 `CameraController.bindUseCases()` 는 **여전히 `AspectRatio.RATIO_4_3`** 사용 (`CameraController.kt:158-162`).
- `CameraFragment.updateRatioSelection()` 은 PreviewView 높이만 `MATCH_PARENT` 로 늘림.
- **결과: 화면은 Full 처럼 보이지만 실제 저장 결과물은 4:3 기반** → 프리뷰와 결과물 불일치.

### 8-2. 개선 방향
- S25 Ultra 기준 비율 옵션:
  - 사진: `3:4`, `9:16`, `1:1`, `Full` 중 실제 지원 가능 항목
  - 동영상: `9:16`/`16:9`, `Full` 또는 기기 지원 해상도 기준
- target aspect ratio 만으로 부족하면 `ResolutionSelector` 또는 output size 전략 별도 적용.
- `RATIO_FULL` 의 의미를 명확히 정의: "센서 native 비율" / "디스플레이 비율" / "프리뷰 늘림" 중 어느 것인지 결정.
- 2-D 의 0-size 버그도 동시 수정.

### 8-3. 완료 조건
- 선택한 화면비와 저장 결과물의 비율이 일치.
- `Full` 이 단순 프리뷰 늘림이 아니라 실제 출력 정책으로 정의됨.

---

## 9. 동영상 설정 분리 및 확장 🟠 P1 (codex 통합)

### 9-1. 현재 부족
- 영상 품질이 CameraX `Quality.UHD/FHD/HD/SD` 만 제공.
- **FPS 선택 부재**.
- 8K, UHD 60fps, FHD 60fps, HDR10+/10-bit, Log Video, 안정화 미지원.
- `isFileSizeOptimizationEnabled` 는 값만 있고 미사용 (2-B 와 동일).

### 9-2. 개선 방향
설정 화면 재구성:
- **해상도**: 8K, UHD, FHD, HD 중 지원 항목
- **FPS**: 24/30/60/120/240 중 지원 항목
- **HDR/10-bit**: 지원 시 토글
- **Log Video**: 지원 시 프로모드 전용 토글
- **영상 안정화**: 지원 시 토글 (`Preview/VideoCapture` 의 stabilization mode)
- **파일 크기 최적화**: 실제 HEVC/H.265 또는 bitrate 정책과 연결

- 단말 미지원 조합은 숨기거나 비활성화.
- `QualitySelector.from(videoQuality)` 만으로 표현 불가한 조합은 CameraX/MediaRecorder/Recorder 지원 범위 재검토.

### 9-3. 완료 조건
- 설정 UI 에서 해상도와 FPS 를 분리 조절 가능.
- 파일 크기 최적화 스위치가 실제 인코딩 정책에 영향.
- 지원하지 않는 옵션은 런타임에서 표시되지 않음.

---

## 10. 린트 실패 및 코드 위생 🟡 P2 (codex 통합)

### 10-1. 확인된 주요 오류
- `VIBRATE` 권한 누락 → `CameraForegroundService.triggerVibration()` 호출 위험.
- Camera2 interop opt-in lint 오류.
- `dialog_media_viewer.xml` 의 `android:tint` 사용 (deprecated, `app:tint` 권장).

### 10-2. 수정 방향
- `AndroidManifest.xml` 에 `<uses-permission android:name="android.permission.VIBRATE" />` 추가.
- Camera2 interop opt-in 은 lint 가 인식하는 방식으로 정리 (모듈/클래스/메서드 단위 `@OptIn` 일관 적용).
- `android:tint` → `app:tint` 로 마이그레이션.

### 10-3. 완료 조건
- `./gradlew.bat assembleDebug` 통과
- `./gradlew.bat lintDebug` 통과 또는 의도적 baseline 문서화

---

## 11. README 정합성 🟡 P2 (codex 통합)

### 11-1. 현재 과장 항목
- *"완벽한 백그라운드"* — 5장 발열 결함으로 무효.
- *"갤럭시 S25 Ultra 하드웨어 제어"* — 3-A/3-B 로 줌 전환이 디지털 줌으로 처리되어 사실상 미동작.
- *"Full Screen 비율"* — 8장 출력 4:3 고정 문제로 사실과 다름.
- *"파일 크기 최적화"* — 2-B / 9장 데드 코드.
- *"마이크 On/Off 동적 Mute"* — 2-A 로 영구 무력화.
- *"터치 확대/축소 등" 사진 뷰어* — 실제는 단순 FIT_CENTER.
- *"안전 비디오 미니 플레이어"* — 실제는 풀스크린 다이얼로그.

### 11-2. 개선 방향
- 구현 전: README 를 **"목표/로드맵"** 과 **"현재 구현"** 으로 분리.
- 구현 후: 실제 동작 기준으로 README 갱신.

### 11-3. 완료 조건
- README 가 현재 소스와 충돌하지 않음.
- 미구현 기능은 명확히 "예정" 또는 "보류" 로 표시.

---

## 12. 비목표 (의도적 제외)

사용자가 명시적으로 필요 없다고 판단한 갤럭시 기능:
- 플래시
- 셀프 타이머
- 영상 녹화 중 사진 캡처 (snapshot during recording)
- 셔터 길게 눌러 연사
- 터치 & 홀드 = AE/AF 잠금
- 전면 카메라 좌우반전 (셀카 미러)
- 사진 직후 갤러리 슬라이드 애니메이션
- 모드 슬라이드 전환 (좌우 스와이프)

추가로 codex 가 비목표로 제안한 항목 (검토 후 동의):
- AR Zone, Bixby Vision, Food, Panorama, Dual Rec, Portrait Video, Motion Photo, QR/document scan, Voice command, Floating shutter

---

## 13. 통합 우선순위

### 🔴 P0 (즉시 처리, 안전·기능·발열 직결)

| 항목 | 출처 | 비고 |
|---|---|---|
| 발열 / 백그라운드 서비스 잔존 (5장 F-1~F-6) | **v2 신규** | 🚨 위험 |
| 마이크 mute 영구 무력화 (2-A) | v1 추가 발견 | 1줄 수정 |
| 백그라운드 freeze 해결책 보강 (1-B) | 1차 정의서 보강 | TextureView or Preview unbind |
| 0.6x 초광각 렌즈 전환 (3-A) | v1 갤럭시 정합성 | 7장과 연계 |
| `startForeground()` 타입에 microphone 비트 추가 (1-C) | codex 9장 정확 | API 호출 인자만 수정 |

### 🟠 P1 (단기 정상화)

| 항목 | 출처 |
|---|---|
| 자동모드/프로모드 분리 (6장) | codex |
| S25 Ultra 줌 6단계 (7장) | codex |
| 화면비 Full 실제 출력 정상화 (8장) | codex |
| 동영상 설정 분리 및 확장 (9장) | codex |
| `lensChipGroup` 실제 제거 (1-A) | 1차 정의서 정정 |
| 2.0x 망원 렌즈 전환 (3-B) | v1 갤럭시 정합성 |
| `isFileSizeOptimizationEnabled` 데드 코드 (2-B) | v1 추가 발견 |
| 비율 복원 0-size 버그 (2-D) | v1 추가 발견 |
| PrivateVault 메인 스레드 IO (2-E) | v1 추가 발견 |
| 영상 일시정지/재개 (4-A) | 추가 구현 희망 |
| HDR 자동 인식 (4-E) | 자동모드 핵심 |
| 씬 자동 인식 (4-F) | 자동모드 핵심 |
| 자동 AF/AE 정확도 개선 (4-G) | 자동모드 핵심 |

### 🟡 P2 (보강)

| 항목 | 출처 |
|---|---|
| 줌 슬라이더 초기값 모순 (3-C) | v1 |
| 셔터 무음 한국 단말 (3-D) | v1 |
| 셔터 변환 애니메이션 (3-F) | v1 |
| `stopRecording()` `muteSystemSound` 의미 (2-C) | v1 |
| VideoView 다이얼로그 누수 (2-F) | v1 |
| 핀치 줌 (4-B) | 추가 구현 희망 |
| 수평선 가이드 (4-C) | 추가 구현 희망 |
| 위치 정보 태그 (4-D) | 추가 구현 희망 |
| 린트 실패 정리 (10장) | codex |
| README 정합성 (11장) | codex |

### 🟢 P3 (디테일·코드 위생)

| 항목 | 출처 |
|---|---|
| 모드 전환 마이크 버튼 visibility (3-E) | v1 |
| WakeLock 해제 보강 (2-G) | v1, 5-3 F-5 와 연계 |
| `switchFacing` 설정 미저장 (2-H) | v1 |
| Preview targetRotation 미설정 (2-I) | v1 |
| 서비스 종료 race (2-J) | v1 |

---

## 14. 권장 구현 순서

1. **🔥 발열 / 백그라운드 잔존 즉시 수정 (5장)** — 사용자 안전 직결
2. **P0 안정성·기능 수정** — 2-A (mic mute), 1-B (freeze 보강), 1-C (FGS API 인자), 1-A (lensChipGroup)
3. **자동/프로 모드 구조 정리 (6장)** — 이후 모든 변경의 토대
4. **S25 Ultra 줌 6단계 (7장) + 3-A/3-B 통합 처리**
5. **화면비 정상화 (8장) + 2-D 동시 처리**
6. **동영상 설정 분리 (9장) + 2-B 통합**
7. **자동 HDR/장면 인식/AF·AE 개선 (4-E/4-F/4-G)**
8. **린트 정리 (10장) → CI 통과 보장**
9. **추가 구현 희망 기능 (4-A 영상 pause, 4-B 핀치줌, 4-C 수평선, 4-D geo-tag)**
10. **README 업데이트 (11장)**

---

## 15. 통합 검증 체크리스트

- [ ] `./gradlew.bat assembleDebug`
- [ ] `./gradlew.bat lintDebug`
- [ ] **카메라 화면 진입 후 즉시 앱 종료 → 서비스 종료 확인** (발열 P0)
- [ ] **녹화 중 앱 종료 → 서비스 유지 + 녹화 정상 지속** (발열 P0)
- [ ] **녹화 종료 후 일정 시간 내 서비스 자동 정지** (발열 P0)
- [ ] **최근 앱 스와이프 → 비녹화 시 서비스 정지** (발열 P0)
- [ ] S25 Ultra 실기기에서 사진 자동모드 촬영
- [ ] S25 Ultra 실기기에서 동영상 자동모드 촬영
- [ ] `0.6x/1x/2x/3x/5x/10x` 프리셋 동작 + 실제 물리 렌즈 전환 확인
- [ ] 프리뷰 화면비와 저장 파일 화면비 일치 확인 (4 가지 비율 전부)
- [ ] 화면 소등 후 녹화 프레임 증가 여부 확인 (freeze 회귀 검증)
- [ ] 오디오 켬/끔 각각 녹화 → 실제 오디오 트랙 존재/부재 확인 (2-A 회귀 검증)
- [ ] 자동모드에서 AWB/AE/AF 가 수동 설정에 오염되지 않는지 확인
- [ ] 프로모드 진입/이탈 시 설정이 독립적으로 동작하는지 확인
- [ ] HDR / 장면 인식 단말 지원 시 자동 활성화 + 미지원 시 fallback 동작
- [ ] 비공개 보관함 100개 이상 파일 로딩 시 ANR 발생 여부 (2-E 회귀 검증)
