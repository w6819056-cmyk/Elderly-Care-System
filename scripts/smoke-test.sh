#!/usr/bin/env bash
# 接口冒烟测试：对运行中的后端依次验证一期核心链路
# 用法：bash scripts/smoke-test.sh [BASE_URL]
# 依赖：curl、python3
set -u
B=${1:-http://localhost:8082}

login() {
  curl -s -m 10 -X POST "$B/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"phone\":\"$1\",\"password\":\"888888\"}" \
    | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])"
}

E=$(login 13800000001); F=$(login 13800000002); A=$(login 13700000001); O=$(login 13900000001)
if [ -z "$E" ] || [ -z "$F" ] || [ -z "$A" ] || [ -z "$O" ]; then
  echo "登录失败：请确认后端已启动且演示数据已导入（$B）"; exit 1
fi
echo "登录成功（老人/家属/管理员/机构）"

echo ""
echo "[1] 服务商城"
curl -s -m 10 "$B/api/public/services" \
  | python3 -c "import sys,json;d=json.load(sys.stdin)['data'];print('  共',len(d),'项');[print('  -',i['id'],i['name'],'¥'+str(i['price'])) for i in d]"

echo ""
echo "[2] 体征上报（超阈值应置 abnormal）"
curl -s -m 10 -X POST "$B/api/health/elders/1/vitals" -H "Authorization: Bearer $E" \
  -H 'Content-Type: application/json' -d '{"type":"BLOOD_PRESSURE","value1":168,"value2":99}' \
  | python3 -c "import sys,json;d=json.load(sys.stdin)['data'];print('  abnormal =',d['abnormal'],d['value1'],'/',d['value2'],d['unit'])"

echo ""
echo "[3] SOS 一键呼叫"
SOS=$(curl -s -m 10 -X POST "$B/api/alerts/sos" -H "Authorization: Bearer $E" \
  -H 'Content-Type: application/json' -d '{"lng":116.481,"lat":39.921,"note":"冒烟测试"}')
echo "$SOS" | python3 -c "import sys,json;d=json.load(sys.stdin)['data'];print('  告警',d['id'],d['level'],d['status'])"
SOSID=$(echo "$SOS" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['id'])")

echo ""
echo "[4] 家属状态面板"
curl -s -m 10 "$B/api/family/elders/1/snapshot" -H "Authorization: Bearer $F" \
  | python3 -c "import sys,json;d=json.load(sys.stdin)['data'];print('  老人',d['name'],'| 体征',len(d['latestVitals']),'| 告警',len(d['activeAlerts']),'| 在办',len(d['activeOrders']),'| 用药',len(d['medications']))"

echo ""
echo "[5] 告警处置闭环"
for act in ACK FOLLOW ARCHIVE; do
  curl -s -m 10 -X POST "$B/api/alerts/$SOSID/handle" -H "Authorization: Bearer $A" \
    -H 'Content-Type: application/json' -d "{\"action\":\"$act\",\"note\":\"冒烟-$act\"}" \
    | python3 -c "import sys,json;d=json.load(sys.stdin)['data'];print('  ',d['status'])"
done

echo ""
echo "[6] 家属代下单 -> 支付 -> 自动派单"
OID=$(curl -s -m 10 -X POST "$B/api/orders?elderId=1" -H "Authorization: Bearer $F" \
  -H 'Content-Type: application/json' -d '{"itemId":1,"address":"北京市朝阳区幸福里3号楼502","remark":"冒烟测试"}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['id'])")
curl -s -m 10 -X POST "$B/api/orders/$OID/pay" -H "Authorization: Bearer $F" -H 'Content-Type: application/json' \
  | python3 -c "import sys,json;d=json.load(sys.stdin)['data'];print('  订单',d['orderNo'],d['status'],'服务商',d['providerName'],'工单',d['workOrderStatus'])"

echo ""
echo "[7] 机构接单 -> 开始打卡 -> 结束打卡"
WOID=$(curl -s -m 10 "$B/api/provider/orders/$OID/work-order" -H "Authorization: Bearer $O" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['workOrder']['id'])")
curl -s -m 10 -X POST "$B/api/provider/work-orders/$WOID/accept" -H "Authorization: Bearer $O" \
  -H 'Content-Type: application/json' -d '{"nurseId":6}' \
  | python3 -c "import sys,json;d=json.load(sys.stdin)['data'];print('  接单:',d['status'],d['workOrderStatus'])"
curl -s -m 10 -X POST "$B/api/provider/work-orders/$WOID/checkin?action=START" -H "Authorization: Bearer $O" \
  -H 'Content-Type: application/json' -d '{"lng":116.481,"lat":39.921}' \
  | python3 -c "import sys,json;d=json.load(sys.stdin)['data'];print('  开始打卡:',d['workOrderStatus'])"
curl -s -m 10 -X POST "$B/api/provider/work-orders/$WOID/checkin?action=END" -H "Authorization: Bearer $O" \
  -H 'Content-Type: application/json' -d '{"lng":116.481,"lat":39.921,"note":"完成服务"}' \
  | python3 -c "import sys,json;d=json.load(sys.stdin)['data'];print('  结束打卡:',d['status'],d['workOrderStatus'])"

echo ""
echo "[8] 老人评价"
curl -s -m 10 -X POST "$B/api/orders/$OID/review" -H "Authorization: Bearer $E" \
  -H 'Content-Type: application/json' -d '{"score":5,"content":"服务很好"}' \
  | python3 -c "import sys,json;d=json.load(sys.stdin)['data'];print('  订单状态:',d['status'])"

echo ""
echo "[9] 越权校验（家属访问未绑定老人，应为 403）"
curl -s -m 10 -o /tmp/smoke-403.json -w "  HTTP %{http_code} " "$B/api/family/elders/2/snapshot" -H "Authorization: Bearer $F"
python3 -c "import json;print(json.load(open('/tmp/smoke-403.json'))['message'])"

echo ""
echo "[10] 数据大屏"
curl -s -m 10 "$B/api/admin/dashboard" -H "Authorization: Bearer $A" \
  | python3 -c "import sys,json;d=json.load(sys.stdin)['data'];print('  老人',d['elderCount'],'| 服务商',d['providerCount'],'| 订单',d['orderCount'],'| 未归档告警',d['activeAlertCount'],'| 已收款',d['totalRevenue'])"

echo ""
echo "冒烟测试完成"
