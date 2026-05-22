# 📸 Galaxy S25 Ultra Spec. Cam & Background Spy Vault (Camera2Study)

본 프로젝트는 **갤럭시 S25 울트라의 하드웨어 특성을 반영한 고성능 카메라 제어**와 **안전한 백그라운드 위장 촬영**, 그리고 외부에서 접근 불가능한 **독립 비공개 보관함(Private Vault) 설계**를 학습하고 필드 테스트하기 위한 모바일 어플리케이션입니다.

---

## 🌟 핵심 기능 및 기술적 지향점

1. **갤럭시 스타일 UI & 초정밀 하드웨어 제어**
   - **줌 배율 퀵 다이얼**: 초광각 `0.6x`, 광각 `1.0x`, 망원 `2.0x` 줌 다이얼 버튼 매핑 및 줌 슬라이더 제공.
   - **프로 모드 제어**: AWB(Auto White Balance) 수동 조정(색온도 Kelvin 및 RGGB Gain 제어), 셔터 스피드(Exposure Time) 수동 조절, 조리개(Aperture) 제어(가변 조리개 지원 기기 대응).
   - **자동/수동 포커스 및 노출**: 화면 터치 시 해당 영역의 `tapToFocus` 동작 및 실시간 `AE Index(노출 지수)` 미세조정 바 노출.
   - **화면비(Aspect Ratio) 다이내믹 피팅**: `3:4`, `16:9`, `Full Screen` 비율 지원 및 UI 프레임 크롭 연동.

2. **완벽한 백그라운드 위장 촬영 및 생존 아키텍처**
   - **1초 컷 빠른 녹화 (Fast Record)**: 메인 홈에서 단 한 번의 터치로 마지막에 선택했던 카메라 설정을 복원하여 즉시 동영상 녹화 파이프라인 가동.
   - **화면 소등 및 백그라운드 촬영 지속**: 사용자가 전원 버튼을 눌러 화면을 완전히 끄거나 홈 화면으로 나가더라도 `Foreground Service` 및 `WakeLock`을 통해 촬영이 프리즈되지 않고 영구 생존.
   - **반복 분할 녹화 (Segment Loop Recording)**: 최대 녹화 지정 시간에 이르면 녹화를 자동 중단하고, 0.5초 이내에 다음 파일로 연속 연쇄 녹화를 격발하여 파일 손실 최소화.
   - **최소 노출 알림**: 백그라운드 서비스 동작 중 시스템 트레이 알림 표시 채널의 중요도를 `IMPORTANCE_MIN`으로 고정하여 눈에 띄지 않게 동작.

3. **비파괴 물리 및 센서 종료 제어 (물리 버튼 & 흔들기 종료)**
   - **물리 볼륨 버튼 백그라운드 종료**: 디바이스가 켜진 상태뿐 아니라 전원 버튼을 눌러 화면이 꺼진 슬립 모드 상태에서도, **볼륨 버튼을 약 2초간 꾹 누르면** 오디오 시스템 스트림 이벤트를 역추적하여 촬영을 완벽하게 자동 중단하고 진동 피드백 후 서비스를 자동 소거(`stopSelf()`).
   - **흔들림 감지 종료**: 가속도 센서를 분석하여 디바이스에 강한 흔들림이 감지(임계값 19.0f 초과)되면 즉시 백그라운드 촬영을 안전하게 종료하고 진동 알림 후 종료.

