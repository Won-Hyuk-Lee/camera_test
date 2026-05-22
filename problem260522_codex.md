# problem260522_codex.md

## 목적

현재 앱은 갤럭시 S25 Ultra 기본 카메라 스타일을 목표로 하고 있지만, 실제 구현은 일부 줌/비율/프로 설정 흉내와 백그라운드 녹화 안정화 작업이 섞여 있다. 이 문서는 현재 소스 기준으로 추가 개선해야 할 문제점을 정리하고, 향후 구현 방향을 공식 S25 Ultra 카메라 동작에 맞춰 정규화하기 위한 작업 기준을 제공한다.

참고 기준:
- Samsung Galaxy S25 시리즈 카메라 기능: https://www.samsung.com/us/support/answer/ANS10004601/
- Samsung Galaxy 카메라 모드/설정: https://www.samsung.com/us/support/answer/ANS10001353/
- Galaxy S25 Ultra 제품/렌즈/줌 설명: https://www.samsung.com/us/smartphones/galaxy-s25-ultra/

## 사용자 결정사항

- 갤럭시 S25 Ultra 공식 동작을 기준으로 맞춘다.
- 전체 기본 카메라 기능을 모두 복제하지 않는다. 사용자가 필요 없다고 판단한 일부 기능은 제외한다.
- 기본 촬영 경험은 자동모드 중심으로 둔다.
- 자동모드는 오토포커스, 자동 노출, 자동 화이트밸런스, HDR/상황 인식이 가능한 범위에서 자동으로 동작해야 한다.
- 필요 시 별도 프로모드를 두되, 프로모드는 자동모드와 명확히 분리한다.
- 화면비와 동영상 설정은 S25 Ultra 기준으로 정상화하고, 설정 화면에서 조절 가능해야 한다.

## 현재 소스 기준 핵심 문제

### 1. S25 Ultra 줌/렌즈 UX 불일치

현재 상태:
- `fragment_camera.xml`에는 `0.6x`, `1.0x`, `2.0x` 버튼만 있다.
- `CameraFragment.kt`의 줌 버튼은 실제 렌즈 전환이 아니라 `controller.setZoomRatio()`만 호출한다.
- `lensChipGroup`은 mm 단위 물리 렌즈 칩을 별도로 표시한다.
- `CameraUtils.listBackLenses()`는 가장 짧은 초점거리 렌즈를 `isWide`로 표시하는데, S25 Ultra 기준으로는 초광각일 가능성이 높아 라벨이 부정확할 수 있다.

문제점:
- 공식 S25 Ultra는 초광각, 광각, 2x optical-quality, 3x optical, 5x optical, 10x optical-quality, Space Zoom 흐름을 제공한다.
- 현재 `0.6x/1.0x/2.0x`만으로는 S25 Ultra의 주요 줌 단계가 누락된다.
- mm 칩과 배율 버튼이 동시에 존재해 UI가 중복되고, 갤럭시 기본 카메라식 UX와 맞지 않는다.

개선 방향:
- 하단 mm 렌즈 칩 UI는 제거한다.
- 줌 프리셋은 S25 Ultra 기준으로 `0.6x`, `1x`, `2x`, `3x`, `5x`, `10x`를 우선 제공한다.
- 슬라이더는 단말이 제공하는 `zoomState.maxZoomRatio`까지 동적으로 확장하되, 10x 초과는 Space Zoom 영역으로 시각적으로 구분한다.
- 실제 물리 카메라 전환은 CameraX logical camera zoom으로 자연 전환되는지 먼저 검증하고, 문제가 있으면 physical camera id 매핑을 보조 전략으로 둔다.
- 프리셋이 단말에서 지원되지 않는 경우 버튼을 숨기거나 비활성화한다.

완료 조건:
- 사진/동영상 모두 같은 줌 UX를 사용한다.
- mm 칩 없이도 S25 Ultra 기준 주요 줌 단계가 조작 가능하다.
- 3x/5x 프리셋이 추가되고, 단말 지원 범위 밖에서는 안전하게 clamp 또는 비활성화된다.

### 2. 자동모드가 명확히 정의되어 있지 않음

현재 상태:
- 기본 카메라 바인딩 후 `CONTROL_AF_MODE_CONTINUOUS_VIDEO`는 강제된다.
- AE/AWB는 기본적으로 자동이지만, 상세설정에서 수동 노출/WB를 건드리면 자동모드와 프로모드의 경계가 흐려진다.
- HDR 자동, 장면 인식, 인물/사물 자동 처리 여부가 UI나 코드상 명확히 없다.

