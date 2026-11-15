# 세이프 리그 (Safe League)

같은 길로 출퇴근하는 운전자끼리, 차만 아는 신호로 가장 안전하게 도착한 순서를 겨루는 리그 게임입니다. 주행 중에는 화면에 아무것도 띄우지 않고, 가점 순간(콤보 달성 등)에만 짧은 사운드 노티로 알립니다. 감점은 알럿 없이 기록만 하고, 도착해 P단에 놓는 순간 결과 화면을 한 번만 팝업으로 엽니다.

A commute league for drivers on the same route, ranked by who arrived most safely, using only signals the car knows.

현대자동차그룹 차량용 앱 개발 공모전 출품작(팀 「같은길」). Pleos Connect 앱입니다.

## 0. 에뮬레이터 실행 가이드 (5단계)

환경은 Pleos Connect Emulator v2.1.0이고, 명령은 저장소 폴더에서 PowerShell로 실행합니다. 에뮬레이터는 차 위치가 고정이라 실제 도착 판정을 만들 수 없어 데모 모드로 확인합니다(고정 값은 아래 「데모 모드」 표).

| 단계 | 할 일 | 확인할 것 |
|---|---|---|
| 1 | APK 설치, 속도 신호 권한 허용 | 출발 카드 표시 |
| 2 | 자동 실행 권한 명령 1회 | P단에서 팝업 동작 |
| 3 | 설정에서 데모 모드 ON | 설정 화면 왼쪽 메뉴 「데모」의 토글 |
| 4 | Virtual Console로 차량 신호 입력 | 주행 중 앱 화면 없음, 가점 순간에만 사운드 노티, 감점은 알럿 없이 기록 |
| 5 | P단 | 결과 화면 팝업, 내역 보기 드롭다운 |

1. **APK 설치**
   ```
   adb install -r apk/같은길_세이프리그_release.apk
   ```
   앱 목록에서 「세이프 리그」를 실행하고, 속도 신호 권한 팝업에서 「허용」을 선택합니다. 속도 권한이 없으면 판정이 동작하지 않습니다.
2. **자동 실행 권한 부여(1회)**
   ```
   adb shell "appops set --user 10 ai.pleos.contest.pleos_commerce SYSTEM_ALERT_WINDOW allow; appops write-settings"
   ```
   시동(부팅) 후 P단에서 출발 카드가, 도착 후 P단에서 결과 화면이 터치 없이 팝업으로 뜨는 데 필요한 안드로이드 「다른 앱 위에 표시」 권한입니다. Pleos 에뮬레이터에는 이 권한의 설정 화면이 없어 명령으로 부여합니다. 부여하지 않으면 팝업이 뜨지 않을 수 있으며, 이 경우 앱 목록 아이콘으로 실행하면 같은 화면이 열립니다.
