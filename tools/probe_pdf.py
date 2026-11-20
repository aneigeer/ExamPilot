# -*- coding: utf-8 -*-
"""题库 PDF 文本层抽样探测（只读，不修改任何资料）。"""
import os
import random
import sys
from collections import defaultdict

from pypdf import PdfReader

ROOT = r"D:\题库"
random.seed(42)

buckets = defaultdict(list)
for dirpath, dirnames, filenames in os.walk(ROOT):
    for fn in filenames:
        if fn.lower().endswith(".pdf"):
            top = os.path.relpath(dirpath, ROOT).split(os.sep)[0]
            buckets[top].append(os.path.join(dirpath, fn))

print("bucket counts:")
for k, v in buckets.items():
    print(f"  {len(v):5d}  {k}")

samples = []
for k, v in buckets.items():
    take = min(6, len(v))
    samples.extend(random.sample(v, take))
print(f"\nsample size = {len(samples)}\n")

stats = defaultdict(list)
for path in samples:
    try:
        r = PdfReader(path)
        n = len(r.pages)
        chars = 0
        for p in r.pages[: min(n, 3)]:
            try:
                chars += len(p.extract_text() or "")
            except Exception:
                pass
        top = os.path.relpath(path, ROOT).split(os.sep)[0]
        density = chars / max(1, min(n, 3))
        stats[top].append(density)
        flag = "TEXT" if density > 100 else ("SPARSE" if density > 20 else "SCAN")
        print(f"{flag:6s} pages={n:4d} chars/3p={chars:6d}  {os.path.relpath(path, ROOT)[:110]}")
    except Exception as e:
        print(f"ERROR  {type(e).__name__}: {os.path.relpath(path, ROOT)[:110]}")

print("\nper-bucket avg chars per page:")
for k, vals in stats.items():
    print(f"  {sum(vals)/len(vals):8.1f}  n={len(vals):3d}  {k}")
