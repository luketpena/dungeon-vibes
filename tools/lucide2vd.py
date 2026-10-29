"""Lucide SVG -> Android vector drawable (stroke-only, round caps/joins, fixed stroke width).
Path data is re-tokenized and re-serialized so compact arc flags (a2 2 0 012.6-1) survive
Android's PathParser, which doesn't understand them."""
import re, sys, xml.etree.ElementTree as ET

NUM = re.compile(r'[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?')
ARGS = {'m':2,'l':2,'h':1,'v':1,'c':6,'s':4,'q':4,'t':2,'a':7,'z':0}

def fmt(x):
    s = ('%.3f' % float(x)).rstrip('0').rstrip('.')
    return '0' if s in ('-0', '') else s

def norm_path(d):
    out, i, n, cmd = [], 0, len(d), None
    def skip(i):
        while i < n and d[i] in ' ,\t\n\r': i += 1
        return i
    while True:
        i = skip(i)
        if i >= n: break
        if d[i].isalpha():
            cmd = d[i]; i += 1
            if cmd.lower() == 'z': out.append(cmd); continue
        elif cmd is None: raise ValueError(d)
        k = ARGS[cmd.lower()]
        vals = []
        for j in range(k):
            i = skip(i)
            if cmd.lower() == 'a' and j in (3, 4):
                vals.append(d[i]); i += 1   # single-char flag
            else:
                m = NUM.match(d, i)
                if not m: raise ValueError((d, i))
                vals.append(fmt(m.group())); i = m.end()
        out.append(cmd + ' '.join(vals))
        # implicit repeat after moveto is lineto
        if cmd == 'm': cmd = 'l'
        elif cmd == 'M': cmd = 'L'
    return ' '.join(out)

def f(e, k, dflt=0.0): return float(e.get(k, dflt))

def elem_to_d(e):
    tag = e.tag.split('}')[-1]
    if tag == 'path': return norm_path(e.get('d'))
    if tag == 'circle' or tag == 'ellipse':
        cx, cy = f(e,'cx'), f(e,'cy')
        rx = f(e,'r') if tag == 'circle' else f(e,'rx'); ry = f(e,'r') if tag == 'circle' else f(e,'ry')
        return (f'M{fmt(cx-rx)} {fmt(cy)} A{fmt(rx)} {fmt(ry)} 0 1 0 {fmt(cx+rx)} {fmt(cy)} '
                f'A{fmt(rx)} {fmt(ry)} 0 1 0 {fmt(cx-rx)} {fmt(cy)} Z')
    if tag == 'line':
        return f"M{fmt(f(e,'x1'))} {fmt(f(e,'y1'))} L{fmt(f(e,'x2'))} {fmt(f(e,'y2'))}"
    if tag in ('polyline', 'polygon'):
        p = [fmt(v) for v in NUM.findall(e.get('points'))]
        pts = [f'{p[i]} {p[i+1]}' for i in range(0, len(p), 2)]
        return 'M' + ' L'.join(pts) + (' Z' if tag == 'polygon' else '')
    if tag == 'rect':
        x, y, w, h = f(e,'x'), f(e,'y'), f(e,'width'), f(e,'height')
        rx = e.get('rx'); ry = e.get('ry')
        rx = float(rx if rx is not None else (ry or 0)); ry = float(ry if ry is not None else rx)
        rx, ry = min(rx, w/2), min(ry, h/2)
        if rx == 0:
            return f'M{fmt(x)} {fmt(y)} H{fmt(x+w)} V{fmt(y+h)} H{fmt(x)} Z'
        return (f'M{fmt(x+rx)} {fmt(y)} H{fmt(x+w-rx)} A{fmt(rx)} {fmt(ry)} 0 0 1 {fmt(x+w)} {fmt(y+ry)} '
                f'V{fmt(y+h-ry)} A{fmt(rx)} {fmt(ry)} 0 0 1 {fmt(x+w-rx)} {fmt(y+h)} H{fmt(x+rx)} '
                f'A{fmt(rx)} {fmt(ry)} 0 0 1 {fmt(x)} {fmt(y+h-ry)} V{fmt(y+ry)} '
                f'A{fmt(rx)} {fmt(ry)} 0 0 1 {fmt(x+rx)} {fmt(y)} Z')
    raise ValueError('unsupported element ' + tag)

def convert(svg_path, lucide_name, stroke, filled=False, white=False):
    root = ET.parse(svg_path).getroot()
    color = '#FFFFFFFF' if white else '#FF000000'
    paths = []
    for e in root.iter():
        tag = e.tag.split('}')[-1]
        if tag in ('svg',): continue
        d = elem_to_d(e)
        fill = f'\n        android:fillColor="{color}"' if filled else ''
        paths.append(f'''    <path{fill}
        android:strokeColor="{color}"
        android:strokeWidth="{stroke}"
        android:strokeLineCap="round"
        android:strokeLineJoin="round"
        android:pathData="{d}" />''')
    note = ' (filled)' if filled else ''
    return (f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Lucide "{lucide_name}"{note}, stroke {stroke} (lucide.dev, ISC; see LICENSE-lucide.txt) -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
''' + '\n'.join(paths) + '\n</vector>\n')

if __name__ == '__main__':
    icons_dir, out_dir, stroke, mapping = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
    import os
    os.makedirs(out_dir, exist_ok=True)
    for line in open(mapping):
        line = line.split('#')[0].strip()
        if not line: continue
        parts = line.split()
        res, name, flags = parts[0], parts[1], parts[2:]
        xml = convert(os.path.join(icons_dir, name + '.svg'), name, stroke, 'filled' in flags, 'white' in flags)
        open(os.path.join(out_dir, res + '.xml'), 'w').write(xml)
        print(res, '<-', name, *flags)
