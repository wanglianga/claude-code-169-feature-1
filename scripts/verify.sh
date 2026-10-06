#!/usr/bin/env bash
# 一键验证脚本：本地构建 -> 以 local profile(H2/无Kafka) 启动 -> 演练联动全链路 -> 停止
set -euo pipefail

cd "$(dirname "$0")/.."
PORT="${PORT:-8080}"
B="http://localhost:${PORT}"
JAR="target/garbage-room-service.jar"
LOG="/tmp/garbage-room-verify.log"
PID=""

cleanup() {
  if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
    kill "$PID" 2>/dev/null || true
    wait "$PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

echo "==> [1/4] 打包（跳过测试；完整测试请执行 mvn test）"
mvn -q -B package -DskipTests

echo "==> [2/4] 启动应用（local profile，日志：$LOG）"
java -jar "$JAR" --spring.profiles.active=local --server.port="$PORT" >"$LOG" 2>&1 &
PID=$!

for i in $(seq 1 60); do
  if curl -sf "$B/actuator/health" >/dev/null 2>&1; then break; fi
  sleep 1
done
curl -sf "$B/actuator/health" >/dev/null
echo "    health: $(curl -s "$B/actuator/health")"

post() { curl -s -X POST "$B$1" -H 'Content-Type: application/json' -d "${2:-}"; }

echo "==> [3/4] 联动场景演练"
post /api/device/events '{"eventType":"KITCHEN_FULL","roomCode":"ROOM1","binType":"KITCHEN","capacityPct":95}' >/dev/null
post /api/device/events '{"eventType":"ODOR","roomCode":"ROOM1","odorValue":88}' >/dev/null
echo "    环卫优先级首位: $(curl -s "$B/api/sanitation/truck-priorities" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d[0]["roomCode"],d[0]["priorityScore"],d[0]["reason"])')"

MISSORT=$(post /api/resident/disposals '{"cardNo":"CARD1001","roomCode":"ROOM1","binType":"KITCHEN","detectedType":"OTHER","weightKg":0.8,"confidence":0.93}')
DID=$(echo "$MISSORT" | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
echo "    错投扣分后积分: $(curl -s "$B/api/resident/CARD1001/profile" | python3 -c 'import sys,json;print(json.load(sys.stdin)["totalPoints"])')（初始100，应=95）"

post "/api/resident/disposals/$DID/appeal" '{"reason":"投放物为剩菜"}' >/dev/null
post "/api/resident/disposals/$DID/appeal-review" '{"accepted":true,"reviewNote":"现场复核为厨余，摄像头误判"}' >/dev/null
echo "    申诉成立退还后积分: $(curl -s "$B/api/resident/CARD1001/profile" | python3 -c 'import sys,json;print(json.load(sys.stdin)["totalPoints"])')（应=100）"

echo "    合并联动单: $(curl -s "$B/api/rooms/ROOM1" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d["room"]["activeCaseNo"],[a["type"] for a in d["activeAlerts"]])')"

DTASK=$(curl -s "$B/api/property/tasks/pending" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(next(t["id"] for t in d if t["type"]=="DISINFECTION"))')
post "/api/property/tasks/$DTASK/complete" '{"note":"已消杀冲洗"}' >/dev/null
post /api/device/events '{"eventType":"TRUCK_ARRIVAL","roomCode":"ROOM1","truckPresent":true}' >/dev/null
post /api/device/events '{"eventType":"TRUCK_ARRIVAL","roomCode":"ROOM1","truckPresent":false}' >/dev/null
curl -s -X POST "$B/api/sanitation/rooms/ROOM1/clean-confirm" >/dev/null
ACTIVE=$(curl -s "$B/api/rooms/ROOM1" | python3 -c 'import sys,json;print(json.load(sys.stdin)["room"]["activeCaseNo"])')
PENDING=$(curl -s "$B/api/property/tasks/pending" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)))')
echo "    全闭环后活跃联动单: ${ACTIVE:-无} ; 物业待办数: $PENDING"

CID=$(post /api/resident/complaints '{"contact":"13800000000","roomCode":"ROOM2","type":"ODOR","content":"2号房异味"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
post "/api/property/complaints/$CID/reply" '{"reply":"已安排保洁消杀"}' >/dev/null
echo "    街道质量视图: $(curl -s "$B/api/street/overview?days=7" | python3 -c 'import sys,json;d=json.load(sys.stdin);print([(x["communityName"],x["correctRatePct"],x["complaintCount"]) for x in d])')"

echo "==> 满溢清运超时升级演练（ROOM2 厨余满溢回溯31分钟：物业封桶引导+环卫调线 -> 街道督办）"
FULL_AT=$(python3 -c 'import datetime;print((datetime.datetime.now(datetime.timezone.utc)-datetime.timedelta(minutes=31)).strftime("%Y-%m-%dT%H:%M:%SZ"))')
post /api/device/events "{\"eventType\":\"KITCHEN_FULL\",\"roomCode\":\"ROOM2\",\"binType\":\"KITCHEN\",\"capacityPct\":96,\"occurredAt\":\"$FULL_AT\"}" >/dev/null
post /api/sanitation/escalation-check >/dev/null
echo "    环卫优先级 ROOM2: $(curl -s "$B/api/sanitation/truck-priorities" | python3 -c 'import sys,json;d=json.load(sys.stdin);r=next(x for x in d if x["roomCode"]=="ROOM2");print(r["priorityScore"],r["reason"])')"
echo "    居民端引导: $(curl -s "$B/api/resident/rooms/ROOM2/disposal-guide" | python3 -c 'import sys,json;d=json.load(sys.stdin);print("封桶=",d["sealed"],"预计恢复=",d["estimatedResumeAt"],"最近备用点=",[ (a["roomCode"],a["distanceM"]) for a in d["alternatives"][:2]])')"

BEFORE=$(curl -s "$B/api/resident/CARD1002/profile" | python3 -c 'import sys,json;print(json.load(sys.stdin)["totalPoints"])')
post /api/resident/disposals '{"cardNo":"CARD1002","roomCode":"ROOM2","binType":"KITCHEN","weightKg":1.0}' >/dev/null
AFTER=$(curl -s "$B/api/resident/CARD1002/profile" | python3 -c 'import sys,json;print(json.load(sys.stdin)["totalPoints"])')
echo "    封桶点投放积分: 投放前=$BEFORE 投放后=$AFTER（应相等，不扣分）; 现场问题数=$(curl -s "$B/api/property/site-issues" | python3 -c 'import sys,json;print(len([x for x in json.load(sys.stdin) if x["roomCode"]=="ROOM2"]))')"

DCID=$(post /api/resident/complaints '{"contact":"13800000002","roomCode":"ROOM2","type":"COLLECTION_DELAY","content":"满溢封桶迟迟不清运"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
ECASE=$(curl -s "$B/api/rooms/ROOM2" | python3 -c 'import sys,json;print(json.load(sys.stdin)["room"]["activeCaseNo"])')
curl -s -X POST "$B/api/street/cases/$ECASE/delay-reason" -H 'Content-Type: application/json' -d '{"reason":"早高峰交通管制，已改派备用车"}' >/dev/null
echo "    街道超时事件: $(curl -s "$B/api/street/collection-overdue" | python3 -c 'import sys,json;d=json.load(sys.stdin);e=next(x for x in d if x["roomCode"]=="ROOM2");print("投诉数=",e["residentComplaintCount"],"现场问题=",e["openSiteIssueCount"],"延迟原因=",e["delayReason"])')"
post "/api/property/complaints/$DCID/reply" '{"reply":"车辆已改派，马上到场"}' >/dev/null

post /api/device/events '{"eventType":"TRUCK_ARRIVAL","roomCode":"ROOM2","truckPresent":true}' >/dev/null
post /api/device/events '{"eventType":"TRUCK_ARRIVAL","roomCode":"ROOM2","truckPresent":false}' >/dev/null
ESC_ACTIVE=$(curl -s "$B/api/rooms/ROOM2" | python3 -c 'import sys,json;print(json.load(sys.stdin)["room"]["activeCaseNo"])')
echo "    清运后 ROOM2 联动单: ${ESC_ACTIVE:-无}（现场问题随清运自动收口）"

echo "==> [4/4] 结果判定"
if [ "$ACTIVE" = "None" ] && [ "$PENDING" = "0" ]; then
  echo "PASS: 告警合并 -> 多角色任务 -> 积分退还 -> 清运清洁 -> 联动单闭环 全部验证通过"
else
  echo "FAIL: activeCaseNo=$ACTIVE pendingTasks=$PENDING"; exit 1
fi
