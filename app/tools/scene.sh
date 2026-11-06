#!/bin/bash
# 대본 v2 장면 curl 묶음(bash 판). 사용: scene.sh 0|1|2a|2b|3|4|join|shot NAME
export PATH="/d/Android/Sdk/platform-tools:$PATH"
v='http://127.0.0.1:8088/vehicle'
S=${SHOT_DIR:-/tmp}
put() { for t in 1 2 3; do c=$(curl.exe -s -o /dev/null -w "%{http_code}" -X PUT "$v/$1"); [ "$c" = 200 ] && break; sleep 0.3; done; printf "%s " "$c"; }
shot() { adb exec-out screencap -p > "$S/$1.png"; }
case "$1" in
0) adb shell cmd car_service day-night-mode day >/dev/null
   put "gear?gear=Park"; put "controls?speed=0"; put "controls?brake=0"; put "controls?steering=0"; put "turn-signal?mode=Off"
   put "lights?feature=headlamp_mode&value=Off"; put "telltales/simulator?type=SeatBelt&state=On&color=Red"; echo ;;
1) put "telltales/simulator?type=SeatBelt&state=Off&color=Red"; echo ;;
join) for t in 1 2 3; do sleep 3; adb shell input tap 2282 1180; sleep 3; adb logcat -d -s LEAGUE | grep -q "리그 참가" && break; done; adb logcat -d -s LEAGUE | tail -2 ;;
d) put "gear?gear=Drive"; echo ;;
2a) adb shell cmd car_service day-night-mode night >/dev/null
   put "body?front_wiper=Low"; put "lights?feature=headlamp_mode&value=LowBeam"; put "controls?speed=60"; echo
   sleep 3
   for i in $(seq 1 10); do
     put "turn-signal?mode=BlinkLeft"; sleep 1; put "controls?steering=8"; sleep 1; put "controls?steering=0"; sleep 1; put "turn-signal?mode=Off"; sleep 1
   done; echo ;;
# 감점 시각은 경로 띠의 점이 M14 자리(55, 68, 81%)에 오도록(허브 결정 11/06). 리그 참가 기준 콤보 10 약 65초, 감점 82, 102, 121초, P단 150초
2b) sleep 14
   put "controls?steering=8"; sleep 1; put "controls?steering=0"; sleep 22
   put "controls?brake=60"; put "controls?speed=50"; sleep 0.8; put "controls?brake=0"
   put "controls?speed=15"; sleep 17
   put "controls?speed=25"; sleep 1; put "controls?brake=60"; put "controls?speed=15"; sleep 0.8; put "controls?brake=0"
   sleep 23; echo ;;
3) put "controls?speed=0"; echo ;;
p) put "gear?gear=Park"; echo ;;
4) adb shell cmd car_service day-night-mode day >/dev/null; put "lights?feature=headlamp_mode&value=Off"; put "gear?gear=Park"; echo ;;
shot) shot "$2" ;;
log) adb logcat -d -s LEAGUE | tail -${2:-20} ;;
esac