4. **완벽 격리형 비공개 보관함 (Private Vault)**
   - **인앱 독립 저장공간**: 촬영된 사진 및 영상은 공용 미디어 스토어에 등록되지 않고, 외부 갤러리나 파일 탐색기가 전혀 접근할 수 없는 **앱 전용 보안 독립 디렉토리(`getExternalFilesDir(null)/private_vault`)**에 다이렉트로 저장됩니다.
   - **외부 스캔 원천 차단**: 저장 영역 내에 `.nomedia` 파일을 상시 강제 배치하여 미디어 스캐너의 인덱싱 차단.
   - **인앱 멀티미디어 뷰어**: 비공개 보관함 화면 내에서 사진과 영상을 안전하게 열고 재생할 수 있는 `ImageView` 및 `VideoView(MediaController 탑재)` 내장 풀스크린 플레이어 탑재.
   - **원터치 내보내기/삭제**: 롱클릭 시 안전하게 원본을 공개 갤러리(`DCIM/StudyVault`, `Movies/StudyVault`)로 배출(Export)하거나 디바이스에서 흔적 없이 영구 소거(Delete).

---

## 📐 시스템 아키텍처 및 동작 시나리오

앱의 핵심 동작은 **UI 프래그먼트 - 포그라운드 서비스 - 카메라 비즈니스 컨트롤러** 간의 상호작용 및 생명주기 바인딩 구조로 움직입니다.

### 🔄 아키텍처 흐름도 (Mermaid Diagram)

```mermaid
graph TD
    %% MainActivity 및 UI 컴포넌트
    MainActivity[MainActivity] -->|1. 시작| HomeFragment[HomeFragment]
    
    HomeFragment -->|카메라 촬영| CameraFragment[CameraFragment]
    HomeFragment -->|빠른 녹화| CameraFragment
    HomeFragment -->|보관함| PrivateVaultFragment[PrivateVaultFragment]
    HomeFragment -->|설정 센터| SettingsFragment[SettingsFragment]
    
    %% CameraFragment와 Service/Controller 연동
    CameraFragment -->|2. 서비스 바인딩| CameraForegroundService[CameraForegroundService]
    CameraForegroundService -->|3. 인스턴스 공유| CameraController[CameraController]
    CameraFragment -->|4. 프리뷰 서피스 부착| CameraController
    
    %% 제어 흐름
    CameraFragment -->|5. 제어 명령 및 파라미터 전달| CameraController
    CameraController -->|6. 물리 하드웨어 제어| CameraX[Jetpack CameraX API]
    
    %% 백그라운드 제어 및 센서 감지
    CameraForegroundService -->|볼륨 변화 감지| VolumeReceiver[Volume BroadcastReceiver]
    CameraForegroundService -->|흔들림 감지| SensorListener[Accelerometer Sensor]
    
    VolumeReceiver -->|볼륨 버튼 2초 꾹 누름| CameraController
    SensorListener -->|임계값 19.0f 초과 흔들림| CameraController
    
    %% 파일 저장 공간 분리
    CameraController -->|비공개 저장 (기본값)| PrivateVault[(Private Directory /private_vault/)]
    CameraController -->|공개 저장 (설정 시)| PublicGallery[(Public Gallery DCIM/Movies/)]
    
    PrivateVaultFragment -->|비공개 파일 직접 로딩| PrivateVault
    PrivateVaultFragment -->|공개 배출 (Export)| PublicGallery
```

---

## 📂 파일별 역할 및 핵심 소스코드 구조 상세 설명

### 1. `MainActivity.kt` (호스트 액티비티)
- **역할**: 앱의 진입점이자 모든 프래그먼트를 호스팅하는 윈도우 컨테이너.
- **동작**: 앱이 구동될 때 `savedInstanceState` 유무를 확인해 첫 화면인 `HomeFragment`로의 최초 트랜잭션을 전개합니다.

### 2. `ui/HomeFragment.kt` (메인 대시보드)
- **역할**: 앱의 홈 허브 및 실시간 디바이스 상태 진단기.
- **핵심 로직**:
  - **디바이스 상태 상시 모니터링**: `onResume()` 주기에서 배터리 최적화 해제 상태(`isIgnoringBatteryOptimizations`)와 필수 6대 권한(Camera, Record Audio, Service 등) 획득 여부를 동적으로 진단합니다. 상태 누락 시 홈 상단에 경고 카드가 활성화되어 설정으로 바로 이동하도록 유도합니다.
  - **빠른 녹화 (Fast Record)**: 해당 카드 터치 시 `CameraFragment`로 전환하면서 인텐트 인자(`EXTRA_AUTO_START = true`)를 밀어 넣습니다.
  - **메뉴 진입**: 촬영 모드, 설정 모드, 비공개 보관함으로의 백스택 관리를 처리합니다.

