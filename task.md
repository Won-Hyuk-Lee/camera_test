# Camera2 API 학습용 Android 앱 (Debug Build)

## 프로젝트 개요
- 목적 Camera2 API + CameraX Camera2Interop 학습용 앱
- 빌드 타겟 Debug APK (로컬 설치용)
- 언어 Kotlin
- 최소 SDK API 26 (Android 8.0)
- 타겟 SDK API 34

---

## 기술 스택
- Android Studio (최신 stable)
- Kotlin
- CameraX (`camera-video`, `camera-camera2`)
- Camera2Interop (CameraX → Camera2 파라미터 주입)
- ForegroundService (백그라운드 녹화)
- ViewBinding

---

## 구현 기능 목록

### 1. 기본 프리뷰
- [ ] `PreviewView`로 카메라 실시간 프리뷰 출력
- [ ] 전후면 카메라 전환 버튼

### 2. 광각 전환
- [ ] 기기에서 사용 가능한 카메라 렌즈 목록 조회 (`CameraManager.getCameraIdList`)
- [ ] 광각(LENS_FACING_BACK, 초점거리 짧은 것) 전환 버튼
- [ ] 현재 활성 렌즈 표시 UI

### 3. 수동 카메라 파라미터 제어 (Camera2Interop)
- [ ] AWB(자동 화이트밸런스) 모드 선택
  - AUTO  CLOUDY_DAYLIGHT  FLUORESCENT  INCANDESCENT  DAYLIGHT
- [ ] 화이트밸런스 수동 색온도 설정 (AWB OFF 시 Color Correction 적용)
- [ ] 조리개(F값) 표시 (하드웨어 지원 여부 체크 후 가변이면 제어 UI 노출)
- [ ] 셔터스피드(노출 시간) 수동 조정 슬라이더

### 4. 동영상 녹화
- [ ] CameraX `VideoCapture` + `Recorder` 기반 녹화
- [ ] 녹화 시작정지 버튼
- [ ] 촬영 시간 타이머 표시 (UI)
- [ ] 저장 경로 `MediaStore` (갤러리 저장)

### 5. 백그라운드 녹화 (ForegroundService)
- [ ] `CameraForegroundService` 구현
  - `Service`를 `LifecycleOwner`로 등록 (`LifecycleService` 사용)
  - CameraX ProcessCameraProvider 바인딩
  - Notification 표시 (Android 요구사항)
- [ ] 앱 화면 닫아도 녹화 유지
- [ ] 서비스 시작종료 시 녹화 자동 연동
- [ ] 상태바 Privacy Indicator (초록 점) 동작 확인용 테스트

---

## 프로젝트 구조
```
app
├── srcmain
│   ├── javacomexamplecamera2study
│   │   ├── MainActivity.kt               # 메인 액티비티, 권한 처리
│   │   ├── CameraController.kt           # CameraX + Camera2Interop 래퍼
│   │   ├── CameraForegroundService.kt    # 백그라운드 녹화 서비스
│   │   ├── ui
│   │   │   ├── CameraFragment.kt         # 카메라 프리뷰 + 컨트롤 UI
│   │   │   └── SettingsBottomSheet.kt    # AWB파라미터 설정 BottomSheet
│   │   └── util
│   │       ├── CameraUtils.kt            # 렌즈 목록, 지원 기능 체크 유틸
│   │       └── PermissionHelper.kt       # 권한 요청 헬퍼
│   ├── res
│   │   ├── layout
│   │   │   ├── activity_main.xml
│   │   │   ├── fragment_camera.xml
│   │   │   └── bottom_sheet_settings.xml
│   │   └── values
│   └── AndroidManifest.xml
└── build.gradle.kts
```

---

## AndroidManifest 필요 권한
```xml
uses-permission androidname=android.permission.CAMERA 
uses-permission androidname=android.permission.RECORD_AUDIO 
uses-permission androidname=android.permission.FOREGROUND_SERVICE 
uses-permission androidname=android.permission.FOREGROUND_SERVICE_CAMERA 
uses-permission androidname=android.permission.POST_NOTIFICATIONS 
```

---

## build.gradle.kts 주요 의존성
```kotlin
val cameraxVersion = 1.3.4

dependencies {
    implementation(androidx.cameracamera-core$cameraxVersion)
    implementation(androidx.cameracamera-camera2$cameraxVersion)
    implementation(androidx.cameracamera-lifecycle$cameraxVersion)
    implementation(androidx.cameracamera-video$cameraxVersion)
    implementation(androidx.cameracamera-view$cameraxVersion)
    implementation(androidx.lifecyclelifecycle-service2.7.0)
}
```

---

## 핵심 구현 포인트 (학습 체크리스트)

### Camera2Interop AWB 설정 예시
```kotlin
val camera2Interop = Camera2Interop.Extender(videoCaptureBuilder)
camera2Interop.setCaptureRequestOption(
    CaptureRequest.CONTROL_AWB_MODE,
    CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
)
```

### ForegroundService LifecycleOwner 등록
```kotlin
class CameraForegroundService  LifecycleService() {
    override fun onCreate() {
        super.onCreate()
         ProcessCameraProvider.getInstance(this) 바인딩
         this (LifecycleService) 를 lifecycleOwner로 사용
    }
}
```

### 조리개 지원 여부 체크
```kotlin
val characteristics = cameraManager.getCameraCharacteristics(cameraId)
val apertures = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
 null이거나 size == 1이면 고정 조리개 → UI 비활성화
```

---

## Debug 빌드 & 설치 방법
```bash
# 프로젝트 루트에서
.gradlew assembleDebug

# APK 경로
appbuildoutputsapkdebugapp-debug.apk

# ADB로 직접 설치 (폰 연결 후)
adb install appbuildoutputsapkdebugapp-debug.apk
```

또는 Android Studio에서 `Run ▶` 버튼으로 직접 디바이스에 설치.

---

## 학습 순서 권장
1. 기본 프리뷰 → 2. 동영상 녹화 → 3. Camera2Interop 파라미터 → 4. 광각 전환 → 5. ForegroundService 백그라운드 녹화