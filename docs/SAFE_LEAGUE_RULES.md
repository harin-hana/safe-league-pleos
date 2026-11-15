# 세이프 리그 규칙 문서 (부록 A)

> 2026-10-30 작성. 규칙은 허브(local_3d7b371c)가 사용자와 확정한 것을 그대로 옮겼다. 10절 검토 의견에 대한 허브 결정(10/30 저녁)을 2절, 6절에 반영했고 10절은 「결정됨」 기록으로 남겼다.
> 2026-11-05 기획서 v3 의견 14건 회신(`RULES_REPLY_2026-11-05.md`)에서 채택한 7건을 2-1, 2-3, 4, 6-4, 7절에 반영하고 2-7 아래에 데모 주석 한 줄을 달았다. 규칙 숫자는 그대로다.
> 근거 표기: 「SDK 확인」 = 이 PC gradle 캐시의 `Vehicle-2.3.2-api.jar`, `navi-data-3.0.4.aar`, `NaviHelper-3.0.5.aar`를 javap로 직접 열어 본 시그니처와 권한 주석. 「문서 미확인」 = Pleos 문서 페이지 본문이 스크립트로 그려져 정적 HTML에 제목만 있어 읽지 못한 것.

## 0. 한 줄 요약

같은 길로 출퇴근하는 운전자끼리, 차가 아는 신호만으로 가장 안전하게 도착한 순서를 겨룬다. 주행 중에는 점수를 보여 주지 않고, 결과는 도착해서 P단에 놓았을 때만 본다.

## 1. 주행 중 규칙 (표시 금지)

| 구분 | 허용 | 금지 |
|---|---|---|
| 화면 | 없음(동승자 심판석 예외, 4절) | 점수, 감점, 감점 알림, 순위, 남은 점수 |
| 소리 | 콤보 효과음, 고비마다 TTS 한 마디(예: 「콤보 10」) | 감점 효과음, 감점 TTS, 경고음 |
| 차 출력 | 고비마다 앰비언트 한 번 반짝 | 감점 때 색 바꾸기 |
| 감점이 났을 때 | 무음. 콤보가 끊겨도 아무것도 하지 않음 | 어떤 형태의 알림도 |
| 결과 | 도착 뒤 P단에서만 | 주행 중 미리 보기 |

근거
- 사용자 지시(10/30): 「티맵처럼 운전 중 점수 알려주는 것 금지, 운전 방해」(`HANDOFF_HUB_2026-10-30.md` 「점수 체계 확정」).
- Pleos 기획 가이드 「Design Considering Driving Safety」는 운전을 흐트러뜨리는 게임 활동을 유도하지 말라고 적는다(`research/06_industry_dev.md` 1절 5번). 이 앱은 주행 중 화면을 없애 이 조건을 설계로 지킨다.
- 끊김 무음 이유: 급제동 직후 놀람을 키우지 않고, 「벌주는 앱」 인상을 막는다(같은 인계 「사용자 추가 지시 3」).

## 2. 점수 다섯 층

### 2-1. 전제 (순위 자격)

아래를 모두 만족한 주행만 리그 순위에 들어간다. 하나라도 빠지면 기록은 남지만 순위와 도장에서 빠진다.

| # | 조건 | 판정 신호 |
|---|---|---|
| 1 | 평일 06~10시 또는 17~21시 출발 | 기기 시계 |
| 2 | 출발 때 내비 목적지와 예상 소요 시간이 있음 | NaviHelper `onRouteStarted(RouteStartInfo)`, `getDestinationInfo` |
| 3 | 주행 거리 3km 이상 | Odometer `registerTripCurrentDrivingDistance` |
| 4 | 예상 소요의 120% 안에 도착 | 출발 때 예상 소요와 실제 소요 비교 |
| 5 | 목적지 반경 300m 안에서 P단 | `onDestinationArrived`, 현재 위치, 기어(Pleos `Gear.registerGearSelection`, 보조로 AAOS `GEAR_SELECTION`) |
| 6 | 같은 길 리그 인원 3명 이상 | 서버 집계. 모자라면 고스트 리그(도장 없음) |

4번은 저속으로 감점을 피하는 악용을 막는다.

- 4번의 실제 소요에서 내비 경유지 반경 300m 안(5번과 같은 반경)에서 P단으로 둔 시간은 뺀다. 도장으로 고른 길 위 가게, 블루핸즈나 어린이집에 들러도 자격을 잃지 않게 한다. 신호 대기와 정체의 속도 0은 빼지 않는다.
- 5번은 목적지 반경 300m 안에서 P단 신호를 받기 전에 전원이 꺼져도 P단으로 본다. 판정은 다음 시동 때 마지막으로 저장한 위치와 도착 신호로 하고, 결과는 그때 P단에서 「지난 주행 결과」로 띄운다.
- 순위에는 하루 시간대(아침, 저녁)마다 1~5번을 채운 첫 주행 1회만 들어간다. 그 뒤 주행은 기록만 남는다(택시, 영업용, 같은 시간대 두 번 출발).

### 2-2. 기본

- 기본 100점.
- 환산 감점 = 감점 합 × 10 ÷ 주행 km.
- 최종 점수 = 100 − 환산 감점 + 가산, 0~110으로 자른다. 소수 첫째 자리까지 표시.

### 2-3. 감점 9종 (지금 활성 8)