3. **데모 모드 ON**: 앱 화면 왼쪽 위 「세이프 리그」를 누르면 설정 화면이 열립니다. 왼쪽 메뉴에서 「데모」를 누르고 「데모 모드」 토글을 켭니다.
4. **차량 신호 입력(Virtual Console)**
   ```
   adb forward tcp:8088 tcp:8088
   ```
   브라우저로 `http://127.0.0.1:8088`을 열어 앞에 둡니다(탭이 뒤로 가면 1~2분 뒤 신호 수신이 멈추고, 새로고침하면 복구됩니다). 벨트와 기어는 콘솔 화면에서도 입력할 수 있고, 속도는 화면 카드가 없어 아래 명령으로 입력합니다.
   ```powershell
   $v='http://127.0.0.1:8088/vehicle'
   curl.exe -X PUT "$v/gear?gear=Park"
   curl.exe -X PUT "$v/controls?speed=0"
   curl.exe -X PUT "$v/telltales/simulator?type=SeatBelt&state=Off&color=Red"   # 벨트 체결 상태(벨트 경고등 OFF)
   ```
   출발 카드에서 「참가하고 출발」 버튼을 누른 뒤 이어서 입력합니다.
   ```powershell
   curl.exe -X PUT "$v/gear?gear=Drive"; Start-Sleep 2           # 앱 화면이 닫히고 차의 기본 내비 표시
   curl.exe -X PUT "$v/controls?speed=60"; Start-Sleep 5
   # 깜빡이 켜고 차선 변경 1회(콤보 +1)
   curl.exe -X PUT "$v/turn-signal?mode=BlinkLeft"; Start-Sleep 4
   curl.exe -X PUT "$v/controls?steering=8";        Start-Sleep 1
   curl.exe -X PUT "$v/controls?steering=0";        Start-Sleep 2
   curl.exe -X PUT "$v/turn-signal?mode=Off";       Start-Sleep 4
   # 깜빡이 없이 차선 변경 1회(감점, 알럿 없음)
   curl.exe -X PUT "$v/controls?steering=8"; Start-Sleep 1
   curl.exe -X PUT "$v/controls?steering=0"; Start-Sleep 3
   # 급제동 1회(감점, 알럿 없음)
   curl.exe -X PUT "$v/controls?brake=60"
   curl.exe -X PUT "$v/controls?speed=50"; Start-Sleep -Milliseconds 800
   curl.exe -X PUT "$v/controls?brake=0";  Start-Sleep 3
   curl.exe -X PUT "$v/controls?speed=0"
   ```
   콤보 사운드 노티는 콤보 5회와 10회에 울립니다. 빗길 전조등 가산과 정체 구간 급제동까지 포함한 2분 시연 순서는 `docs/DEMO_SCRIPT_v2.md`에 있습니다.
5. **P단에서 결과 확인**
   ```powershell
   curl.exe -X PUT "$v/gear?gear=Park"
   ```
   데모 모드는 주행 중 P단을 도착으로 처리합니다. 결과 화면이 팝업으로 뜨고, 「내역 보기」를 누르면 감점과 가산 내역이 드롭다운으로 펼쳐집니다. 판정 로그는 `adb logcat -s LEAGUE_JUDGE`로 확인합니다.

## 앱 한눈에

**화면 네 장면**: 아침 출근 한 번(집에서 회사)을 따라갑니다. 아침 출발(리그 카드, 참가), 주행(앱 화면 없음, 콤보 소리와 앰비언트만), 도착 결과(P단에서 리그 결과, 순위, 맨 아래 보상 한 줄), 다음 날 아침 카드(도장과 보상 고르기, 「들러요」로 내비 경유지 추가). 판정은 `app/app/src/main/java/ai/pleos/contest/league/`(10Hz 엔진, 감점 8종, 가산 6종)에서 차 신호만으로 합니다.

**보상(도장)**: 주간 리그 상위 3명이 도장을 하나 받습니다(칸 그림은 첫째 벨트, 둘째 깜빡이, 셋째 전조등). 도장 세 개가 차면 길 위 카페 아메리카노나 블루핸즈 안전 점검(제휴 가정) 중 하나를 고르고, 다음 날 출발 전 카드에서 「들러요」를 누르면 내비 경유지로 들어갑니다. 커머스는 이 도장 보상으로만 들어오고 주행 중에는 없습니다.

**리그 서버**: `https://safe-league.alwaysharin.workers.dev` (Cloudflare Workers + D1, 소스는 `server/`). 설정 화면에서 서버 주소를 비우면 서버 없이 고스트 리그(기기 안 기록만)로 동작합니다.

**데모 모드**: 에뮬레이터 내비는 GPS 신호가 없어 차 위치가 고정됩니다. 실제 도착 판정(목적지 300 m 안에서 P단)을 만들 수 없어, 설정 화면의 「데모 모드」를 켜면 주행 중 P단을 도착으로 처리하고 출발부터 결과까지 아침 출근 한 번으로 시연합니다. 데모 모드에서도 판정(벨트, 전조등, 깜빡이, 급제동, 콤보)과 점수 계산식은 차량 신호로 실제 동작합니다. 데모 모드가 고정하는 값은 아래 표와 같습니다.

