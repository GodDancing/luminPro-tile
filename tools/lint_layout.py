"""
布局静态检查 —— 拦住「打开即闪退」这类构建期能发现、编译期不报错的错误。

目前检查一项：每个 View 是否都带 android:layout_width 与 android:layout_height。
缺失时 Android 会在 inflate 阶段抛
    RuntimeException: Binary XML file line #N: You must supply a layout_width attribute.
整个 Activity 直接崩掉，而 aapt2 链接阶段完全不会报错 —— 所以必须单独检查。

注意：样式（style）里写 layout_* 是能生效的，但本检查只看元素自身与它引用的样式链，
避免依赖那个容易让人误解的行为。

用法: python tools/lint_layout.py      退出码非 0 表示有问题
"""

import pathlib
import sys
import xml.etree.ElementTree as ET

# Windows 控制台默认是 GBK，直接打印 ✗ 这类字符会抛 UnicodeEncodeError ——
# 偏偏是在「真的发现问题、正要报告」的时候崩掉，等于检查器失效。
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

A = "{http://schemas.android.com/apk/res/android}"
ROOT = pathlib.Path(__file__).resolve().parent.parent
VALUES = ROOT / "res" / "values"


def load_styles():
    """style 名 -> 该 style 及其所有祖先定义的属性集合。"""
    styles = {}
    for f in VALUES.glob("*.xml"):
        try:
            tree = ET.parse(f)
        except ET.ParseError as e:
            print(f"  ! {f.name} 解析失败: {e}")
            continue
        for st in tree.iter("style"):
            name = st.get("name")
            if not name:
                continue
            attrs = {it.get("name") for it in st if it.tag == "item"}
            styles[name] = (st.get("parent"), attrs)

    # 把 parent 链上的属性并进来
    resolved = {}

    def gather(name, seen):
        if name in resolved:
            return resolved[name]
        if name in seen or name not in styles:
            return set()
        seen.add(name)
        parent, attrs = styles[name]
        out = set(attrs)
        if parent:
            # @android:style/... 这类平台样式不解析，只看项目内自定义样式
            out |= gather(parent.split("/")[-1], seen)
        resolved[name] = out
        return out

    for name in list(styles):
        gather(name, set())
    return resolved


def main():
    styles = load_styles()
    problems = 0
    checked = 0

    for layout in sorted((ROOT / "res" / "layout").glob("*.xml")):
        tree = ET.parse(layout)
        for el in tree.iter():
            if not isinstance(el.tag, str):
                continue
            checked += 1
            have = {A + "layout_width", A + "layout_height"}

            # 元素自身的属性
            own = set(el.keys())
            # 加上它引用的 style 链提供的属性
            style_ref = el.get("style")
            inherited = set()
            if style_ref:
                inherited = styles.get(style_ref.split("/")[-1], set())

            def present(attr):
                short = attr.replace(A, "")
                return attr in own or ("android:" + short) in inherited

            missing = [a.replace(A, "android:") for a in sorted(have)
                       if not present(a)]
            if missing:
                el_id = el.get(A + "id") or "(无 id)"
                print(f"  ✗ {layout.name}: <{el.tag}> id={el_id} 缺少 {'、'.join(missing)}")
                problems += 1

    print(f"布局检查: 扫描 {checked} 个元素，发现问题 {problems} 个")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