| # | 항목 | 감점 | 정의 | 상태 |
|---|---|---|---|---|
| D1 | 급제동 | 1 | 브레이크 켜진 채 초당 10km/h 넘게 감속, 0.5초 이상 | 활성 |
| D2 | 급가속 | 1 | 초당 기준을 넘게 가속, 1초 이상. 기준은 속도별로 10km/h 이하 12, 10~20km/h 10, 20km/h 초과 8(km/h/s, eTAS 택시 경계 그대로) | 활성 |
| D3 | 급핸들 | 1 | 40km/h 이상에서 추정 회전율이 초당 10° 이상. 추정 회전율 = 속도 × 핸들각 ÷ (조향비 × 축거), 기본값 조향비 14, 축거 2.9m(차종 손잡이) | 활성 |
| D4 | 깜빡이 없는 차선 변경 | 2 | 40km/h 이상, 4초 안에 핸들이 한쪽으로 갔다 돌아옴. 각도는 40~80km/h 5~20도, 80km/h 이상 3~12도. 직전 2초 안 그쪽 지시등 없음. 내비 회전 지점(TBT) 100m 안과 내비 커브 주의 구간은 제외 | 활성 |
| D5 | 과속 | 10초마다 1 | 제한속도 110% 초과 10초마다(첫 10초는 유예). 구간단속 평균 초과는 1회 3 | 활성 |
| D6 | 벨트 미착용 주행 | 3 | 10km/h 이상에서 착석한 자리의 벨트 해제. 판정은 착석 + 미체결 조합이 기본, 벨트 경고 신호는 보조. 동승석과 뒷좌석은 그 자리 벨트 경고 신호까지 켜져야 센다(카시트, 반려동물, 가방 오탐 방지) | 활성 |
| D7 | 주의 경고 | 5 | DRIVER_ATTENTION 경고등 켜짐 1회마다 | 활성 |
| D8 | 스텔스 주행 | 3 | 어두울 때 하향등과 미등 모두 꺼진 채 10km/h 이상 30초 넘게. 주간주행등(DAYTIME_RUNNING)만 켜진 상태는 꺼짐으로 봄(미등 기준). 구간당 1회, 그 구간이 5분 넘게 이어지면 1회 추가. 어두움 = AAOS NIGHT_MODE 또는 위치 기반 일몰 | 활성 |
| D9 | 신호 위반 | 5 | 적색 신호에 정지선을 넘어 통과 | 지금은 판정 신호 없음, 비활성. SDV OS 신호등 API가 열리면 활성 |

D7 해설: 차에 실린 운전자 상태 감시(DMS) 결과가 경고등 DRIVER_ATTENTION으로 들어온다. 앱은 카메라 영상을 받지 않고 「경고등이 켜졌다」는 사실만 읽는다(`HANDOFF_HUB_2026-10-30.md` 「사용자 추가 지시 2」).

### 2-4. 가산 6종 (합계 상한 +10)

| # | 항목 | 가산 | 정의 |
|---|---|---|---|
| B1 | 벨트 올인 | +1 | 출발 전 착석한 전원이 벨트 |
| B2 | 클린 주행 | +5 | 감점 0 |
| B3 | 깜빡이 콤보 20 | +1 | 지시등 켜고 한 차선 변경이 연속 20회 |
| B4 | 보호구역 감속 | 구간마다 +1, 최대 3 | 내비 주의 구간 안에서 제한속도 이하 유지. 구간은 주의 지점 통과부터 제한속도 값이 바뀔 때까지, 바뀌지 않으면 300m |
| B5 | 미리 감속 | 곳마다 +1, 최대 3 | 단속 카메라 300m 전부터 급제동 없이 제한속도 이하로 통과 |
| B6 | 빗길 전조등 | +1 | 와이퍼가 켜진 동안 전조등 켜짐 |

뺀 것(다시 넣지 않음): 크루즈 롱런, 에코 모드, 회생제동. 이유는 출퇴근길에 크루즈를 켜기 어렵고, 감점 0과 겹치고, 차 기능 홍보로 읽힌다는 것(같은 인계 「점수 체계 확정」).

### 2-5. 보정 1종

- 정체 중에 난 급제동(D1)과 급가속(D2)은 감점 절반(0.5).
- 정체 = 직전 60초 평균 속도 20km/h 이하. 내비 정체 목록은 보조로만 쓴다.

### 2-6. 순위

1. 점수 높은 순
2. 같으면 클린 km(감점 없이 달린 거리 합)가 긴 순
3. 같으면 감점 건수가 적은 순

주간 순위 = 월~금 5회 중 상위 3회 점수 합. 하루 이틀 빠져도 겨룰 수 있게 한다.

### 2-7. 계산 예시

15km 주행, 급제동 2회(그중 1회는 정체 구간), 깜빡이 없는 차선 변경 1회. 출발 전 전원 벨트, 보호구역 1곳 감속.

| 단계 | 계산 | 값 |
|---|---|---|
| 감점 합 | 급제동 1 + 정체 급제동 0.5 + 차선 변경 2 | 3.5 |
| 환산 | 3.5 × 10 ÷ 15 | 2.3 |
| 가산 | 벨트 올인 1 + 보호구역 1 | +2 |
| 최종 | 100 − 2.3 + 2 | **99.7** |

주석: 데모 대본과 기획서 4번은 저녁 비 주행이라 보호구역 감속 +1 대신 빗길 전조등 +1로 쓴다. 가산 합 +2, 최종 99.7은 같고 이 예시는 그대로 둔다.

## 3. 리그

| 항목 | 규칙 |
|---|---|
| 같은 길 | 목적지 반경 1km 그리고 출발지 반경 3km |
| 같은 리그 | 같은 길 + 같은 시간대(아침 06~10 또는 저녁 17~21) |
| 일일 리그 | 그날 같은 리그 주행끼리 순위 |
| 주간 왕중왕 | 월~금 상위 3회 합으로 순위 |
| 도장 받는 조건 | 주간 왕중왕 상위 3명은 도장 하나 |
| 도장 모양 | 도장 칸 세 개의 그림이 정해져 있다. 첫째 벨트, 둘째 깜빡이, 셋째 전조등 |
| 도장 세 개 | 누적으로 세 개가 차면(연속 아니어도 됨) 길 위 카페 아메리카노나 블루핸즈 안전 점검(타이어, 와이퍼, 브레이크액, 제휴 가정) 중 하나를 고른다. 고르면 다음 출발 전 카드 「들러요」 → 내비 경유지(`addWaypoint`). 쓰면 도장은 0부터 다시 |
| 인원 부족 | 3명 미만이면 고스트 리그(지난주의 나와 겨룸, 도장 없음) |
| 팀 리그 | 로드맵(같은 회사, 같은 단지) |

커머스는 도장 보상으로만 들어온다. 도착 결과 화면 맨 아래 보상 한 줄과 다음 날 출발 전 카드 한 장(도장 세 개 찬 주에 받을 것을 고르고 들러요 → 경유지 추가)뿐이고, 주행 중 커머스는 0이다(`DIRECTION_2026-10-25.md` 7절 「갈래 선택」). 경유지 추가는 에뮬레이터에서 실제로 동작했다(`SIGNAL_CHECK.md` ④).

