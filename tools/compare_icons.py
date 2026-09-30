"""渲染图标修复前后的对比图，供人工核对。"""
import pathlib
import re
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent

# ── 修复前的路径（第一版手写坐标）──────────────────────────────────────────
OLD_TILE = [
    "M12,6.6 A5.4,5.4 0 1,1 12,17.4 A5.4,5.4 0 1,1 12,6.6 Z",
    "M11,0.6h2v4.2h-2z M11,19.2h2v4.2h-2z M0.6,11h4.2v2H0.6z M19.2,11h4.2v2h-4.2z",
    "M3.4,4.8l1.4,-1.4 3,3 -1.4,1.4z M16.2,17.6l1.4,-1.4 3,3 -1.4,1.4z"
    " M17.6,3.4l1.4,1.4 -3,3 -1.4,-1.4z M4.8,16.2l1.4,1.4 -3,3 -1.4,-1.4z",
]
OLD_FG = [
    "M54,33 A21,21 0 1,1 54,75 A21,21 0 1,1 54,33 Z",
    "M51,15h6v13h-6z M51,80h6v13h-6z M15,51h13v6H15z M80,51h13v6H80z",
    "M24.5,27.1l4.2,-4.2 8.5,8.5 -4.2,4.2z M70.8,73.4l4.2,-4.2 8.5,8.5 -4.2,4.2z"
    " M74,22.9l4.2,4.2 -8.5,8.5 -4.2,-4.2z M27.1,75.3l4.2,4.2 -8.5,8.5 -4.2,-4.2z",
]


def read_paths(rel):
    txt = (ROOT / rel).read_text(encoding="utf-8")
    return re.findall(r'android:pathData="([^"]+)"', txt)


NEW_TILE = read_paths("res/drawable/ic_tile.xml")
NEW_FG = read_paths("res/drawable/ic_launcher_foreground.xml")


def svg(paths, view, px, color, mask_r=None, guide_r=None, bg=None):
    body = "".join(f'<path d="{p}" fill="{color}"/>' for p in paths)
    clip = ""
    if mask_r:
        clip = (f'<defs><clipPath id="c{id(paths)}{px}">'
                f'<circle cx="{view/2}" cy="{view/2}" r="{mask_r}"/>'
                f'</clipPath></defs>')
        body = f'<g clip-path="url(#c{id(paths)}{px})">{body}</g>'
    back = f'<rect width="{view}" height="{view}" fill="{bg}"/>' if bg else ""
    guide = (f'<circle cx="{view/2}" cy="{view/2}" r="{guide_r}" fill="none" '
             f'stroke="#f87171" stroke-width="{view/300}" '
             f'stroke-dasharray="{view/40},{view/60}"/>') if guide_r else ""
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{px}" height="{px}" '
            f'viewBox="0 0 {view} {view}">{clip}{back}{body}{guide}</svg>')


CARD = "#0B1020"          # 模拟启动器的深色背景
TILE_BG = "#20242e"       # 模拟快捷设置面板底色

html = f"""<!doctype html><meta charset="utf-8">
<style>
 body{{background:#0e1220;color:#dde3f5;font:13px/1.6 system-ui;margin:0;padding:24px;
      display:flex;gap:40px;align-items:flex-start}}
 .col{{text-align:center}}
 .h{{font-size:14px;font-weight:700;margin-bottom:14px}}
 .bad{{color:#f87171}} .good{{color:#4ade80}}
 .row{{display:flex;gap:18px;align-items:flex-end;justify-content:center}}
 .it{{text-align:center}} .cap{{margin-top:8px;font-size:11px;color:#8f9ac0}}
 .vline{{width:1px;align-self:stretch;background:#2b3350}}
</style>

<div class="col">
  <div class="h bad">修复前</div>
  <div class="row">
    <div class="it">{svg(OLD_TILE, 24, 120, "#FFFFFF", bg=TILE_BG)}
      <div class="cap">磁贴 24dp</div></div>
    <div class="it">{svg(OLD_FG, 108, 120, "#FFC24B", mask_r=36, bg=CARD)}
      <div class="cap">应用图标</div></div>
  </div>
</div>

<div class="vline"></div>

<div class="col">
  <div class="h good">修复后</div>
  <div class="row">
    <div class="it">{svg(NEW_TILE, 24, 120, "#FFFFFF", bg=TILE_BG)}
      <div class="cap">磁贴 24dp</div></div>
    <div class="it">{svg(NEW_FG, 108, 120, "#FFC24B", mask_r=36, bg=CARD)}
      <div class="cap">应用图标</div></div>
  </div>
</div>

<div class="vline"></div>

<div class="col">
  <div class="h">应用图标 · 安全区校验</div>
  <div class="row">
    <div class="it">{svg(OLD_FG, 108, 150, "#FFC24B", guide_r=33, bg="#151b2e")}
      <div class="cap bad">光芒超出虚线（66dp 安全区）</div></div>
    <div class="it">{svg(NEW_FG, 108, 150, "#FFC24B", guide_r=33, bg="#151b2e")}
      <div class="cap good">完全落在安全区内</div></div>
  </div>
</div>
"""

(HERE / "icons-compare.html").write_text(html, encoding="utf-8")
print("已写入 tools/icons-compare.html")
