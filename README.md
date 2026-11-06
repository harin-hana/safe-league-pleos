# 세이프 리그 (Safe League)

같은 길로 출퇴근하는 운전자끼리, 차만 아는 신호로 가장 안전하게 도착한 순서를 겨루는 리그 게임입니다. 주행 중에는 아무것도 보여 주지 않고, 결과는 도착해서 P단에 놓았을 때 한 번만 보여 줍니다.

A commute league for drivers on the same route, ranked by who arrived most safely, using only signals the car knows.

현대자동차그룹 차량용 앱 개발 공모전 출품작(팀 「같은길」). Pleos Connect 앱입니다.

> 지금 올라간 앱은 **신호 진단 단계**입니다. 에뮬레이터에서 Vehicle SDK와 NaviHelper 신호를 받아 리그 판정 규칙(`Rules.kt`)에 넣어 보는 화면까지 들어 있습니다. 시연 앱 화면은 다음 푸시에 올라갑니다.

## 1. 실행 방법

1. **Pleos Connect Emulator v2.1.0**을 설치하고 Cold boot로 켭니다. 내비 앱이 떠 있어야 NaviHelper가 응답합니다.
2. 빌드합니다. Android Studio로 `app/` 폴더를 열어 실행하거나, 명령 창에서 아래처럼 빌드합니다.
   ```
   cd app
   gradlew.bat assembleDebug
   ```
   결과 파일은 `app/app/build/outputs/apk/debug/app-debug.apk`입니다.
3. 에뮬레이터에 설치하고 실행합니다.
   ```
   adb install -r app/app/build/outputs/apk/debug/app-debug.apk
   adb shell am start -n ai.pleos.contest.pleos_commerce/ai.pleos.contest.MainActivity
   ```
   release APK로 볼 때는 `adb install -r apk/같은길_세이프리그_release.apk`를 씁니다(4절).

## 2. 테스트 환경과 필요한 설정

| 항목 | 값 |
|---|---|
| 에뮬레이터 | Pleos Connect Emulator v2.1.0 |
| 앱 | minSdk 28, targetSdk 35, Kotlin 2.0, Java 17 |
| 빌드 | Gradle 8.10.2(래퍼 포함), Android Gradle Plugin 8.8.2 |
| SDK | `ai.pleos.playground:Vehicle:2.3.2`, `ai.pleos.playground:NaviHelper:3.0.5` |
| SDK 저장소 | `https://nexus-playground.pleos.ai/repository/maven-releases/` (`settings.gradle.kts`에 들어 있음) |

`local.properties`는 저장소에 없습니다. Android Studio가 처음 열 때 만들어 주고, 명령 창으로만 빌드할 때는 `app/local.properties`에 Android SDK 경로(`sdk.dir=...`)를 직접 적습니다.

**차 신호 넣기(Virtual Console)**

1. 포트를 연결합니다.
   ```
   adb forward tcp:8088 tcp:8088
   ```
2. 브라우저로 `http://127.0.0.1:8088`을 엽니다. 기어, 브레이크, 핸들, 벨트, 조명, 와이퍼, 배터리 잔량을 바꾸면 앱이 바로 받습니다.
3. **콘솔 브라우저 탭을 앞에 둡니다.** 탭이 뒤로 숨으면 1~2분 뒤 콘솔이 신호를 받지 않습니다(`503 emulator_inactive`). 새로고침하면 다시 받습니다.
4. 장면별로 어떤 신호를 어떤 순서로 넣는지는 `docs/DEMO_SCRIPT_v2.md`에 적어 두었습니다.

**권한**: `AndroidManifest.xml`에 Pleos 차량 권한(`pleos.car.permission.*`)과 내비 권한(`NAVI_ROUTE` 외 3개), AAOS 속도 권한이 선언돼 있습니다. 에뮬레이터에서는 따로 허용할 것이 없습니다.

## 3. 문서

| 파일 | 내용 |
|---|---|
| `docs/SAFE_LEAGUE_RULES.md` | 리그 규칙: 주행 중 표시 금지, 순위 자격, 감점과 가산, 판정 신호 |
| `docs/DEMO_SCRIPT_v2.md` | 2분 시연 대본과 장면별 신호 넣기 순서 |

두 문서 안에 적힌 다른 파일 이름(인계 문서, 조사 자료)은 팀 내부 작업 기록이라 저장소에 넣지 않았습니다.

## 4. APK 위치

`apk/같은길_세이프리그_release.apk`

서명된 release 빌드입니다. 지금 올라간 APK는 신호 진단 단계 빌드이고, 시연 앱이 완성되면 같은 이름으로 바꿔 올립니다. 용량 제한을 넘으면 GitHub Releases에 올리고 이 자리에 Release 주소를 적습니다.

## 폴더

```
app/    Pleos Connect 앱 (Gradle 프로젝트, Kotlin)
apk/    release APK
docs/   리그 규칙, 시연 대본
```