## 4. 재미 요소 (점수 밖)

| 요소 | 내용 | 주행 중 | 도착 뒤 |
|---|---|---|---|
| 콤보 사다리 | 깜빡이 콤보 5, 10, 20 → 클린 1km 5, 10 → 클린 주행 → 클린 위크 | 고비마다 효과음, TTS 한 마디, 앰비언트 한 번 반짝 | 작은 콤보가 겹쳐 큰 숫자가 되는 연출 |
| 출근 스트릭 | 평일 연속 리그 참가 일수 | 없음 | 결과 화면 |
| 고스트 | 같은 길 지난주의 나 | 없음 | 「자기 기록 경신」 |
| 리그 뱃지 | n주 1위 = 그 길의 주인 | 없음 | 결과 화면 |
| 오늘 아침 한 줄 | 저녁 결과 화면에 그날 아침 리그 등수와 리그 결과를 한 줄로(예: 「오늘 아침 리그 2위 99.7」). 아침 리그 기록이 있을 때만. 합산하지 않고 순위는 3절대로 따로 | 없음 | 저녁 결과 화면 |
| 출퇴근 둘 다 클린 | 같은 날 아침과 저녁 리그 주행이 모두 클린 주행 | 없음 | 저녁 결과 화면 |
| 벨트 올인 효과음 | 착석 전원이 벨트를 맨 순간 | 출발 전 효과음 | 없음 |
| 끊김 무음 | 콤보가 끊겨도 소리 없음 | 무음 | 끊긴 자리를 지도 점으로 |
| 동승자 심판석 | 동승석 착석 때 동승자 화면에만 실시간 사다리 | 동승자 화면만 | 같음 |

동승자 심판석은 동승석 착석(`registerSeatOccupancy`)으로 켠다. 운전석 화면에는 그대로 아무것도 띄우지 않는다.

## 5. 신호별 API 표

### 5-0. 지금과 향후

**플랫폼에 기술은 있고 공모 SDK는 일부만 연 것이다. 열리면 같은 감점 항목의 판정 정밀도만 올라가고 규칙은 안 바뀐다.**

「향후」 칸은 Pleos 문서 사이트맵의 `api-reference/sdvos/sdvos-api/vehicle-api/` 아래에 실제로 있는 이름이다(주소는 11절). 이 API는 공모 앱이 못 쓴다. Pleos 사이트맵에 보이는 플랫폼 SDK는 권한이 signature 또는 privileged라 일반 앱이 쓸 수 없다고 조사 6번 12항이 적고 있다(`hmg-app-challenge/research/06_industry_dev.md` 1절 12번). 공개 주소(document.pleos.ai)로 열면 404로 넘어가고 본문도 공개되지 않아, 필드와 권한은 「문서 미확인」이다.

| 항목 | 지금 (공모 Vehicle SDK 2.3.2, NaviHelper 3.0.5) | 향후 (SDV OS 플랫폼 API, 현대가 열면) |
|---|---|---|
| D1 급제동, D2 급가속 | 속도(`PERF_VEHICLE_SPEED`) 미분 + 브레이크 페달 | 가속도 원값: 후보(Motion DrivingInfo), 필드 미공개 |
| D3 급핸들 | 핸들 각도로 회전율 추정 | 가속도 원값: 후보(Motion DrivingInfo), 필드 미공개. 핸들은 Motion `sdv_motion_steering_wheel`의 `SteeringWheel` |
| D4 깜빡이 없는 차선 변경 | 핸들 각도와 지시등 조합으로 근사 | ADAS 차선 인식: Adas `sdv_adas_local_map`의 `Lane`, `LaneLine`, Adas `sdv_adas_warning`의 `LaneDepartureWarning` |
| D5 과속 | NaviHelper 제한속도, 단속 카메라, 구간 평균속도 | 같음. 표지판 제한속도로 보강: Adas `sdv_adas_local_map`의 `TrafficSign`, `TrafficSignType` |
| D9 신호 위반 (지금 비활성) | 판정 신호 없음 | 신호등 색과 통과 판정: Adas `sdv_adas_local_map`의 `TrafficLight`, `TrafficLights`, `TrafficLightLamp`, `TrafficLightLampColor` |
| D6 벨트 미착용 | Seat 착석과 벨트 체결 | 같음(Convenience `sdv_convenience_seat`) |
| D7 주의 경고 | 경고등 DRIVER_ATTENTION 켜진 횟수 | DMS 상태와 시선 이탈: Adas `sdv_adas_warning` driver_monitoring의 `DriverMonitoringStatus`, `LockoutEvent`, `LockoutReason`, `RequestLevel`. 손 뗌 감지: `HandsOffDetectionLevel` |
| D8 스텔스 주행 | 하향등, 미등 상태 + `NIGHT_MODE` | 전조등 모드: Convenience `sdv_convenience_lights` headlamp의 `HeadlampMode`, `ModeSwitch` |
| B1~B6 가산 | 5-1, 5-2 표 | 같음. B4 보호구역은 `sdv_adas_local_map`의 `AreaType`, `Areas`로 구간 끝까지 알 수 있을 가능성(문서 미확인) |

아래 5-1, 5-2는 「지금」 칸의 상세다.

권한 이름은 SDK 메서드의 권한 주석에서 직접 읽었다. 보호 수준 「normal」은 `SIGNAL_CHECK.md` 「권한 추가로 알게 된 것」에서 확인된 것만 적었다. 콜백 주기는 모든 Pleos 메서드에서 「문서 미확인」(SDK에 주기 인자가 없고 값이 바뀔 때 오는 리스너 구조).

### 5-1. Vehicle SDK 2.3.2와 AAOS

