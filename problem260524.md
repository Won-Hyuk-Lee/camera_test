# problem260524.md

2026-05-24 기준 현재 상태, 미진행 작업, 버전 조사 결과를 기록합니다.
다음 세션에서 이 문서와 `progress260522.md`를 함께 읽고 이어 작업할 수 있도록 정리합니다.

---

## 메타

- 이슈: [#8 Fix camera lifecycle, S25 Ultra controls, and background recording stability](https://github.com/Won-Hyuk-Lee/camera_test/issues/8)
- 브랜치: `main`
- HEAD: `0fec383`
- 기기: Galaxy S25 Ultra / **Android 16 (API 36)** / One UI 8.0
- 작업 디렉터리: `c:\Users\Lee\Desktop\camera_test`

---

## 현재 코드 상태 요약

`progress260522.md` 기술과 실제 소스가 대체로 일치함. 코드 레벨 완료 항목:

| 항목 | 상태 |
|------|------|
| P0-1: 비녹화 FGS 잔존/발열 | 완료 (`onDestroyView`에서 비녹화 시 stop, `START_NOT_STICKY`) |
| P0-2: 백그라운드 freeze 1차 가드 | 완료 (`detachPreview` 녹화 중 skip) |
| P0-3: Android 14+ FGS camera/microphone type | 완료 (Manifest + startForeground 양쪽 모두) |
| P0-4: 볼륨 길게 누르기 종료, finalize race 수정 | 완료 (2000ms, finalize 콜백에서 stopSelf) |
| P0-5: 알림 최소화 | 완료 ("동기화 중" / "백그라운드 작업이 진행 중입니다" / "작업 종료", IMPORTANCE_MIN) |
| P1-1: 줌 프리셋 0.6/1/2/3/5/10 | 완료 (지원 범위 밖 GONE 처리) |
| P1-2: 자동/프로 모드 분리 | 완료 (OFF 시 수동 파라미터 reset, 사진/영상 AF 분기) |
| P1-3: 비율 preview/output 일치 | 완료 (post{} 기반 초기 깨짐 수정) |
| P1-4: FPS/HDR/파일 크기 최적화 제거 | 완료 |
| P1-5: 사진 모드 마이크 버튼 GONE | 완료 |
| P2: PrivateVault IO thread, LruCache | 완료 |
| P2: setTargetRotation 명시 | 완료 |
| 앱 이름 `test` | 완료 |

실기기 검증은 전항목 미수행. 코드 수정만으로는 확인 불가.

---

## 미진행 작업

### 1. 위치 태그 완전 제거 (결정: 제거)

**결정 근거**: 영상 private_vault 저장 경로에서 MP4 메타데이터 주입이 CameraX VideoCapture 구조상 사실상 불가. 사진만 연결하는 것도 반쪽짜리이므로 기능 자체를 제거하기로 확정.

제거 대상:
- `CameraController.kt` — `isLocationTagEnabled` 변수
- `SettingsBottomSheet.kt` — 위치 태그 토글 UI, `onLocationTag` 콜백
- `SettingsBottomSheet.Callbacks` — `onLocationTag` 인터페이스 메서드
- `CameraFragment.kt` — `onLocationTag` 콜백 구현, `saveCameraSettings()`/`restoreCameraSettings()`의 `last_location_tag` 키
- `bottom_sheet_settings.xml` — 위치 태그 토글 뷰

### 2. 배터리 최적화 문구 수정 (2곳)

`app_name`이 `test`로 바뀌었으나 문구는 여전히 `camera2study` 표기.

| 파일 | 위치 | 현재 문구 | 수정 문구 |
|------|------|-----------|-----------|
| `app/src/main/res/layout/fragment_settings.xml` | line 109 | `'camera2study' 앱 검색` | `'test' 앱 검색` |
| `app/src/main/java/com/example/camera2study/ui/SettingsFragment.kt` | line 147 | `'camera2study' 앱을 검색해 찾습니다` | `'test' 앱을 검색해 찾습니다` |

### 3. Deprecated API 교체

#### 3-1. `setTargetAspectRatio` → `ResolutionSelector`

현재 `CameraController.kt`의 Preview, ImageCapture 빌더에서 `setTargetAspectRatio()`를 사용 중. CameraX에서 deprecated.

교체 방향:
- `ResolutionSelector` + `AspectRatioStrategy`로 교체
- `RATIO_4_3` / `RATIO_16_9` 각각 `AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY` / `RATIO_16_9_FALLBACK_AUTO_STRATEGY` 사용
- VideoCapture는 `QualitySelector` 기반이라 해당 없음

주요 파일: `CameraController.kt` (line 217, 238)

#### 3-2. `VIBRATOR_SERVICE` → `VibratorManager`

현재 `CameraForegroundService.kt`에서 `getSystemService(VIBRATOR_SERVICE) as Vibrator` 사용. API 31+ deprecated.

교체 방향:
- `getSystemService(VibratorManager::class.java).defaultVibrator` 사용
- `minSdk = 26`이므로 분기 처리 필요 (`Build.VERSION.SDK_INT >= 31` 이상이면 VibratorManager, 미만이면 기존)

주요 파일: `CameraForegroundService.kt` (line 177)

### 4. 영상 pause/resume 구현

CameraX `Recording.pause()` / `Recording.resume()` API 활용. 선택 P3였으나 이번에 구현.

구현 범위:
- `CameraController.kt` — `pauseRecording()`, `resumeRecording()`, `isPaused()` 메서드 추가
- `CameraForegroundService.kt` — pause/resume 위임 메서드 추가
- `CameraFragment.kt` — pause/resume 버튼 로직, UI 상태 전환
- `fragment_camera.xml` — pause/resume 버튼 뷰 추가 (녹화 중에만 표시)
- 타이머: pause 중 멈춤, 재개 시 누적 시간 이어서 표시
- `VideoRecordEvent.Pause` / `VideoRecordEvent.Resume` 이벤트 처리

---

## 버전 업그레이드 조사

### 배경: Android 16 / One UI 8.0 영향

기기가 Android 16 (API 36)이므로 현재 버전 셋업에 실질적 문제가 있음.

#### 핵심 문제: 16 KB 페이지 크기

Android 16부터 기기가 4 KB 대신 16 KB 메모리 페이지를 사용할 수 있음.
앱에 포함된 네이티브 `.so` 파일이 16 KB 정렬을 지원하지 않으면 크래시 또는 로드 실패 가능.
CameraX는 네이티브 코드를 포함하며, **1.3.4는 Android 16 출시 이전 버전이라 보장 없음**.

#### compileSdk / targetSdk 전략

- **compileSdk 35 → 36**: API 36 기반으로 컴파일해야 함. AGP 업그레이드 선행 필수.
- **targetSdk 35 유지**: 36으로 올리면 Predictive Back 제스처 강제, 일부 암시적 Intent 제한 강화 등 대응 비용 발생. 지금은 35 유지가 안전.

#### AGP 경고 원인

`AGP 8.5.2` + `compileSdk 35` 조합에서 "테스트 범위 밖" 경고. compileSdk 36 사용 불가. → 업그레이드 필요.

### 확정 버전 업그레이드 목록

| 항목 | 현재 | 변경 목표 | 이유 |
|------|------|-----------|------|
| `compileSdk` | 35 | **36** | Android 16 대응 |
| `targetSdk` | 35 | **35 유지** | Predictive Back 등 대응 비용 회피 |
| AGP | 8.5.2 | **8.9.x** | compileSdk 36 지원 |
| Kotlin | 1.9.24 | **2.0.x** | AGP 8.7+ 권장 버전 |
| CameraX | 1.3.4 | **1.4.0 이상** | 16 KB 페이지 크기 네이티브 정렬 보장 최소 버전 |
| `core-ktx` | 1.13.1 | **1.15.0** | API 36 대응 |
| `lifecycle-*` | 2.7.0 | **2.8.x** | Kotlin 2.x / AGP 호환성 |
| `activity-ktx` | 1.9.1 | **1.10.x** | API 36 대응 |

`appcompat`, `material`, `constraintlayout`, `fragment-ktx`는 현재 버전이 양호하거나 AGP 업그레이드 후 호환성 재확인 예정.

### One UI 8.0 특이사항 (코드 수정 불필요, 실기기 확인 사항)

- 배터리 최적화가 더 공격적. 앱 내 배터리 최적화 해제 가이드 이미 있음.
- `VOLUME_CHANGED_ACTION` 방식은 Samsung 기기에서도 동작 확인된 안전한 접근법.
- FGS 알림 시각 표현이 다를 수 있으나 기능에는 영향 없음.
- Samsung 전용 카메라 HAL은 CameraX가 추상화하므로 직접 영향 없음.

---

## 알려진 위험 / 보류 항목

### 볼륨 길게 누르기 종료 안정성

현재 `VOLUME_CHANGED_ACTION` 이벤트 빈도 추적 방식. 볼륨이 max/min에 도달하면 일부 단말에서 이벤트가 끊길 수 있음.

- S25 Ultra 실기기 검증 후 불안정하면 `MediaSessionCompat` raw key event 수신으로 교체.
- **지금은 코드 수정 없이 실기기 확인 대기.**

주요 파일: `CameraForegroundService.kt`

### 실기기 검증 미수행 (전항목)

progress260522.md의 실기기 체크리스트 14항목 전부 미수행 상태.
버전 업그레이드 및 이번 수정 완료 후 S25 Ultra 실기기 검증 필요.

---

## 이번 세션에서 하지 않는 것

- targetSdk 36 올리기 (Predictive Back 대응 비용, 별도 세션)
- 영상 pause/resume을 제외한 추가 기능
- 강제 셔터 무음 우회
- 핀치 줌
- Android 시스템 카메라/마이크 사용 표시 숨기기

---

## 작업 순서 (이번 세션)

1. **버전 업그레이드** (AGP → Kotlin → compileSdk 36 → CameraX + AndroidX)
   - 빌드 확인 (`.\gradlew.bat assembleDebug`)
2. **위치 태그 완전 제거**
3. **배터리 최적화 문구 수정 2곳**
4. **deprecated API 교체** (ResolutionSelector, VibratorManager)
5. **영상 pause/resume 구현**
6. **최종 빌드 + lint 확인** (`.\gradlew.bat assembleDebug lintDebug`)
