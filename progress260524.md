# progress260524.md

2026-05-24 기준 `camera_test` 작업 현황 보고서.

## 완료된 커밋 목록

| # | SHA | 커밋 | 핵심 변경 |
|---|-----|------|-----------|
| 1 | 38c4af5 | `Build: Android 16 빌드 기반 및 CameraX 1.4.2 업그레이드` | AGP 8.9.2, Gradle 8.11.1, compileSdk 36, CameraX 1.4.2 |
| 2 | 2f88837 | `Fix: 위치 태그 미구현 기능 제거 및 설정 문구 정리` | isLocationTagEnabled 등 완전 제거, camera2study→test |
| 3 | d447cce | `Fix: deprecated API 정리` | setTargetAspectRatio→ResolutionSelector, VIBRATOR_SERVICE→VibratorManager |
| 4 | 778eb45 | `Feat: 영상 pause/resume 구현` | CameraX Recording.pause/resume, UI 버튼, 타이머 보정 |

## 빌드 및 검증 결과

### assembleDebug

```
BUILD SUCCESSFUL in 53s
39 actionable tasks: 39 executed
```

### lintDebug

```
BUILD SUCCESSFUL in 21s
30 actionable tasks: 12 executed, 18 up-to-date
```

### 정적 검색 결과

위치 태그 잔재 검색:
```
isLocationTagEnabled|last_location_tag|switchLocationTag|onLocationTag → CLEAN
```

deprecated API 잔재:
- `VIBRATOR_SERVICE` — API 30 이하 fallback 코드에만 존재, `@Suppress("DEPRECATION")` 적용. 정상.
- `setTargetAspectRatio` — 전체 제거 완료.

CameraX 버전 확인:
```
val cameraxVersion = "1.4.2"
```

### 16KB ELF alignment 검증

APK 내 native library:
```
lib/arm64-v8a/libimage_processing_util_jni.so
lib/arm64-v8a/libsurface_util_jni.so
lib/armeabi-v7a/libimage_processing_util_jni.so
lib/armeabi-v7a/libsurface_util_jni.so
lib/x86/libimage_processing_util_jni.so
lib/x86/libsurface_util_jni.so
lib/x86_64/libimage_processing_util_jni.so
lib/x86_64/libsurface_util_jni.so
```

zipalign -P 16 검증:
```
Verification successful
```

CameraX 1.4.2에서 `libsurface_util_jni.so`가 새로 포함되었으며, 두 라이브러리 모두 16KB page size 정렬 대응 완료.

## S25 Ultra 실기기 검증

코드로 대체할 수 없는 항목. Galaxy S25 Ultra / Android 16 / One UI 8.0에서 직접 확인 필요:

1. 카메라 진입 → 녹화 안 하고 앱 닫기 → foreground service 잔존 없어야 함
2. 녹화 시작 → 홈 이동 30초 이상 → 저장 파일 정지 프레임 아니어야 함
3. 녹화 시작 → 화면 OFF 30초 이상 → 저장 파일 정지 프레임 아니어야 함
4. 백그라운드 녹화 중 볼륨업 2초 이상 → 녹화 종료, 파일 정상 finalize
5. 백그라운드 녹화 중 볼륨다운 2초 이상 → 4번과 동일
6. foreground에서 볼륨 길게 → 녹화 종료되지 않아야 함
7. pause → resume → stop → 최종 파일 정상 재생 확인
8. 줌/비율/마이크 UI 회귀

## 남은 위험 및 후속 작업

- **실기기 검증 미수행**: 백그라운드 녹화 안정성, pause/resume 파일 무결성은 S25 Ultra에서 직접 확인 필요.
- **targetSdk 36 미적용**: predictive back, edge-to-edge opt-out 제거 등 별도 대응 후 진행.
- **CameraX 1.6.1 미적용**: 내부 CameraPipe 변화로 백그라운드 녹화 회귀 가능성, 별도 세션에서 실기기 포함해 진행.
- **Kotlin 2.x 미업그레이드**: AGP 8.9.2 + Kotlin 1.9.24 조합 유지 중. 경고 발생 시 최소 버전 조정 필요.