| 쓰는 곳 | 신호 | 메서드 또는 속성 | 콜백 반환 타입 | 권한 | 에뮬레이터 |
|---|---|---|---|---|---|
| D1 D2 D3 D4 D5 D6 D8, 전제 | 속도 | AAOS `PERF_VEHICLE_SPEED` (`CarPropertyManager`, 10Hz 구독) | Float, m/s | `android.car.permission.CAR_SPEED`, 위험 권한(사용자 허락) | ○ 콘솔 카드에는 없고 HTTP `PUT /vehicle/controls?speed=60` → 16.666668 m/s(60km/h) |
| D1 | 브레이크 페달 | `Brake.registerBrakePedalStatus(BrakePedalStatusListener)` | `onBrakePedalStatusUpdated(Boolean)` | `CAR_DRIVE` | ○ 60% → true, 0% → false |
| D3 D4 | 핸들 각도 | `SteeringWheel.registerSteeringWheelAngle(SteeringWheelAngleListener)` | `onSteeringWheelAngleUpdated(Float)`, 단위 문서 미확인 | `CAR_DRIVE` | ○ 부호가 콘솔과 반대(콘솔 -30° → 앱 30.0) |
| D4 B3 | 방향지시등 | `TurnSignal.registerTurnSignalState(TurnSignalStateListener)` | `onTurnSignalStateUpdated(TurnSignalValue)`, 값 OFF, LEFT, RIGHT | `CAR_LIGHTS` | ○ Blink left → LEFT, Off → OFF |
| D8 B6 | 하향등 | `Light.registerLowBeamLightState(LightStateListener)` | `onLightStateUpdated(LightState)`, 값 OFF, ON, DAYTIME_RUNNING | `CAR_LIGHTS` | ○ Low beam → ON, Tail lamp → OFF |
| D8 | 미등 | `Light.registerTailLampLightState(LightStateListener)` | 같음 | `CAR_LIGHTS` | ○ Low beam, Tail lamp → ON, Off → OFF |
| D8 | 어두움 | AAOS `NIGHT_MODE` | Boolean | `android.car.permission.CAR_EXTERIOR_ENVIRONMENT`, 보통 권한 | ✕ 속성은 내내 false. 야간은 `Configuration.uiMode`로 ○(`cmd car_service day-night-mode night` → mCurUiMode 0x13 → 0x23, 권한 불필요) |
| D6 | 벨트 미착용 경고 | `Safety.registerSeatBeltNotBuckledWarning(SeatArea, SeatBeltNotBuckledWarningListener)` | `onSeatBeltNotBuckledWarningUpdated(Boolean)` | `CAR_SAFETY`, normal | △ 동승석은 미지원(0x25200709), 운전석은 미확인. 벨트 경고등은 경고등 줄(Telltales SEAT_BELT)로 ○ |
| D7 | 경고등 | `Safety.registerTelltales(TelltaleInfoListener)` | `onTelltaleInfoUpdated(Telltales)`, 안에 `TelltaleData(type, state, color, priority, warningCount)`, type에 `DRIVER_ATTENTION` 있음 | `PRIVATE_CAR_SAFETY`, normal | △ 경고등 콜백은 ○(PARKING_BRAKE, MasterWarning On → MASTER_WARNING=ON, SeatBelt On → SEAT_BELT=ON). 콘솔 목록 37종에 Driver Attention이 없어 DRIVER_ATTENTION은 못 넣음 |
| D6 B1, 심판석 | 착석 | `Seat.registerSeatOccupancy(SeatArea, SeatOccupancyListener)` | `onSeatOccupancyUpdated(Boolean)` | `CAR_SEATS` | ○ 콜백은 옴, 값이 콘솔과 반대(Occupied → false). 실차에서는 뒤집지 않음 |
| D6 B1 | 벨트 체결 | `Seat.registerSeatBeltBuckledState(SeatArea, SeatBeltBuckledStateListener)` | `onSeatBeltBuckledStateUpdated(Boolean)` | `CAR_SEATS` | 미확인(콘솔에 체결 상태 카드 없음) |
| 전제 3, 환산 | 주행 거리 | `Odometer.registerTripCurrentDrivingDistance(DistanceDrivenListener)` | `onDistanceDrivenUpdated(Float)`, 단위 문서 미확인 | `CAR_DRIVE` | △ 읽기는 됨(`0.0 km`), 주행 시뮬레이션 안 함 |
| B6 | 와이퍼 | `Wiper.registerWiperState(WindowArea, WiperStateListener)` | `onWiperStateUpdated(WiperState)`, 값 OTHER, OFF, ON, SERVICE | `CAR_WINDOWS`, normal | ○ Front Wiper Low → ON |
| 고비 연출 | 앰비언트 | `Light.setAmbientLightBrightnessColor(SeatArea, Int, 성공 콜백, 실패 콜백)` | 성공 때 (SeatArea, Int) | 주석상 `CAR_LIGHTS` | ✕ 설정 권한 `pleos.car.permission.CONTROL_CAR_LIGHTS`(normal)를 넣어도 구역 6가지 모두 거부(「areaId is Invalid」, 「not supported for 0x25400783」) |
| (참고, 점수에 안 씀) | 크루즈 | `DrivingMode.registerCruiseControlState(CruiseControlStateListener)` | `onCruiseControlStateUpdated(CruiseControl)` | `CAR_DRIVE` | ○ 값 대응 주의(Full autonomous → ACTIVATED, Not ready → null) |
| 전제 5 | 기어 | Pleos `Vehicle.getGear().registerGearSelection(GearSelectionListener)`, 보조로 AAOS `GEAR_SELECTION` | `onSelected(VehicleGear)`, 값 GEAR_PARK, GEAR_DRIVE, GEAR_NEUTRAL, GEAR_REVERSE. AAOS 쪽은 Int | Pleos 쪽 `CAR_DRIVE`(jar 권한 문자열 `pleos.car.permission.CAR_DRIVE` 확인). AAOS 쪽 `android.car.permission.CAR_POWERTRAIN`, 보통 권한 | ○ D → GEAR_DRIVE(AAOS 8), P → GEAR_PARK(AAOS 4) |

### 5-2. NaviHelper 3.0.5 (`NaviHelperEventListener`)

