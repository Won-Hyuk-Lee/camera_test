# progress260522.md

`task260522.md` 기준 2026-05-22 작업 현황 스냅샷입니다. 다음 세션에서 이 문서만 읽고 이어 작업할 수 있도록 정리합니다.

## 메타

- 이슈: [#8 Fix camera lifecycle, S25 Ultra controls, and background recording stability](https://github.com/Won-Hyuk-Lee/camera_test/issues/8)
- 브랜치: `main`
- 빌드 검증: `./gradlew clean assembleDebug lintDebug` → **BUILD SUCCESSFUL** (lint error 0)
- 푸시 완료 범위: `e14a9ba..892cd38`

## 커밋 요약

총 16 커밋. 모든 본문에 `Issue: #8` 포함, `Co-Authored-By` 없음, 파일 개별 stage.

| 단계 | 해시 | 메시지 | 핵심 파일 |
|------|------|--------|-----------|
| Docs | `88237ef` | 작업 정의 및 문제 문서 정리 | task260522.md, notice.md, problem260522*.md, README.md |
| P0-3 | `2fee239` | Android 14+ FGS camera/microphone 타입 선언 | AndroidManifest.xml |
| P0-1/4/5 | `3ea754a` | 비녹화 상태 FGS 잔존과 발열 제거 | CameraForegroundService.kt |
| P0-2/4 | `f8c5e2e` | 백그라운드 freeze와 stop/finalize race 수정 | CameraController.kt |
| P0-1/5 | `a247875` | 비녹화 lifecycle 정리 및 Toast 최소화 | CameraFragment.kt |
| Lint | `a401e6a` | Camera2 interop opt-in 및 UseAppTint 오류 수정 | CameraController.kt, dialog_media_viewer.xml |
| P0-4+ | `9d7e604` | 볼륨 길게 누르기 종료를 백그라운드 한정 | build.gradle.kts, CameraForegroundService.kt |
| P0-2+ | `594a8d9` | PreviewView implementationMode compatible 보강 | fragment_camera.xml |
| Chore | `8600c89` | 앱 표시 이름 `test` | strings.xml |
| P1-1 | `1146d14` | 줌 다이얼 0.6x/1x/2x/3x/5x/10x, lens chip 제거 | fragment_camera.xml, CameraFragment.kt |
| P1-3 | `f7fd897` | 비율 preview/output 일치 및 초기 깨짐 수정 | CameraController.kt, CameraFragment.kt |
| P1-4 | `3f4ee89` | FPS/HDR/위치 태그 추가, 파일 크기 최적화 제거 | bottom_sheet_settings.xml, SettingsBottomSheet.kt, CameraController.kt, CameraFragment.kt |
| P1-2 | `b576f1a` | 자동/프로 모드 분리, 프로 OFF 시 수동값 초기화 | bottom_sheet_settings.xml, SettingsBottomSheet.kt, CameraController.kt, CameraFragment.kt |
| Docs | `5fceafc` | README를 실제 동작과 일치하도록 재작성 | README.md |
| P2 | `c0ae588` | PrivateVault IO thread, LruCache, stopPlayback | PrivateVaultFragment.kt |
| P2 | `892cd38` | use case별 setTargetRotation 명시 | CameraController.kt |

## P0 완료 항목

| ID | 항목 | 상태 | 비고 |
|----|------|------|------|
| P0-1 | 비녹화 상태 FGS 잔존 / 발열 | 코드 완료 | 카메라 진입 시 service는 bind만, 녹화 시작 시점에만 `requestStartRecording` foreground 격상. onDestroyView 비녹화면 stop. `START_NOT_STICKY`, `onTaskRemoved` 비녹화 시 정리. |
| P0-2 | 백그라운드/화면 OFF freeze | 코드 1차 완료 | `detachPreview()`에 `if (isRecording()) skip` 가드. PreviewView `implementationMode="compatible"` 적용. **실기기 검증 미수행.** |
| P0-3 | Android 14+ camera/microphone FGS type | 완료 | manifest 권한+type, `startForeground`에 `CAMERA or MICROPHONE` 전달. |
| P0-4 | 볼륨 2초 종료, 흔들기 제거, finalize race | 코드 완료 | 가속도 센서 로직 완전 삭제. 길게 누르기 임계 2000ms, Toast 없음. `stopRecording()`은 즉시 정리하지 않고 `Recording.Finalize` 콜백에서 `recording=null` + wakeLock release + `stopSelf`. `ProcessLifecycleOwner`로 백그라운드일 때만 동작. |
| P0-5 | 촬영 알림 최소화 | 완료 | 시작/완료/저장 완료 Toast 모두 제거 (오류만 노출). 알림 채널 IMPORTANCE_MIN, 문구 "동기화 중 / 백그라운드 작업이 진행 중입니다 / 작업 종료". |

## P1 완료 항목

| ID | 항목 | 상태 | 비고 |
|----|------|------|------|
| P1-1 | S25 Ultra 줌 다이얼 | 완료 | 0.6x/1x/2x/3x/5x/10x. 단말 지원 범위 밖 프리셋 `View.GONE`. mm 렌즈 칩과 `populateLensChips` 전량 제거. 핀치 줌 미구현. |
| P1-2 | 자동/프로 모드 분리 | 완료 | 기본 자동. 프로 모드 ON 시에만 AWB/색온도/셔터/조리개 컨테이너 노출. OFF 전환 시 manual 변수와 AWB를 강제 reset. 사진/영상 모드에 따라 AF mode 분기. |
| P1-3 | 비율 preview/output 일치 | 완료 | RATIO_16_9·RATIO_FULL 모두 use case에 16:9 적용. `previewView.post {}` + parent 측정값 기반으로 초기 width=0 깨짐 수정. |
| P1-4 | FPS/HDR/해상도 / 파일 크기 최적화 제거 | 완료 | `isFileSizeOptimizationEnabled`와 switchOptimization 완전 삭제. FPS 30/60 `CONTROL_AE_TARGET_FPS_RANGE` 주입, HDR `DynamicRange.HDR_UNSPECIFIED_10_BIT` 시도. 위치 태그 토글은 추가됨(EXIF 적용은 미연결, 별도 작업). |
| P1-5 | UI 정리 / README | 완료 | 사진 모드 마이크 버튼 `View.GONE`. README 과장 표현 제거 및 현재 구현 사실에 맞춰 재작성. |

## P2/P3 완료/미완료

| 항목 | 상태 | 비고 |
|------|------|------|
| PrivateVault 성능 | 완료 | `lifecycleScope+Dispatchers.IO`로 파일 조회, 2-스레드 IO + 8MB `LruCache`로 썸네일. holder rebind 가드. |
| VideoView 누수 | 완료 | dialog `setOnDismissListener`에서 `videoView.stopPlayback()`. |
| 설정 저장/회전 | 완료 | `last_lens_facing_back` 추가, switchFacing 후 save 호출, Preview/ImageCapture/VideoCapture `setTargetRotation` 명시. |
| WakeLock 안전성 | 부분 | 30분 timeout으로 acquire 중. 별도 reentrancy 보강은 미적용 (현재 isHeld 체크로 충분). |
| 권한/lint | 완료 | `VIBRATE` 권한 추가, Camera2 interop opt-in 정리, `app:tint` 교체. lint error 0. |
| 위치 태그 EXIF | **미완료** | 토글과 저장은 추가됐지만 `ACCESS_FINE_LOCATION` 권한 요청, `LocationManager` 연동, `ImageCapture.Metadata.location` / MediaStore `LATITUDE/LONGITUDE` 주입은 별도 PR로 분리. |
| 영상 pause/resume | 미수행 | 선택 P3. |
| 앱 이름 / 아이콘 | 완료 | `app_name=test`. 런처 아이콘은 기본 `ic_launcher` 유지. |

## 의도적으로 하지 않은 작업

- 강제 셔터 무음 우회
- 핀치 줌
- 흔들어서 종료
- 파일 크기 최적화 옵션 유지
- Android 시스템 카메라/마이크 사용 표시 숨김
- 빠른 녹화 0.5초 지연 자체 제거

## 실기기 검증 필요 (S25 Ultra)

다음은 코드 수정만으로는 완전 확인이 불가능합니다.

1. 카메라 화면 진입 후 녹화하지 않고 닫았을 때 foreground service 알림이 남지 않는지 / `adb shell dumpsys activity services com.example.camera2study` 결과
2. 영상 녹화 시작 후 홈 이동 30초 이상에서 저장된 파일이 정지 프레임이 아닌지
3. 영상 녹화 시작 후 전원 버튼으로 화면 OFF 30초 이상에서 저장된 파일이 정지 프레임이 아닌지
4. 백그라운드 녹화 중 볼륨 업 2초 이상 → 종료 + 파일 finalize 정상
5. 백그라운드 녹화 중 볼륨 다운 2초 이상 → 동일 동작
6. 백그라운드 녹화 중 기기 흔들기 → 종료되지 않아야 함
7. foreground에서 볼륨 길게 눌러도 녹화가 종료되지 않아야 함
8. 줌 프리셋 0.6/1/2/3/5/10이 표시되고 실제 동작 일치, mm 칩 없음
9. 비율 3:4 / 16:9 / Full preview-output 일치, 초기 진입 깨지지 않음
10. 설정에서 해상도/FPS/HDR 선택이 실제 파일에 반영 / 파일 크기 최적화 옵션 없음
11. 사진 모드에서 마이크 버튼 없음, 영상 모드에서만 보임
12. PrivateVault grid 스크롤 버벅임 없음, 영상 dialog 닫으면 playback 정지
13. 알림 문구가 "동기화 중", 액션이 "작업 종료"
14. 앱 이름이 `test`로 표시

## 알려진 제한 / 위험

- 볼륨 길게 누르기 종료는 `VOLUME_CHANGED_ACTION` 이벤트 빈도를 추적합니다. 볼륨이 max 또는 min에 도달해 더 이상 변하지 않는 상황에서 일부 단말은 이벤트가 끊길 수 있으므로 S25 Ultra 실기기 확인이 필요합니다. 만약 불안정하면 `MediaSessionCompat`을 이용해 raw key event를 직접 수신하는 방식으로 교체해야 합니다.
- HDR 10-bit는 단말 지원 여부에 따라 fallback됩니다. `try/catch`로 묶여 있어 실패해도 일반 녹화로 진행됩니다.
- 위치 태그는 UI/persistence만 들어가 있고 EXIF/metadata 적용 코드는 없습니다. 토글을 켜도 실제 파일에는 위치가 기록되지 않습니다.

## 다음 작업 후보 (우선순위 순)

1. **위치 태그 EXIF/metadata 실제 연결** — `ACCESS_FINE_LOCATION` 권한 요청 흐름, `FusedLocationProviderClient` 또는 `LocationManager.getLastKnownLocation`, ImageCapture `Metadata.location` 및 MediaStore `LATITUDE/LONGITUDE` 주입.
2. **볼륨 종료 안정성 보강 (실기기에서 불안정 확인 시)** — `MediaSessionCompat`으로 교체.
3. **영상 pause/resume** — `Recording.pause()/resume()` UI 노출.
4. **deprecated API 정리** — `setTargetAspectRatio` 대신 `ResolutionSelector` 사용, `VIBRATOR_SERVICE` 대신 `VibratorManager` 사용.

## 환경

- 작업 디렉터리: `c:\Users\HERO\Desktop\camera_test`
- compileSdk 35, targetSdk 35, minSdk 26
- CameraX 1.3.4 (업데이트 후보: 1.6.1)
- Kotlin 17, AGP 8.5.2
