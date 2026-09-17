# -*- coding: utf-8 -*-
"""复刻 Kotlin 端 TransactionParser + MerchantNormalizer 的判定逻辑，验证真实文案的解析成功率。
纯校验脚本，不属于 Android 工程。"""
import json, re, os, sys

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
rules = json.load(open(os.path.join(BASE, "app", "src", "main", "assets", "parser_rules.json"), encoding="utf-8"))

# ---------------------------------------------------------------------------
# schema 校验：JSON 键名必须与 RuleStore.parseParserRules 读取的键完全一致。
# 背景：曾出现 JSON 写成 "packageName"（字符串）而 Kotlin 读 "packageNames"（数组），
# 导致 matchesPackage 永远为 false、采集链路静默失效——解析测试全绿但真机收不到数据。
# ---------------------------------------------------------------------------
REQUIRED_KEYS = {
    "id": str, "enabled": bool, "packageNames": list, "titlePatterns": list,
    "ignoreIfContains": list, "amountPattern": str, "direction": dict,
    "merchantPatterns": list, "minAmount": (int, float),
}
SCHEMA_ERRORS = []
for r in rules.get("sources", []):
    for key, typ in REQUIRED_KEYS.items():
        if key not in r:
            SCHEMA_ERRORS.append(f"缺少键 {key}（source id={r.get('id', '?')}）")
        elif not isinstance(r[key], typ):
            SCHEMA_ERRORS.append(f"键 {key} 类型应为 {typ.__name__}，实际 {type(r[key]).__name__}（source id={r.get('id', '?')}）")
    if isinstance(r.get("packageNames"), list) and not r["packageNames"]:
        SCHEMA_ERRORS.append(f"packageNames 为空数组（source id={r.get('id', '?')}）")
    re.compile(r.get("amountPattern", ""))          # 正则必须可编译
    for p in r.get("merchantPatterns", []):
        re.compile(p)
if SCHEMA_ERRORS:
    print("=== schema 校验失败 ===")
    for e in SCHEMA_ERRORS:
        print("  [FAIL]", e)
    sys.exit(1)
print("=== schema 校验通过（键名/类型/正则均与 Kotlin 端一致）===")

# ---------------------------------------------------------------------------
# importProfiles schema 校验：账单导入档案必须与 RuleStore.parseImportProfiles
# 以及 BillImporter 的字段约定一致。
# ---------------------------------------------------------------------------
PROFILE_REQUIRED = {
    "id": str, "displayName": str, "headerSignatures": list, "minSignatureHits": int,
    "columns": dict, "directionTokens": dict, "dropStatusTokens": list,
}
# BillImporter 依赖的最小字段集：缺任一列则该平台必然导入失败
REQUIRED_FIELDS = ("time", "amount", "direction")
PROFILE_ERRORS = []
for p in rules.get("importProfiles", []):
    pid = p.get("id", "?")
    for key, typ in PROFILE_REQUIRED.items():
        if key not in p:
            PROFILE_ERRORS.append(f"缺少键 {key}（profile id={pid}）")
        elif not isinstance(p[key], typ):
            PROFILE_ERRORS.append(f"键 {key} 类型应为 {typ.__name__}（profile id={pid}）")
    cols = p.get("columns", {})
    for f in REQUIRED_FIELDS:
        if f not in cols:
            PROFILE_ERRORS.append(f"columns 缺少必需字段 {f}（profile id={pid}）")
        elif not isinstance(cols[f], list) or not cols[f]:
            PROFILE_ERRORS.append(f"columns.{f} 必须是非空数组（profile id={pid}）")
    dt = p.get("directionTokens", {})
    for k in ("income", "expense", "neutral"):
        if not isinstance(dt.get(k), list) or not dt[k]:
            PROFILE_ERRORS.append(f"directionTokens.{k} 必须是非空数组（profile id={pid}）")
    # 中性词若被误写进 expense，会把转账/提现记成消费（账目直接做错）
    for tok in dt.get("neutral", []):
        if tok in dt.get("expense", []):
            PROFILE_ERRORS.append(f"中性词「{tok}」同时出现在 expense（profile id={pid}）")
    seed = p.get("categorySeed")
    if seed is not None and not isinstance(seed, dict):
        PROFILE_ERRORS.append(f"categorySeed 应为对象（profile id={pid}）")
    elif isinstance(seed, dict) and seed.get("column"):
        if not isinstance(seed.get("map"), dict) or not seed["map"]:
            PROFILE_ERRORS.append(f"categorySeed.column 已指定但 map 为空（profile id={pid}）")
if PROFILE_ERRORS:
    print("=== importProfiles schema 校验失败 ===")
    for e in PROFILE_ERRORS:
        print("  [FAIL]", e)
    sys.exit(1)
print("=== importProfiles schema 校验通过（%d 份档案）===" % len(rules.get("importProfiles", [])))

# ---------------------------------------------------------------------------
# 官方分类种子（categorySeed.map）的两条硬校验。
#
# 为什么值得单独写：v5 之前支付宝的 map 里缺「爱车养车」「公共服务」，
# 结果是 6 笔加油停车 + 2 笔社保罚没（合计 1907.29 元）全部落进「其他」——
# 而这个问题在 schema 校验、单测、金额汇总里**全都看不出来**，
# 只有拿真实账单跑一遍才会暴露。
# ---------------------------------------------------------------------------
classify = json.load(open(os.path.join(BASE, "app", "src", "main", "assets", "classify_rules.json"), encoding="utf-8"))
VALID_CATEGORY_IDS = {c["id"] for c in classify.get("categories", [])}
VALID_CATEGORY_IDS.add((classify.get("fallback") or {}).get("id", "other"))

