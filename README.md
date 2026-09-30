# LuminPro 磁贴 (LuminPro Tile)

LuminPro 模块的**快捷设置磁贴扩展**：点一下磁贴就把屏幕亮度锁定到你指定的值，再点一下解除并恢复原亮度。

面向 **LuminPro V2.5.0 正式版**（`versionCode 2050090`）编写，与模块共用同一套亮度节点和配置。

- 包名 `com.luminpro.tile`，与模块 `LuminPro` 完全独立，可单独安装 / 卸载
- 不修改模块的守护进程逻辑；模块没装或不可用时自动降级为 root 直写
- 无 AndroidX 依赖，APK 约 45 KB

---

## 三种工作模式

应用启动 / 每次操作前会探测一次，按下面的优先级选路：

| 模式 | 触发条件 | 亮度写入方式 |
| --- | --- | --- |
| **模块模式** | `bin/luminpro` 存在且可执行，`status` 能返回 JSON | 走模块的 `luminpro brightness set`，范围校验与操作锁由模块负责 |
| **Root 直写** | 模块缺失、被停用，或二进制不可用 | 自行扫描 `/sys/class/backlight/*`，用 root 直接读写节点 |
| **未就绪** | 拿不到 root，或找不到可用亮度节点 | 不写入，界面提示原因 |

界面上方的徽标会实时显示当前是哪种模式。

### 节点是怎么选的

- **模块模式**：直接采用模块配置里的 `now_bri_file` / `max_bri_file`，保证两边操作同一个文件。
- **Root 直写**：扫描 `/sys/class/backlight/*` 与 `/sys/class/leds/lcd-backlight`，
  优先取路径含 `panel0` 的（通常是主屏面板），其余按量程从大到小排序。

---

## 与模块守护进程的共存（关键设计）

模块的守护进程在亮度节点发生变化时会做一次判定，触发条件是：

```go
// go/internal/policy/policy.go
func ShouldBoost(nowBri, uiMax, target int) bool {
    return nowBri >= uiMax && nowBri < target   // target 即配置里的 max_bri
}
```

也就是说，**只要磁贴目标亮度低于模块的 `max_bri`，模块就会把我们刚写下去的亮度再拉回峰值**，
两边来回覆盖。

应用的处理办法：模块模式下，如果磁贴亮度低于模块当前的 `max_bri`，就先把模块的 `max_bri`
同步为磁贴亮度（记住原值），此时 `now < max_bri` 恒不成立，守护进程自然让位。解锁时按
**先恢复亮度、再还原 `max_bri`** 的顺序复原 —— 顺序反过来的话亮度仍在峰值，还原 `max_bri`
会立刻触发模块自己的提升。

这个同步可以在界面上用「同步模块峰值亮度」关掉。关掉后若模块已校准过，两边确实会互相覆盖，
日志里会有提示。

> 模块未校准（`max_bri` 为 0）时不做任何同步 —— 这种情况下 `evaluate()` 本来就直接返回，
> 守护进程不会动作。

---

## 「锁定」的含义

默认采用和模块 `boost.sh` 一致的做法：**写一次即可长期有效**。系统在用户不主动调亮度、
未开自动亮度时不会回写亮度节点，所以一次写入就能一直保持。

以下两种情况需要额外开启「持续保持」：

- 开了**自动亮度**：环境光一变，系统就会改写亮度节点
- 部分机型在亮屏 / 灭屏、切换刷新率时会重置亮度

开启后会常驻一个前台服务，按设定间隔（默认 1.5 秒）读一次节点，与目标值不符就写回。
这是唯一会显示常驻通知的场景。

另外，每次拉开快捷设置面板、磁贴进入 `onStartListening` 时，应用会顺手做一次校正 ——
所以即使不开持续保持，也有轻量的自愈能力。

---

## 界面说明

- **状态卡**：当前亮度 / 量程、工作模式、亮度节点路径、模块版本、root 授权状态
- **磁贴锁定亮度**：滑块直接对应亮度原始值，下方显示百分比；「使用亮度上限」一键拉满
- **锁定**：手动触发锁定 / 解除；「立即写入一次」只改亮度不改锁定状态
- **行为**：
  - `解锁时恢复原亮度` —— 关闭后解锁只是停止保持，亮度停在当前值
  - `同步模块峰值亮度` —— 见上一节
  - `持续保持` —— 见上一节
  - `保持间隔` —— 500 ms ~ 8 s
