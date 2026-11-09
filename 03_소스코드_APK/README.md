# 차차알림 (ChaCha Allim)

**말로 맡기고, 차가 기억합니다.**

차차알림은 Pleos Connect용 음성 기반 차량 리마인더입니다. 출발 전에 맡긴 일을 다음 주차 시점에 알려줍니다.

## 사용 방법

1. 주차(P) 상태에서 앱 마이크를 누르고 “내릴 때 뒷자리 가방 챙기라고 알려줘”라고 말합니다.
2. 등록된 알림을 확인합니다. 출발 전에는 **다시 말하기**로 교체하거나 **알림 취소**로 삭제할 수 있습니다.
3. Pleos Connect Virtual Console에서 기어를 **P → D → P**로 전환합니다.
4. 주차 알림 카드와 음성 안내를 확인하고 **완료**를 누릅니다. 다른 앱 사용 중에는 음성 안내와 함께 알림 권한이 허용된 경우 시스템 알림이 표시됩니다.

## 구현 범위

- Pleos SpeechToText 3.3.1의 온디바이스 모드로 음성 요청을 받아씁니다.
- 지원하는 한국어 요청 패턴을 안내 문구로 변환하고 대기 알림 1개를 기기에 저장합니다.
- 포그라운드 서비스가 기어 상태를 감시하고 Pleos TextToSpeech 3.3.1로 안내합니다.
- 신규 음성 등록은 P단에서 지원합니다.

## 빌드

Android Studio에서 이 폴더를 열거나 Gradle Wrapper를 사용합니다. Android SDK 37과 Pleos Connect 호환 에뮬레이터 또는 차량 환경이 필요합니다. 앱 패키지명은 `com.chachaallim.app`입니다.

```sh
./gradlew assembleDebug testDebugUnitTest
./gradlew assembleRelease
```

제출용 서명 APK는 `apk/사륜구동_차차알림_release.apk`에 있습니다.