| 항목 | 데모 모드 | 일반 모드 |
|---|---|---|
| 목적지, 예상 소요, 거리 | 회사, 35분, 15 km | 차의 기본 내비 값 |
| 점수 계산 거리 | 15 km | 실제 주행 거리 |
| 도착 화면의 소요 시간 | 26분 | 실제 소요 시간 |
| 콤보 사다리 「감점 0, 5 km」 칸 | 항상 달성으로 표시 | 감점 없이 5 km 주행 시 달성 |
| 시간대 | 아침 | 출발 시각 기준(아침 06~10시, 저녁 17~21시) |
| 순위 자격 | 항상 충족으로 처리 | 시간대, 목적지, 3 km 이상, 예상 소요 120% 안 도착 |
| 같은 길 참가자 | 시드 6명(화면의 「같은 길 7명」은 본인 포함) | 리그 서버의 같은 리그 참가자 |
| 「어제 아침보다」 기준값 | 시드 값 99.3 | 리그 서버의 본인 이전 기록 |
| 서버 전송 계정 | 시연용 계정으로 고정 | 익명 id |
| 정체 판정 기준 시간 | 직전 10초 | 직전 60초 |

다음 주 월요일 카드의 도장 3개와 보상 선택지 2곳은 모드와 관계없이 시연용 고정 값입니다(리그 서버 응답도 같은 값). 서버 응답이 4초 안에 없으면 모드와 관계없이 앱 내 시드 순위표로 대체합니다. 그 밖에 에뮬레이터가 만들지 못하는 신호(앰비언트, 다음 날 등)는 「데모 입력」 줄에서 발생시킵니다. 이 줄은 평소 화면에 보이지 않고, 머리줄의 단계 꼬리표(출발 전, 리그 중, 도착)를 길게 누르면 열립니다. adb로도 넣을 수 있습니다.
```
adb shell am start -n ai.pleos.contest.pleos_commerce/ai.pleos.contest.MainActivity --es input 도착
```

진단 화면(`DiagActivity`, `LeagueDiagActivity`)은 신호 확인용으로 남겨 두었습니다.

**차의 기본 내비 연동 범위**: 세이프 리그에는 자체 내비 기능이 없고, 차의 기본 내비와 Pleos NaviHelper SDK로 연동합니다. 주행 중에는 차의 기본 내비가 표시되고 앱은 백그라운드에서 판정만 하며, P단에서 결과 화면이 팝업으로 뜹니다(같은 앱 슬롯을 번갈아 사용).

| 구분 | 항목 | 비고 |
|---|---|---|
| NaviHelper SDK 연동 | 목적지명과 예상 소요 표시, 도착 판정, 경유지 추가, 제한속도, 단속 카메라와 보호구역 가산 | 차의 기본 내비 기준 |
| 내비와 무관 | 벨트, 전조등, 깜빡이, 급제동 판정 | 차량 신호 기반이라 내비 종류와 관계없이 동작 |
| 확장 과제 | 앱마켓 내비(네이버지도, 카카오내비), 티맵 연동 | 티맵은 입점 예정 보도만 있고, 연동 규격은 공개 문서에서 미확인 |

**시동 자동 실행(0.4.0)**: 차량 화면이 켜지면(부팅) 기어가 P단일 때 출발 카드가 팝업으로 뜹니다. D단에서는 뜨지 않습니다. 설정 화면의 「시동 때 자동 실행」으로 끌 수 있습니다. Vehicle SDK 2.3.2와 에뮬레이터에는 앱이 받을 수 있는 시동 신호가 없어 부팅을 시동으로 봅니다. 0절 2번 권한 명령(1절 5번과 같은 명령)을 한 번 쳐야 동작합니다.