| 쓰는 곳 | 신호 | 콜백 | 들어 있는 값 | 에뮬레이터 |
|---|---|---|---|---|
| D5 B5 B4 | 현재 도로 제한속도 | `onCurrentRoadSpeedLimit(Int)` | km/h로 추정, 단위 문서 미확인 | △ 경로 요청 뒤 0이 1초마다 옴(차가 안 움직임) |
| D5 B5 | 단속 카메라 | `onCameraAlert(List<CameraAlert>)` | id, type, status, speedLimit, remainDistance, sectionAvrSpeed. type에 SPEED_CAMERA_FIX, SPEED_CAMERA_SECTION_START, SECTION_SPEED_ALERT, SPEED_CAMERA_SECTION_END, SPEED_CAMERA_SCHOOL_ZONE 등 10종 | 미확인(경로 요청 뒤 30초 0건, 차가 안 움직임) |
| B4 | 주의 구간 | `onCautionPointInfo(List<CautionPoint>)` | id, type, remainDistance. type에 SCHOOL_ZONE, SPEED_BUMP, ACCIDENT_PRONE_AREA 등 23종 | 미확인(경로 요청 뒤 30초 0건, 차가 안 움직임) |
| 보정 | 정체 | `onRouteStateInfo(RouteStateInfo)` 안 `trafficStatus.congestionItemList` | 구간별 offset, distance, 정체 종류(FAST, MODERATE, SLOW, HEAVY), avrSpeed | 미확인 |
| 전제 2 4 | 경로 시작과 예상 소요 | `onRouteStarted(RouteStartInfo)`, `getDestinationInfo` | 남은 거리, 남은 시간 | ○ `서울역 / 남은 23000 m, 3240 초` |
| 전제 5 | 도착 | `onDestinationArrived(DestinationArrivedInfo)` | duration, distance, name, address | 미확인(내비 엔진 위치가 성남에 고정, `adb emu geo fix`와 콘솔 속도로도 안 움직임) |
| 리그 키 | 현재 위치 | `onCurrentLocationInfo(CurrentLocationInfo)` | latitude, longitude, heading, currentRouteIndex | ○ 기본 위치 읽힘 |
| 보상(도장 세 개) | 경유지 추가 | `addWaypoint(RequestWaypointInfo)` | 다시 짠 경로, 늘어난 도착 시간 | ○ |

권한: `NAVI_ROUTE`가 없으면 NaviHelper 요청 전부 SecurityException(`SIGNAL_CHECK.md`).

### 5-3. 없는 것 (쓰지 않는다)

가속도 센서, 카메라 영상, ADAS 판정값, 차선 카메라는 공모용 SDK에 없다(`NOTE_FOR_PLEOS_HUB_안전운전랭킹_2026-10-30.md` 3절, `RESEARCH_BRIEF.md` 「없는 것」). 그래서 가속은 속도 변화로, 차선 변경은 핸들과 지시등 조합으로, 졸음과 주의 산만은 경고등으로만 잰다. 이 기술이 플랫폼에 없다는 뜻은 아니다. 5-0의 향후 칸처럼 SDV OS에는 있고 공모 SDK에 열리지 않았을 뿐이다.

## 6. 판정 알고리즘 (의사코드)

모든 판정은 차 안에서 한다. 속도 10Hz 콜백 하나가 시계 역할을 하고, 나머지 신호는 바뀔 때만 들어와 최근 값으로 들고 있는다.

### 6-1. 속도 미분 (D1 D2)

```
매 0.1초 속도 v(km/h) 도착:
  a = (v - v_0.5초전) / 0.5            # km/h per s, 0.5초 창으로 잡음 줄임
  정체중 = 직전 60초 평균 v <= 20       # 내비 정체 목록은 보조
  if 브레이크 == ON and a < -10: 제동창 += 0.1 else 제동창 = 0
  if 제동창 >= 0.5 and 아직 이번 사건 안 셈: 감점(D1, 정체중 ? 0.5 : 1)
  가속기준 = v <= 10 ? 12 : (v <= 20 ? 10 : 8)   # eTAS 택시 경계
  if a > 가속기준: 가속창 += 0.1 else 가속창 = 0
  if 가속창 >= 1.0 and 아직 이번 사건 안 셈: 감점(D2, 정체중 ? 0.5 : 1)
  # 한 사건은 창이 0으로 돌아갈 때 닫힌다
```

### 6-2. 급핸들과 차선 변경 패턴 (D3, D4, B3)

```
핸들각 θ(도) 도착, 최근 4초 버퍼 유지:
  # D3 급핸들
  회전율 = (v / 3.6) × θ ÷ (조향비 14 × 축거 2.9)   # 도/초. θ가 도 단위이므로 (180/π)는 곱하지 않음(11/06 앱 구현 때 이중 변환 발견, 허브 정정. 기준 10°/초는 그대로)
  if v >= 40 and |회전율| >= 10 and 아직 이번 사건 안 셈: 감점(D3, 1)

  # D4, B3 차선 변경
  if v >= 40 and not (TBT 회전 지점 100m 안 or 커브 주의 구간 안):
    범위 = v < 80 ? (5, 20) : (3, 12)
    기준 = 4초 전 θ
    최대 = 버퍼에서 기준과 차이가 가장 큰 값, 방향 s = 그 부호
    if 범위 안에 |최대 - 기준| and |지금 θ - 기준| < 2:     # 한쪽으로 갔다 돌아옴
      if 최근 2초 안 지시등 == s 방향: 깜빡이콤보 += 1
      else: 감점(D4, 2); 깜빡이콤보 = 0                   # 무음
      버퍼 비움
```

### 6-3. 스텔스 판정 (D8)

```
어두움 = NIGHT_MODE == true or 해가 진 시각(현재 위치, 날짜로 계산)
무등 = 하향등 != ON and 미등 != ON            # DAYTIME_RUNNING은 꺼짐으로 봄
if 어두움 and 무등 and v >= 10: 스텔스창 += 0.1 else 스텔스창 = 0, 추가셈 = 0
if 스텔스창 > 30 and 이번 구간 안 셈: 감점(D8, 3)
if 스텔스창 > 300 and 추가셈 == 0: 감점(D8, 3); 추가셈 = 1   # 5분 넘게 이어지면 1회 추가
```

### 6-4. 콤보 카운터 (점수 밖)