### 3. `ui/CameraFragment.kt` (카메라 조작 패널 및 UI 프레젠터)
- **역할**: 갤럭시 S25 울트라의 수동 제어 패널 UI를 처리하고 사용자의 액션을 서비스 및 컨트롤러로 매핑하는 핵심 뷰(View).
- **핵심 로직**:
  - **서비스 바인딩 패턴**: 서비스 생명주기 제어를 위해 `CameraForegroundService`와 `ServiceConnection`을 통해 연결한 뒤, 서비스 내부의 싱글톤 `CameraController` 인스턴스를 공유받아 직접 바인딩합니다.
  - **UI-컨트롤러 동기화 리스너**: `CameraController`의 콜백 인터페이스(`onCameraChanged`, `onRecordingEvent`, `onZoomChanged`)를 구독하여 UI 줌 슬라이더 값 변경, 줌 버튼 상태 갱신, 타이머 구동(`timerRunnable`), 셔터 버튼 형상 애니메이션 등을 정밀 동기화합니다.
  - **다이내믹 종횡비 뷰 조정**: 사용자가 `3:4`, `16:9`, `Full` 버튼을 누르면 컨트롤러의 이미지캡처 비율 세팅을 변경함과 동시에 `PreviewView` 레이아웃 가로세로 치수를 수학적으로 계산해 크롭함으로써 화면 일그러짐 없는 프리뷰를 보장합니다.

### 4. `CameraController.kt` (카메라 비즈니스 컨트롤러)
- **역할**: Jetpack CameraX와 Camera2 Interop API를 감싸고 모든 카메라 유스케이스(`Preview`, `ImageCapture`, `VideoCapture`) 바인딩, 포커스, 줌, 조리개, 시스템 오디오 및 백그라운드 WakeLock을 총괄 제어하는 최하단 핵심 로직 엔진.
- **핵심 로직**:
  - **가변 카메라 바인딩**: 후면 멀티 렌즈 데이터(`LensInfo`)를 획득하여 사용자가 배율 다이얼을 선택하면 해당 하드웨어 카메라 ID를 `CameraSelector`에 주입해 세션을 실시간 재구축합니다.
  - **Camera2 Interop 수동 제어**: `Camera2CameraControl`을 꺼내 수동 AWB 모드, 노출 시간(`CaptureRequest.SENSOR_EXPOSURE_TIME`), 조리개 수치(`CaptureRequest.LENS_APERTURE`), 그리고 연속 비디오 자동초점(`CONTROL_AF_MODE_CONTINUOUS_VIDEO`)을 강제 바인딩합니다.
  - **촬영 무음 제어 (`muteSystemSound`)**: 동영상 녹화 시작 및 사진 셔터 클릭 직전에 AudioManager를 통해 단말을 일시적으로 완벽 무음 모드(`RINGER_MODE_SILENT`)로 스위칭하고, 캡처 이벤트 완료 즉시 기존 설정되어 있던 볼륨 모드로 역복구합니다.
  - **백그라운드 지속성 (`WakeLock`)**: 백그라운드 녹화 시작 시 `PowerManager.PARTIAL_WAKE_LOCK`을 최대 30분 기한으로 활성화 획득하여, 화면 소등 상황에서도 AP(CPU)가 슬립 상태로 빠지지 않도록 절대 방어합니다. 녹화 종료 또는 연쇄 루프 녹화 완료 시 자원을 해제합니다.
  - **분할 반복 녹화 루프**: 지정 시간이 초과되어 녹화 중지 이벤트가 트리거되었을 때, `currentRepeatCount < maxRepeatCount` 조건이 유효하면 즉시 재귀적으로 `startRecording()`을 스케줄링하여 무제한 릴레이 백그라운드 녹화를 가능케 합니다.