- **维护**：重新申请 root、重新探测模块与节点
- **运行日志**：内存环形缓冲，可一键复制

亮度滑块的量程来自 `max_brightness` 节点，下限为 1（锁到 0 等于黑屏，没有意义）。

---

## 变更记录

### 1.0.1

修掉两个只在真机上才暴露的问题（感谢实测反馈）：

- **修复：应用打开即闪退。** 六个「卡片」`LinearLayout` 只写了 `style="@style/Card"`，
  没有 `android:layout_width` / `android:layout_height`，而 `Card` 样式里也没有定义它们。
  Android 在 inflate 阶段直接抛 `You must supply a layout_width attribute` 并终止 Activity。
  这个错误 aapt2 链接阶段完全不报错，只有真机运行才会暴露 —— **磁贴不受影响**，
  因为磁贴走的是完全独立的代码路径，不 inflate 这个布局。
  现已补上宽高，并在 `build.sh` 里加了 `tools/lint_layout.py` 静态检查，构建时直接拦下这类问题。
- **修复：应用图标与磁贴图标画得不对。** 两个图标都是手写坐标，有两处问题：
  1. 自适应图标的光芒伸到半径 39，而 108dp 画布的安全区只有半径 33，
     超出部分被启动器的圆形遮罩切掉（见 `tools/icons-compare.png` 右栏）。
  2. 圆形用「两点跨直径」的圆弧（`M cx,cy-r A r,r 0 1,1 cx,cy+r`）绘制，落在
     弦长 = 2r 的退化情形上，不同渲染器的表现不一致。
  现改为由 `tools/gen_icons.py` 按半径参数生成：圆形用 4 段四分弧，光盘与 8 根光芒
  全部收进安全半径内（应用图标 29 < 33，磁贴图标 9.6 < 12 并留出四周留白）。
  顺带补上了 `<monochrome>`，支持 Android 13+ 的主题图标。
- 修复：`LockService` 前台化失败时未调用 `startForeground` 就继续运行，
  会被系统以 `RemoteServiceException` 杀掉整个进程；现在失败即退出服务。



不需要 Gradle。只用 Android SDK 自带的 `aapt2` / `d8` / `zipalign` / `apksigner`
加上 JDK 的 `javac` / `keytool`，因此**不依赖网络**，也不会被 JDK 版本与 AGP 的兼容矩阵卡住。

```bash
cd tile-app
./build.sh
```

产物在 `build/LuminProTile.apk`。

需要：
- Android SDK，含 `platforms/android-*` 与 `build-tools`（脚本取版本号最高的一个）
- JDK 17 或更高（在 JDK 26 上验证通过）

SDK 位置按 `ANDROID_HOME` → `ANDROID_SDK_ROOT` → 默认安装路径依次查找。
首次构建会用 `keytool` 生成 `keystore/debug.keystore` 自签名密钥 —— **仅供自用安装，发布请换成自己的密钥**。

安装：

```bash
adb install -r build/LuminProTile.apk
```

装上后到「快捷设置」的编辑页把「亮度锁定」磁贴拖出来。

### 为什么不用 Gradle

`RootShell` / `ModuleBridge` 这些类只用到平台 API 和 `org.json`，没有任何第三方依赖；
本机构建过一次之后发现 JDK 26 与 AGP 的兼容矩阵反而是主要风险，而 aapt2 工具链本身完全够用。
代价是：**新增 AndroidX 或 Material 组件依赖时需要改回 Gradle**。

---

## 测试

`RootShell` 是全部代码里最容易出错的一段（常驻 shell、随机哨兵、超时杀进程），
但它本质是纯 IO 逻辑，可以用本机的 `sh` 冒充 root shell 来跑真实代码：

```bash
cd tile-app
./test.sh
```

`test/stubs` 下是 `android.util.Log` / `android.os.Handler` / `android.os.Looper` 的测试替身，
编译期用 `android.jar` 校验签名、运行期放在 classpath 最前面顶替平台实现。
这些 stub **不会**被打进 APK —— `build.sh` 只编译 `src/` 和生成的 `R.java`。

