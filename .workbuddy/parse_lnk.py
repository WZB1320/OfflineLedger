import re, os

LNKS = [
    r'C:\Users\zongb\AppData\Roaming\Microsoft\Windows\Recent\支付宝交易明细(20260810-20260910).lnk',
    r'C:\Users\zongb\AppData\Roaming\Microsoft\Office\Recent\微信支付账单流水文件(20260810-20260910)_20260910170423.LNK',
]

for lnk in LNKS:
    data = open(lnk, 'rb').read()
    found = set()
    # UTF-16LE 字符串
    for m in re.findall(rb'(?:[\x20-\x7e]\x00){6,}', data):
        s = m.decode('utf-16le', 'ignore')
        if re.match(r'^[A-Za-z]:\\', s):
            found.add(s)
    # ASCII 字符串
    for m in re.findall(rb'[A-Za-z]:\\[\x20-\x7e]{8,}', data):
        found.add(m.decode('latin-1'))
    print(os.path.basename(lnk), '=>')
    for f in sorted(found):
        print('   ', f, '| 存在:', os.path.exists(f))
