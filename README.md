# 세이프 리그 (Safe League)

같은 길로 출퇴근하는 운전자끼리, 차만 아는 신호로 가장 안전하게 도착한 순서를 겨루는 리그 게임입니다. 주행 중에는 아무것도 보여 주지 않고, 결과는 도착해서 P단에 놓았을 때 한 번만 보여 줍니다.

A commute league for drivers on the same route, ranked by who arrived most safely, using only signals the car knows.

현대자동차그룹 차량용 앱 개발 공모전 출품작(팀 「같은길」). Pleos Connect 앱입니다.

**화면 네 장면**: 아침 출발(리그 카드, 참가), 저녁 주행(앱 화면 없음, 콤보 소리와 앰비언트만), 저녁 도착(P단에서 리그 결과, 순위, 맨 아래 보상 한 줄), 다음 날 아침 카드(도장과 보상 고르기, 「들러요」로 내비 경유지 추가). 판정은 `app/app/src/main/java/ai/pleos/contest/league/`(10Hz 엔진, 감점 8종, 가산 6종)에서 차 신호만으로 합니다.

**보상(도장)**: 주간 리그 상위 3명이 도장을 하나 받습니다(칸 그림은 첫째 벨트, 둘째 깜빡이, 셋째 전조등). 도장 세 개가 차면 길 위 카페 아메리카노나 블루핸즈 안전 점검(제휴 가정) 중 하나를 고르고, 다음 날 출발 전 카드에서 「들러요」를 누르면 내비 경유지로 들어갑니다. 커머스는 이 도장 보상으로만 들어오고 주행 중에는 없습니다.

**리그 서버**: `https://safe-league.alwaysharin.workers.dev` (Cloudflare Workers + D1, 소스는 `server/`). 설정 화면에서 서버 주소를 비우면 서버 없이 고스트 리그(기기 안 기록만)로 동작합니다.

**데모 모드**: 설정 화면의 「데모 모드」 스위치를 켜면 화면 구석에 「데모 입력」이 항상 표시되고, 에뮬레이터가 만들지 못하는 신호(도착, 앰비언트 등)를 앱 안에서 발생시킬 수 있습니다. 정체 판정 창 60초가 10초로 줄어 시연이 짧아집니다. 진단 화면(`DiagActivity`, `LeagueDiagActivity`)은 신호 확인용으로 남겨 두었습니다.

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
4. 속도 신호 권한을 한 번 허용합니다(앱 안 팝업에서 「허용」, 또는 아래 명령).
   ```
   adb shell pm grant --user 10 ai.pleos.contest.pleos_commerce android.car.permission.CAR_SPEED
   ```

## 2. 테스트 환경과 필요한 설정

| 항목 | 값 |
|---|---|
| 에뮬레이터 | Pleos Connect Emulator v2.1.0 |
| 앱 | minSdk 28, targetSdk 35, Kotlin 2.0, Java 17 |
| 빌드 | Gradle 8.10.2(래퍼 포함), Android Gradle Plugin 8.8.2 |
| SDK | `ai.pleos.playground:Vehicle:2.3.2`, `ai.pleos.playground:NaviHelper:3.0.5` |
| SDK 저장소 | `https://nexus-playground.pleos.ai/repository/maven-releases/` (`settings.gradle.kts`에 들어 있음) |
| TTS | Android 기본 TTS. Gleo AI SDK(TextToSpeech, 준비 중)가 열리면 교체 |

`local.properties`는 저장소에 없습니다. Android Studio가 처음 열 때 만들어 주고, 명령 창으로만 빌드할 때는 `app/local.properties`에 Android SDK 경로(`sdk.dir=...`)를 직접 적습니다.

**차 신호 넣기(Virtual Console)**

1. 포트를 연결합니다.
   ```
   adb forward tcp:8088 tcp:8088
   ```
2. 브라우저로 `http://127.0.0.1:8088`을 엽니다. 기어, 브레이크, 핸들, 벨트, 조명, 와이퍼, 배터리 잔량을 바꾸면 앱이 바로 받습니다.
3. **콘솔 브라우저 탭을 앞에 둡니다.** 탭이 뒤로 숨으면 1~2분 뒤 콘솔이 신호를 받지 않습니다(`503 emulator_inactive`). 새로고침하면 다시 받습니다.
4. 장면별로 어떤 신호를 어떤 순서로 넣는지는 `docs/DEMO_SCRIPT_v2.md`에 적어 두었습니다.

**권한**: `AndroidManifest.xml`에 Pleos 차량 권한(`pleos.car.permission.*`)과 내비 권한(`NAVI_ROUTE` 외 3개), AAOS 속도 권한이 선언돼 있습니다. 런타임 허용이 필요한 것은 속도(`CAR_SPEED`) 하나이며 1절 4번으로 허용합니다.

**글꼴**: IBM Plex Sans KR, Barlow Semi Condensed를 `res/font/`에 동봉했습니다(SIL Open Font License, 전문은 `assets/licenses/`).

## 3. 문서

| 파일 | 내용 |
|---|---|
| `docs/PLAN_v3.md` | 기획서: 무엇을 왜 만드는지, 네 장면, 보상, 개발 범위 |
| `docs/SAFE_LEAGUE_RULES.md` | 리그 규칙: 주행 중 표시 금지, 순위 자격, 감점과 가산, 판정 신호 |
| `docs/DEMO_SCRIPT_v2.md` | 2분 시연 대본과 장면별 신호 넣기 순서 |
| `docs/COPY_REVIEW_2026-11-06.md` | 화면 문구 검토 기록 |
| `docs/DEMO_DATA.md` | 시연 데이터 구성, 사건 시각표, 고스트 6명 감점과 가산, 판정 알고리즘 요약 |
| `server/` | 리그 서버(Workers 코드 `src/index.js`, D1 스키마 `schema.sql`, 시연용 시드 `seed_demo.sql`, `wrangler.toml`) |

두 문서 안에 적힌 다른 파일 이름(인계 문서, 조사 자료)은 팀 내부 작업 기록이라 저장소에 넣지 않았습니다.

## 4. APK 위치

`apk/같은길_세이프리그_release.apk`

서명된 release 빌드(versionCode 3, 0.3.0)입니다. 용량 제한을 넘으면 GitHub Releases에 올리고 이 자리에 Release 주소를 적습니다.

## 5. 시연 영상

(업로드 뒤 기입)

## 6. 시연 데이터와 판정

시연 차량과 고스트 6명의 리그 결과가 어떤 감점과 가산으로 만들어졌는지, 판정 창과 임계값은 무엇인지 `docs/DEMO_DATA.md`에 표로 정리했습니다.

## 폴더

```
app/     Pleos Connect 앱 (Gradle 프로젝트, Kotlin)
apk/     release APK
docs/    기획서, 리그 규칙, 시연 대본, 문구 검토
server/  리그 서버 (Cloudflare Workers + D1)
```