```
깜빡이콤보: 6-2에서 +1, D4에서 0
  고비(5, 10, 20): 효과음 + TTS 「콤보 n」 + 앰비언트 한 번 반짝
  내비 음성 안내 중이면 TTS는 건너뛰고 효과음과 앰비언트만
  20 도달: 가산 B3 표시
클린거리: 마지막 감점 이후 km. 감점이 나면 0(무음)
  고비(1km 5개, 10개): 효과음 + 앰비언트
도착: 감점 0이면 클린 주행, 주 5회 모두 클린이면 클린 위크
결과 화면: 작은 콤보 숫자들이 차례로 겹쳐 큰 숫자가 되는 연출
```

### 6-5. CPU

- 속도 10Hz 콜백 하나에서 위 판정을 모두 돌린다. 버퍼는 3초 분량(30개)뿐이고 폴링이 없다.
- 목표는 Pleos 가이드의 주행 중 CPU 5% 미만(`research/06_industry_dev.md` 1절 6번).

## 7. 서버와 데이터

| 항목 | 설계 |
|---|---|
| 서버 | Cloudflare Workers + D1 |
| 올리는 시점 | 도착 때 한 번 |
| 올리는 것 | 리그 키(출발지 격자, 목적지 격자, 시간대), 거리, 시간, 감점 종류별 건수, 가산, 콤보 결과 |
| 올리지 않는 것 | 궤적, 속도 원본, 정확한 출발지와 목적지 좌표 |
| 리그 묶기 | 격자 키로 먼저 모은 뒤 이웃 격자까지 묶고, 출발지 3km와 목적지 1km 거리로 다시 거르는 2단계 |
| 계정 | 앱 자체 익명 id(VIN 해시) + 별명. 로드맵: 한 차에 별명 프로필 여럿, 출발 카드에서 고르고 기본은 마지막 프로필(부부 공유 차) |
| 주행 중 통신 | 없음 |
| 주행 중 CPU | 5% 안(속도 10Hz 콜백, 판정은 차 안) |

근거
- Pleos 보안 가이드는 「차는 여러 사람이 쓰는 기기」라 운전자 전환이 쉬워야 하고, 주행 중 행동 데이터를 마케팅에 쓰지 않도록 분리하라고 요구한다(`research/06_industry_dev.md` 1절 7번). 궤적을 올리지 않고 도장은 리그 순위로만 주는 구조가 이 조건에 맞는다.
- 브레이크 페달 데이터만으로 15분 안에 운전자 87%를 식별했다는 연구가 있다(같은 파일 보조 출처, PETS 2016). 그래서 원본 신호는 차 밖으로 내보내지 않고 건수만 올린다.

## 8. 페인 근거