문제점:
- 사용자가 원하는 핵심은 프로모드가 아니라 상황에 맞는 자동 처리다.
- 현재 구조는 자동모드보다 수동 파라미터 조작 기능이 먼저 노출되어 있다.
- 수동 설정이 적용된 뒤 자동모드로 완전히 복구되는 동작 기준이 없다.

개선 방향:
- 촬영 모드를 `자동모드`와 `프로모드`로 분리한다.
- 자동모드 진입 시 항상 다음 상태를 보장한다.
  - 연속 AF 활성화
  - AE 자동
  - AWB 자동
  - 사용 가능한 경우 자동 HDR 또는 CameraX HDR Extension 활성화
  - 사용 가능한 경우 얼굴/장면 관련 자동 보정 기능 활성화 또는 UI 표시
- Camera2/CameraX에서 단말이 지원하지 않는 자동 기능은 조용히 비활성화하고, 설정 UI에는 "지원 안 함"으로 표시한다.
- 자동모드에서는 수동 노출, 수동 WB, 수동 조리개, 수동 ISO 설정이 남아 있으면 안 된다.

완료 조건:
- 앱 시작 기본값은 자동모드다.
- 자동모드 전환 시 이전 프로 설정이 캡처 요청에 남지 않는다.
- HDR/장면 관련 기능은 지원 여부를 런타임 감지하고, 지원 단말에서만 활성화된다.

### 3. 프로모드는 현재 "진짜 프로모드"가 아님

현재 상태:
- 상세설정에 AWB, 색온도, 셔터 스피드, 조리개, 영상 품질 설정이 있다.
- ISO 수동 제어가 없다.
- 수동 초점 거리 제어가 없다.
- EV 보정은 터치 포커스 후 임시 노출 슬라이더로만 제공된다.
- 수동 노출은 `SENSOR_EXPOSURE_TIME`만 제어하고 `SENSOR_SENSITIVITY`는 제어하지 않는다.

문제점:
- 삼성 Pro/Pro Video에 가까운 모드라고 하기에는 ISO, Focus, EV, WB, shutter의 완성도가 부족하다.
- 현재 UI는 프로모드처럼 보이지만 자동모드와 섞여 있어 사용자가 현재 어떤 제어 상태인지 알기 어렵다.

개선 방향:
- 프로모드는 선택 모드로만 둔다.
- 프로모드에 최소한 다음 항목을 명확히 제공한다.
  - ISO
  - 셔터 스피드
  - WB
  - EV
  - 수동 초점 또는 AF 모드 선택
- 조리개는 S25 Ultra에서 실제 가변 조리개가 지원되는 경우에만 표시한다.
- 프로모드에서만 Camera2 manual capture request를 적용한다.
- 자동모드로 돌아오면 모든 manual request를 clear/reset한다.

완료 조건:
- 자동모드와 프로모드의 설정 저장/적용 범위가 분리된다.
- 프로모드 설정이 자동모드 캡처에 영향을 주지 않는다.

### 4. 모드 구조가 공식 카메라 UX와 다르게 섞여 있음

현재 상태:
- 하단에는 `사진`, `동영상`만 있다.
- 상세설정 안에 자동/프로 성격의 설정이 섞여 있다.
- 사용자가 원하지 않는 전체 모드 복제는 필요 없지만, 자동/프로의 개념 분리는 필요하다.

개선 방향:
- 큰 모드 축은 `자동모드` / `프로모드` 두 개로 정리한다.
- 촬영 타입은 `사진` / `동영상`으로 유지한다.
- UI 구조 예시:
  - 상단 또는 설정: `자동 | 프로`
  - 하단: `사진 | 동영상`
- 자동모드는 기본 카메라처럼 "그냥 찍으면 단말이 판단"하는 흐름을 우선한다.
- 프로모드는 필요한 사용자만 들어가는 고급 설정으로 둔다.

완료 조건:
- 사용자가 현재 자동모드인지 프로모드인지 즉시 알 수 있다.
- 사진/동영상 전환과 자동/프로 전환이 서로 충돌하지 않는다.

### 5. 공식 카메라의 일부 퀵 기능은 의도적으로 제외하되, 기준은 문서화 필요

현재 상태:
- flash, timer, motion photo, effects, QR/document scan, shot suggestions 등은 없다.
- 사용자가 일부 기능은 필요 없어 제외했다고 판단했다.

개선 방향:
- 제외할 기능을 명시적으로 비목표로 둔다.
- 단, S25 Ultra 정상화에 직접 필요한 항목은 제외하지 않는다.

비목표 후보:
- AR Zone
- Bixby Vision
- Food
- Panorama
- Dual Rec
- Portrait Video
- Motion Photo
- QR/document scan
- Voice command
- Floating shutter

