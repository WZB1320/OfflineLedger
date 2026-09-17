#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
账单文件结构探针 —— 复刻 Android 端 XlsxReader + BillImporter 的判定逻辑，
用真实导出的账单文件先行验证表头定位、按名取列、金额/方向判定是否正确。

用法:
  python tools/inspect_bill.py <账单文件.xlsx|.csv> [--dump N]

设计约束（与 Kotlin 端一致）:
  - 零第三方依赖：只用 zipfile + xml（对应 Android 的 java.util.zip + XmlPullParser）
  - 表头自动定位：不固定行号，找含「交易时间」的那一行
  - 按表头名取列，绝不按列号
  - 宁缺毋滥：金额解析不出、方向判不出 → 丢弃并计数，不猜默认值

注意：平台档案（列名、方向词、丢弃状态词）**全部从 assets/parser_rules.json 读取**，
本脚本内不再保留任何一份副本——两份实现迟早会漂移，而以哪份为准永远说不清。
"""
import sys
import os
import re
import json
import zipfile
import xml.etree.ElementTree as ET

NS = '{http://schemas.openxmlformats.org/spreadsheetml/2006/main}'

# Excel 序列号日期（1900 日期系统，含 1900-02-29 的历史 bug，故基准取 1899-12-30）
EXCEL_EPOCH_DAYS = 25569  # 1899-12-30 到 1970-01-01 的天数

TIME_PATTERNS = [
    '%Y-%m-%d %H:%M:%S',
    '%Y-%m-%d %H:%M',
    '%Y/%m/%d %H:%M:%S',
    '%Y/%m/%d %H:%M',
    '%Y-%m-%d',
    '%Y/%m/%d',
    '%Y%m%d%H%M%S',
]


def excel_serial_to_epoch(serial: float):
    """
    Excel 序列号 -> epoch 秒。
    Excel 存的是「墙上时钟」（无时区），所以必须先还原成 naive 日期时间，
    再按本机时区转 epoch。若直接按 UTC 换算再本地格式化，会产生时区双重偏移
    （UTC+8 下整整错 8 小时，会直接破坏 ±3 分钟去重窗口）。
    """
    from datetime import datetime, timedelta
    if not (20000 <= serial <= 80000):
        return None
    naive = datetime(1899, 12, 30) + timedelta(days=serial)
    return naive.timestamp()


def parse_time(raw: str):
    """返回 (epoch秒, 来源说明) 或 (None, 原因)。数字按 Excel 序列号，字符串按多格式尝试。"""
    from datetime import datetime
    if not raw:
        return None, 'empty'
    s = raw.strip()
    try:
        num = float(s)
        ep = excel_serial_to_epoch(num)
        if ep is None:
            return None, 'serial_out_of_range:%s' % s
        return ep, 'excel_serial'
    except ValueError:
        pass
    for pat in TIME_PATTERNS:
        try:
            dt = datetime.strptime(s, pat)
            return dt.timestamp(), 'string'
        except ValueError:
            continue
    return None, 'unparsed_time:%s' % s


def fmt_time(epoch):
    from datetime import datetime
    return datetime.fromtimestamp(epoch).strftime('%Y-%m-%d %H:%M:%S')


def col_letter_to_idx(ref: str) -> int:
    """A1 -> 0, K15 -> 10"""
    letters = re.match(r'([A-Z]+)', ref).group(1)
    idx = 0
    for ch in letters:
        idx = idx * 26 + (ord(ch) - ord('A') + 1)
    return idx - 1


def read_shared_strings(zf):
    if 'xl/sharedStrings.xml' not in zf.namelist():
        return []
    out = []
    for _, el in ET.iterparse(zf.open('xl/sharedStrings.xml'), events=('end',)):
        if el.tag == NS + 'si':
            out.append(''.join(t.text or '' for t in el.iter(NS + 't')))
            el.clear()
    return out


def read_sheet_rows(zf, shared):
    """返回 rows: list[list[str]]，按 A 列索引对齐（缺失单元格补空）"""
    rows = []
    for _, el in ET.iterparse(zf.open('xl/worksheets/sheet1.xml'), events=('end',)):
        if el.tag != NS + 'row':
            continue
        cells = {}
        maxc = -1
        for c in el.iter(NS + 'c'):
            ref = c.get('r', '')
            if not ref:
                continue
            ci = col_letter_to_idx(ref)
            v = c.find(NS + 'v')
            is_el = c.find(NS + 'is')
            if is_el is not None:
                text = ''.join(t.text or '' for t in is_el.iter(NS + 't'))
            elif v is None or v.text is None:
                text = ''
            elif c.get('t') == 's':
                i = int(v.text)
                text = shared[i] if 0 <= i < len(shared) else ''
            else:
                text = v.text
            cells[ci] = text.strip()
            maxc = max(maxc, ci)
        rows.append([cells.get(i, '') for i in range(maxc + 1)])
        el.clear()
    return rows


# ---------------------------------------------------------------- CSV 读取
# 以下三个函数逐行镜像 Kotlin 端 CsvIo.decode / CsvIo.splitCsvLine
# 与 BillImporter 的 CSV 分支，保证「脚本跑通 == APK 跑通」。

def decode_bytes(raw: bytes) -> str:
    """先严格 UTF-8，失败退 GBK。

    实测：支付宝导出的 CSV 是 **GBK**（UTF-8 解码会在第 86 字节
    报 0xb5 invalid start byte）；微信是 xlsx，内部 XML 才是 UTF-8。
    搞错编码的后果是商户名全乱码，而金额、时间照样能解析出来——
    账目看着正常，分类全废。
    """
    try:
        return raw.decode('utf-8')          # Python 的 utf-8 解码本身即严格模式
    except UnicodeDecodeError:
        pass
    for enc in ('gbk', 'gb18030'):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    return raw.decode('latin-1')


def split_csv_line(line: str) -> list:
    """RFC4180 风格的 CSV 切分，支持双引号包裹与转义（镜像 CsvIo.splitCsvLine）"""
    out, sb, in_quotes, i = [], [], False, 0
    while i < len(line):
        ch = line[i]
        if ch == '"':
            if in_quotes and i + 1 < len(line) and line[i + 1] == '"':
                sb.append('"')
                i += 1
            else:
                in_quotes = not in_quotes
        elif ch == ',' and not in_quotes:
            out.append(''.join(sb))
            sb = []
        else:
            sb.append(ch)
        i += 1
    out.append(''.join(sb))
    return out


def read_csv_rows(path: str):
    """镜像 BillImporter 的 CSV 分支：decode → split('\\n') → trimEnd('\\r') → splitCsvLine

    注意：Kotlin 端取单元格时统一 .trim()，而 trim() 会去掉 '\\t'。
    支付宝导出的「交易订单号」列 132/132 全部尾部带制表符，
    「商家订单号」为空时不是空串而是单个 '\\t' —— 不 trim 的话，
    单号精确去重会永远匹配不上（同一笔被记两次），
    空商家单号还会被当成"有值"参与匹配。
    """
    text = decode_bytes(open(path, 'rb').read())
    return [split_csv_line(ln.rstrip('\r').rstrip()) for ln in text.split('\n')]


def locate_header(rows):
    """找表头行：优先含「交易时间」，其次含「收/支」"""
    for i, r in enumerate(rows):
        if any('交易时间' in c for c in r):
            return i, r
    for i, r in enumerate(rows):
        if any('收/支' in c for c in r):
            return i, r
    return -1, None


def clean_number(raw):
    """只清洗不判正负：用于区分「金额就是 0」与「金额根本解析不出」"""
    if raw is None:
        return None
    s = raw
    for ch in ('¥', '￥', ',', '，', '元', '"', ' '):
        s = s.replace(ch, '')
    s = s.strip()
    if not s:
        return None
    try:
        return float(s)
    except ValueError:
        return None


def parse_amount(raw: str):
    """镜像 BillImporter.parseAmount：去掉货币符号/千分位/空格/「元」后转数字，只认正数。
    不做任何单位猜测（分/角），只认元。方向列为空时才可能靠符号兜底，此处不猜。"""
    if raw is None:
        return None
    s = raw
    for ch in ('¥', '￥', ',', '，', '元', '"', ' '):
        s = s.replace(ch, '')
    s = s.strip()
    if not s:
        return None
    try:
        v = float(s)
    except ValueError:
        return None
    return v if v > 0 else None


def load_profiles():
    """从 assets/parser_rules.json 加载导入档案——验证的必须是真正打进 APK 的那份配置"""
    base = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    path = os.path.join(base, 'app', 'src', 'main', 'assets', 'parser_rules.json')
    return json.load(open(path, encoding='utf-8')).get('importProfiles', [])


def match_profile(header, profiles):
    """与 Kotlin 端 BillImporter.matchProfile 一致：命中特征数最多且达门槛者胜"""
    hs = set(normalize_header(h) for h in header)
    best, best_hits = None, 0
    for p in profiles:
        hits = sum(1 for s in p.get('headerSignatures', []) if normalize_header(s) in hs)
        if hits >= p.get('minSignatureHits', 3) and hits > best_hits:
            best, best_hits = p, hits
    return best, best_hits


def build_columns(header, profile):
    out = {}
    for field, aliases in profile.get('columns', {}).items():
        for j, h in enumerate(header):
            if normalize_header(h) in [normalize_header(a) for a in aliases]:
                out[field] = j
                break
    seed_col = (profile.get('categorySeed') or {}).get('column') or ''
    if seed_col:
        for j, h in enumerate(header):
            if normalize_header(h) == normalize_header(seed_col):
                out['seed'] = j
                break
    return out


def normalize_header(raw):
    return raw.strip().strip('"').replace('（', '(').replace('）', ')').replace(' ', '')


def parse_direction(raw, profile):
    """顺序至关重要：先判不计收支，否则「不计收支」会被当成支出"""
    dt = profile.get('directionTokens', {})
    v = (raw or '').strip()
    if not v:
        return None
    for t in dt.get('neutral', []):
        if t == v or t in v:
            return 'neutral'
    for t in dt.get('income', []):
        if t in v:
            return 'income'
    for t in dt.get('expense', []):
        if t in v:
            return 'expense'
    return None


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    path = sys.argv[1]
    dump = 0
    if '--dump' in sys.argv:
        dump = int(sys.argv[sys.argv.index('--dump') + 1])

    print('文件:', os.path.basename(path))
    print('大小: %d 字节' % os.path.getsize(path))
    print()

    is_csv = path.lower().endswith('.csv')
    if is_csv:
        rows = read_csv_rows(path)
        shared = []
    else:
        with zipfile.ZipFile(path) as zf:
            shared = read_shared_strings(zf)
            rows = read_sheet_rows(zf, shared)

    print('== 结构 ==')
    print('容器: %s | 总行数: %d%s' % (
        'CSV(走 CsvIo.decode/splitCsvLine)' if is_csv else 'xlsx(zip+SAX)',
        len(rows),
        '' if is_csv else ' | 共享字符串: %d' % len(shared)))
    hi, header = locate_header(rows)
    if hi < 0:
        print('!! 未找到表头行 —— 解析应中止')
        sys.exit(3)
    print('表头定位: 第 %d 行（0-based），说明区 %d 行' % (hi + 1, hi))
    print('表头:', ' | '.join(header))
    print()

    # ==== 关键：用 parser_rules.json 里的真实配置来解析 ====
    profiles = load_profiles()
    if not profiles:
        print('!! parser_rules.json 里没有 importProfiles')
        sys.exit(4)
    profile, hits = match_profile(header, profiles)
    print('== 导入档案匹配（用 assets/parser_rules.json 的真实配置）==')
    if profile is None:
        print('!! 没有档案命中，导入会被拒绝')
        sys.exit(5)
    print('命中档案: %s（%s，匹配 %d 个特征列，门槛 %d）'
          % (profile['id'], profile.get('displayName', ''), hits, profile.get('minSignatureHits', 3)))
    print('校准状态: %s' % ('已用真实文件验证' if profile.get('verified') else '待校准'))
    colmap = build_columns(header, profile)
    print('列映射:')
    for f, j in sorted(colmap.items()):
        print('   %-13s <- 第 %d 列 %s' % (f, j + 1, header[j]))
    missing = [f for f in ('time', 'direction', 'amount') if f not in colmap]
    if missing:
        print('!! 关键列缺失:', missing, '-> 该文件会被拒绝导入')
        sys.exit(6)
    print()

    drop_status = profile.get('dropStatusTokens', [])
    seed_map = (profile.get('categorySeed') or {}).get('map', {}) or {}
    # 空行不计入数据行：支付宝 csv 尾部带一个空行，微信 xlsx 也有零星空行
    data = [r for r in rows[hi + 1:] if any(c.strip() for c in r)]
    kept, dropped = [], []
    neutral = refund = zero_amount = 0
    seed_hit = 0

    for r in data:
        def g(f):
            j = colmap.get(f)
            return r[j] if (j is not None and j < len(r)) else ''
        status = g('status')
        if any(t in status for t in drop_status):
            refund += 1
            continue
        direction = parse_direction(g('direction'), profile)
        if direction is None:
            dropped.append(('direction_unparsed:' + repr(g('direction')), r))
            continue
        if direction == 'neutral':
            neutral += 1
            continue
        raw_amt = g('amount')
        amt = parse_amount(raw_amt)
        ts, tsrc = parse_time(g('time'))
        if amt is None:
            # 区分「解析不出」和「金额就是 0」——两者都丢弃，但含义完全不同：
            # 前者是规则不够用（要修规则），后者是平台真实的 0 元订单（无法入账）
            if clean_number(raw_amt) == 0:
                zero_amount += 1
            else:
                dropped.append(('amount_unparsed:' + repr(raw_amt), r))
            continue
        if ts is None:
            dropped.append(('time_unparsed:' + repr(g('time')), r))
            continue
        seed_raw = g('seed')
        seed_id = seed_map.get(seed_raw)
        if seed_id:
            seed_hit += 1
        kept.append({
            'time': ts, 'time_src': tsrc, 'type': g('type'), 'counterparty': g('counterparty'),
            'product': g('product'), 'direction': direction, 'amount': amt,
            'status': status, 'txn_no': g('txnNo'), 'merchant_no': g('merchantNo'),
            'seed': seed_id or '',
        })

    print('== 解析结果（按真实配置）==')
    print('数据行: %d | 入库: %d | 跳过: %d | 丢弃: %d'
          % (len(data), len(kept), neutral + refund, len(dropped)))
    inc = sum(k['amount'] for k in kept if k['direction'] == 'income')
    exp = sum(k['amount'] for k in kept if k['direction'] == 'expense')
    print('收入: %d 笔 %.2f 元' % (sum(1 for k in kept if k['direction'] == 'income'), inc))
    print('支出: %d 笔 %.2f 元' % (sum(1 for k in kept if k['direction'] == 'expense'), exp))
    if neutral:
        print('不计收支(跳过): %d 笔' % neutral)
    if refund:
        print('退款/关闭(跳过): %d 笔' % refund)
    if zero_amount:
        print('金额为 0.00(未入账): %d 笔  ← 与平台自报笔数会差这么多，金额不受影响' % zero_amount)
    if seed_hit:
        print('官方分类种子命中: %d 笔' % seed_hit)
    if kept:
        times = sorted(k['time'] for k in kept)
        print('时间范围: %s ~ %s' % (fmt_time(times[0]), fmt_time(times[-1])))
    # 笔数平衡：任何一行都必须有归宿，不允许静默消失
    accounted = len(kept) + neutral + refund + zero_amount + len(dropped)
    print('笔数平衡: %d(入库) + %d(不计收支) + %d(退款/关闭) + %d(零金额) + %d(无法解析) = %d / 数据行 %d %s'
          % (len(kept), neutral, refund, zero_amount, len(dropped), accounted, len(data),
             'OK' if accounted == len(data) else '!! 不相等，有行被静默丢弃'))
    print()

    if dropped:
        print('== 丢弃明细（宁缺毋滥原则，逐条列出）==')
        for reason, r in dropped:
            print('  [%s] %s' % (reason, ' | '.join(r)[:120]))
        print()

    print('== 交易类型枚举 ==')
    types = {}
    for k in kept:
        types[k['type']] = types.get(k['type'], 0) + 1
    for t, n in sorted(types.items(), key=lambda x: -x[1]):
        print('   %-16s %d' % (t, n))
    print()

    uniq = len(set(k['txn_no'] for k in kept if k['txn_no']))
    print('== 去重维度可用性 ==')
    print('带交易单号: %d/%d | 唯一单号: %d' % (sum(1 for k in kept if k['txn_no']), len(kept), uniq))
    print()

    if dump:
        print('== 前 %d 条记录 ==' % dump)
        for k in kept[:dump]:
            print('  %s | %-8s | %-10s | %-14s | ¥%.2f | %s%s' % (
                fmt_time(k['time']), k['direction'], k['type'], k['counterparty'][:14],
                k['amount'], k['status'], (' | 种子:' + k['seed']) if k['seed'] else ''))


if __name__ == '__main__':
    main()
