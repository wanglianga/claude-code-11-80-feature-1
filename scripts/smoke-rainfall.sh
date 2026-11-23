#!/usr/bin/env bash
# 降雨事件重建端到端冒烟：
#   校时对齐 → 两方案重建（固定间隔候选区间 / 湿润状态单场跨日）→ 缺测上下界 →
#   流域映射分区过程 → 两方案风险归因差异 → 版本账（站点增补/设备更换/权重变动）
# 前置：应用已启动且演示数据为初始状态（如需重置见 README「重置演示数据」）。
set -euo pipefail

BASE=${BASE:-http://127.0.0.1:8080}
AUTH=${AUTH:-operator:operator123}
FROM="2026-08-14T23:00:00Z"
TO="2026-08-15T02:00:00Z"

req() { curl -sf -u "$AUTH" -H 'Content-Type: application/json' "$@"; }
step() { echo; echo "=== $* ==="; }

step "1. 雨量站与校时对齐（ST-A 快 30s / ST-B 慢 40s）"
SID_A=$(req "$BASE/api/rain/stations" | jq -r '.[] | select(.stationCode=="ST-A") | .id')
SID_B=$(req "$BASE/api/rain/stations" | jq -r '.[] | select(.stationCode=="ST-B") | .id')
[ -n "$SID_A" ] && [ -n "$SID_B" ] || { echo "找不到演示雨量站"; exit 1; }
req "$BASE/api/rain/stations/$SID_A/samples?from=$FROM&to=2026-08-14T23:30:00Z" \
  | jq '.[0] | {observedAt, correctedAt, bucketStart, mm, clockOffsetSecApplied}'
req "$BASE/api/rain/stations/$SID_A/samples?from=$FROM&to=2026-08-14T23:30:00Z" \
  | jq -e '.[0] | select(.observedAt=="2026-08-14T23:00:30Z" and .correctedAt=="2026-08-14T23:00:00Z")' >/dev/null
req "$BASE/api/rain/stations/$SID_B/samples?from=$FROM&to=2026-08-14T23:30:00Z" \
  | jq -e '.[0] | select(.observedAt=="2026-08-14T22:59:20Z" and .correctedAt=="2026-08-14T23:00:00Z")' >/dev/null
echo "校时对齐 OK"

step "2. 重建两方案（23:50–00:20 无雨间隔，ST-B 同段缺测，00:40 再降雨）"
REC=$(req -X POST "$BASE/api/rain/events/reconstruct" -d "{\"from\":\"$FROM\",\"to\":\"$TO\"}")
echo "$REC" | jq '{FIXED_GAP: .FIXED_GAP.status, ADAPTIVE_WETNESS: .ADAPTIVE_WETNESS.status}'
echo "$REC" | jq -e '.FIXED_GAP.status=="CANDIDATE" and .ADAPTIVE_WETNESS.status=="CONFIRMED"' >/dev/null

step "3. 固定间隔方案：缺站导致边界不可判 → 保留候选区间（不强行定场）"
echo "$REC" | jq '.FIXED_GAP.candidateSets[] | {setNo, events: [.events[] | {startAt, endAt, accMmLower, accMmUpper, crossesDay}]}'
echo "$REC" | jq -e '
  .FIXED_GAP.candidateSets as $s
  | (($s | length) == 2)
    and (($s[0].events | length) == 2)
    and ([$s[0].events[].crossesDay] | all(. == false))
    and ($s[0].events[0].startAt == "2026-08-14T23:00:00Z")
    and ($s[0].events[0].endAt == "2026-08-14T23:50:00Z")
    and ($s[0].events[1].startAt == "2026-08-15T00:20:00Z")' >/dev/null
echo "$REC" | jq -e '
  .FIXED_GAP.candidateSets as $s
  | (($s[1].events | length) == 1) and ($s[1].events[0].crossesDay == true)' >/dev/null
echo "$REC" | jq -e '.FIXED_GAP.undecidable[0] | select(.gapStart=="2026-08-14T23:50:00Z" and (.missingStations | contains(["ST-B"])))' >/dev/null
echo "候选区间 OK（候选集1 两场不跨日 / 候选集2 单场跨日）"

step "4. 湿润状态方案：前期面雨量饱和 + 间歇 30min < 响应 45min → 单场跨日事件"
echo "$REC" | jq '.ADAPTIVE_WETNESS.candidateSets[0].events[0] | {startAt, endAt, accMmLower, accMmUpper, crossesDay, openEnded, spatialCoverage}'
echo "$REC" | jq -e '
  .ADAPTIVE_WETNESS.candidateSets[0].events[0]
  | select(.crossesDay == true and .openEnded == true)
  | select(.accMmLower == 24.369 and .accMmUpper == 29.276)' >/dev/null
echo "跨日事件与累计上下界 OK（[24.369, 29.276]mm，缺测不插值）"

step "5. 两方案风险归因差异"
CMP=$(req "$BASE/api/rain/events/compare?from=$FROM&to=$TO")
echo "$CMP" | jq '{diff: .riskAttributionDiff}'
echo "$CMP" | jq -e '.schemes.FIXED_GAP.status=="CANDIDATE" and .schemes.ADAPTIVE_WETNESS.status=="CONFIRMED"' >/dev/null
echo "$CMP" | jq -e '.riskAttributionDiff | contains("跨日")' >/dev/null

step "6. 流域映射：SB-1 分区降雨过程（缺测时段下界 0 / 上界 0.96，实测覆盖 0.7）"
EID=$(req "$BASE/api/rain/events?scheme=ADAPTIVE_WETNESS&from=$FROM&to=$TO" | jq -r '.[0].id')
req "$BASE/api/rain/subbasins/SB-1/series?from=$FROM&to=$TO&scheme=ADAPTIVE_WETNESS" \
  | jq '.[] | select(.bucketStart=="2026-08-14T23:50:00Z") | {bucketStart, mmLower, mmUpper, measuredCoverage}'
req "$BASE/api/rain/subbasins/SB-1/series?from=$FROM&to=$TO&scheme=ADAPTIVE_WETNESS" \
  | jq -e '
    [.[] | select(.bucketStart=="2026-08-14T23:50:00Z")] as $g
    | (($g | length) == 1) and ($g[0].mmLower == 0)
      and ($g[0].mmUpper == 0.96) and ($g[0].measuredCoverage == 0.7)' >/dev/null

step "7. 版本账·站点增补：ST-C 证实间歇期有雨 → 候选区间消解为单场跨日事件"
SID_C=$(req -X POST "$BASE/api/rain/stations" -d '{
  "stationCode":"ST-C","name":"城南雨量站","clockOffsetSec":0,"equipmentId":"EQ-C-1"}' | jq -r '.id')
SAMPLES="["
BASE_EPOCH=$(date -u -d "2026-08-14T23:00:00Z" +%s)
for i in $(seq 0 17); do
  TS=$(date -u -d "@$((BASE_EPOCH + 600 * i))" +%Y-%m-%dT%H:%M:%SZ)
  MM="0.0"; [ "$i" -ge 5 ] && [ "$i" -le 7 ] && MM="1.0"
  [ "$i" -gt 0 ] && SAMPLES+=","
  SAMPLES+="{\"observedAt\":\"$TS\",\"mm\":$MM,\"quality\":\"MEASURED\"}"
done
SAMPLES+="]"
req -X POST "$BASE/api/rain/stations/$SID_C/samples" -d "{\"samples\":$SAMPLES}" | jq 'length'
LED1=$(req -X POST "$BASE/api/rain/ledger/changes" -d "{
  \"changeType\":\"STATION_ADDED\",\"effectiveAt\":\"$FROM\",
  \"reprocessFrom\":\"$FROM\",\"reprocessTo\":\"$TO\",
  \"stationCode\":\"ST-C\",
  \"weights\":[
    {\"stationCode\":\"ST-A\",\"subbasinCode\":\"SB-1\",\"weight\":0.5},
    {\"stationCode\":\"ST-B\",\"subbasinCode\":\"SB-1\",\"weight\":0.3},
    {\"stationCode\":\"ST-C\",\"subbasinCode\":\"SB-1\",\"weight\":0.2},
    {\"stationCode\":\"ST-A\",\"subbasinCode\":\"SB-2\",\"weight\":0.1},
    {\"stationCode\":\"ST-B\",\"subbasinCode\":\"SB-2\",\"weight\":0.6},
    {\"stationCode\":\"ST-C\",\"subbasinCode\":\"SB-2\",\"weight\":0.3}],
  \"note\":\"新增城南雨量站\"}")
echo "$LED1" | jq '{changeType, summary, newWeightSetVersion}'
echo "$LED1" | jq -e '.newWeightSetVersion=="WS-2" and (.impactedJson | contains("FIXED_GAP"))' >/dev/null
req "$BASE/api/rain/events?scheme=FIXED_GAP&from=$FROM&to=$TO" \
  | jq -e 'length == 1 and .[0].status=="CONFIRMED" and .[0].crossesDay==true' >/dev/null
echo "站点增补影响 OK（FIXED_GAP 候选消解为 CONFIRMED 单场跨日）"

step "8. 版本账·设备更换：ST-B 更换 EQ-B-2（校时误差 -5s）→ 采样重对齐"
LED2=$(req -X POST "$BASE/api/rain/ledger/changes" -d "{
  \"changeType\":\"EQUIPMENT_REPLACED\",\"effectiveAt\":\"2026-08-15T00:30:00Z\",
  \"reprocessFrom\":\"$FROM\",\"reprocessTo\":\"$TO\",
  \"stationCode\":\"ST-B\",\"newEquipmentId\":\"EQ-B-2\",\"newClockOffsetSec\":-5,
  \"note\":\"巡检更换采集器\"}")
echo "$LED2" | jq '{changeType, summary}'
echo "$LED2" | jq -e '.summary | contains("EQ-B-2")' >/dev/null
req "$BASE/api/rain/stations/$SID_B/samples?from=2026-08-15T00:30:00Z&to=2026-08-15T00:40:00Z" \
  | jq -e '.[] | select(.bucketStart=="2026-08-15T00:30:00Z" and .mm==1.8 and .equipmentId=="EQ-B-2")' >/dev/null
echo "设备更换影响 OK（原 00:40 的 1.8mm 重对齐到 00:30 时段）"

step "9. 版本账·权重变动：SB-1 等权重 → WS-3"
LED3=$(req -X POST "$BASE/api/rain/ledger/changes" -d "{
  \"changeType\":\"WEIGHT_CHANGED\",\"effectiveAt\":\"$FROM\",
  \"reprocessFrom\":\"$FROM\",\"reprocessTo\":\"$TO\",
  \"weights\":[
    {\"stationCode\":\"ST-A\",\"subbasinCode\":\"SB-1\",\"weight\":0.4},
    {\"stationCode\":\"ST-B\",\"subbasinCode\":\"SB-1\",\"weight\":0.4},
    {\"stationCode\":\"ST-C\",\"subbasinCode\":\"SB-1\",\"weight\":0.2},
    {\"stationCode\":\"ST-A\",\"subbasinCode\":\"SB-2\",\"weight\":0.2},
    {\"stationCode\":\"ST-B\",\"subbasinCode\":\"SB-2\",\"weight\":0.5},
    {\"stationCode\":\"ST-C\",\"subbasinCode\":\"SB-2\",\"weight\":0.3}],
  \"note\":\"权重复核\"}")
echo "$LED3" | jq '{changeType, summary, newWeightSetVersion}'
echo "$LED3" | jq -e '.newWeightSetVersion=="WS-3"' >/dev/null

step "10. 版本账查询：三类变更前后对照"
req "$BASE/api/rain/ledger" | jq '[.[] | {changeType, summary}]'
req "$BASE/api/rain/ledger" | jq -e 'length == 3' >/dev/null
req "$BASE/api/rain/ledger?changeType=WEIGHT_CHANGED" | jq -e 'length == 1' >/dev/null

echo
echo "RAIN_SMOKE_OK"