**심사용 판정 보기(0.4.1)**: 설정 화면 왼쪽 메뉴 「데모」의 「심사용 판정 보기」 스위치(기본 꺼짐)를 데모 모드에서 켜면, 주행 중 받은 신호와 판정을 화면에 보여 줍니다. 꺼져 있으면 실제 쓰는 모습 그대로 주행 중에는 앱이 비켜 주고 화면이 없습니다. 스위치와 관계없이 판정이 날 때마다 logcat에 한 줄이 남습니다.
```
adb logcat -s LEAGUE_JUDGE
```
한 줄의 형식은 `출발 후 시각 | 판정 이름 | 점수 변화 | 조건=그때 받은 값(MET, MISS, GAIN) ; …`입니다. 같은 버전에서 메인 화면 머리줄을 왼쪽 「세이프 리그」, 오른쪽 시각과 요일, 기어, 단계 꼬리표로 정리했고, 앱 아이콘을 방패 모양으로 바꿨습니다.

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
5. 시동 자동 실행을 보려면 「다른 앱 위에 표시」 권한을 한 번 줍니다. Android 14는 백그라운드 앱이 화면을 띄우는 것을 막고, Pleos Connect에는 이 권한을 켜는 설정 화면이 없어 명령으로 줍니다. 주지 않으면 자동 실행만 동작하지 않고, 아이콘을 눌러 여는 흐름은 그대로입니다.
   ```
   adb shell "appops set --user 10 ai.pleos.contest.pleos_commerce SYSTEM_ALERT_WINDOW allow; appops write-settings"
   ```
   그 뒤 에뮬레이터를 다시 켜면(`adb reboot`) 홈 화면이 뜬 뒤 1~2분 안에 출발 카드가 팝업으로 뜹니다. 부팅 방송이 다른 앱 뒤에 차례로 오기 때문에 PC가 느리면 더 걸립니다. 앱을 한 번은 직접 열어 둔 상태여야 하고, `adb shell am force-stop`을 한 뒤에는 다시 한 번 직접 열어야 다음 부팅에 뜹니다.

## 2. 테스트 환경과 필요한 설정

| 항목 | 값 |
|---|---|
| 에뮬레이터 | Pleos Connect Emulator v2.1.0 |
| 화면 규격 | 전체 2560×1440 px, 160 dpi(1 px = 1 dp), 가로형. 앱 영역은 오른쪽 1682×1210 dp |
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

**권한**: `AndroidManifest.xml`에 Pleos 차량 권한(`pleos.car.permission.*`)과 내비 권한(`NAVI_ROUTE` 외 3개), AAOS 속도 권한이 선언돼 있습니다. 런타임 허용이 필요한 것은 속도(`CAR_SPEED`) 하나이며 1절 4번으로 허용합니다. 0.4.0에서 부팅 방송(`RECEIVE_BOOT_COMPLETED`)과 「다른 앱 위에 표시」(`SYSTEM_ALERT_WINDOW`, 1절 5번)를 더했습니다.

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

서명된 release 빌드(versionCode 6, 0.4.1)입니다. 용량 제한을 넘으면 GitHub Releases에 올리고 이 자리에 Release 주소를 적습니다.

## 5. 시연 영상

`video/safe_league_demo.mp4` (2분, 1920×1080)

Pleos Connect Emulator v2.1.0에서 release APK 0.4.1을 직접 조작한 화면 녹화입니다. 왼쪽 화면이 녹화 그대로이고, 오른쪽 칸과 자막, 효과음은 편집으로 더했습니다. 같은 파일을 접수 메일에도 첨부했습니다.

## 6. 시연 데이터와 판정

시연 차량과 고스트 6명의 리그 결과가 어떤 감점과 가산으로 만들어졌는지, 판정 창과 임계값은 무엇인지 `docs/DEMO_DATA.md`에 표로 정리했습니다.

## 폴더

```
app/     Pleos Connect 앱 (Gradle 프로젝트, Kotlin)
apk/     release APK
docs/    기획서, 리그 규칙, 시연 대본, 문구 검토
server/  리그 서버 (Cloudflare Workers + D1)
video/   시연 영상
```
