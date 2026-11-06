#!/bin/bash
# Virtual Console 세션 신호(브라우저 탭이 보내는 POST /browser/session)를 대신 보내 콘솔 HTTP를 살려 둔다
end=$((SECONDS+7200))
while [ $SECONDS -lt $end ]; do curl.exe -s -m 3 -o /dev/null -X POST "http://127.0.0.1:8088/browser/session?client=claude-keepalive-1"; sleep 1; done