보류 또는 선택 후보:
- flash
- timer
- grid
- stabilization
- HDR toggle

완료 조건:
- README와 문제 문서가 "전체 갤럭시 카메라 복제"처럼 보이지 않는다.
- 구현 범위가 자동모드/프로모드/줌/비율/영상 설정으로 명확히 제한된다.

### 6. 화면비 `Full`이 실제 출력 비율과 일치하지 않음

현재 상태:
- `RATIO_FULL`을 선택해도 `CameraController.bindUseCases()`는 4:3 target ratio를 사용한다.
- `CameraFragment.updateRatioSelection()`은 `PreviewView` 높이만 `MATCH_PARENT`로 바꾼다.

문제점:
- 화면은 Full처럼 보이지만 실제 사진/영상 출력은 4:3 기반일 수 있다.
- 프리뷰와 저장 결과물이 다르면 갤럭시 기본 카메라 UX와 맞지 않는다.

개선 방향:
- S25 Ultra 기준 비율 옵션을 정리한다.
  - 사진: 3:4, 9:16, 1:1, Full 중 실제 지원 가능 항목
  - 동영상: 9:16/16:9, Full 또는 기기 지원 해상도 기준
- 프리뷰 crop과 실제 capture output의 차이를 명확히 처리한다.
- CameraX에서 target aspect ratio만으로 부족하면 resolution selector 또는 output size 전략을 별도 적용한다.
- `PreviewView.width == 0`인 초기 레이아웃 타이밍에도 비율 계산이 깨지지 않도록 `post {}` 또는 constraint 기반으로 처리한다.

완료 조건:
- 선택한 화면비와 저장 결과물의 비율이 일치한다.
- Full이 단순 프리뷰 늘림이 아니라 실제 출력 정책으로 정의된다.

### 7. 동영상 설정이 S25 Ultra 기준으로 부족함

현재 상태:
- 영상 품질은 CameraX `Quality.UHD/FHD/HD/SD` 정도만 제공한다.
- FPS 선택이 없다.
- 8K, UHD 60fps, FHD 60fps, HDR10+/10-bit, Log Video, stabilization 등은 없다.
- `isFileSizeOptimizationEnabled`는 값만 있고 실제 recorder 설정에 사용되지 않는다.

문제점:
- S25 Ultra 기준 영상 설정이라고 보기 어렵다.
- 사용자가 설정에서 조절하고 싶은 항목이 해상도/프레임/저장 효율로 분리되어 있지 않다.

개선 방향:
- 설정 화면에 동영상 설정을 다음처럼 재구성한다.
  - 해상도: 8K, UHD, FHD, HD 중 지원 항목
  - FPS: 24/30/60/120/240 중 지원 항목
  - HDR/10-bit: 지원 시 토글
  - Log Video: 지원 시 프로모드 전용 토글
  - 영상 안정화: 지원 시 토글
  - 파일 크기 최적화: 실제 HEVC/H.265 또는 bitrate 정책과 연결
- 단말이 지원하지 않는 조합은 숨기거나 비활성화한다.
- `QualitySelector.from(videoQuality)`만으로 표현할 수 없는 조합은 CameraX/MediaRecorder/Recorder 지원 범위를 재검토한다.

완료 조건:
- 설정 UI에서 해상도와 FPS를 분리해 조절할 수 있다.
- 파일 크기 최적화 스위치가 실제 인코딩 정책에 영향을 준다.
- 지원하지 않는 옵션은 런타임에서 표시되지 않는다.

### 8. HDR/상황 인식 자동 처리 부재

현재 상태:
- 자동 HDR 관련 코드가 없다.
- 인물/사물/장면 인식 관련 코드가 없다.
- CameraX Extension 또는 Camera2 capability 감지가 없다.

문제점:
- 사용자가 원하는 "상황에 맞게 자동으로 잘 찍히는" 경험이 현재 앱의 핵심 기능으로 구현되어 있지 않다.

개선 방향:
- 우선순위는 다음 순서로 둔다.
  1. CameraX Extensions의 HDR/Night/Bokeh 지원 여부 확인
  2. Camera2 capability 기반 자동 HDR/scene mode 지원 여부 확인
  3. 지원 불가 항목은 UI에서 숨김
- 인물/사물 자동 인식은 Android 공개 API만으로 삼성 기본 카메라와 동일하게 구현하기 어렵다. 가능한 범위는 얼굴 감지, AF/AE metering, HDR/Night extension 정도로 제한한다.
- "AI ProVisual Engine과 동일" 같은 표현은 쓰지 않는다.

