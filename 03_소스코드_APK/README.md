# 차차알림 (ChaCha Allim)

**말로 맡기면, 차가 기억합니다.**

영문 계정·URL 표기: `chachaallim`

차차알림은 현대 Pleos Connect 환경을 위한 음성 기반 차량 리마인더 앱입니다. 운전자가 주차 상태에서 음성으로 할 일을 등록하면, 이후 차량이 주행 상태를 거쳐 다시 주차 상태가 되었을 때 알림을 표시하고 음성으로 안내합니다.

## 주요 기능

- **음성 리마인더 등록:** 주차(P) 상태에서 음성 명령으로 알림 생성
- **차량 상태 인식:** 기어 상태 변화(P → D → P)를 이용한 하차 시점 알림
- **화면 및 음성 알림:** 리마인더 카드 표시 및 TTS 음성 안내
- **알림 완료:** 확인한 리마인더를 완료 처리

## 사용 예시

1. 주차 상태에서 “내릴 때 노트북 챙기라고 알려줘”라고 말합니다.
2. 차차알림이 리마인더를 등록합니다.
3. 차량이 주행(D) 상태를 거쳐 다시 주차(P) 상태가 됩니다.
4. “내리기 전에 노트북을 챙겨 주세요.”라는 알림과 음성 안내가 제공됩니다.

## 개발 환경

- Kotlin / Android
- Android Automotive OS
- Hyundai Pleos Connect SDK
- Pleos SpeechToText 및 TextToSpeech
- Android Studio

## APK

차차알림(ChaCha Allim) 최종 서명 APK입니다. 공모전 규정에 따라 `apk/` 폴더에 있습니다. Android 패키지명은 `com.chachaallim.app`입니다.

- [apk/사륜구동_차차알림_release.apk](apk/사륜구동_차차알림_release.apk)

새 패키지 `com.chachaallim.app`은 기존 앱과 별도로 설치되며, 기존 앱의 데이터는 자동으로 이전되지 않습니다. 설치 및 사용에는 지원되는 차량용 Android 환경이 필요합니다.

## 현재 구현 범위

현재 버전은 차차알림 앱이 전면에서 실행된 상태에서 기어 상태 변화를 감지하여 리마인더를 안내하는 MVP입니다. 백그라운드 상태에서의 알림 보장은 아직 구현 범위에 포함되지 않습니다.

현대자동차그룹 차량용 앱 개발 공모전 출품작

---

**ChaCha Allim** is a voice-powered vehicle reminder app for Hyundai Pleos Connect, designed to help drivers remember important items when parking.