### 5. `CameraForegroundService.kt` (백그라운드 생존 엔진)
- **역할**: Android 14 백그라운드 라이프사이클 정책에 완벽히 대응하기 위한 `LifecycleService` 기반 장기 생존 백그라운드 시스템 서비스.
- **핵심 로직**:
  - **물리 버튼 감지 리시버 (`volumeReceiver`)**: 
    - `android.media.VOLUME_CHANGED_ACTION` 방송 수신을 실시간 낚아챕니다.
    - 녹화 중인 상태에서 볼륨 키를 꾹 누르게 되면 수 밀리초 단위로 수십 개의 볼륨 변경 이벤트가 연쇄 격발됩니다.
    - 소스코드 내에서는 볼륨 이벤트의 격발 간격이 **600ms 미만**으로 연이어 들어오는 빈도를 추적하고, **최초 입력 시점 대비 누적 1800ms(약 1.8초~2초) 이상** 이 패턴이 유지되면 **"물리 볼륨 버튼 장기 연속 누름"**으로 판단합니다.
    - 감지 즉시 `triggerVibration()` 진동으로 성공을 비공개 알림하고, 백그라운드 촬영을 안전히 마감한 뒤 볼륨 값을 원래 수준으로 정교하게 복원하고 스스로 서비스를 종료(`stopSelf()`)합니다.
  - **흔들림 감지 소거**: 가속도 센서를 분석하여 중력가속도 크기를 제외한 운동 가속도 성분이 `19.0f`를 상회할 시 흔들기 종료 메커니즘을 격발합니다.
  - **알림 채널 극대화**: 포그라운드 승격을 위해 상단 노티를 띄우되 `IMPORTANCE_MIN`으로 등록하여 사용자 시각적 요소를 최소화하고, 알림창에 비상 '녹화 종료' 액션 펜딩 인텐트를 제공합니다.

### 6. `ui/PrivateVaultFragment.kt` (비공개 보안 보관함)
- **역할**: 외부 유출 차단용 미디어 파일 관리 및 보안 인앱 뷰어.
- **핵심 로직**:
  - **미디어 리드/정렬**: `private_vault` 폴더를 스캔하여 파일 최종 수정 시각(`lastModified()`) 기준으로 디바이스상 최신 캡처 미디어를 그리드 레이아웃에 정렬합니다.
  - **풀스크린 보안 플레이어**: 외부 기본 플레이어 호출 대신 커스텀 `VideoView` 및 `ImageView`를 풀스크린 검은색 다이얼로그 윈도우에 얹어 다이렉트로 디코딩하여 인앱 재생을 완성합니다.
  - **갤러리 강제 배출 (Export)**: `ContentResolver.insert`를 통해 외부 공유 미디어스토어(`DCIM/StudyVault` or `Movies/StudyVault`) 공간에 신규 로우(Row)를 확보하고 파일 입출력 스트림 복사를 완료한 뒤, 보관함 원본 파일을 물리 소거합니다.

---

## 🛠️ 백그라운드 위장 촬영 핵심 구조 및 예외 설계의 기술적 팩트

어플리케이션은 화면이 완전히 꺼지거나 앱을 닫았을 때도 카메라 디바이스가 멈추지 않고 안전하게 파일 작성을 지속하는 데 초점을 둡니다.