완료 조건:
- 자동모드에서 지원 가능한 HDR/장면 보정이 자동 적용된다.
- 지원 불가 단말에서도 기능 실패 없이 일반 자동 촬영으로 fallback된다.

### 9. 기존 백그라운드 녹화 안정성 문제도 계속 필수 수정 대상

현재 상태:
- `CameraController.detachPreview()`는 녹화 중에도 `setSurfaceProvider(null)`을 호출한다.
- Android 14+에서 foreground service type이 `camera`만 지정되어 있고 `microphone`이 빠져 있다.
- `startForeground()`도 `FOREGROUND_SERVICE_TYPE_CAMERA`만 넘긴다.

개선 방향:
- 녹화 중에는 preview surface 분리로 인해 video pipeline이 freeze되지 않도록 별도 처리한다.
- Android 14+에서는 `camera | microphone` foreground service type을 정확히 선언하고 시작한다.
- 백그라운드 녹화는 OS 정책상 포그라운드 서비스 알림 및 권한 요구사항을 준수하는 범위에서 안정화한다.

완료 조건:
- 화면 소등/앱 전환 후에도 실제 프레임이 계속 증가하는지 `VideoRecordEvent.Status` 또는 파일 분석으로 검증한다.
- 오디오 포함 녹화와 오디오 mute 녹화를 각각 검증한다.

### 10. 현재 lint 실패 항목도 별도 정리 필요

현재 상태:
- `lintDebug`는 실패한다.
- 확인된 주요 오류:
  - `VIBRATE` 권한 누락
  - Camera2 interop opt-in lint 오류
  - `dialog_media_viewer.xml`의 `android:tint` 사용

개선 방향:
- `VIBRATE` 권한을 manifest에 추가하거나 진동 기능을 제거한다.
- Camera2 interop opt-in은 lint가 인식하는 방식으로 정리한다.
- `android:tint`는 `app:tint`로 수정한다.

완료 조건:
- `./gradlew.bat assembleDebug` 통과
- `./gradlew.bat lintDebug` 통과 또는 의도적 baseline 문서화

### 11. README 설명이 현재 구현보다 과장되어 있음

현재 상태:
- README는 "완벽한 백그라운드", "갤럭시 S25 Ultra 하드웨어 제어", "Full Screen 비율", "파일 크기 최적화" 등을 구현 완료처럼 설명한다.
- 실제 소스는 위 항목들이 미완성이거나 일부만 구현되어 있다.

개선 방향:
- 구현 전에는 README를 "목표/로드맵"과 "현재 구현"으로 분리한다.
- 구현 후에는 실제 동작 기준으로 README를 갱신한다.

완료 조건:
- README가 현재 소스와 충돌하지 않는다.
- 미구현 기능은 명확히 "예정" 또는 "보류"로 표시된다.

## 권장 구현 순서

1. 기존 안정성 필수 수정
   - `detachPreview()` 녹화 중 freeze 방지
   - Android 14+ camera/microphone foreground service type 정리
   - `VIBRATE` 등 lint 치명 오류 수정

2. 모드 구조 정리
   - 자동모드/프로모드 분리
   - 사진/동영상 전환은 별도 유지
   - 자동모드 진입 시 manual request reset

3. S25 Ultra 줌 UX 정상화
   - mm 렌즈 칩 제거
   - `0.6x/1x/2x/3x/5x/10x` 프리셋 추가
   - Space Zoom 영역 슬라이더/표시 정책 정리

4. 화면비 정상화
   - 프리뷰와 저장 결과물 비율 일치
   - `Full`의 실제 의미 재정의

5. 동영상 설정 확장
   - 해상도/FPS/HDR/Log/안정화/파일 크기 최적화 분리
   - 지원 여부 런타임 감지

6. 자동 HDR/상황 인식 보강
   - CameraX Extensions 또는 Camera2 capability 기반으로 지원되는 자동 기능만 활성화
   - 지원 불가 시 일반 자동 촬영 fallback

7. README 업데이트
   - 실제 구현 완료 항목과 로드맵 분리

## 검증 체크리스트

- `./gradlew.bat assembleDebug`
- `./gradlew.bat lintDebug`
- S25 Ultra 실기기에서 사진 자동모드 촬영
- S25 Ultra 실기기에서 동영상 자동모드 촬영
- `0.6x/1x/2x/3x/5x/10x` 프리셋 동작 확인
- 프리뷰 화면비와 저장 파일 화면비 비교
- 화면 소등 후 녹화 프레임 증가 여부 확인
- 오디오 켬/끔 각각 녹화 확인
- 자동모드에서 AWB/AE/AF가 수동 설정에 오염되지 않는지 확인
- 프로모드 진입/이탈 시 설정이 독립적으로 동작하는지 확인
