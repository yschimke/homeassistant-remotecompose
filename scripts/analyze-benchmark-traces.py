#!/usr/bin/env python3
"""Summarise macrobenchmark Perfetto traces: where the frames of a scroll went, and whether the
baseline profile was in effect.

  scripts/analyze-benchmark-traces.py [--tp trace_processor_shell] [--package ee.schimke.harc] \
      <trace-or-directory>...

For every `*.perfetto-trace` it prints, for the target app's process:

- the frame timeline (Android 12+): frames, janky frames and the jank types SurfaceFlinger blamed;
- main-thread `Choreographer#doFrame` and RenderThread `DrawFrame*` durations (p50/p90/p99/max)
  and how many overran a 16.7 ms budget;
- the slices with the most self time inside the over-budget frames, on the main thread and on
  RenderThread, which is where to look for the cause;
- JIT activity (`JIT compiling` slices and `Jit thread pool` CPU), the direct check that a
  baseline profile was loaded: with one, scrolled code is already compiled and JIT stays quiet;
- GC slices overlapping the frames.

Needs the `perfetto` Python package; without --tp it downloads trace_processor itself.
"""

import argparse
import json
import os
import sys

from perfetto.trace_processor import TraceProcessor, TraceProcessorConfig

BUDGET_NS = 16_666_667


def find_traces(paths):
    for path in paths:
        if os.path.isdir(path):
            for root, _, files in os.walk(path):
                for name in sorted(files):
                    if name.endswith(".perfetto-trace"):
                        yield os.path.join(root, name)
        elif path.endswith(".perfetto-trace") or os.path.isfile(path):
            yield path


def q(tp, sql):
    return list(tp.query(sql))


def pct(values, p):
    if not values:
        return 0.0
    values = sorted(values)
    k = min(len(values) - 1, int(round(p / 100.0 * (len(values) - 1))))
    return values[k] / 1e6


def stats(label, durs):
    if not durs:
        print(f"  {label}: none")
        return
    over = sum(1 for d in durs if d > BUDGET_NS)
    print(
        f"  {label}: n={len(durs)} p50={pct(durs, 50):.1f}ms p90={pct(durs, 90):.1f}ms "
        f"p99={pct(durs, 99):.1f}ms max={max(durs) / 1e6:.1f}ms over16.7ms={over} "
        f"({100.0 * over / len(durs):.0f}%)"
    )


def top_self_time(tp, thread_filter, window_sql, limit):
    """Slices on the matching threads, inside the windows, ranked by total self time."""
    rows = q(
        tp,
        f"""
        WITH win AS ({window_sql}),
        s AS (
          SELECT sl.id, sl.name, sl.ts, sl.dur,
                 sl.dur - COALESCE((SELECT SUM(c.dur) FROM slice c WHERE c.parent_id = sl.id), 0)
                   AS self
          FROM slice sl
          JOIN thread_track tt ON sl.track_id = tt.id
          JOIN thread t USING (utid)
          WHERE {thread_filter} AND sl.dur > 0
        )
        SELECT s.name AS name, COUNT(*) AS n, SUM(s.self) AS self_ns, MAX(s.dur) AS max_ns
        FROM s JOIN win ON s.ts >= win.ts AND s.ts < win.ts + win.dur
        GROUP BY s.name
        ORDER BY self_ns DESC
        LIMIT {limit}
        """,
    )
    for r in rows:
        name = (r.name or "")[:90]
        print(f"    {r.self_ns / 1e6:8.1f}ms self  n={r.n:<5} max={r.max_ns / 1e6:6.1f}ms  {name}")
    if not rows:
        print("    (nothing)")