| 페인 | 근거 | 출처 |
|---|---|---|
| 출퇴근길이 위험하다 | 출퇴근 산재 신청이 2021년 8,932건에서 2024년 12,864건, 2025년 14,075건으로 매년 늘었다. 출퇴근 유족 산재 승인은 2024년 112건, 2025년 127건 | [YTN 2026-07-26](https://www.ytn.co.kr/_ln/0103_202607261053141047), [파이낸셜뉴스 2026-07-26](https://www.fnnews.com/news/202607261752003248) |
| 출퇴근 시간대에 사고가 몰린다 | 인천 TAAS 자료(2013~2015) 분석에서 오전 7~9시와 오후 18~20시 첨두시간 사고가 전체의 약 3분의 1, 사망사고의 약 30% | [한국ITS학회 논문](https://koreascience.kr/article/CFKO201634158693252.pdf) |
| 운전점수 앱은 급감속 감점이 억울하다 | 티맵 운전점수의 급감속 감점이 오래된 불만이라는 이용자 글, 전기차 급감속 감점 의심 글 | [클리앙 19175038](https://www.clien.net/service/board/park/19175038), [클리앙 18816239](https://www.clien.net/service/board/cm_car/18816239), [클리앙 18231293](https://www.clien.net/service/board/cm_car/18231293) |
| 운전점수는 이미 보험 할인용으로 있다 | 티맵모빌리티가 2016년 국내 처음 주행 데이터 기반 운전점수를 도입, 보험사가 점수로 할인 | [다음 뉴스](https://v.daum.net/v/YBHZg5wMFc), [테크42](https://www.tech42.co.kr/?p=14344) |
| 비 오는 날이 더 치명적이다 | 도로교통공단 2017~2021년 빗길 사고 치사율이 맑은 날의 약 1.4배. 한국교통안전공단 2023~2025년 우천 치사율 1.58명, 맑은 날 1.22명 | [파이낸셜뉴스 2023-06-23](https://www.fnnews.com/news/202306231024152984), [세계일보 2026-07-07](https://www.segye.com/newsView/20260707519306) |
| 밤에 불 끄고 달리는 스텔스 차량 | 경기 지역 등화 위반 단속 2021~2023년 10,192건(연 약 3,400건), 범칙금 2만 원. 국토부가 2026-09-01 이후 제작, 수입 신차에 끌 수 없는 전조등, 미등 자동 점등을 의무화 | [경기일보 2024-03-21](https://kyeonggi.com/article/20240321580263), [파이낸셜뉴스 2026-06-04](https://www.fnnews.com/news/202606040859074495), [헤럴드경제 2026-06-03](https://www.heraldk.com/article/2026060318000070659). 그래서 스텔스 감점은 자동 점등을 수동으로 끈 차만 걸린다 |
| 밤 사고 치사율 | 야간 사고 치사율이 주간의 1.8배(도로교통공단 경북, 2013 자료라 오래됨) | [경북매일 2013](https://kbmaeil.com/article/201308280076479) |
| 스쿨존 사고가 다시 는다 | TAAS 기준 스쿨존 보행 어린이 사고 2023년 350건, 2024년 360건, 2025년 496건 | [뉴데일리 2026-05-21](https://www.newdaily.co.kr/site/data/html/2026/05/21/2026052100169.amp.html) |
| 단속 장비는 늘었는데 빈 곳이 있다 | 어린이보호구역 무인단속 장비 22,489대, 보호구역 약 16,500곳 중 4,445곳은 장비 없음 | [세계일보 2025-09-01](https://www.segye.com/newsView/20250901510024) |

못 찾은 것: 출퇴근 시간대 사고의 전국 단위 TAAS 공식 수치(TAAS 직접 조회 필요), 스텔스 차량 사고 건수와 치사율 공식 통계, 어린이보호구역 과속 단속 연간 건수.

## 9. 심사 예상 질문

| # | 질문 | 답 초안 |
|---|---|---|
| Q1 | 티맵 운전점수와 뭐가 다른가 | 티맵은 폰 센서로 혼자 받는 점수이고 주행 중에 알려 준다. 세이프 리그는 차만 아는 신호(지시등, 브레이크 페달, 핸들 각도, 벨트, 졸음 경고등, 전조등)로 재고, 같은 길 출퇴근자끼리 겨루며, 주행 중에는 아무것도 보여 주지 않는다. 폰은 깜빡이를 켰는지, 벨트를 맸는지 모른다 |
| Q2 | 운전 중 게임이 안전한가 | 주행 중 화면이 없다. 점수와 감점은 도착 P단에서만 나오고, 감점은 무음이다. 주행 중 나오는 것은 가점 순간의 짧은 효과음, TTS 한 마디, 앰비언트 한 번뿐이다. 게임의 목표 자체가 급제동, 과속, 깜빡이 누락을 줄이는 것이라 게임을 잘할수록 운전이 얌전해진다 |
| Q3 | 가속도 센서 없이 급가속과 급제동을 어떻게 재나 | 클러스터와 같은 속도 값(AAOS `PERF_VEHICLE_SPEED`)을 초당 10번 받아 0.5초 창으로 미분한다. 급제동은 브레이크 페달 신호와 함께 볼 때만 세서 오인을 줄인다. 기준은 교통안전공단 운행기록 분석(eTAS)의 위험운전행동 정의(초당 km/h 감속, 가속)와 같은 방식이다 |
| Q4 | 상대가 없으면 | 같은 리그가 3명 미만이면 고스트 리그로 돌아간다. 상대는 같은 길 지난주의 나다. 도장은 없지만 콤보, 스트릭, 기록 경신은 그대로라 혼자서도 매일 탈 이유가 있다. 2분 데모도 고스트로 한 대만 있으면 된다 |
| Q5 | 개인정보는 | 궤적과 속도 원본은 차 밖으로 나가지 않는다. 도착 때 격자로 뭉갠 출발지, 목적지와 감점 건수만 한 번 올린다. 계정은 VIN 해시로 만든 익명 id와 별명이다. Pleos 보안 가이드의 「주행 행동 데이터 마케팅 분리」를 따른다 |
| Q6 | 외부 서버는 허용되나 | 공모 규정에서 외부 서버 금지 문구는 접수양식(`form_text.md`)에서 찾지 못했다. Pleos Gleo도 서버 LLM을 쓰는 구조다. 서버가 막혀도 고스트 리그와 콤보는 차 안에서만 돌아가므로 앱은 동작한다. 10/28 설명회 질의 목록 확인 필요 |
| Q7 | 과속 기준이 법보다 엄격하지 않나 | 법은 단속 기준, 리그는 안전 기준이다. 제한속도 110%를 넘긴 채 10초가 지나야 감점이 시작되므로 순간 초과는 세지 않는다 |

## 10. 검토 의견과 결정 (모두 결정됨, 허브 10/30 저녁)

| # | 대상 | 검토 의견(요지) | 결정 | 반영 |
|---|---|---|---|---|
| R1 | D1 급제동 | 7.5는 옛 eTAS 값. 지금 eTAS는 화물 8, 버스 9~12, 택시 14~15 | **초당 10km/h**, 브레이크 동시, 0.5초 유지. 7.5는 옛값이고 택시 14는 영업용 완화 기준이라 화물 8과 택시 14 사이 | 2-3, 6-1 |
| R2 | D2 급가속 | 11은 옛 값. 지금 eTAS 택시는 6~10km/h 12, 11~20km/h 10, 21km/h 초과 8 | **eTAS 경계 그대로: 10km/h 이하 12, 10~20km/h 10, 20km/h 초과 8.** 인용을 정확히 맞추고, 출퇴근길 급가속은 대부분 저속 출발에서 나오므로 이 경계가 맞다 | 2-3, 6-1 |
| R3 | D3 급핸들 | eTAS는 GPS 방위각 변화율(택시 초당 10°). 핸들각을 회전율로 바꿔 대조 | **제안 채택.** 추정 회전율 = 속도 × 핸들각 ÷ (조향비 × 축거), 초당 10° 이상. 조향비 14, 축거 2.9m 기본, 차종 손잡이 | 2-3, 6-2 |
| R4 | D4 차선 변경 | 고속에서 3~5°로 끝나 놓침, 3초가 짧음, 커브 오인 | **40~80km/h 5~20도, 80km/h 이상 3~12도, 창 4초, TBT 회전 지점 100m 안과 커브 주의 구간 제외** | 2-3, 6-2 |
| R5 | D5 과속 | 110%가 eTAS(+20km/h)보다 엄격 | **110% 유지, 10초 유예 유지.** 9절 Q7에 「법은 단속 기준, 리그는 안전 기준」 | 2-3, 9 |
| R6 | D8 스텔스 | 신차 자동 점등 의무, 주간주행등 해석, 반복 셈 | **주간주행등만 켜진 상태는 꺼짐(미등 기준). 구간당 1회, 5분 넘게 이어지면 1회 추가.** 8절에 「수동으로 끈 차만 걸린다」 | 2-3, 6-3, 8 |
| R7 | B4 보호구역 끝 | 지점만 주고 끝을 안 줌 | **제안 채택.** 제한속도 값이 바뀔 때까지, 없으면 300m | 2-4 |
| R8 | 보정 정체 | 내비 정체 목록과 내 위치 맞춤 문서 미확인 | **직전 60초 평균 20km/h 이하면 정체, 내비 정체 목록은 보조** | 2-5, 6-1 |
| R9 | D6 벨트 | 두 신호 갈래 | **제안대로.** 착석 + 미체결 기본, 경고 신호 보조 | 2-3 |
| R10 | 리그 키 격자 | 격자 경계에서 같은 길이 갈림 | **제안대로.** 서버에서 이웃 격자까지 묶고 거리로 다시 거르는 2단계 | 7 |
| R11 | Q3 질문 문구 | 금지어 규칙 | **제안대로.** 「가속도 센서 없이 급가속과 급제동을 어떻게 재나」 | 9 |
| R12 | 향후 신호 위반 | 지금 규칙에 신호 위반 감점 없음 | **D9 신호 위반 5를 감점 표에 넣고 비활성으로 둠.** SDV OS 신호등 API가 열리면 판정만 켜고 규칙은 그대로 | 2-3, 5-0 |
| R13 | 향후 가속도 원값 | 사이트맵에서 이름 못 찾음, 후보 DrivingInfo뿐 | **향후 칸에 「후보(Motion DrivingInfo), 필드 미공개」로 표기** | 5-0 |

예시(2-7) 재계산: 예시는 사건 건수로 주어져 있어 새 기준이 결과를 바꾸지 않는다. 감점 3.5, 환산 2.3, 가산 +2, 최종 **99.7 그대로**.

## 11. 출처

내부 파일
- `pleos-contest/HANDOFF_HUB_2026-10-30.md` 「점수 체계 확정」 「결정(10/30)」 「사용자 추가 지시」 1~3
- `pleos-contest/DIRECTION_2026-10-25.md` 7절 「갈래 선택 (10/30)」
- `pleos-contest/SIGNAL_CHECK.md` (에뮬레이터 확인 결과, 권한)
- `hmg-app-challenge/NOTE_FOR_PLEOS_HUB_안전운전랭킹_2026-10-30.md` 3절, 5절
- `hmg-app-challenge/RESEARCH_BRIEF.md` 「없는 것」 「피해야 할 것」
- `hmg-app-challenge/research/06_industry_dev.md` 1절 1~7번, 17번, 보조 출처(PETS 2016)
- `hmg-app-challenge/research/04_community_pain.md`
- `hmg-app-challenge/form/form_text.md` (작품 소개 1~8번 칸, 분량 제한 없음)

SDK (이 PC에서 javap로 확인)
- `ai.pleos.playground:Vehicle:2.3.2` api jar: Brake, SteeringWheel, TurnSignal, Light, Safety, Seat, Odometer, Wiper, DrivingMode 메서드와 `@RequiresPermission` 주석, 리스너 반환 타입, TurnSignalValue, LightState, WiperState, TelltaleType(DRIVER_ATTENTION)
- `ai.pleos.playground:NaviHelper:3.0.5`의 `NaviHelperEventListener`, `navi-data:3.0.4`의 CameraAlert, CameraType, CautionPoint, CautionPointType, TrafficCongestionInfo, TrafficCongestionType, RouteStateInfo, DestinationArrivedInfo
- Pleos 문서 사이트맵: https://document.pleos.ai/sitemap.xml (각 메서드 페이지는 제목만 받힘, 본문 문서 미확인)
- Pleos FAQ App Runtime(속도는 `PERF_VEHICLE_SPEED`): https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/vehicle-app-faq
- Android VehiclePropertyIds(권한 수준): https://developer.android.com/reference/android/car/VehiclePropertyIds

SDV OS 플랫폼 API (5-0 향후 칸). 사이트맵에 실린 주소 그대로다. 같은 경로를 document.pleos.ai로 열면 404로 넘어가고, 사이트맵의 호스트는 이 PC에서 접속되지 않았다(본문 문서 미확인)
- 기준 경로: `https://pleos-playground-docs.appmarket-int.umos.ai/api-reference/sdvos/sdvos-api/vehicle-api/`
- DMS: `Adas/sdv_adas_warning/vapi_warning/driver_monitoring/struct_DriverMonitoringStatus`, `.../driver_monitoring/struct_LockoutEvent`, `.../driver_monitoring/enum_LockoutReason`, `.../driver_monitoring/enum_RequestLevel`
- 차선 이탈 경고: `Adas/sdv_adas_warning/vapi_warning/struct_LaneDepartureWarning`
- 차선: `Adas/sdv_adas_local_map/vapi_local_map/struct_Lane`, `.../vapi_local_map/struct_LaneLine`
- 신호등: `Adas/sdv_adas_local_map/vapi_local_map/struct_TrafficLight`, `.../vapi_local_map/struct_TrafficLights`, `.../traffic_light_lamp/struct_TrafficLightLamp`, `.../traffic_light_lamp/enum_TrafficLightLampColor`
- 표지판: `Adas/sdv_adas_local_map/vapi_local_map/struct_TrafficSign`, `.../vapi_local_map/enum_TrafficSignType`
- 구역: `Adas/sdv_adas_local_map/vapi_local_map/area_type/enum_AreaType`, `.../vapi_local_map/struct_Areas`
- 전조등: `Convenience/sdv_convenience_lights/vapi_lights/headlamp/enum_HeadlampMode`, `.../headlamp/struct_ModeSwitch`
- 주행 정보: `Motion/sdv_motion_driving_info/struct_DrivingInfo`, `.../vapi_driving_info/struct_Speed`
- 핸들: `Motion/sdv_motion_steering_wheel/struct_SteeringWheel`, `.../vapi_steering_wheel/enum_HandsOffDetectionLevel`
- 권한 근거: `hmg-app-challenge/research/06_industry_dev.md` 1절 12번(플랫폼 SDK는 signature 또는 privileged 권한이라 공모 앱은 못 씀)

DTG 위험운전행동
- 한국교통안전공단 eTAS 위험운전행동 기준(11대 위험운전행동, 화물, 버스, 택시별): https://etas.kotsa.or.kr/etas/frtl0401/pop/goList.do
- eTAS 소개: https://main.kotsa.or.kr/portal/contents.do?menuCode=01040400
- 강준규 외, 「디지털 운행기록계와 스마트폰을 활용한 실시간 위험운전행동 분석」, 한국컴퓨터정보학회논문지 20(12), 2015 (옛 기준 급가속 11km/h/s, 급감속 7.5km/h/s 인용): https://www.koreascience.or.kr/article/JAKO201502150666203.pdf

페인 통계: 8절 표의 링크