# 真实支付宝样本（2026-08-10 ~ 2026-09-10）中**能真正入账**的分类值枚举。
# 刻意不含「退款」「信用借还」「投资理财」：它们在账单里一律是不计收支，到不了分类这一步。
ALIPAY_SEED_OBSERVED = [
    "餐饮美食", "日用百货", "爱车养车", "医疗健康", "美容美发", "家居家装",
    "充值缴费", "公共服务", "宠物", "酒店旅游", "生活服务", "运动户外",
]

SEED_ERRORS = []
for p in rules.get("importProfiles", []):
    pid = p.get("id", "?")
    mapping = (p.get("categorySeed") or {}).get("map") or {}
    # 只有「指定了种子列」的档案才必须有 map（微信的种子列为空：
    # 它的「交易类型」是交易形态而非消费类别，不能当分类种子）
    if not mapping:
        if (p.get("categorySeed") or {}).get("column"):
            SEED_ERRORS.append(f"指定了 categorySeed.column 但 map 为空（profile id={pid}）")
        continue
    if not (p.get("categorySeed") or {}).get("column"):
        SEED_ERRORS.append(f"有 map 却没指定 categorySeed.column，种子列无从取值（profile id={pid}）")
    for src, dst in mapping.items():
        # 值必须是 classify_rules.json 里真实存在的分类 id，
        # 否则 Classifier 的 rules.byId() 查不到，会静默退化成「其他」——拼错一个字母就是这个后果
        if dst not in VALID_CATEGORY_IDS:
            SEED_ERRORS.append(f"分类种子「{src}」映射到不存在的分类 id「{dst}」（profile id={pid}）")
    if pid == "alipay_bill":
        missing = [v for v in ALIPAY_SEED_OBSERVED if v not in mapping]
        if missing:
            SEED_ERRORS.append(f"真实样本中能入账的分类值缺映射：{missing}（profile id={pid}）")
if SEED_ERRORS:
    print("=== 官方分类种子校验失败 ===")
    for e in SEED_ERRORS:
        print("  [FAIL]", e)
    sys.exit(1)
_alipay_map = next((p.get("categorySeed") or {}).get("map") or {}
                   for p in rules.get("importProfiles", []) if p.get("id") == "alipay_bill")
_covered = sum(1 for v in ALIPAY_SEED_OBSERVED if v in _alipay_map)
print("=== 官方分类种子校验通过（map 值均为有效分类 id %d 个；支付宝实测枚举覆盖 %d/%d）==="
      % (len(VALID_CATEGORY_IDS), _covered, len(ALIPAY_SEED_OBSERVED)))

NOISE = {"微信支付", "微信", "支付宝", "财付通", "微信收款助手", "服务通知", "云闪付", "银联", "支付宝安全中心"}
STOP = ["付款", "支付", "消费", "支出", "已", "成功", "收款", "¥", "￥"]
TRIM = "，,。、：:-— \t"


def clean(raw):
    s = raw.strip().strip(TRIM)
    if s in NOISE:
        return ""
    for w in STOP:
        i = s.find(w)
        if i == 0:
            return ""
        if i > 0:
            s = s[:i]
            break
    s = s.strip().strip(TRIM)
    return "" if len(s) < 2 else s[:40]


def parse(rule, title, text, ):
    body = f"{title} {text}".strip()
    if not body:
        return None
    if any(k in body for k in rule.get("ignoreIfContains", [])):
        return None
    m = re.search(rule["amountPattern"], body)
    if not m:
        return None
    try:
        amount = float(m.group(1).replace(",", ""))
    except ValueError:
        return None
    d = rule.get("direction", {})
    if any(k in body for k in d.get("income", [])):
        direction = "收入"
    elif any(k in body for k in d.get("expense", [])):
        direction = "支出"
    else:
        return None
    merchant = ""
    for p in rule.get("merchantPatterns", []):
        mm = re.search(p, f"{title} {text}")
        if mm and mm.group(1).strip():
            merchant = clean(mm.group(1))
            if merchant:
                break
    return amount, direction, merchant


CASES = [
    ("wechat", "微信支付", "向星巴克(浦东世纪汇店)付款成功 ¥25.00", "支出", 25.00, "星巴克(浦东世纪汇店)"),
    ("wechat", "微信支付", "已支付¥25.00", "支出", 25.00, ""),
    ("wechat", "微信支付", "恭喜获得红包 ¥8.88", None, None, None),
    ("wechat", "微信支付", "已支付¥1,299.00", "支出", 1299.00, ""),
    ("wechat", "微信收款助手", "微信支付 已收款¥30.00", "收入", 30.00, ""),
    ("alipay", "支付宝", "你在星巴克消费25.00元，付款成功", "支出", 25.00, "星巴克"),
    ("alipay", "支付宝", "退款成功，25.00元已到账", "收入", 25.00, ""),
    ("alipay", "支付宝", "您有一笔25.00元的待处理事项", None, None, None),
    ("alipay", "支付宝", "扫码领红包，最高得25.00元", None, None, None),
]

ok = fail = 0
for sid, title, text, exp_dir, exp_amt, exp_mer in CASES:
    rule = next(r for r in rules["sources"] if r["id"] == sid)
    got = parse(rule, title, text)
    if exp_dir is None:
        passed = got is None
        shown = "正确丢弃" if got is None else f"误收 -> {got}"
    else:
        passed = got is not None and got[1] == exp_dir and abs(got[0] - exp_amt) < 0.001 and got[2] == exp_mer
        shown = f"{got}" if got else "解析失败(None)"
    ok, fail = (ok + 1, fail) if passed else (ok, fail + 1)
    print(f"[{'PASS' if passed else 'FAIL'}] {sid:7s} {text[:34]:36s} -> {shown}")

print(f"\n通过 {ok} / {ok + fail}")
sys.exit(0 if fail == 0 else 1)
