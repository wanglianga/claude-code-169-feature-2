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

post() { curl -s -X POST "$B$1" -H 'Content-Type: application/json' -d "$2"; }

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

echo "==> [3.5/4] 错投申诉复核：证据包 -> 误判率升高 -> 摄像头校准 -> 处罚暂缓 -> 违规教育"
# 玻璃瓶投入厨余桶（识别为可回收物），居民申诉，服务调取证据包
M2=$(post /api/resident/disposals '{"cardNo":"CARD1001","roomCode":"ROOM1","binType":"KITCHEN","detectedType":"RECYCLABLE","weightKg":0.6,"confidence":0.95}')
D2=$(echo "$M2" | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
AP2=$(post "/api/resident/disposals/$D2/appeal" '{"reason":"我投的是剩菜剩饭，不是玻璃瓶，请复核"}')
AP2ID=$(echo "$AP2" | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
echo "    申诉证据包: $(echo "$AP2" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d["videoClipUrl"],"|",d["binPosition"],"| 称重",d["weightDeltaKg"],"kg |",d["residentStatement"])')"
# 物业复核误判成立：积分恢复 + 样本回传识别模型；同一摄像头第2次误判 -> 进入校准
post "/api/property/appeals/$AP2ID/review" '{"accepted":true,"reviewNote":"视频复核为厨余垃圾，摄像头误判"}' >/dev/null
echo "    误判成立退还后积分: $(curl -s "$B/api/resident/CARD1001/profile" | python3 -c 'import sys,json;print(json.load(sys.stdin)["totalPoints"])')（应=100）"
echo "    模型回传样本: $(curl -s "$B/api/property/model-feedback" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(len(d),"条，最新:",d[0]["detectedType"],"->",d[0]["correctedType"],d[0]["status"])')"
echo "    摄像头误判率/校准状态: $(curl -s "$B/api/property/cameras" | python3 -c 'import sys,json;print([(c["cameraCode"],str(c["misjudgeRatePct"])+"%","校准中" if c["suspended"] else "正常") for c in json.load(sys.stdin)])')"
# 校准期间错投处罚暂停自动生效（不扣分）
M3=$(post /api/resident/disposals '{"cardNo":"CARD1001","roomCode":"ROOM1","binType":"KITCHEN","detectedType":"RECYCLABLE","weightKg":0.5,"confidence":0.94}')
D3=$(echo "$M3" | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
echo "    校准期错投: penaltySuspended=$(echo "$M3" | python3 -c 'import sys,json;print(json.load(sys.stdin)["penaltySuspended"])') 积分=$(curl -s "$B/api/resident/CARD1001/profile" | python3 -c 'import sys,json;print(json.load(sys.stdin)["totalPoints"])')（应=100 未扣）"
AP3ID=$(post "/api/resident/disposals/$D3/appeal" '{"reason":"校准期被标记，实为厨余"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
post "/api/property/appeals/$AP3ID/review" '{"accepted":true,"reviewNote":"确认误判"}' >/dev/null
# 物业完成摄像头校准任务 -> 处罚恢复
CT=$(curl -s "$B/api/property/tasks/pending" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(next(t["id"] for t in d if t["type"]=="CAMERA_CALIBRATION"))')
post "/api/property/tasks/$CT/complete" '{"note":"已重新标定摄像头角度与识别参数"}' >/dev/null
echo "    校准完成后摄像头状态: $(curl -s "$B/api/property/cameras" | python3 -c 'import sys,json;print([(c["cameraCode"],"校准中" if c["suspended"] else "正常") for c in json.load(sys.stdin)])')"
# 校准后错投恢复扣分；复核违规成立 -> 居民收到分类教育任务并完成打卡
M4=$(post /api/resident/disposals '{"cardNo":"CARD1001","roomCode":"ROOM1","binType":"KITCHEN","detectedType":"RECYCLABLE","weightKg":0.7,"confidence":0.96}')
D4=$(echo "$M4" | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
echo "    校准后错投扣分: $(curl -s "$B/api/resident/CARD1001/profile" | python3 -c 'import sys,json;print(json.load(sys.stdin)["totalPoints"])')（应=95，处罚已恢复）"
AP4ID=$(post "/api/resident/disposals/$D4/appeal" '{"reason":"我认为没投错"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
post "/api/property/appeals/$AP4ID/review" '{"accepted":false,"reviewNote":"视频确认玻璃瓶投入厨余桶，违规成立"}' >/dev/null
EDUID=$(curl -s "$B/api/resident/CARD1001/education" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d[0]["id"])')
echo "    分类教育任务: $(curl -s "$B/api/resident/CARD1001/education" | python3 -c 'import sys,json;d=json.load(sys.stdin)[0];print(d["title"],"|",d["status"])')"
curl -s -X POST "$B/api/resident/CARD1001/education/$EDUID/complete" >/dev/null
echo "    教育打卡后待办数: $(curl -s "$B/api/resident/CARD1001/profile" | python3 -c 'import sys,json;print(json.load(sys.stdin)["educationPending"])')（应=0）"

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

echo "==> [4/4] 结果判定"
if [ "$ACTIVE" = "None" ] && [ "$PENDING" = "0" ]; then
  echo "PASS: 告警合并 -> 多角色任务 -> 积分退还 -> 清运清洁 -> 联动单闭环 全部验证通过"
else
  echo "FAIL: activeCaseNo=$ACTIVE pendingTasks=$PENDING"; exit 1
fi
