# -*- coding: utf-8 -*-
"""性能测试（大纲 10.8：50 并发、响应时间）——对核心公开接口压测"""
import statistics
import sys
import threading
import time
import urllib.request

sys.stdout.reconfigure(encoding='utf-8')
BASE = 'http://localhost:8080/api'
CONCURRENCY = int(sys.argv[1]) if len(sys.argv) > 1 else 50
REQUESTS_PER_THREAD = int(sys.argv[2]) if len(sys.argv) > 2 else 10


def fetch(path):
    t0 = time.time()
    try:
        with urllib.request.urlopen(BASE + path, timeout=10) as resp:
            resp.read()
            return time.time() - t0, resp.status
    except Exception:
        return time.time() - t0, 0


def bench(name, path):
    latencies = []
    errors = 0
    lock = threading.Lock()

    def worker():
        nonlocal errors
        for _ in range(REQUESTS_PER_THREAD):
            lat, status = fetch(path)
            with lock:
                latencies.append(lat)
                if status != 200:
                    errors += 1

    threads = [threading.Thread(target=worker) for _ in range(CONCURRENCY)]
    t0 = time.time()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    wall = time.time() - t0

    latencies.sort()

    def pct(p):
        idx = min(int(len(latencies) * p), len(latencies) - 1)
        return latencies[idx]

    print('%-22s %3d 并发 × %d = %4d 请求 | P50=%.3fs P95=%.3fs P99=%.3fs | 错误 %d | 吞吐 %.0f req/s'
          % (name, CONCURRENCY, REQUESTS_PER_THREAD, len(latencies),
             pct(0.50), pct(0.95), pct(0.99), errors, len(latencies) / wall))
    return pct(0.95)


print('开始压测：%d 并发线程 × %d 请求/线程\n' % (CONCURRENCY, REQUESTS_PER_THREAD))
r1 = bench('首页聚合 /home', '/home')
r2 = bench('资料列表 /resources', '/resources?page=1&size=10')
r3 = bench('题目列表 /questions', '/questions?subjectId=5&page=1&size=10')
print('\n达标判定（大纲 0.5：核心接口 P95 < 2 秒）:')
print('  /home      P95 = %.3fs %s' % (r1, '✓' if r1 < 2 else '✗'))
print('  /resources P95 = %.3fs %s' % (r2, '✓' if r2 < 2 else '✗'))
print('  /questions P95 = %.3fs %s' % (r3, '✓' if r3 < 2 else '✗'))