覆盖的用例包括：退出码传播、stderr 合并、含单引号 JSON 的传参、多行循环命令、超时中断，
以及一条回归用例：**命令输出不以换行结尾时，末段必须和哨兵正确分离**（sysfs 亮度节点被
`echo -n` 写入后正是这种形态，早期版本会把这段输出整个丢掉）。

### 布局静态检查

`tools/lint_layout.py` 检查每个 View 是否都带 `layout_width` / `layout_height`。
缺失时 Android 会在 inflate 阶段崩溃，而 aapt2 链接**不会**报错 —— 1.0.0 的闪退就是这个原因。
这个检查已接进 `build.sh`，构建前自动执行，不通过直接中止：

```bash
python tools/lint_layout.py
```

### 图标

图标由 `tools/gen_icons.py` 从半径参数生成，不手写坐标（手写坐标正是 1.0.0 图标被裁的原因）：

```bash
python tools/gen_icons.py      # 重新生成 res/drawable/ic_tile.xml 与 ic_launcher_foreground.xml
python tools/compare_icons.py  # 生成修复前后对比图 tools/icons-compare.html
```

---

## 已知限制

- **必须 root。** 两条路径都要读写 `/sys` 亮度节点，也都要经 `su` 运行模块二进制。
  应用在首次进入或首次点磁贴时会申请 root。
- **磁贴点击的工作是尽力而为的。** 磁贴 `onClick` 返回后进程随时可能被回收；
  正常情况下一两秒内能写完，但如果第一次点击正好撞上 su 授权弹窗（需要用户操作、
  耗时不可控），这次点击可能失败 —— 授权完成后重点一次即可。
- **持续保持会显示常驻通知**，这是 Android 对前台服务的硬性要求。
- **重启后不保留锁定状态**，与模块守护进程的行为保持一致。
- **必须在真机上验证。** 本机没有可运行的模拟器（SDK 里只有 system-images 的空目录，
  没有 cmdline-tools，无法创建 AVD），应用界面与磁贴的运行时行为都只能靠真机反馈。
  已验证的是：APK 结构 / 对齐 / v2+v3 签名、shell 协议与读取逻辑（JVM 实测）、
  布局宽高完整性（静态检查）、编译后资源与清单（aapt2 反查）。
  **1.0.0 的「打开即闪退」正是这类静态检查覆盖不到、必须真机才暴露的问题。**
- 真机已确认可用：**磁贴点击锁定/解锁正常工作**（1.0.0 实测）。

---

## 目录结构

```
tile-app/
├── AndroidManifest.xml          磁贴服务 / 保持服务 / 开机接收器
├── build.sh                     无 Gradle 构建脚本
├── test.sh                      RootShell 单元测试
├── src/com/luminpro/tile/
│   ├── MainActivity.java        主界面
│   ├── LuminProTileService.java 快捷设置磁贴
│   ├── LockService.java         可选的「持续保持」前台服务
│   ├── BootReceiver.java        开机清理锁定状态
│   ├── BrightnessController.java 锁定 / 解锁状态机（核心）
│   ├── ModuleBridge.java        与模块的 JSON 通信
│   ├── Nodes.java               亮度节点发现与读写
│   ├── RootShell.java           常驻 root shell 与命令协议
│   ├── Root.java                root shell 单例
│   ├── Prefs.java               本地设置
│   ├── TileState.java           磁贴状态广播
│   ├── Bg.java                  后台任务池
│   └── Logx.java                内存日志
├── res/                         全部为纯 XML 资源，无二进制图片
├── test/                        JVM 测试与平台 stub
└── tools/
    ├── ZipAdd.java              把 classes.dex 并入资源 APK
    ├── gen_icons.py             由半径参数生成两份图标矢量
    ├── compare_icons.py         生成图标修复前后的对比图
    └── lint_layout.py           布局静态检查（构建前自动执行）
```

---

## 风险提示

长时间维持高亮度会显著加快 OLED 老化与烧屏，并增加发热和耗电。
滑块的下限设为 1 只是防止误触黑屏，并不代表高值是安全的 —— 请按实际使用场景设置。

---

## 许可证

本项目以 [MIT 许可证](LICENSE) 开源，版权归 GodDancing 所有。
