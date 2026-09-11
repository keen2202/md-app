#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""墨阅 MoRead 启动图标生成器（唯一几何来源）。

设计概念「墨井」：
    Markdown 的标题标记 `#`，在中文排版里就叫「井字号」——即汉字「井」。
    「墨阅」= 墨（ink）+ 阅（read）：把 `#` 写成书法四笔的「井」，
    井心悬一滴未散的墨——一口以墨注满的井。

本脚本由同一份几何定义同时产出：
    app/src/main/res/drawable/ic_launcher_foreground.xml   前景（井 + 墨滴）
    app/src/main/res/drawable/ic_launcher_background.xml   背景（墨色渐变）
    app/src/main/res/drawable/ic_launcher_monochrome.xml   单色层（Android 13+ 主题图标）
    docs/brand/*.png                                       设计预览图

用法：
    python3 tools/gen_launcher_icon.py                # 生成 XML 资源 + 预览图
    python3 tools/gen_launcher_icon.py --preview      # 只生成预览图
    python3 tools/gen_launcher_icon.py --ascii        # 终端 ASCII 校对（无需看图）
    python3 tools/gen_launcher_icon.py --check        # 只做安全区/几何体检
    python3 tools/gen_launcher_icon.py --verify       # 校验 res 资源未被手改（CI 用，只读）

CI（ci/check_launcher_icon.sh）会依次跑 --check 与 --verify，
保证「改了设计源却忘了重出资源」或「直接手改 res」都会在流水线上失败。

依赖：Pillow（仅预览图需要；XML 生成与 --ascii/--check/--verify 只用标准库）。
"""

from __future__ import annotations

import argparse
import math
import os
from dataclasses import dataclass, field
from typing import Sequence

# ——————————————————————————————————————————————————————————————
# 画布与品牌色（与 res/values/colors.xml 对齐）
# ——————————————————————————————————————————————————————————————
VIEWPORT = 108.0          # adaptive icon 标准画布 108×108dp
SAFE_RADIUS = 33.0        # 圆形遮罩下保证可见的半径（66dp 安全区）
CENTER = (54.0, 54.0)

INK_DEEP = "#12141A"      # 墨黑渐变终点（夜）
INK_MID = "#1E222B"       # 墨黑渐变起点（夜）
PAPER = "#FAFAF7"         # 纸白（日间主题背景色，此处作笔画色）
ACCENT = "#7FA8D9"        # 强调蓝（夜间主题强调色，此处作墨滴色）

# ——————————————————————————————————————————————————————————————
# 几何：笔画 =（起点，终点，起笔宽，收笔宽），单位 = 108 画布坐标。
#   竖画微侧、横画右行略提——取手写书法感，不取字体几何感。
#   四笔围出的「井心」留给墨滴。
# ——————————————————————————————————————————————————————————————
STROKES: list[tuple[tuple[float, float], tuple[float, float], float, float]] = [
    # 左竖（丿）：起笔重，收锋轻
    ((42.60, 32.50), (40.60, 75.50), 8.00, 7.00),
    # 右竖（丨）：起笔顿，行笔匀
    ((68.40, 32.00), (67.60, 76.00), 8.20, 7.40),
    # 上横：右行微提
    ((33.00, 40.50), (75.00, 39.30), 7.40, 6.90),
    # 下横
    ((32.50, 66.00), (75.50, 64.80), 7.60, 7.10),
]

# 墨滴：悬在井心（上下净空各约 3，48dp 下约 1.3dp），尖端朝上
DROP_TIP = (55.00, 46.50)
DROP_CENTER = (55.00, 54.10)
DROP_RADIUS = 4.50


# ——————————————————————————————————————————————————————————————
# 极简路径 IR：Line / Arc / Cubic —— 同时驱动 XML 与 PNG 预览
# ——————————————————————————————————————————————————————————————
@dataclass(frozen=True)
class Line:
    p0: tuple[float, float]
    p1: tuple[float, float]


@dataclass(frozen=True)
class Arc:
    center: tuple[float, float]
    radius: float
    start: tuple[float, float]   # 弧起点（用于 XML 的 A 命令）
    end: tuple[float, float]     # 弧终点
    clockwise: bool              # 屏幕坐标（y 向下）下的扫掠方向

    @property
    def sweep_angle(self) -> float:
        """带符号的扫掠角：正 = 屏幕坐标下顺时针（角度增大）。"""
        a0 = math.atan2(self.start[1] - self.center[1], self.start[0] - self.center[0])
        a1 = math.atan2(self.end[1] - self.center[1], self.end[0] - self.center[0])
        delta = (a1 - a0) % (2 * math.pi)
        return delta if self.clockwise else delta - 2 * math.pi

    @property
    def large_arc(self) -> int:
        """弧 > 180° 时必须置 1，否则 SVG/VectorDrawable 会画成另一侧的劣弧。"""
        return 1 if abs(self.sweep_angle) > math.pi + 1e-9 else 0


@dataclass(frozen=True)
class Cubic:
    p0: tuple[float, float]
    c1: tuple[float, float]
    c2: tuple[float, float]
    p1: tuple[float, float]


Segment = Line | Arc | Cubic


@dataclass
class SubPath:
    start: tuple[float, float]
    segments: list[Segment] = field(default_factory=list)
    closed: bool = True


# ——————————————————————————————————————————————————————————————
# 基础几何工具
# ——————————————————————————————————————————————————————————————
def _unit(dx: float, dy: float) -> tuple[float, float]:
    length = math.hypot(dx, dy)
    if length == 0.0:
        raise ValueError("零长度向量")
    return dx / length, dy / length


def _offset(point: tuple[float, float], normal: tuple[float, float], amount: float):
    return (point[0] + normal[0] * amount, point[1] + normal[1] * amount)


def _cap_clockwise(center, forward, from_pt, to_pt) -> bool:
    """在屏幕坐标（y 向下）下，判断从 from_pt 到 to_pt 的半圆是否经过 forward 一侧。"""
    def ang(pt):
        return math.atan2(pt[1] - center[1], pt[0] - center[0])

    a_from, a_to = ang(from_pt), ang(to_pt)
    a_via = ang((center[0] + forward[0], center[1] + forward[1]))

    def sweep_cw(start, end):        # 顺时针（角度增大）扫过的角
        return (end - start) % (2 * math.pi)

    return sweep_cw(a_from, a_via) <= sweep_cw(a_from, a_to)


def stroke_outline(
    p0: tuple[float, float],
    p1: tuple[float, float],
    width0: float,
    width1: float,
) -> SubPath:
    """带圆头（笔锋）的笔画轮廓：梯形 + 两端半圆。"""
    d = _unit(p1[0] - p0[0], p1[1] - p0[1])
    n = (-d[1], d[0])                 # 左法线
    r0, r1 = width0 / 2.0, width1 / 2.0

    a = _offset(p0, n, r0)
    b = _offset(p1, n, r1)
    c = _offset(p1, n, -r1)
    dd = _offset(p0, n, -r0)

    cap_end = Arc(center=p1, radius=r1, start=b, end=c, clockwise=_cap_clockwise(p1, d, b, c))
    # 起笔端半圆朝反方向外凸（-d），否则会啃进笔画本体
    cap_start = Arc(center=p0, radius=r0, start=dd, end=a,
                    clockwise=_cap_clockwise(p0, (-d[0], -d[1]), dd, a))

    return SubPath(start=a, segments=[Line(a, b), cap_end, Line(c, dd), cap_start], closed=True)


def drop_outline(tip, center, radius) -> SubPath:
    """墨滴：自尖端引圆的两条切线（与圆相切即天然 C1 连续）+ 底部圆弧。"""
    cx, cy = center
    tx, ty = tip
    lift = cy - ty
    if lift <= radius:
        raise ValueError("墨滴尖端需高于圆心至少一个半径")

    sin_a = radius / lift
    cos_a = math.sqrt(1.0 - sin_a * sin_a)
    right_t = (cx + radius * sin_a, cy - radius * cos_a)
    left_t = (cx - radius * sin_a, cy - radius * cos_a)

    bottom = Arc(center=center, radius=radius, start=right_t, end=left_t, clockwise=True)

    return SubPath(
        start=tip,
        segments=[Line(tip, right_t), bottom, Line(left_t, tip)],
        closed=True,
    )


def glyph_paths() -> list[SubPath]:
    return [stroke_outline(*spec) for spec in STROKES] + [
        drop_outline(DROP_TIP, DROP_CENTER, DROP_RADIUS)
    ]


def stroke_paths() -> list[SubPath]:
    return [stroke_outline(*spec) for spec in STROKES]


def drop_path() -> SubPath:
    return drop_outline(DROP_TIP, DROP_CENTER, DROP_RADIUS)


# ——————————————————————————————————————————————————————————————
# 路径 → VectorDrawable pathData
# ——————————————————————————————————————————————————————————————
def to_path_data(subpaths: Sequence[SubPath]) -> str:
    out: list[str] = []
    for sub in subpaths:
        out.append(f"M{sub.start[0]:.2f},{sub.start[1]:.2f}")
        for seg in sub.segments:
            if isinstance(seg, Line):
                out.append(f"L{seg.p1[0]:.2f},{seg.p1[1]:.2f}")
            elif isinstance(seg, Arc):
                sweep = 1 if seg.clockwise else 0
                out.append(
                    f"A{seg.radius:.2f},{seg.radius:.2f} 0 {seg.large_arc} {sweep} "
                    f"{seg.end[0]:.2f},{seg.end[1]:.2f}"
                )
            elif isinstance(seg, Cubic):
                out.append(
                    f"C{seg.c1[0]:.2f},{seg.c1[1]:.2f} "
                    f"{seg.c2[0]:.2f},{seg.c2[1]:.2f} {seg.p1[0]:.2f},{seg.p1[1]:.2f}"
                )
        if sub.closed:
            out.append("Z")
    return "".join(out)


# ——————————————————————————————————————————————————————————————
# 输出 XML
# ——————————————————————————————————————————————————————————————
VECTOR_HEAD = (
    '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
    '    android:width="108dp"\n'
    '    android:height="108dp"\n'
    '    android:viewportWidth="108"\n'
    '    android:viewportHeight="108">\n'
)


def foreground_xml() -> str:
    body = (
        "    <!-- 井（Markdown 标题标记 # 的中文名「井字号」）四笔：纸白。 -->\n"
        f'    <path\n        android:fillColor="{PAPER}"\n'
        f'        android:pathData="{to_path_data(stroke_paths())}" />\n\n'
        "    <!-- 井心一滴墨：强调蓝，全图唯一的高饱和色，也是本图标的记忆点。 -->\n"
        f'    <path\n        android:fillColor="{ACCENT}"\n'
        f'        android:pathData="{to_path_data([drop_path()])}" />\n'
    )
    return VECTOR_HEAD + body + "</vector>\n"


def monochrome_xml() -> str:
    """Android 13+ 主题图标单色层：系统自行着色，此处只保留剪影。"""
    body = (
        f'    <path\n        android:fillColor="#FFFFFF"\n'
        f'        android:pathData="{to_path_data(glyph_paths())}" />\n'
    )
    return VECTOR_HEAD + body + "</vector>\n"


def background_xml() -> str:
    body = (
        "    <!-- 墨底：墨蓝灰 → 墨黑的对角渐变，给纸白笔画留一块沉静的底。 -->\n"
        "    <path android:pathData=\"M0,0h108v108h-108z\">\n"
        "        <aapt:attr name=\"android:fillColor\">\n"
        '            <gradient\n'
        '                android:type="linear"\n'
        '                android:startX="0"\n'
        '                android:startY="0"\n'
        '                android:endX="108"\n'
        '                android:endY="108"\n'
        f'                android:startColor="{INK_MID}"\n'
        f'                android:endColor="{INK_DEEP}" />\n'
        "        </aapt:attr>\n"
        "    </path>\n"
    )
    return (
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    xmlns:aapt="http://schemas.android.com/aapt"\n'
        '    android:width="108dp"\n'
        '    android:height="108dp"\n'
        '    android:viewportWidth="108"\n'
        '    android:viewportHeight="108">\n'
        + body
        + "</vector>\n"
    )


ADAPTIVE_XML = """<?xml version="1.0" encoding="utf-8"?>
<!-- 自适应图标：墨底 + 井字四笔 + 井心墨滴；Android 13+ 附带单色层。 -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
"""


# ——————————————————————————————————————————————————————————————
# 几何体检：安全区、墨滴净空
# ——————————————————————————————————————————————————————————————
def flatten(subpaths: Sequence[SubPath], steps: int = 24) -> list[list[tuple[float, float]]]:
    polys: list[list[tuple[float, float]]] = []
    for sub in subpaths:
        pts: list[tuple[float, float]] = [sub.start]
        for seg in sub.segments:
            if isinstance(seg, Line):
                pts.append(seg.p1)
            elif isinstance(seg, Cubic):
                for i in range(1, steps + 1):
                    t = i / steps
                    mt = 1 - t
                    x = (mt ** 3) * seg.p0[0] + 3 * (mt ** 2) * t * seg.c1[0] \
                        + 3 * mt * (t ** 2) * seg.c2[0] + (t ** 3) * seg.p1[0]
                    y = (mt ** 3) * seg.p0[1] + 3 * (mt ** 2) * t * seg.c1[1] \
                        + 3 * mt * (t ** 2) * seg.c2[1] + (t ** 3) * seg.p1[1]
                    pts.append((x, y))
            elif isinstance(seg, Arc):
                a0 = math.atan2(seg.start[1] - seg.center[1], seg.start[0] - seg.center[0])
                span = seg.sweep_angle
                for i in range(1, steps + 1):
                    a = a0 + span * (i / steps)
                    pts.append((
                        seg.center[0] + seg.radius * math.cos(a),
                        seg.center[1] + seg.radius * math.sin(a),
                    ))
        polys.append(pts)
    return polys


def _point_seg_distance(p, a, b) -> float:
    ax, ay = a
    bx, by = b
    dx, dy = bx - ax, by - ay
    denom = dx * dx + dy * dy
    t = 0.0 if denom == 0 else max(0.0, min(1.0, ((p[0] - ax) * dx + (p[1] - ay) * dy) / denom))
    return math.hypot(p[0] - (ax + t * dx), p[1] - (ay + t * dy))


def verify_path_data(tolerance: float = 0.05) -> list[str]:
    """把已生成的 pathData 字符串按 SVG 语义重新解析、展平，与几何 IR 比对。

    专门用于验证圆弧的 largeArc/sweep 标志：标志写错时圆弧会朝反方向鼓出，
    比对误差会立刻暴露（而不是等到装机才看出来）。
    """
    import re

    token = re.compile(r"([MLACZ])([^MLACZ]*)")
    numbers = re.compile(r"-?\d+(?:\.\d+)?")
    problems: list[str] = []

    for name, subpaths in (("井字四笔", stroke_paths()), ("墨滴", [drop_path()])):
        data = to_path_data(subpaths)
        parsed: list[list[tuple[float, float]]] = []
        for cmd, raw in token.findall(data):
            vals = [float(v) for v in numbers.findall(raw)]
            if cmd == "M":
                parsed.append([(vals[0], vals[1])])
            elif cmd == "L":
                parsed[-1].append((vals[0], vals[1]))
            elif cmd == "C":
                p0 = parsed[-1][-1]
                c1, c2, p1 = (vals[0], vals[1]), (vals[2], vals[3]), (vals[4], vals[5])
                for i in range(1, 25):
                    t = i / 24
                    mt = 1 - t
                    parsed[-1].append((
                        mt ** 3 * p0[0] + 3 * mt ** 2 * t * c1[0] + 3 * mt * t ** 2 * c2[0] + t ** 3 * p1[0],
                        mt ** 3 * p0[1] + 3 * mt ** 2 * t * c1[1] + 3 * mt * t ** 2 * c2[1] + t ** 3 * p1[1],
                    ))
            elif cmd == "A":
                rx, ry, _rot, large, sweep = vals[0], vals[1], vals[2], int(vals[3]), int(vals[4])
                end = (vals[5], vals[6])
                start = parsed[-1][-1]
                for pt in _svg_arc_points(start, end, rx, ry, large, sweep, steps=48):
                    parsed[-1].append(pt)
            # Z：闭合，无需采样

        reference = flatten(subpaths, steps=48)
        # 参考折线是闭合环；解析结果不含闭合段，只比对采样点集合的单向距离
        worst = 0.0
        for poly in parsed:
            for pt in poly:
                best = min(
                    _point_seg_distance(pt, ref[i], ref[(i + 1) % len(ref)])
                    for ref in reference for i in range(len(ref))
                )
                worst = max(worst, best)
        status = "OK" if worst <= tolerance else "不一致"
        problems.append(f"pathData 回读校验（{name}）：最大偏差 {worst:.4f} → {status}")

    return problems


def _svg_arc_points(start, end, rx, ry, large, sweep, steps: int = 48):
    """SVG 端点式圆弧 → 中心式参数，用于独立复算（与 SVG/VectorDrawable 语义一致）。"""
    x1, y1 = start
    x2, y2 = end
    if rx == 0 or ry == 0 or (x1 == x2 and y1 == y2):
        return [(x2, y2)]

    # 端点参数 → 中心参数（F.6.5）
    dx2, dy2 = (x1 - x2) / 2.0, (y1 - y2) / 2.0
    lam = (dx2 * dx2) / (rx * rx) + (dy2 * dy2) / (ry * ry)
    if lam > 1.0:
        scale = math.sqrt(lam)
        rx, ry = rx * scale, ry * scale
    num = rx * rx * ry * ry - rx * rx * dy2 * dy2 - ry * ry * dx2 * dx2
    den = rx * rx * dy2 * dy2 + ry * ry * dx2 * dx2
    factor = math.sqrt(max(0.0, num / den)) if den else 0.0
    if large == sweep:
        factor = -factor
    cxp = factor * rx * dy2 / ry
    cyp = -factor * ry * dx2 / rx
    cx = cxp + (x1 + x2) / 2.0
    cy = cyp + (y1 + y2) / 2.0

    def angle(ux, uy, vx, vy):
        dot = ux * vx + uy * vy
        norm = math.hypot(ux, uy) * math.hypot(vx, vy)
        val = max(-1.0, min(1.0, dot / norm if norm else 1.0))
        ang = math.acos(val)
        return -ang if (ux * vy - uy * vx) < 0 else ang

    theta1 = angle(1, 0, (dx2 - cxp) / rx, (dy2 - cyp) / ry)
    dtheta = angle((dx2 - cxp) / rx, (dy2 - cyp) / ry, (-dx2 - cxp) / rx, (-dy2 - cyp) / ry)
    if sweep == 0 and dtheta > 0:
        dtheta -= 2 * math.pi
    elif sweep == 1 and dtheta < 0:
        dtheta += 2 * math.pi

    return [
        (cx + rx * math.cos(theta1 + dtheta * i / steps),
         cy + ry * math.sin(theta1 + dtheta * i / steps))
        for i in range(1, steps + 1)
    ]


def drop_clearance() -> float:
    """墨滴轮廓到井字四笔的最小距离（>0 即不相接）。"""
    drop_pts = flatten([drop_path()], steps=48)[0]
    stroke_polys = flatten(stroke_paths(), steps=24)
    return min(
        min(_point_seg_distance(p, poly[i], poly[(i + 1) % len(poly)])
            for poly in stroke_polys for i in range(len(poly)))
        for p in drop_pts
    )


def geometry_report() -> list[str]:
    """输出可核对的几何指标：安全区越界、墨滴净空、笔画粗细。"""
    lines: list[str] = []
    all_pts = [pt for poly in flatten(glyph_paths()) for pt in poly]
    far = max(all_pts, key=lambda p: math.hypot(p[0] - CENTER[0], p[1] - CENTER[1]))
    radius = math.hypot(far[0] - CENTER[0], far[1] - CENTER[1])
    lines.append(f"最大半径 {radius:.2f} / 安全半径 {SAFE_RADIUS:.1f}"
                 f"（余量 {SAFE_RADIUS - radius:+.2f}）  最远点 {far[0]:.1f},{far[1]:.1f}")

    xs = [p[0] for p in all_pts]
    ys = [p[1] for p in all_pts]
    lines.append(f"内容包围盒 x {min(xs):.1f}–{max(xs):.1f}（宽 {max(xs) - min(xs):.1f}）"
                 f"  y {min(ys):.1f}–{max(ys):.1f}（高 {max(ys) - min(ys):.1f}）")

    min_clear = drop_clearance()
    lines.append(f"墨滴与笔画最小净空 {min_clear:.2f}（>0 即不相接）")

    widths = [w for _, _, w, _ in STROKES]
    lines.append(f"笔画宽度 {min(widths):.1f}–{max(widths):.1f}"
                 f"（48dp 下约 {min(widths) / VIEWPORT * 48:.1f}–{max(widths) / VIEWPORT * 48:.1f}dp）")
    return lines


MIN_SAFE_MARGIN = 1.0      # 安全区余量下限（108 画布坐标）
MIN_DROP_CLEARANCE = 1.0   # 墨滴与笔画净空下限（>0 即不相接，此处留 1 的工程余量）


def hard_failures() -> list[str]:
    """硬指标断言：返回未通过项（空列表 = 全部通过）。

    体检报表是给人看的，退出码才是给 CI 看的：几何越界、墨滴贴笔、
    pathData 回读超差都必须让流水线失败，而不是只打印一行「不一致」。
    """
    failures: list[str] = []
    all_pts = [pt for poly in flatten(glyph_paths()) for pt in poly]
    radius = max(math.hypot(p[0] - CENTER[0], p[1] - CENTER[1]) for p in all_pts)
    if SAFE_RADIUS - radius < MIN_SAFE_MARGIN:
        failures.append(f"安全区余量不足：{SAFE_RADIUS - radius:+.2f} < {MIN_SAFE_MARGIN:.2f}")

    clearance = drop_clearance()
    if clearance < MIN_DROP_CLEARANCE:
        failures.append(f"墨滴与笔画净空不足：{clearance:.2f} < {MIN_DROP_CLEARANCE:.2f}")

    for line in verify_path_data():
        if "→ OK" not in line:
            failures.append(f"pathData 回读超差：{line}")
    return failures


def enforce_hard_metrics() -> None:
    """硬指标不通过即以退出码 1 结束（--check / --verify 用）。"""
    failures = hard_failures()
    if failures:
        print("硬指标未通过：")
        for item in failures:
            print(f"  · {item}")
        raise SystemExit(1)


# ——————————————————————————————————————————————————————————————
# PNG 预览（Pillow，4× 超采样）
# ——————————————————————————————————————————————————————————————
def _hex(color: str) -> tuple[int, int, int]:
    color = color.lstrip("#")
    return tuple(int(color[i:i + 2], 16) for i in (0, 2, 4))  # type: ignore[return-value]


def _linear_gradient(size: int, c0, c1, supersample: int):
    from PIL import Image

    grad = Image.new("RGB", (size, size))
    px = grad.load()
    for y in range(size):
        for x in range(size):
            t = (x + y) / (2 * (size - 1))
            px[x, y] = tuple(int(c0[i] + (c1[i] - c0[i]) * t) for i in range(3))
    return grad.resize((size * supersample, size * supersample), Image.BICUBIC)


def render_icon(size: int, supersample: int = 4, paper: bool = False):
    """渲染整枚图标（墨底 + 井 + 墨滴）。

    paper=True 时输出「纸白底 / 墨字」的备选配色（与日间主题一致），
    仅用于预览对比；正式资源采用墨底版本。
    """
    from PIL import Image, ImageDraw

    if paper:
        bg_from, bg_to = _hex("#FFFFFF"), _hex("#F2F0E9")
        stroke_color, drop_color = _hex("#2B2B2B"), _hex("#3A6EA5")
    else:
        bg_from, bg_to = _hex(INK_MID), _hex(INK_DEEP)
        stroke_color, drop_color = _hex(PAPER), _hex(ACCENT)

    big = size * supersample
    scale = big / VIEWPORT
    canvas = _linear_gradient(size, bg_from, bg_to, supersample).convert("RGB")
    draw = ImageDraw.Draw(canvas)

    def scaled(polys):
        return [[(x * scale, y * scale) for x, y in poly] for poly in polys]

    for poly in scaled(flatten(stroke_paths())):
        draw.polygon(poly, fill=stroke_color)
    for poly in scaled(flatten([drop_path()])):
        draw.polygon(poly, fill=drop_color)

    return canvas.resize((size, size), Image.LANCZOS)


def _masked(img, size: int, kind: str):
    from PIL import Image, ImageDraw

    ss = 4
    mask = Image.new("L", (size * ss, size * ss), 0)
    d = ImageDraw.Draw(mask)
    box = [0, 0, size * ss - 1, size * ss - 1]
    if kind == "circle":
        d.ellipse(box, fill=255)
    elif kind == "squircle":
        d.rounded_rectangle(box, radius=int(size * ss * 0.30), fill=255)
    else:
        d.rounded_rectangle(box, radius=int(size * ss * 0.18), fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out


def write_previews(out_dir: str) -> list[str]:
    from PIL import Image, ImageDraw, ImageFont

    os.makedirs(out_dir, exist_ok=True)
    written: list[str] = []

    try:
        font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 20)
        font_s = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 16)
    except OSError:
        font = font_s = ImageFont.load_default()

    # 1. 主图 512×512（应用商店主视觉）
    master = render_icon(512)
    path = os.path.join(out_dir, "icon-512.png")
    master.save(path)
    written.append(path)

    # 2. 三种遮罩下的观感
    sheet = Image.new("RGB", (216 * 3 + 80, 216 + 96), (247, 246, 241))
    d = ImageDraw.Draw(sheet)
    for i, kind in enumerate(("circle", "squircle", "rounded")):
        tile = _masked(render_icon(216), 216, kind)
        x = 20 + i * (216 + 20)
        sheet.paste(tile, (x, 20), tile)
        d.text((x + 4, 216 + 40), kind, fill=(60, 60, 60), font=font)
    path = os.path.join(out_dir, "icon-masks.png")
    sheet.save(path)
    written.append(path)

    # 3. 尺寸阶梯：48 / 32 / 24 / 16（启动器实际观感）
    ladder = Image.new("RGB", (48 + 32 + 24 + 16 + 6 * 20 + 40, 150), (247, 246, 241))
    d = ImageDraw.Draw(ladder)
    x = 20
    for size in (48, 32, 24, 16):
        icon = _masked(render_icon(size), size, "squircle")
        ladder.paste(icon, (x, 60 - size // 2), icon)
        d.text((x - 2, 108), f"{size}", fill=(90, 90, 90), font=font_s)
        x += size + 20
    path = os.path.join(out_dir, "icon-sizes.png")
    ladder.save(path)
    written.append(path)

    # 4. 浅色壁纸 / 深色壁纸对比
    bg = Image.new("RGB", (256 * 2 + 60, 300), (238, 237, 232))
    d = ImageDraw.Draw(bg)
    d.rectangle([256 + 20, 0, 256 * 2 + 59, 299], fill=(20, 21, 26))
    for i in range(2):
        tile = _masked(render_icon(220), 220, "squircle")
        bg.paste(tile, (18 + i * 256, 40), tile)
    path = os.path.join(out_dir, "icon-on-wallpaper.png")
    bg.save(path)
    written.append(path)

    # 5. 单色层（Android 13+ 主题图标）示意
    mono = Image.new("RGB", (300, 300), (232, 234, 237))
    tile = render_icon(220)
    src = tile.load()
    for y in range(tile.height):
        for x in range(tile.width):
            r, g, b = src[x, y]
            lit = (r + g + b) / 3 > 110          # 亮于阈值即视为笔画，统一染成主题色
            src[x, y] = (58, 110, 165) if lit else (232, 234, 237)
    mono.paste(tile, (40, 40))
    path = os.path.join(out_dir, "icon-monochrome.png")
    mono.save(path)
    written.append(path)

    # 6. 备选配色：纸白底 / 墨字（与日间主题一致，仅供对比）
    pair = Image.new("RGB", (220 * 2 + 60, 280), (247, 246, 241))
    d = ImageDraw.Draw(pair)
    for i, is_paper in enumerate((False, True)):
        tile = _masked(render_icon(220, paper=is_paper), 220, "squircle")
        pair.paste(tile, (20 + i * 240, 20), tile)
        d.text((24 + i * 240, 248), "ink (默认)" if not is_paper else "paper (备选)",
               fill=(70, 70, 70), font=font_s)
    path = os.path.join(out_dir, "icon-variants.png")
    pair.save(path)
    written.append(path)

    return written


# ——————————————————————————————————————————————————————————————
def ascii_preview(width: int = 58) -> None:
    """终端 ASCII 校对：无需看图即可检查字形与留白。"""
    from PIL import Image

    img = render_icon(432, supersample=3).convert("L")
    height = int(img.height / img.width * width)
    small = img.resize((width, height), Image.LANCZOS)
    px = small.load()
    for y in range(height):
        row = ""
        for x in range(width):
            v = px[x, y] / 255.0
            row += "#" if v > 0.45 else ("+" if v > 0.10 else ".")
        print(row)
    print(f"（ASCII {width}×{height}，'#' = 笔画/墨滴，'+' = 墨底，'.' = 更深的墨底）")


def verify_targets(root: str, targets: dict[str, str]) -> int:
    """校验磁盘上的资源与设计源当前输出是否逐字节一致（CI 用，不写文件）。

    返回不一致（含缺失）的文件数，0 表示 res 未偏离设计源。
    只覆盖 XML 资源：预览 PNG 的栅格化结果依赖 Pillow 版本与系统字体，
    允许存在肉眼不可辨的字节差异，故不参与一致性断言。
    """
    stale = 0
    for path, expected in targets.items():
        rel = os.path.relpath(path, root)
        if not os.path.exists(path):
            print(f"  · 缺失 {rel}")
            stale += 1
            continue
        with open(path, encoding="utf-8") as fh:
            actual = fh.read()
        if actual == expected:
            print(f"  · 一致 {rel}")
        else:
            print(f"  · 不一致 {rel}")
            stale += 1
    return stale


def main() -> None:
    parser = argparse.ArgumentParser(description="生成墨阅 MoRead 启动图标资源与预览图")
    parser.add_argument("--preview", action="store_true", help="只生成预览图，不改动 res/")
    parser.add_argument("--ascii", action="store_true", help="终端 ASCII 校对")
    parser.add_argument("--check", action="store_true", help="只做几何体检")
    parser.add_argument("--verify", action="store_true",
                        help="校验 res 资源与设计源逐字节一致（只读，CI 用）")
    parser.add_argument("--preview-dir", default=os.path.join("docs", "brand"))
    args = parser.parse_args()

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    res = os.path.join(root, "app", "src", "main", "res")

    # 设计源当前输出：路径 → 文件内容。写入与校验共用同一份，避免两处漂移。
    targets = {
        os.path.join(res, "drawable", "ic_launcher_foreground.xml"): foreground_xml(),
        os.path.join(res, "drawable", "ic_launcher_background.xml"): background_xml(),
        os.path.join(res, "drawable", "ic_launcher_monochrome.xml"): monochrome_xml(),
        os.path.join(res, "mipmap-anydpi-v26", "ic_launcher.xml"): ADAPTIVE_XML,
        os.path.join(res, "mipmap-anydpi-v26", "ic_launcher_round.xml"): ADAPTIVE_XML,
    }

    # --verify 只报「一致性」结论：几何数字已由 CI 上一步的 --check 打印过，
    # 硬指标不通过时 enforce_hard_metrics() 会自己把失败原因写清楚。
    if not args.verify:
        print("几何体检：")
        for line in geometry_report():
            print(f"  · {line}")
        for line in verify_path_data():
            print(f"  · {line}")

    if args.check:
        enforce_hard_metrics()
        return

    if args.verify:
        enforce_hard_metrics()
        print("资源一致性校验（res ← 设计源）：")
        stale = verify_targets(root, targets)
        if stale:
            print(f"校验失败：{stale} 个资源与设计源不一致；"
                  f"运行 python3 tools/gen_launcher_icon.py 重新生成。")
            raise SystemExit(1)
        print(f"校验通过：{len(targets)} 个图标资源与设计源逐字节一致。")
        return

    if not args.preview:
        for path, content in targets.items():
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(content)
            print(f"写入 {os.path.relpath(path, root)}")

    if args.ascii:
        ascii_preview()

    for path in write_previews(os.path.join(root, args.preview_dir)):
        print(f"写入 {os.path.relpath(path, root)}")


if __name__ == "__main__":
    main()
