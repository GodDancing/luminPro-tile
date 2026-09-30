"""
生成两份图标的矢量资源，并渲染预览图供人工核对。

为什么要用脚本生成而不是手写 pathData：
  1) 圆形若用「两点跨直径」的圆弧（M cx,cy-r A r,r 0 1,1 cx,cy+r ...）会落在
     弦长 = 2r 的退化情形上，不同渲染器的表现不一致。这里改用 4 段四分弧，
     每段弦长 r*sqrt(2) < 2r，不会退化。
  2) 自适应图标的内容必须落在 108x108 画布中央 66x66 的安全区内（半径 33），
     超出部分会被圆形遮罩切掉。第一版的光芒伸到半径 39，因此被裁。
     这里统一按半径参数推算，绝不再手算坐标。

坐标一律由 RAY_COUNT / 半径参数推导，改参数即可整体缩放。
"""

import math
import pathlib

OUT = pathlib.Path(__file__).resolve().parent.parent

# ── 几何参数 ────────────────────────────────────────────────────────────────
# 每个场景：(画布边长, 圆心, 太阳核心半径, 光芒内半径, 光芒外半径, 光芒半宽, 颜色)
# 关键约束：光芒外半径 ≤ 安全半径
#   自适应图标 108 画布 → 安全半径 33（66dp 安全区），留 4dp 余量 → 29
#   磁贴图标   24 画布 → 留出四周约 2.4dp 的呼吸空间 → 9.6
LAUNCHER = dict(size=108, cx=54.0, cy=54.0, core=16.0, ri=20.5, ro=29.0, hw=2.6,
                color="#FFFFC24B")
TILE = dict(size=24, cx=12.0, cy=12.0, core=5.2, ri=6.6, ro=9.6, hw=0.85,
            color="#FFFFFFFF")

RAY_COUNT = 8


def n(v):
    """格式化坐标，去掉多余的小数位。"""
    s = f"{v:.3f}".rstrip("0").rstrip(".")
    return s if s else "0"


def circle_path(cx, cy, r):
    """用 4 段四分弧画圆 —— 避免弦长等于直径的退化情形。"""
    return (f"M{n(cx)},{n(cy - r)} "
            f"A{n(r)},{n(r)} 0 0 1 {n(cx + r)},{n(cy)} "
            f"A{n(r)},{n(r)} 0 0 1 {n(cx)},{n(cy + r)} "
            f"A{n(r)},{n(r)} 0 0 1 {n(cx - r)},{n(cy)} "
            f"A{n(r)},{n(r)} 0 0 1 {n(cx)},{n(cy - r)} Z")


def ray_paths(cx, cy, ri, ro, hw, count=RAY_COUNT):
    """沿 count 个等分角向外发散的矩形光芒。"""
    out = []
    for i in range(count):
        a = math.radians(360.0 * i / count)
        dx, dy = math.cos(a), math.sin(a)
        px, py = -dy, dx  # 垂直于径向

        def pt(r, s):
            return (cx + dx * r + px * hw * s, cy + dy * r + py * hw * s)

        a1 = pt(ri, 1)
        b1 = pt(ro, 1)
        b2 = pt(ro, -1)
        a2 = pt(ri, -1)
        out.append(
            f"M{n(a1[0])},{n(a1[1])} L{n(b1[0])},{n(b1[1])} "
            f"L{n(b2[0])},{n(b2[1])} L{n(a2[0])},{n(a2[1])} Z")
    return out


def vector_xml(cfg):
    s = cfg["size"]
    paths = [circle_path(cfg["cx"], cfg["cy"], cfg["core"])]
    paths += ray_paths(cfg["cx"], cfg["cy"], cfg["ri"], cfg["ro"], cfg["hw"])
    body = "\n".join(
        f'    <path\n        android:fillColor="{cfg["color"]}"\n'
        f'        android:pathData="{p}" />' for p in paths)
    return (f'<?xml version="1.0" encoding="utf-8"?>\n'
            f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="{s}dp"\n'
            f'    android:height="{s}dp"\n'
            f'    android:viewportWidth="{s}"\n'
            f'    android:viewportHeight="{s}">\n'
            f'{body}\n'
            f'</vector>\n')


def write(path, cfg):
    (OUT / path).write_text(vector_xml(cfg), encoding="utf-8")
    print(f"已写入 {path}")


write("res/drawable/ic_tile.xml", TILE)
write("res/drawable/ic_launcher_foreground.xml", LAUNCHER)
print("预览与安全区核对见 compare_icons.py")