def analyze(tp, package, limit):
    procs = q(
        tp,
        f"SELECT upid, pid, name FROM process WHERE name = '{package}' OR name LIKE '{package}:%'",
    )
    main = [p for p in procs if p.name == package] or procs
    if not main:
        print(f"  no process named {package}")
        return
    upid, pid = main[0].upid, main[0].pid
    main_thread = f"t.upid = {upid} AND t.tid = {pid}"
    render_thread = f"t.upid = {upid} AND t.name = 'RenderThread'"

    # Frame timeline (API 31+).
    try:
        frames = q(
            tp,
            f"""
            SELECT a.dur AS dur, a.jank_type AS jank, a.on_time_finish AS on_time
            FROM actual_frame_timeline_slice a
            WHERE a.upid = {upid}
            """,
        )
    except Exception:  # noqa: BLE001 - older traces have no frame timeline tables
        frames = []
    if frames:
        janky = [f for f in frames if f.jank and f.jank != "None"]
        print(
            f"  frame timeline: frames={len(frames)} janky={len(janky)} "
            f"({100.0 * len(janky) / len(frames):.0f}%)"
        )
        kinds = {}
        for f in janky:
            for k in f.jank.split(", "):
                kinds[k] = kinds.get(k, 0) + 1
        for k, n in sorted(kinds.items(), key=lambda kv: -kv[1]):
            print(f"    {n:5}  {k}")
        stats("frame timeline durations", [f.dur for f in frames])
    else:
        print("  frame timeline: not in trace")

    do_frames = q(
        tp,
        f"""
        SELECT sl.ts AS ts, sl.dur AS dur FROM slice sl
        JOIN thread_track tt ON sl.track_id = tt.id JOIN thread t USING (utid)
        WHERE {main_thread} AND sl.name LIKE 'Choreographer#doFrame%' AND sl.depth = 0
        """,
    )
    stats("main Choreographer#doFrame", [f.dur for f in do_frames])
    draws = q(
        tp,
        f"""
        SELECT sl.dur AS dur FROM slice sl
        JOIN thread_track tt ON sl.track_id = tt.id JOIN thread t USING (utid)
        WHERE {render_thread} AND sl.name GLOB 'DrawFrame*' AND sl.depth = 0
        """,
    )
    stats("RenderThread DrawFrame", [d.dur for d in draws])

    slow_windows = f"""
        SELECT sl.ts AS ts, sl.dur AS dur FROM slice sl
        JOIN thread_track tt ON sl.track_id = tt.id JOIN thread t USING (utid)
        WHERE {main_thread} AND sl.name LIKE 'Choreographer#doFrame%' AND sl.depth = 0
          AND sl.dur > {BUDGET_NS}
    """
    print("  main thread, self time inside over-budget doFrames:")
    top_self_time(tp, main_thread, slow_windows, limit)
    print("  RenderThread, self time inside over-budget doFrames:")
    top_self_time(tp, render_thread, slow_windows, limit)

    all_frames = f"""
        SELECT sl.ts AS ts, sl.dur AS dur FROM slice sl
        JOIN thread_track tt ON sl.track_id = tt.id JOIN thread t USING (utid)
        WHERE {main_thread} AND sl.name LIKE 'Choreographer#doFrame%' AND sl.depth = 0
    """
    jit = q(
        tp,
        f"""
        SELECT COUNT(*) AS n, COALESCE(SUM(sl.dur), 0) AS ns FROM slice sl
        JOIN thread_track tt ON sl.track_id = tt.id JOIN thread t USING (utid)
        WHERE t.upid = {upid} AND sl.name LIKE 'JIT compiling%'
        """,
    )[0]
    jit_cpu = q(
        tp,
        f"""
        SELECT COALESCE(SUM(s.dur), 0) AS ns FROM sched s JOIN thread t USING (utid)
        WHERE t.upid = {upid} AND t.name LIKE 'Jit thread pool%'
        """,
    )[0]
    print(
        f"  JIT: {jit.n} 'JIT compiling' slices ({jit.ns / 1e6:.0f}ms), "
        f"Jit thread pool on-CPU {jit_cpu.ns / 1e6:.0f}ms"
    )
    top_jit = q(
        tp,
        f"""
        SELECT sl.name AS name, COUNT(*) AS n FROM slice sl
        JOIN thread_track tt ON sl.track_id = tt.id JOIN thread t USING (utid)
        WHERE t.upid = {upid} AND sl.name LIKE 'JIT compiling%'
        GROUP BY sl.name ORDER BY n DESC LIMIT 8
        """,
    )
    for r in top_jit:
        print(f"    {r.n:4}  {r.name[:110]}")
    gc = q(
        tp,
        f"""
        WITH win AS ({all_frames})
        SELECT COUNT(*) AS n, COALESCE(SUM(sl.dur), 0) AS ns FROM slice sl
        JOIN thread_track tt ON sl.track_id = tt.id JOIN thread t USING (utid)
        JOIN win ON sl.ts < win.ts + win.dur AND sl.ts + sl.dur > win.ts
        WHERE t.upid = {upid} AND (sl.name LIKE '%GC%' OR sl.name LIKE 'Lock contention%')
        """,
    )[0]
    print(f"  GC / lock contention overlapping frames: {gc.n} slices, {gc.ns / 1e6:.0f}ms")


def print_metrics(paths):
    """Each benchmark's metrics from the macrobenchmark `*benchmarkData.json` files."""
    for path in paths:
        for root, _, files in os.walk(path) if os.path.isdir(path) else []:
            for name in sorted(files):
                if not name.endswith("benchmarkData.json"):
                    continue
                with open(os.path.join(root, name)) as f:
                    data = json.load(f)
                for b in data.get("benchmarks", []):
                    print(f"\n--- {b.get('className', '').split('.')[-1]}.{b.get('name')}")
                    for key, m in sorted(b.get("metrics", {}).items()):
                        runs = len(m.get("runs", []))
                        print(
                            f"  {key}: median={m.get('median', 0):.1f} min={m.get('minimum', 0):.1f} "
                            f"max={m.get('maximum', 0):.1f} runs={runs}"
                        )
                    for key, m in sorted(b.get("sampledMetrics", {}).items()):
                        cols = " ".join(
                            f"{k}={m[k]:.1f}" for k in ("P50", "P90", "P95", "P99") if k in m
                        )
                        print(f"  {key}: {cols} samples={len(m.get('runs', []))}")


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("paths", nargs="+")
    ap.add_argument("--tp", help="trace_processor_shell binary (downloaded when omitted)")
    ap.add_argument("--package", default="ee.schimke.harc")
    ap.add_argument("--match", default="", help="only traces whose file name contains this")
    ap.add_argument("--limit", type=int, default=15)
    args = ap.parse_args()

    print_metrics(args.paths)
    traces = [t for t in find_traces(args.paths) if args.match in os.path.basename(t)]
    if not traces:
        print("no traces found")
        return 0
    config = TraceProcessorConfig(bin_path=args.tp) if args.tp else None
    for path in traces:
        print(f"\n=== {os.path.basename(path)}")
        try:
            tp = TraceProcessor(trace=path, config=config) if config else TraceProcessor(trace=path)
        except Exception as e:  # noqa: BLE001
            print(f"  could not load: {e}")
            continue
        try:
            analyze(tp, args.package, args.limit)
        finally:
            tp.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