```
[화면 소등 및 슬립 진입]
       │
       ▼
[Fragment onDestroyView] ─────────► controller.detachPreview() 호출
       │                                 │
       │ (녹화 중이 아닌 경우)              │ (녹화 진행 중인 경우)
       ▼                                 ▼
setSurfaceProvider(null) 실행        setSurfaceProvider(null)을 우회(Skip)하여
카메라 세션 파괴 및 대기             Capture Session 파이프라인 활성 유지! (정지 현상 방지)
                                         │
                                         ▼
                                     [화면 재점등 복귀 시]
                                     controller.attachPreview(PreviewView)
                                     새로운 SurfaceProvider를 세션 끊김 없이 실시간 갱신 매핑!
```

1. **카메라 세션 붕괴 극복 (`detachPreview` 안전 설계)**
   - 기존 구현에서는 화면이 소등되면 프래그먼트가 파괴되면서 `controller.detachPreview()`가 호출되어 `Preview.setSurfaceProvider(null)`을 격발하였습니다.
   - 이는 CameraX 디바이스로 하여금 "더 이상 출력할 Preview 화면이 없다"고 판단하여 카메라 캡처 세션 재구성(Reconfiguration)을 강제 명령하게 만들었고, 이는 `VideoCapture` 파이프라인의 영상 프레임 전송까지 완전히 일시 정지(Freeze)시키는 최악의 부작용을 유발하였습니다.
   - **설계 수정 팩트**: 현재 소스코드 개선 방향에서는 **`isRecording() == true`인 경우 프리뷰 Surface 차단 명령(`setSurfaceProvider(null)`)을 완전히 건너뛰도록 설계**되어 있습니다. 이로 인해 단말 화면이 꺼져도 카메라 파이프라인은 끊김 없이 프레임을 디스크 인코더로 안전히 전달하고, 단말 화면이 다시 켜져 복귀할 때만 바인딩된 새 `PreviewView`의 `SurfaceProvider`를 매핑해 줍니다.

2. **Android 14+ 백그라운드 오디오 차단 극복**
   - Android 14 (API 34) 이상에서는 포그라운드 서비스라 할지라도 포그라운드 서비스 타입(`foregroundServiceType`) 설정에 `microphone`이 없으면, 앱이 백그라운드로 가는 즉시 마이크에 대한 실시간 스트림 접근 권한이 안드로이드 OS 코어 단에서 강제 묵음 처리됩니다.
   - **설계 수정 팩트**: `AndroidManifest.xml`에 `FOREGROUND_SERVICE_MICROPHONE` 권한을 요청하고, `CameraForegroundService` 태그 아래에 `foregroundServiceType="camera|microphone"` 병렬 명시를 완전 확보함으로써 오디오 Muxing 차단으로 인한 파일 기록 대기 락 프리즈 현상을 원천적으로 분쇄합니다.

---

## 🚀 향후 로드맵 및 개선 내역 (problem260522.md 연동)

현재 `problem260522.md`에 문제점으로 정의된 아래 2가지 긴급 조치 사항은 기술 팩트 분석이 완료되었으며, 향후 다음과 같은 코드 패치가 예정되어 있습니다.

1. **하단 물리 렌즈 선택 칩 UI 제거**
   - 이미 `0.6x`, `1.0x`, `2.0x` 줌 다이얼 버튼 및 하단 슬라이더로 갤럭시 S25 울트라 하드웨어의 초광각/광각/망원 제어가 완벽히 대체되므로, `CameraFragment.kt` 및 `fragment_camera.xml`에서 `lensChipGroup` 관련 칩 생성 및 뷰를 전량 소거하여 한층 심플하고 고급스러운 UI로 통일합니다.

2. **백그라운드 녹화 정지 패치 반영**
   - `CameraController.kt` 내 `detachPreview()`에서 `if (isRecording()) return` 분기를 적용하여 백그라운드/소등 시 세션 붕괴를 영구히 해결합니다.
   - `AndroidManifest.xml` 내 `microphone` 포그라운드 서비스 타입 보강 및 권한 획득 파이프라인을 온전히 활성화하여 Android 14+ 최신 디바이스 최적화를 완수합니다.
