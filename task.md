# Camera2 API 학습용 Android 앱 작업 목록

## 📌 전체 진행 상황
- [ ] 1단계: 백그라운드 녹화 중단 문제 해결 및 구조 개편 (#1)
- [ ] 2단계: 광각 카메라 및 자동 초점(Continuous AF + Tap to Focus) 개선 (#2)
- [ ] 3단계: 배터리 최적화 해제, 영상 품질, 최대 시간/반복, 알림창 즉시 종료 구현 (#3)

---

## 🛠 세부 작업 체크리스트

### 1단계: 백그라운드 녹화 중단 문제 해결 (#1)
- [ ] `CameraForegroundService` 리팩토링
  - [ ] `LifecycleService` 수명주기를 `CameraController`로 이관하여 서비스가 카메라 인스턴스 소유하게 변경
  - [ ] 서비스가 직접 프로세스 바인딩을 관리하도록 구현
  - [ ] UI가 없을 때(백그라운드)에도 캡처 세션이 죽지 않는 안정적인 생명주기 관리 설계
- [ ] `CameraController` 리팩토링
  - [ ] `LifecycleOwner`와 `PreviewView`를 동적으로 결합 및 분리(`attachPreview`/`detachPreview`)할 수 있도록 로직 수정
- [ ] `CameraFragment`와 `Service` 바인딩 연동
  - [ ] `bindService`를 사용해 서비스 연결 관리
  - [ ] 프래그먼트 진입 시 서비스에 바인딩하고, 화면 프리뷰를 주입
  - [ ] 앱이 화면에서 사라지거나 파괴되어도 카메라 캡처 세션을 종료하지 않고 유지

### 2단계: 광각 카메라 및 자동 초점 개선 (#2)
- [ ] 광각 전환 방식 보완 (배율 제어)
  - [ ] `camera.cameraControl.setZoomRatio()` 기능 구현
  - [ ] UI에 줌 배율 제어 슬라이더(`Slider`) 배치
  - [ ] `0.5x`(광각), `1x`(기본), `2x`(망원) 퀵 줌 제어 버튼 도입
- [ ] 자동 초점(AF) 개선
  - [ ] `CaptureRequest.CONTROL_AF_MODE`를 `CONTROL_AF_MODE_CONTINUOUS_VIDEO`로 강제 주입하여 촬영 중 상시 포커싱 보장
  - [ ] `PreviewView`에 터치 리스너 연결 및 좌표 계산
  - [ ] `FocusMeteringAction`을 통해 클릭 영역에 즉시 초점을 맞추는 **Tap to Focus(원터치 초점)** 구현 및 UI 피드백 제공

### 3단계: 배터리 최적화 해제 및 신규 부가 기능 탑재 (#3)
- [ ] 배터리 최적화 해제 (`Ignore Battery Optimizations`) 연동
  - [ ] `AndroidManifest.xml`에 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 권한 추가
  - [ ] 앱 시작 시 해제 여부 판단 및 시스템 최적화 예외 다이얼로그 호출 로직 연동
- [ ] 최대 녹화 시간 & 반복 녹화 구현
  - [ ] 설정 BottomSheet에 최대 녹화 시간 선택 스피너 추가 (제한 없음, 10초(테스트), 1분, 5분, 10분)
  - [ ] 설정 BottomSheet에 반복 횟수 선택 스피너 추가 (1회, 3회, 5회, 무한)
  - [ ] 녹화 파일 완료 시(`VideoRecordEvent.Finalize`) 남은 횟수가 있고 한계 도달로 종료되었다면 릴레이식 재녹화 자동 시작
- [ ] 영상 품질 선택 및 파일 크기 최적화
  - [ ] 설정 BottomSheet에 품질 스피너 추가 (`UHD`, `FHD`, `HD`, `SD`)
  - [ ] 품질 선택에 따라 `QualitySelector`를 다르게 하여 CameraX Video Recorder 생성
  - [ ] 파일 용량을 극도로 아끼기 위한 SD/HD 지원 및 비트레이트 조절
- [ ] 알림창 강제 종료 버튼 구현
  - [ ] `CameraForegroundService`의 알림(Notification) 레이아웃에 `녹화 종료` 액션 버튼 삽입
  - [ ] 브로드캐스트 리시버(`NotificationActionReceiver`) 또는 PendingIntent를 통해 알림 클릭 시 백그라운드 녹화를 안전하게 중단하고 서비스 정지(`stopSelf`)