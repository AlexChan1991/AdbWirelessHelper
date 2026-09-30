# Adb Wireless（AdbWirelessHelper）

> **用一台安卓手机，直接无线连接、投屏并反向控制同一局域网里的另一台安卓手机。**
> 不需要 PC，不需要 root，不需要数据线。

本 App 自己就是 ADB 主机：它内置了 Android 平台原生的 `adb` 可执行文件与 scrcpy 服务端，
因此控制端（下面称 **A**）可以直接完成「发现 → 配对 → 连接 → 投屏 → 控制 → 执行命令 → 管理文件」
的完整链路，而被控端（下面称 **B**）只需要打开系统自带的「无线调试」。

---

## 效果预览

<table>
<tr>
<td width="50%" align="center">
<img src="docs/images/01-devices.png" width="100%" alt="设备列表"><br><br>
<b>① 设备列表</b><br>首屏自动发起一轮发现：mDNS 为主，未发现时自动降级为端口扫描兜底；已知 / 历史设备可长按改别名
</td>
<td width="50%" align="center">
<img src="docs/images/02-detail.png" width="100%" alt="设备详情"><br><br>
<b>② 设备详情</b><br>12 项基础信息 + 实时 CPU／内存 + Top 进程，底部两层操作栏
</td>
</tr>
<tr>
<td width="50%" align="center">
<img src="docs/images/03-mirror.png" width="100%" alt="投屏与控制"><br><br>
<b>③ 投屏与控制</b><br>顶部工具条 + 底部系统三键，实时统计叠层
</td>
<td width="50%" align="center">
<img src="docs/images/04-shell.png" width="100%" alt="命令行"><br><br>
<b>④ 命令行</b><br>16 条预设命令（13 普通 + 3 高危），输出可复制
</td>
</tr>
<tr>
<td width="50%" align="center">
<img src="docs/images/05-settings.png" width="100%" alt="设置"><br><br>
<b>⑤ 设置</b><br>分组卡片：设备刷新 / 投屏参数 / 音频 / 连接与安全 / 外观 / 高级
</td>
<td width="50%" align="center">
<img src="docs/images/06-files.png" width="100%" alt="文件管理"><br><br>
<b>⑥ 文件管理</b><br>直接管理被控端的文件系统，预览不落盘
</td>
</tr>
</table>

---

## 使用前准备

| 项 | 要求 |
|---|---|
| 控制端 A | Android 8.0（API 26）及以上，安装本 App |
| 被控端 B | Android 8.0 及以上；**Android 11+ 才能用「无线调试 + 配对码」** |
| 网络 | 两台设备在**同一个 Wi-Fi**（同一网段） |
| root | **不需要**，两端都不需要 |
| PC | **不需要** |

**在被控端 B 上需要做的事**

- **Android 11 及以上**：设置 → 开发者选项 → **无线调试** → 打开。
  首次连接时用「使用配对码配对设备」拿到 6 位配对码。
- **Android 10 及以下**：系统没有无线调试开关，需要先经 USB 连一次电脑执行 `adb tcpip 5555`，
  之后 B 就会在 5555 端口监听。本 App 的端口扫描兜底会探测 5555，因此这类设备仍可被发现。

> 如果「开发者选项」没出现：设置 → 关于手机 → 连续点击「版本号」7 次。

---

## 快速上手

**1 · 扫描**
打开 App，首屏自动发起一轮发现。列表按「可用设备 / 已知设备 / 历史设备」分组展示。
在下列「已知设备」或「历史设备」里**长按任一条目**，可以给它起个别名方便识别（详见「设备别名」）。

![设备列表](docs/images/01-devices.png)
*扫描结果按来源标注（mDNS / 端口扫描 / 手动添加）；「已知设备」来自 `adb devices -l`。*

**2 · 连接或配对**
点击条目即可连接。若 B 尚未授权，会弹出配对对话框：填入 B 上显示的**配对端口**与 6 位**配对码**。

> ⚠️ 配对端口 ≠ 连接端口。B 的「无线调试」页面会分别给出这两个端口，不要填错。

**3 · 投屏**
连接成功后进入设备详情页，点底部的「开始投屏」。

![投屏与控制](docs/images/03-mirror.png)
*首帧出现后即为可操作状态；顶部工具条与底部三键常显，不自动隐藏。*

**4 · 控制**
- 画面内触摸、滑动、多指手势会原样透传给 B。
- 底部三键：返回 / 主页 / 最近任务。
- 顶部工具条：音量 ±、电源、旋转、方向策略、全屏、退出。

---

## 功能详解

### 1. ADB 无线连接

App 内置 Android 平台的原生 adb 可执行文件
（`app/src/main/jniLibs/arm64-v8a/libadb.so`、`armeabi-v7a/libadb.so`），
以「未压缩 + 可执行」方式打包落地，因此 A 自己就是 adb 客户端，全程不依赖 PC。

> 这条决定了后面所有能力都能在手机上闭环：发现、配对、连接、投屏、命令、文件，
> 走的都是 A 本地的 adb 进程，不需要中间任何一台电脑。

### 2. 局域网扫描发现

**怎么用**

1. 打开 App，首屏自动发起一轮发现（`DeviceListScreen` 里那个 `LaunchedEffect(Unit)`，
   内部调 `vm.startScan()`），无需手动触发；
2. 想重新扫，点右上角扫描图标；扫到一半想停，同一个图标会变成停止；
3. 扫不到就点**右下角的悬浮按钮「＋ 手动添加 IP:端口」**（`Scaffold` 的
   `floatingActionButton = { AddDeviceFab(...) }`）。

**两条发现路径 —— 无条件并行，各自负责不同的设备集合**

| 路径 | 机制 | 能抓到什么 |
|---|---|---|
| **mDNS** | 同时监听 `_adb-tls-connect._tcp.`（连接端口）与 `_adb-tls-pairing._tcp.`（配对端口）两类服务（`NsdDiscoveryEngine` 的 `SERVICE_TYPE_CONNECT` / `SERVICE_TYPE_PAIRING`） | 正在广播服务的 Android 11+ 无线调试设备 |
| **端口扫描** | 探测固定端口 `5555` + 无线调试随机高位端口区间 `37000–44000` 的等距抽样（`PortScanEngine.scan()` 的 `dynamicRange` 默认参数） | **`adb tcpip 5555` 的老式明文设备** —— 它们根本不广播 mDNS，只能靠扫；以及动态端口恰好落在抽样点上的设备 |

**每次扫描两条路径同时跑**（2026-09-28 改）。以前是「mDNS 观察 2 秒没结果才叠加上去」的兜底模式，
它有个隐蔽但致命的副作用：**只要 mDNS 找到过任何一台设备，端口扫描就永远不跑** ——
而最容易留在 mDNS 列表里的往往正是这台 App 以前连过的那台，于是表现是
「首页只显示我连过的那台，局域网里其它开了无线调试的怎么扫都扫不出来」。
现在两者结果按 `host` 合并去重，谁先回来谁先出条目。状态条会显示「mDNS + 端口扫描并行」。

**结果不设上限，也不区分是否曾经连接过**：局域网内有多少台符合条件就列多少台，
`DiscoveryRepository` 的候选表只按 `host` 去重（同一台的配对端口与连接端口合并成一张卡），
不会因数量多而截断，也不会因为「这台以前连过」而跳过或隐藏。这条规则由 `DiscoveryScanTest` 里
「60 台都要列出」的用例钉死。

> 端口扫描**只抽样 200 个端口**（`PortScanEngine` 的 `DYNAMIC_SAMPLE_COUNT`）。全量扫 7000 个端口 × 254 台主机不可接受，
> 所以它抓的是「最可能命中」的那一段，不是穷举 —— **动态随机端口有可能抽不到**，
> 这种情况下请按「常见问题 / 排障」的顺序排查，别急着加大抽样数。

### 端口扫描为什么能这么快（两级裁剪）

端口扫描慢从来不是因为并发不够，而是**把 SYN 发给了不存在的 IP**。旧实现是
`254 台主机 × 200 个端口 = 5 万次 connect`，其中绝大多数是给空地址发，每次都要干等到超时；
而对真正在线的主机，哪怕端口没开也会立刻回 RST（毫秒级）。所以耗时几乎全浪费在「等」上。

现在分两级裁剪（`PortScanEngine`）：

| 阶段 | 做什么 | 量级 |
|---|---|---|
| ① 主机探活 | 每台主机只探一次 `5555`。**`REFUSED`（对方回 RST）也算在线** —— 这恰恰是「这台存在且响应极快」的证据；`UNREACHABLE` 说明没人应答，这台主机直接出局 | 254 次探测，约 1 秒 |
| ② 端口抽样 | 只对 ① 活下来的主机做 200 个动态端口抽样 | 典型家庭网 ~20 台 → 约 4 千次，多为毫秒级 RST |

判定在线用的是三态值（`NetUtils.ProbeResult` 的 `OPEN` / `REFUSED` / `UNREACHABLE`），
不再是布尔的 `isPortOpen` —— 布尔版把「连不上」和「没人答」混为一谈，正是这个信息丢失导致不得不全量扫。

两个安全设计：

- **探活一台都没发现时退回全量扫描**（`scanAllWhenNoAlive`，默认开）。整段被防火墙静默丢包会造成「全不可达」，
  这时覆盖率优先：**宁可慢，也不能扫不出东西**。日志会有「退回全量扫描」提示。
- **`REFUSED` 与超时同级重要的是别判反方向**：把 `UNREACHABLE` 当成在线 = 退化回老样子（慢）；
  把 `REFUSED` 当成不在线 = **设备直接消失**（快但扫不到）。这条判定由 `DiscoveryScanTest` 的探活分类器用例钉死。

列表按三组展示：**可用设备 · 发现结果**（每轮扫描的即时结果）、**已知设备**（来自 `adb devices -l`）、
**历史设备**（连过的，点一下直连）。同一台设备不会在已知与历史里重复出现。

**分组里还能做什么**

| 分组 | 条目右侧 / 标题右侧的操作 |
|---|---|
| **已知设备** | 右侧「**断开**」按钮，断开这条 adb 连接（`KnownDeviceRow` 的 `onDisconnect`）；长按改别名（见第 5 节） |
| **历史设备** | 右侧垃圾桶图标 **删除这一条记录**（`HistoryDeviceRow` 的 `onDelete`）；长按改别名（见第 5 节） |
| **历史设备**（整组） | 分组标题右侧的「**清空**」：一次删掉全部历史记录，**不可撤销**，走底部 Sheet 二次确认（最终调 `DeviceListViewModel.clearHistory()`） |

> 「删除这一条记录」与「移除别名」是两件事：前者删的是历史条目，后者只清名字、历史条目还在。

### 3. 配对

**怎么用**

1. 在被控端 B 上：设置 → 开发者选项 → **无线调试** → 点「**使用配对码配对设备**」；
2. B 会显示三个值：**配对端口**、**6 位配对码**、**连接端口**；
3. 回到 A，点 B 的条目，A 弹出配对对话框 → 填**配对端口**与**配对码** → 配对 → 自动回连。

> ⚠️ **这三个数是三个不同的东西，最容易填错的就是这里。**
> 配对端口 ≠ 连接端口，配对码只在配对时用一次。手动添加设备时填的是**连接端口**。

**参数**：配对码 6 位（`PairingManager.pair()` 的 `code` 参数），配对超时 10 秒
（`PairingManager.DEFAULT_TIMEOUT_MS` `:315`）。
失败会分类提示：配对码过期 / 配对码错误 / 该端口不是配对端口 / 无法连接 / 配对超时 / 被控端不支持无线配对。

**为什么要点配对**：无线调试的第一次连接必须完成一次 TLS 配对，A 才被 B 信任。
配过一次以后，B 会记住 A 的密钥，之后直接连就行，不用再配对。

### 4. 设备状态

**怎么用**：设备列表里点任一条目 → 连上后进入详情页。数据**自动刷新**，不用手动拉；
想立刻刷新一次，点**顶栏右上角的刷新按钮**（`FlexibleDetailTopAppBar` 的 `onRefresh`）。

详情页是 4 张卡（`DeviceDetailScreen` 的 `TXT_SEC_BASIC` / `TXT_SEC_RESOURCE` /
`TXT_SEC_DISPLAY` / `TXT_SEC_QUICK` 四个分组标题常量）：

| 卡片 | 内容 |
|---|---|
| **基础信息** | 序列号、制造商、型号、设备代号、Android 版本、SDK 等级、Build 号、安全补丁、CPU、ABI、核心数、完整指纹 |
| **实时资源** | CPU 占用环、内存条、Top 进程 |
| **展示与能耗** | 分辨率、屏幕状态（已亮屏 / 已息屏）、电量、存储 `/data`、运行时长、内核、Wi-Fi IP、负载 |
| **快捷命令** | 6 个常用命令 chip，点击直接跳命令行页 |

基础数据（除 Top 进程）的刷新频率由设置页的「轮询间隔」决定，**默认 1.5 秒**
（`SettingsScreen.kt` 的 `TEXT_POLL_FOOTNOTE` `:67`）。
**Top 进程是另一条更慢的循环，固定 3 秒一次**（`DeviceDetailViewModel` 的 `HEAVY_POLL_MS`，轮询循环里 `delay` 它），
与「轮询间隔」无关 —— 它要跑一次完整 `dumpsys`，太频繁会发热。

App 进后台会**完全暂停采集**（不是降频），回到前台才恢复（`DeviceDetailViewModel.pause()`）。
取不到的字段显示「未知」，不会编一个值出来。

![设备详情](docs/images/02-detail.png)
*底部操作栏分两层：第一行是主行动「开始投屏」，第二行是次要操作组（文件管理 / 命令行 / 断开）。*

### 5. 设备别名

连过的设备一多，一屏全是同一型号就认不出谁是谁了。在首页的「**已知设备**」或「**历史设备**」分组里
**长按任一条目**，会弹出「设置设备别名」对话框。

> ⚠️ 「可用设备 · 发现结果」这个分组**不支持**改别名——它是每轮扫描的即时结果，还没有稳定身份。

**对话框里有什么**

- 顶部显示这台设备的**真实标识**（`serial` 或 `IP:端口`），方便确认改的是哪一台；
- 一个单行输入框，**最多 32 个字符**；
- 若此前已设过别名，会额外出现「**移除别名**」按钮。

保存前会做 `trim()`，因此**输入空白等同于清除别名**；保存 / 清除成功后走 Snackbar 提示。
注意「移除别名」清掉的只是名字，**历史记录本身不会被删**。

**起完名之后怎么显示**

| 项 | 表现 |
|---|---|
| 标题 | **别名优先**显示，没有别名时才显示型号 |
| 副标题 | **仅「已知设备」**：原标题（型号）降级到副标题，与 `serial` / `IP:端口` 并排 —— 起了别名也丢不了真实型号。⚠️ **「历史设备」不成立**：那条行的副标题固定是 `hostPort`，设了别名后型号**不再显示**（`DeviceListScreen` 历史设备行的副标题 `Text(entry.hostPort)`） |
| 标记 | 已设别名的条目，标题前会有一个小铅笔图标（`primary` 色）：既是「这条改过名」的标识，也是「这里能长按」的暗示 |
| 生效范围 | 改一次，**已知设备与历史设备两个列表同时生效**（它们算的是同一个键） |

> 本节**不配图**：原型里没有「设置设备别名」这一屏。
> 另外，「效果预览」里的设备列表截图是早期原型，上面**没有**小铅笔图标和长按菜单 —— 别照着图找。

**别名是怎么认出这台设备的**

主键由 `deviceAliasKey(serial)` 计算，它内部委托 `serialToHost()`：取 `serial` 里**最后一个冒号之前**的部分。

- 无线设备（`IP:端口`）→ 取 **IP**，丢掉端口；
- USB 设备（serial 里没有冒号）→ 取整条 `serial`。

之所以**不**拿完整 `serial` 当键：无线调试的端口**每次开关都会变**（这次是 `192.168.1.5:37123`，
下次可能就是 `192.168.1.5:41207`），绑全串的话每次重连别名都会失效——
而这功能存在的意义恰恰是「下次自动套用」。

> 这个主键函数与历史记录的主键 `HistoryDevice.host` **是同一个函数**算出来的，
> 所以在「已知设备」里设的别名，在「历史设备」里一定查得到，反之亦然。

存储上用的是**独立的 DataStore（`device_alias`）**，与连接历史分开存，
**不改动历史记录原有的存储格式**——这个功能不会动你已经积累的历史数据。

### 6. scrcpy 投屏与反向控制

**怎么用**

1. 设备详情页 → 底部第一行「**开始投屏**」；
2. 首帧出现后即可操作；顶部工具条与底部三键**常显，不自动隐藏**；
3. 退出：顶部工具条的「退出」→ 确认。返回键是两段式：全屏时先退全屏，非全屏时才弹退出确认。

**它的原理**

把内置的 `app/src/main/assets/scrcpy-server.jar` 推送到 B，用 `app_process` **以 shell 身份**启动服务端
（**这就是它不需要 root 的原因**），再经 `adb forward` 隧道建立 video 与 control 两条通道：

- 视频用 `MediaCodec` 低延迟解码，渲染到 `TextureView`；
- 控制通道注入按键与触摸事件。

支持转发 B 的音频（可选编码与来源，见第 11 节设置）。

**控件与它作用在谁身上**

| 位置 | 控件 | 作用对象 |
|---|---|---|
| 底部 | 返回 / 主页 / 最近任务 | **被控端 B** 的系统导航键 |
| 顶部 | 音量 + / 音量 − | **被控端 B** 的音量（`MirrorScreen` 的 `onVolumeUp` / `onVolumeDown` 回调），不是 A 的 |
| 顶部 | 电源 | 切换 **B** 的亮屏 / 息屏 |
| 顶部 | 旋转 | 旋转 **B** 的屏幕 |
| 顶部 | 方向策略 | 只改 **A** 自己的窗口方向（跟随 / 竖屏 / 横屏） |
| 顶部 | 全屏 / 退出 | 只作用于 **A** 本页 |

**画面上的两个叠层**

| 叠层 | 什么时候出现 | 内容 |
|---|---|---|
| **统计叠层**（左上角） | **只在全屏模式下**（`fullscreen && streaming != null` 才渲染）；非全屏时不渲染 | fps / 码率 / 延迟 / 丢帧，外加了音频时会多一段音频状态 |
| **音频状态徽标** | 开了音频转发、或音频降级有提示时（`MirrorScreen` 的 `AudioBadge`）；未开音频且无提示时完全不渲染 | 音频是否在播，以及降级原因原文（**不改写、不截半句**，最多 2 行 + 省略号） |

> 音频徽标是音频功能**唯一的可诊断出口**：被控端不支持、`output` 采集失败这类情况
> 不会静默无声，而是把服务端给的原因原样显示在画面上。所以「画面有声音图标但没声音」
> 时先看这行字，别先怀疑解码。

> ⚠️ **遥控语义的物理现实**（这几条反直觉，先读一遍再上手）：
>
> - **音量键改的是 B 的音量**。A 的媒体音量不受影响，按了没反应请先看 B。
> - **B 熄屏后画面会停住**：屏幕灭了系统就不再产出画面帧，投屏没有新帧可推，这是预期行为，不是断流。
>   用顶部的「电源」把 B 点亮即可恢复。
> - **电源键走的是 `SET_DISPLAY_POWER` 控制消息，不是注入 POWER 按键**（`MirrorViewModel.setDisplayPower` / `toggleDisplayPower`）。
>   原因：POWER 是系统级按键，普通 `injectInputEvent` 在多数 ROM 上会被系统吞掉，表现出来就是「点了没反应」。

**未实装 / 需注意**

- 小米 / HyperOS 上控制无反应，需要额外开「USB 调试（安全设置）」并**重启**才生效 —— 详见排障章节。

> **当前内置 scrcpy server 版本是 3.3.4。** 不要换成 3.3.2 —— 它在 Android 16/17 上会因
> `AbstractMethodError` 崩溃（详见下方排障）。

### 7. 自定义 adb shell 命令

**怎么用**

1. 设备详情页 → 底部第二行「**命令行**」（或点详情页的快捷命令 chip 直接跳过来）；
2. 点上方预设 chip，或直接在输入框里写命令 → 点执行；
3. 输出区可整段复制；历史命令点一下回填输入框。

**参数**

| 项 | 值 | 出处 |
|---|---|---|
| 预设命令 | **16 条**（13 条普通 + 3 条高危） | `ShellViewModel` 的 `presets` 列表 |
| 单次执行超时 | **默认 10 秒**，可切 30 秒 / 无限制 | `ShellViewModel.DEFAULT_TIMEOUT_MS`（右上角菜单切换） |
| 历史命令 | 最多保存 **50** 条，界面显示最近 **8** 条（每条截断 28 字符） | `ShellViewModel.HISTORY_LIMIT`、`ShellScreen` 的 `history.take(8)` 与 `command.take(28)` |
| 前缀剥离循环上限 | **6 层** | `ShellExecutor.MAX_SHELL_PREFIX_STRIP` |

高危的 3 条是 `logcat -c` / `pm uninstall` / `reboot`，界面上标红。

**命令在被控端 shell 里执行，不在本机 adb 上**

这一页下发的是 `adb -s <serial> shell <命令>`：你输入的整串内容会被当成**被控端 shell 里的一行**
执行，被控端上并没有 adb 这个可执行文件。所以手写 `adb` 前缀会直接 127。

现在输入会先经 `ShellExecutor.normalizeShellCommand` 归一化，把宿主侧前缀自动剥掉：

| 你输入 | 实际下发到被控端 | 结果 |
|---|---|---|
| `getprop ro.product.model` | `getprop ro.product.model` | ✅ 原样执行 |
| `adb shell getprop ro.product.model` | `getprop ro.product.model` | ✅ 自动剥掉 `adb shell` |
| `adb -s 192.168.1.9:5555 shell ls /sdcard` | `ls /sdcard` | ✅ 剥掉 `adb` / `-s <serial>` / `shell`，并提示已忽略 `-s` |
| `adb devices` | —— | ⛔ 拦截，见下 |

**会被自动剥掉的前缀**（逐个 token **完全相等**才剥，大小写敏感；所以 `adbd`、`adbx` 不会被误伤）：

- `adb`、`shell` —— 允许重复出现，`adb adb shell shell ls` 也能剥干净；
- `-s <serial>`、`-t <transportId>` —— 连带后面那一个参数一起剥；
- `-d`、`-e` —— 不带参数，只剥自己。

后两条**只在 `shell` 之前**才认（adb 的目标 flag 本来就只能写在子命令前）。两道保险确保不会吃掉
被控端命令自己的参数：① 头部 token 一旦不认识就立刻停手（`ls -s 100` 第一轮就在 `ls` 上 break）；
② `shell` 已被剥掉之后不再认任何目标 flag（`adb shell -s 100` 的 `-s` 留给设备）。

剥掉前缀时会给一行说明，让你知道发生了什么：

- 只剥了 `adb` / `shell`：`ℹ 已自动去掉「…」前缀：本页命令直接在被控端 shell 中执行，无需再写 adb`
- 用了 `-s` / `-t` 指向别的目标：`ℹ 已忽略 -s <serial>：命令行页固定对当前已连接的设备执行，
  无法切换目标；已改为在当前设备上执行后面的命令`

**会被拦截的 adb 主机端子命令**（`ShellExecutor.HOST_ONLY_ADB_SUBCOMMANDS`，25 个）：

`devices` `connect` `disconnect` `pair` `forward` `reverse` `push` `pull` `install`
`uninstall` `sideload` `root` `remount` `tcpip` `usb` `exec-out` `exec-in` `version`
`get-serialno` `wait-for-device` `backup` `restore` `emu` `kill-server` `start-server`

它们走的是 adb 自己的协议，不是被控端的可执行文件，塞进 `adb shell` 里必然 127。命中时**不执行**，
只给一行说明 + 替代做法，例如：

> `devices 是 adb 主机端的子命令（走的是 adb 自身协议，不是被控端的可执行文件），本页跑不了，
> 已停止执行。替代做法：查看设备请回到首页的「已知设备」分组。`

替代做法表在 `ShellExecutor.HOST_ONLY_ALTERNATIVES`，只写本 App 确实具备的入口（文件管理页 /
首页已知设备 / 首页手动添加 / 配对引导 / 设备端 `pm` / `getprop` 命令），25 个里 **20 个**有映射：

| 子命令 | 提示的替代做法 |
|---|---|
| `devices` | 查看设备请回到首页的「已知设备」分组 |
| `connect` | 连接设备请在首页点选候选设备，或用「手动添加 IP:端口」 |
| `disconnect` | 断开请在首页「已知设备」分组里点该设备的「断开」 |
| `pair` | 配对请在首页点未配对的设备，按引导输入被控端显示的 6 位配对码 |
| `push` / `pull` | 传文件 / 取文件请用「文件管理」页 |
| `install` | 安装应用请先用「文件管理」页把 apk 传到设备上，再在本页执行 `pm install <apk 路径>` |
| `uninstall` | 卸载应用可在本页执行 `pm uninstall <包名>`（属高危命令，会弹二次确认） |
| `root` | 本页不提供提权；请先确认被控端已 root，再直接执行需要的命令 |
| `remount` | 本页不支持重新挂载分区 |
| `tcpip` | 切换无线调试请在被控端「开发者选项 → 无线调试」里操作 |
| `usb` | 切回 USB 调试请在被控端「开发者选项」里关闭无线调试 |
| `sideload` | 卡刷请在被控端进入 recovery 后用官方工具操作，本页不支持 |
| `forward` | 端口转发由本 App 的投屏 / 文件能力自动完成，无需手动设置 |
| `reverse` | 反向端口转发本页不支持 |
| `exec-out` | 取原始输出可在本页跑对应的设备端命令（如 `screencap -p > /sdcard/a.png`），再用「文件管理」页取回 |
| `get-serialno` | 查看序列号可在本页执行 `getprop ro.serialno` |
| `wait-for-device` | 本页只对已连接的设备开放，设备在线时这里才可用，无需另等 |
| `kill-server` / `start-server` | 本 App 自行管理 adb 连接，无需手动停止 / 启动 |

剩下 5 个（`exec-in` / `version` / `backup` / `restore` / `emu`）确实没有等价入口，走通用兜底
「本页不支持该操作」——与其编一个不存在的入口，不如老实说不支持。

> ⚠️ 反过来，`reboot` / `bugreport` / `logcat` / `getprop` / `dumpsys` / `pm` / `am` / `settings` /
> `input` / `sync` **不在**这张表里 —— 它们是被控端 `/system/bin` 下**真实存在**的可执行文件，
> `adb shell logcat`、`adb shell reboot` 现在能正常跑。把它们加进拦截表会把本来可用的命令拦死。

**退出码 127 说明**

127 = 被控端 shell 中找不到该命令。现在碰到 127 会自动补一行说明，把两种可能一起讲清楚：
命令名拼错，或被控端系统上没有这个 toybox / busybox 命令；并提示 `adb` / `adb shell` 前缀已自动
剥除、不需要手写。

**限制与为什么**

- **高危命令一律拦截并弹二次确认，且该确认无法通过设置关闭。**
  设置页那个「危险命令二次确认」开关对执行行为**没有任何影响**——关掉它只会多打一行日志，
  危险命令照样拦截、照样弹确认（`ShellViewModel` 执行入口里的 `ShellExecutor.isDangerous` 分支）。这是安全底线，不是 bug。
- 命令由 `adb -s <serial> shell` 下发，**不做伪终端**：`top -i`、`su` 这类交互型命令跑不了。
- 同时只能跑一条命令；执行中点右侧的红色停止按钮可中断。
- 单次超时是「防呆」：一条 `dumpsys` 卡住不会把界面锁死。

![命令行](docs/images/04-shell.png)
*预设以横向 chip 排列；输出区按行着色（命令回显 / stdout / stderr）。*

### 8. 文件管理

管理的是**被控端 B 的文件系统**，不是 A 本机。入口：设备详情页 → 底部第二行「**文件管理**」。

**怎么用**

| 想做什么 | 怎么操作 |
|---|---|
| 进目录 | 点目录行 |
| **退一级目录** | 顶栏**左起第二个**的箭头按钮，或系统返回键 |
| **关闭整个文件管理页** | 顶栏**最左侧**的「×」（`FileBrowserScreen.kt` 的 `TXT_CLOSE_PAGE` 常量 `:101`；顺序的权威说明见 `FileTopAppBar` 的 KDoc `:789-794`） |
| 跳回某一级 | 点面包屑上那一级的名字（**最后一级 = 当前目录，高亮且不可点**，`FileBrowserScreen.kt` 面包屑里的 `isLast` 分支 `:975-983`） |
| 搜索 | 顶栏搜索图标，只搜**当前目录**、不递归子目录 |
| 排序 | 顶栏排序图标 → 底部 Sheet，6 档（名称 / 大小 / 日期 × 升 / 降，`FileSortKey` / `FileSortDir`）；**同一排序键再点一次 = 反转方向**（`FileBrowserViewModel.setSort`） |
| 显示隐藏文件 | 顶栏 ⋮ → 「显示隐藏文件」（默认关） |
| **全选** | 顶栏 ⋮ → 「全选」（`FileBrowserScreen.kt` 的 `TXT_MENU_SELECT_ALL`，More Sheet `:522-539`） |
| **刷新** | 顶栏 ⋮ → 「刷新」（同上，常量 `TXT_MENU_REFRESH` `:534-539`） |
| 多选 | **长按任一行**（`FileBrowserScreen.kt` 的 `FileRow.onLongClick` `:448`）；退出多选按顶栏**左起第二个**按钮（多选态下它的图标会变成 ×） |
| **取消选择** | 多选态下顶栏 ⋮ → 「取消选择」：只清空已选，**仍停留在多选态**（`FileBrowserScreen` 的 `TXT_MENU_CLEAR_SELECTION`、`FileBrowserViewModel.clearSelection`） |
| **退出多选** | 多选态下顶栏 ⋮ → 「退出多选」（`FileBrowserScreen.kt` 的 `TXT_MENU_EXIT_SELECT` `:573-578`） |
| 复制 / 移动 / 重命名 / 删除 | 先长按进多选，再用底部操作条。**重命名只在恰好选中 1 项时可用**（`FileBrowserScreen.kt` 的 `SelectAction(TXT_ACT_RENAME)`，`enabled = enabled && count == 1` 在 `:1336`） |
| 新建文件夹 | 顶栏 ⋮ → 「新建文件夹」 |

> ⚠️ **顶栏这两个按钮不是一回事，位置也别记反**（顺序是刻意定的，见 `FileBrowserScreen.kt` 里
> `FileTopAppBar` 的 KDoc `:789-794`，那段是全仓唯一权威，本页其余注释都以它为准）：
>
> - **最左侧「×」= 关闭整个文件管理页**。无论在哪一层目录、是否在多选态，一次到位直接退出，
>   不吃多选态、也不逐层回退。
> - **左起第二个（箭头）= 退一级目录**；在根目录时它退无可退，行为等价于退出页面。
>   多选态下这个位置复用为「退出多选」（图标变成 ×），但**最左侧那个 × 始终是关闭整页**。

**几个必须知道的规则**

- **目录永远排在文件之前**，组内才按所选排序键排。
- **返回止于 `/storage/emulated/0`**（`FileRepository.DEFAULT_ROOT`），不会一路退到挂载点层。
- **重名自动改名**：目标已有同名项时追加 ` (2)`、` (3)`，插在扩展名之前（`FileBrowserViewModel.uniqueName`）。
- **复制 / 移动的目标目录不能是当前目录、被选中项自身、或其任意子孙目录**
  （`FileBrowserViewModel.targetCandidates()`）—— 允许「把目录搬进它自己」会是数据灾难。
- **「复制到 / 移动到」的候选目录最多列 24 个**（`FileBrowserViewModel.MAX_TARGET_DIRS`）。
  构成是「存储根 + 当前目录的直接子目录」，超过 24 个时列表末尾会补一行
  「仅显示前 24 个目录（共 N 个）」—— 不是漏了，是截断（`FileBrowserScreen.kt` 的
  `TXT_TARGET_TRUNCATED_*` 拼接处 `:605-616`）。
- **掉线时会降级为只读**：顶部出现横幅「连接已断开，当前显示的是断开前的缓存」并给一个
  「重新加载」按钮（`FileBrowserScreen.kt` 的 `TXT_OFFLINE_BANNER` `:379-385`）；
  此时**长按不再进多选**（`FileBrowserScreen.kt` 的 `FileRow.onLongClick` 里的 `if (!connected)` 分支 `:449-451`）、
  底部操作条整体禁用（`SelectActionBar` 的 `enabled = connected && !busy`，`:467`）。
  断线判定走 `adb devices` 状态回查，不是「列目录失败」——
  目录读不出来有相当比例是权限问题（`FileBrowserViewModel.probeConnection`）。
- 两种空态文案不同：目录本身空是「此目录为空」，搜索没命中是「没有匹配项」。

**存储根目录独有的一块**

在存储根（如 `/storage/emulated/0`）时，列表上方会多一张**存储概览卡**：`FileBrowserScreen.kt`
里 `if (atRoot && storages.isNotEmpty())` 那段 `:390-391`，卡片本体是同文件的 `StorageCard`
（`FileBrowserScreen.StorageCard`）：

- 逐块列出内部存储与 SD 卡的「**已用 X%** · 已用 / 共 / 可用」并配一条进度条；
- 内部存储恒定排在首位（它既是默认起始目录，也是一个合法存储根）；
- 只在根目录显示，进了子目录就收起，不占垂直空间。

另外，目录行的副标题会显示该目录的**子项数「N 项」**：由 `FileBrowserScreen.kt` 的
`subtitleOf()` 拼出 `:1141-1143`。
这是列完当前目录后在后台逐个统计出来的，**只统计前 10 个目录**（`FileBrowserViewModel.MAX_COUNTED_DIRS`），
失败或不计入时退回显示「文件夹」三个字 —— 所以它时有时无是正常的，不是漏统计。

打开文件时按扩展名判定走哪个内置查看器：

| 类型 | 扩展名 | 行为 |
|---|---|---|
| 图片 | `jpg` `jpeg` `png` `gif` `webp` `bmp` `heic` `avif` | 全屏图片查看器 |
| 视频 | `mp4` `mkv` `webm` `avi` `mov` `3gp` `m4v` `flv` `ts` | 全屏视频播放器 |
| 音乐 | `mp3` `flac` `wav` `ogg` `m4a` `aac` `opus` | 音乐播放器 |
| 文本 / APK / 压缩包 | `txt` `log` `json` … / `apk` / `zip` `rar` … | 暂不支持预览，给提示 |

![文件管理](docs/images/06-files.png)
*顶部副标题常显「设备名 · 当前路径」，滚动时不淡出 —— 它是这一页唯一的路径上下文。*

#### 技术亮点：预览与播放不落盘

文件预览和播放**不把文件拉到本机**，而是用 `adb exec-out` 做**远端随机读**：

1. 首选 `tail -c +<offset+1> <path> | head -c <length>`；
2. 不可用时降级为 `dd bs=8192 skip=… count=…`；
3. 再不行才整文件读进内存，且**仅当 ≤ 64 MB**（更大宁可失败也不 OOM）。

读取结果按 **2 MB 分块**，配 LRU 缓存（默认 6 块，峰值约 12 MB）与顺序预取，
最后套一个 `MediaDataSource` 直接喂给 `MediaPlayer` 流式播放。

> 顺带说明另一条更省事的路线为什么没走：App 声明了 `android:usesCleartextTraffic="false"`，
> 本机明文 HTTP 会被系统拒绝，因此「本机起一个 HTTP 服务、把远端文件用 `http://` 交给播放器」
> 这条路同样不成立。

![全屏图片查看器](docs/images/07-image-viewer.png)
*查看器支持缩放、旋转、缩略图条跳转，两端循环；切换上一张 / 下一张也可横向拖动。*

**「更多」菜单里还有三个动作**（`ImageViewerScreen` 的 `TXT_MENU_*` 常量区）

| 动作 | 行为 |
|---|---|
| **查看详情** | 面板列出文件名 / 路径 / 类型 / 大小 / 修改时间 / 当前序号 / 当前缩放与旋转 |
| **重命名** | 弹输入框，在被控端真跑一次 `mv`（`ImageViewerScreen` 重命名确认里的 `onRename(target.path, name)`）。名称非空、不含 `/` 才能提交 |
| **删除** | 二次确认后在被控端真跑一次 `rm`，**无法恢复**（`ImageViewerScreen` 删除确认里的 `onDelete(target.path)`） |

> ⚠️ **重命名与删除改的是被控端 B 上的真文件**，不是 A 本机的副本，也没有回收站。
> 这两个动作与文件管理页多选操作条里的重命名 / 删除是**同一套后端**。

### 9. 视频播放器

在文件管理页点击视频文件打开。它**不是路由**，是盖在页面之上的全屏覆盖层，关闭即回到文件管理页。

**手势（刻意重新定义过，与常见播放器不同，先读一遍）**

| 手势 | 作用 |
|---|---|
| **点一下屏幕** | **显示 / 隐藏控制栏**（顶栏与底部控制区一起）。★ 它**不**切换播放暂停 |
| **横向拖动** | 拖进度。整屏宽度 = 整片时长；**拖动时只预览，抬手才真正 seek** —— 远端流式读经不起实时 seek |
| **纵向拖动** | 调音量。**整屏一致，不分左右半区** |
| **长按左右任一侧** | 2× 临时加速。**松手恢复到长按前的倍速**（不是硬回 1×） |

> 「左半屏调亮度 / 右半屏调音量」是早期形态，**已废弃并整条删除**：
> 远程串流画面下调亮度没有真实语义（改 A 的屏幕亮度不等于改 B 的）。

> ★ 「点一下 = 播放 / 暂停」是很多播放器的默认，但**本项目刻意不做**。
> 理由：点击已经承担了「调出控制栏」，再叠加暂停会让两个意图互相打架——
> 想调出控制栏时把视频暂停了，或者想暂停时控制栏收起来了。
> 播放 / 暂停只在底部控制栏的按钮上（`VideoPlayerScreen` 控制栏回调 `onTogglePlay`）。

**参数**

| 项 | 值 | 出处 |
|---|---|---|
| 倍速 | **9 档**：0.25 / 0.5 / 0.75 / 1 / 1.25 / 1.5 / 2 / 3 / 4 × | `VideoPlayerScreen.SPEED_STEPS` |
| 快进 / 快退 | **±10 秒**（连点会累加） | `VideoPlayerScreen.SEEK_STEP_SEC` |
| 长按加速 | 目标 **2×**；长按前已 ≥2× 则不生效；不支持时维持原倍速 | `VideoPlayerScreen.BOOST_SPEED` 与 `startBoost` / `endBoost` |
| 睡眠定时 | 关闭 / 15 / 30 / 60 / 90 分钟后**暂停** | `VideoPlayerScreen.SLEEP_MINUTES` |
| 控制栏隐藏延时 | **3 秒**；淡入淡出 **220 ms** | `VideoPlayerScreen.CONTROLS_AUTO_HIDE_MS` / `CONTROLS_FADE_MS` |
| 其他 | 播放列表、A-B 循环、循环模式、画面比例、跳转时间、媒体信息 | — |

**控制栏自动隐藏**

顶栏与底部控制区**联动**，一起显示、一起隐藏。播放中 **3 秒无操作**自动淡出，
点一下屏幕再调出来（220 ms 淡入淡出，`AnimatedVisibility` 在 `:1593`、`:1661`）。

| 情形 | 控制栏 |
|---|---|
| 播放中，3 秒没动 | 淡出 |
| 点一下屏幕 | 显示 / 收起（取反） |
| 点控制栏里的按钮 / **点进度条** | 显示，并**重新计时 3 秒**（不会把控制栏收走） |
| **按住**控制栏不放 | 常显，松手后重新计时（手指还按着就收走太难受） |
| 暂停中 | **常显**，不计时（对齐 YouTube / VLC：停下来通常就是要操作） |
| 拖动中 / 手势 HUD 显示中 | 常显（手还在屏幕上，中途收走是干扰） |
| 面板或「更多」菜单打开中 | 常显（浮层还开着，底下悄悄淡出会显得界面在闪） |
| 锁屏中 | 常显（反正被锁屏层盖住，解锁瞬间就该看得见） |
| **播到片尾** | 自动显示（用户要看"播完了"；否则会卡在隐藏状态） |

> ★ 实现上有四个坑，改动前先读：
>
> 1. **自动隐藏的 `LaunchedEffect`（`:1039`）的 key 里绝不能放 `currentMs` / `gestureTargetMs`**。
>    它们每帧都在变，放进去 Effect 会每帧重建，`delay` 永远走不完 ——
>    控制栏就**永远不会隐藏**，而且症状是「功能没生效」而不是报错，很难查。
> 2. **淡入淡出必须用 `tween`，不能用 `spring`**。spring 是欠阻尼的，从 1 回到 0 会
>    **下冲到负值**，喂给 alpha 会抛 `IllegalArgumentException`。本项目已因 spring 下冲
>    喂负值给 `Modifier.padding` 崩过一次，别再踩（常量抽在 `:220`）。
> 3. **「按住」这个状态必须同时进 key 和 guard，缺一不可**（`:1042` 进 key、`:1053` 进 guard）：
>    只进 guard → Effect 不重启，旧协程还在跑 `delay`，guard 形同虚设；
>    只进 key → 按下只是重新数 3 秒，手指还按着照样消失。
> 4. **复位「按住」必须用 `finally`**（`:2392`）。注意理由不是「手势被 cancel」——
>    `waitForUpOrCancellation()` 在 cancel 时是 **返回 `null`**，属于正常返回，后面那句本来就会执行。
>    真正危险的是协程被**从外部**取消（`pointerInput` 的 key 变化、或 node 被 dispose）时抛
>    `CancellationException`，那时函数不再往下走；不复位的话「按住」永久为 true
>    → 控制栏**再也不隐藏**。
>
> 「控制栏内按下就重新计时」没写进十几个回调里，而是做成一个 modifier
> `resetAutoHideOnPress`（`:2380`）挂在外层容器上一次覆盖，新增控件自动生效。
> 它不会吃掉按钮自己的点击——Compose 的 pointer input 影响别人的唯一手段是
> `consume()`，而这个 modifier 全程不 consume，对命中链上的其他节点观测等价于不存在。
>
> ⚠️ **反过来说：别在这个 modifier 里加 `consume()`**。它是挂在控制栏 root 上的父级 modifier，
> 一旦消费 down，控制栏里所有 `clickable` 按钮（默认 `requireUnconsumed = true`）会**全部失效**。
> 「点进度条不收走控制栏」是在 `PlayerSeekBar`（`:2834`）**自己**身上加消费块实现的，别挪到这里。

**一个刻意接受的边界**

淡出那 220 ms 内**快速点一下**控制栏，它仍然会收起：按下时代码把 `controlsVisible` 置回了 `true`，
但紧随的抬手会被手势层的 `onTap` 取反写回 `false`。

要修得用 flag 记住"这次按下落在控制栏内"，但那样点按钮时（按钮的 `clickable` 会消费 down，
手势层收不到 `onTap`，flag 没机会清）会导致**下一次点画面变成"点了没反应"**——比原问题更糟。
淡出中点控制栏的意图本来就二义（"我要用控制栏" vs "我要收起来"），所以选择接受。按住不放是吃得到修复的。

**旋转 90°：转的是屏幕，不是画面**

底部工具条的「旋转 90°」只切**控制端 A 的屏幕方向**，**不会**再给视频画面额外下发一次旋转：

| 操作 | 视频画面 | 控制端 A 的屏幕 |
|---|---|---|
| 点一下旋转 | **不动**（保持原样） | **转成横屏** |
| 再点一下 | **不动** | **交还系统自动** |
| 退出播放器 | — | **改回进播放器之前的样子** |

> ★ 为什么这两件事**只能做一件**：看着都是「转 90°」，底层完全是两码事——
>
> | 手段 | 本质 | 布局尺寸 |
> |---|---|---|
> | `view.rotation` / CSS `transform: rotate()` | **视觉旋转** | **不变** |
> | 切屏幕方向（`requestedOrientation`） | 真的改变窗口/SurfaceView 的可用空间 | **变**（宽 > 高） |
>
> 屏幕一旦横过来，布局空间已经是横的了，横屏视频本来就自然填满；
> 此时若再给 SurfaceView 下发 `rotation = 90`，等于**在同一个横屏空间里又视觉转了一次**，
> 画面会被压成一条 —— 这正是真机上「点了旋转，画面被压缩、不是原视频尺寸」的原因。
> 所以 `rotationDeg` 恒为 0，SurfaceView 不做任何额外旋转（`:927`、`:1714`）。
>
> ★ 屏幕方向**只有一份状态**：始终由「方向」三档（自动 / 横屏 / 竖屏）驱动，
> 旋转按钮只是把它切成「横屏」，而不是自己去写 `requestedOrientation`。
> 否则旋转按钮和方向面板会各持一份方向状态、互相覆盖（`VideoPlayerScreen` 的 `onRotate` 回调与 `activity?.requestedOrientation = when (orientation)`）。

> ⚠️ 退出时恢复的是 `SCREEN_ORIENTATION_UNSPECIFIED`（= 交还传感器、跟随握持姿态），
> 这在本 App 里就等价于「原来的方向」—— 因为 App 没有在 Manifest 上强制过方向。
> **不要**为了「更精确地恢复」去回写 `SENSOR_PORTRAIT`：本项目踩过坑，
> MIUI 平板上强行下发竖屏会触发 letterbox 兼容模式，导致窗口反复重建（`:1128` 处注释有记）。

**已实装 vs 只有按钮（重要）**

以下功能**界面上有按钮，真机目前只弹一句 toast 提示**：截图、画中画、字幕轨切换、字幕延迟、
音轨切换、音画延迟、均衡器（`VideoPlayerScreen` 常量区，形如 `TXT_NO_SUBTITLE` / `TXT_NO_*`）。
媒体信息面板里的「当前音轨 / 当前字幕」会主动标注「**（不可切换）**」，不会装作能切。

![全屏视频播放器](docs/images/08-video-player.png)
*播放列表取的是当前目录下的全部视频，两端循环。*

### 10. 音乐播放器

在文件管理页点击音频文件即可打开，它同样是非路由的全屏覆盖层。

![音乐播放器](docs/images/09-audio-player.png)
*封面占位图 + 标题/艺术家，底部是进度条与上一首 / 播放暂停 / 下一首。*

与视频同理走 `MediaDataSource` 流式播放，**播放列表取当前目录下的全部音频**。
编码路径：PCM 直接进 `AudioTrack`，AAC / Opus / FLAC 先经 `MediaCodec` 解码再播放
（`MediaDecoders` 的 `BuiltCodec`，三条编码路径一次初始化）。

> ⚠️ 它的倍速档位**和视频播放器不一样**：音频是 **6 档 0.5×–2×**
> （`AudioPlayerScreen.AUDIO_SPEED_STEPS`，0.5 / 0.75 / 1 / 1.25 / 1.5 / 2），
> 比视频的 9 档窄 —— `MediaPlayer` 对音频倍速的支持区间更有限，超了会直接抛异常。
> 若某条音频吃不下所选倍速，会提示「该音频不支持 N 倍速」并维持原速。

### 11. 设置项

**默认值（界面上改，改完立即持久化）**

| 分组 | 项 | 默认值 | 出处 |
|---|---|---|---|
| 设备刷新 | 轮询间隔 | **1.5 秒**（可选 3 / 10 秒） | `SettingsScreen.kt` 的 `TEXT_POLL_FOOTNOTE` `:67` |
| 投屏参数 | 分辨率上限 | **1280p**（可选 480p / 720p） | `SettingsScreen.kt` 的 `TEXT_VIDEO_NOTE` `:78-80` |
| 投屏参数 | 码率 | **8 Mbps** | 同上 |
| 投屏参数 | 帧率 | **30 fps** | 同上 |
| 音频 | 转发被控端音频 | **关** | `ScrcpyConfig.audioEnabled` |
| 音频 | 编码 / 缓冲 / 来源 | **原始 PCM · 50 ms · 设备输出** | `SettingsScreen.kt` 的 `TEXT_AUDIO_RESTART_NOTE` `:93-94` |
| 连接与安全 | 自动重连 | 开；**线性 1.2 / 2.4 / 3.6 秒，最多 3 次** | `SettingsScreen.kt`（已按源码校正的文案） |
| 连接与安全 | 危险命令二次确认 | 开（**但它不改变执行行为**，见第 7 节） | `ShellExecutor.isDangerous` 分支 |
| 外观 | 主题 | 跟随系统 | — |

> ⚠️ **投屏与音频参数不即时生效**，改完要**下次开始投屏**才应用（也可中途停止后重新开始）。
> 两个分组下方都有脚注写明这件事，别以为改了没起作用。

> 转发音频需要被控端 Android 11 及以上，且 Android 11 上要先**解锁屏幕**才能采集。
> 不支持时自动降级为无声投屏、**不会报错**，但会在投屏画面上给出降级原因（见第 6 节「音频状态徽标」）。

**音频三项的系统要求（容易踩）**

| 项 | 要求 | 出处 |
|---|---|---|
| 音频来源 `output`（设备输出，默认） | **被控端** Android 11+ | `ScrcpyConfig.audioSource`（`AUDIO_SOURCE_*` 常量） |
| 音频来源 `playback`（仅媒体播放） | **被控端** Android 13+ | `AUDIO_SOURCE_PLAYBACK` 常量注释 |
| 音频来源 `mic`（麦克风） | 无额外版本要求 | `AUDIO_SOURCE_MIC` 常量 |
| 音频编码 `opus` | **控制端** Android 10+（解码在 A 这边） | `SettingsScreen.kt` 的 `TEXT_AUDIO_CODEC_NOTE` `:87` |

> 这也是为什么默认编码是 `raw` 而不是 scrcpy 自己的默认 `opus`：Opus 解码要 API 29，
> 而本工程 minSdk = 26，做默认会让 Android 8/9 的控制端「开了音频却完全没声」。

**高级**：手动导入 adb 二进制、日志导出、关于与开源许可（详见「本地日志」一节）。

![设置](docs/images/05-settings.png)
*六个分组：设备刷新 / 投屏参数 / 音频 / 连接与安全 / 外观 / 高级。*

---

## 界面导览

单 Activity + Navigation Compose，共 **6 条路由**：

| 路由 | 页面 |
|---|---|
| `devices` | 设备列表（启动页） |
| `detail/{serial}` | 设备详情 / 状态面板 |
| `mirror/{serial}` | 投屏与控制 |
| `shell/{serial}` | 命令行 |
| `files/{serial}` | 文件管理 |
| `settings` | 设置 |

另有 **3 个全屏覆盖层**：图片查看器、视频播放器、音乐播放器。
它们是覆盖层而**不是路由**，不进返回栈，关闭即回到文件管理页。

---

## 编译与运行

**环境要求**

- Android Studio（AGP 8.7 需 **Gradle 8.9+**，请用能匹配该 AGP 的版本）
- JDK 17（Gradle 用 17 或 21 均可，脚本以 `--release 17` 编译）
- Android SDK Platform 35

**关键版本**（`gradle/libs.versions.toml`、`app/build.gradle.kts`）

| 项 | 值 |
|---|---|
| AGP | 8.7.3 |
| Kotlin | 2.0.21 |
| Compose BOM | 2024.12.01 |
| compileSdk / targetSdk | 35 |
| minSdk | 26（Android 8.0） |
| Java | 17 |

**步骤**

```bash
git clone <本仓库地址>
cd adbhelper/AdbWirelessHelper
./gradlew :app:assembleDebug
```

装到 A 上即可（`adb install` 或直接 Run）。

> **C 盘空间不足 / Gradle 守护进程锁**：可以把 `GRADLE_USER_HOME` 重定向到空间更大的盘，
> 本项目实际就是这么做的：
> ```bash
> export GRADLE_USER_HOME=/d/GradleHome   # 按你的实际盘符调整
> ```

**单元测试**

```bash
./gradlew :app:testDebugUnitTest
```

当前 **98 个用例**，位于 `app/src/test/java/com/adb/adbwirelesshelper/`：

| 测试类 | 用例数 | 覆盖什么 |
|---|---|---|
| `DeviceInfoParserTest` | 35 | 设备信息解析 |
| `DeviceAliasRepositoryTest` | 17 | 设备别名主键（`serialToHost` / IPv6 边界）与 200 条容量上限 |
| `ControlProtocolTest` | 13 | scrcpy 控制协议编解码 |
| `AudioProtocolTest` | 10 | 音频协议编解码（流头 codec id、禁用码判定） |
| `StatsFormatTest` | 9 | 统计格式化 |
| `ConnectionHistoryTest` | 8 | 连接历史 |
| `DeviceDetailViewModelMergeTest` | 6 | 详情页 Top 进程合并逻辑（4 个分支） |

---

## 项目结构

```
AdbWirelessHelper/app/src/main/java/com/adb/adbwirelesshelper/
├── AdbWifiApp.kt / MainActivity.kt
├── data/
│   ├── adb/        AdbRuntime、NativeAdbClient、PairingManager、ShellExecutor
│   ├── deviceinfo/ DeviceInfoCollector、DeviceInfoParser
│   ├── discovery/  DiscoveryRepository、NsdDiscoveryEngine、PortScanEngine
│   ├── file/       FileRepository（远端随机读）
│   ├── media/      RemoteFileSource、MediaDecoders
│   ├── repository/ DeviceRepository、ConnectionHistoryRepository
│   ├── scrcpy/     ScrcpyServerDeployer、ScrcpyController、ControlProtocol
│   └── settings/   AppSettings（DataStore）
├── di/             AppContainer、AppViewModelFactory
├── domain/model/   Models、FileModels
├── service/        ConnectionForegroundService
├── ui/
│   ├── nav/        AppNavHost
│   ├── screen/     6 个页面 + 3 个覆盖层
│   ├── components/ 跨页复用组件
│   └── theme/      Theme、Color、Type、Shape、Motion、AppIcons
└── util/           AdbErrors、Logx、NetUtils、ProcessRunner、StatsFormat
```

其他关键资源：

- `app/src/main/jniLibs/{arm64-v8a,armeabi-v7a}/libadb.so` —— 内置 adb 二进制
- `app/src/main/assets/scrcpy-server.jar` —— scrcpy 服务端（3.3.4）

---

## 本地日志

日志由 `util/Logx` 统一收口，全部落在**控制端 A 本机**，不上传、不经网络。

**日志去哪了**（三级，互不影响）

| 级 | 位置 | 容量 |
|---|---|---|
| 系统日志 | Logcat | — |
| 内存环形缓冲 | 进程内 | 最近 **500** 条（`Logx.RING_CAPACITY`） |
| 滚动文件 | `filesDir/logs/`（App 私有目录，`Logx` 的 `LOG_DIR`） | **≤ 5 MB × 3 个**（`Logx.MAX_FILE_BYTES` / `MAX_FILE_COUNT`） |

文件写满 5 MB 就做一次滚动：`.log → .log.1 → .log.2`，更早的丢弃（`Logx.rotateIfNeeded`）。
因为落在 App 私有目录，不需要任何存储权限，卸载即清除。

**配对码脱敏**（`Logx.sanitize`）

写文件前会过一遍 `sanitize()`：当 tag 或正文里出现 `pair` / `code` / `配对` / `配对码`
这类关键字时，把其中**连续的 6 位数字**替换成 `******`。
也就是说——**配对码不会以明文落盘**，提 issue 时贴日志是安全的。

> 判定是「疑似配对上下文才脱敏」而不是「见 6 位数字就打码」：
> 后者会把端口号、时间戳一起打花，日志就没法读了。

**怎么导出**

设置页 → 高级 → 「**日志导出**」。

> ⚠️ **当前版本的这一项是「操作说明弹窗」，不是一键导出按钮**
> （`SettingsScreen.kt` 的 `SettingsClickRow(TEXT_LOGS)` `:532-537` 打开的是 `InfoDialog`，文案见
> 同文件的 `TEXT_LOGS_BODY` `SettingsScreen.TEXT_LOGS_BODY`）。
> 弹窗给出的是步骤说明，可整段复制。真正的导出方法 `Logx.exportTo()` 已实现
> （`Logx.exportTo`，按 `.log.2 → .log.1 → .log` 从旧到新拼接、开头写导出时间、
> 正文里配对码已脱敏），但**尚未接到界面上**。

弹窗里写的步骤是：

1. 日志文件位于 App 私有目录，滚动保留 ≤ 5 MB × 3 个；
2. 通过系统分享把日志发送到本机其他应用查看；
3. 导出前会自动对配对码等敏感信息做脱敏；
4. 详细实现见 `Logx`。

---

## 已知限制

以下能力**界面上有控件，但真机目前只弹一句 toast 提示，没有真正实现**。

**视频播放器**（常量集中在 `ui/screen/VideoPlayerScreen.kt`，形如 `TXT_NO_*`，从 `:178` 起）：

- 截图
- 画中画
- 字幕轨切换、字幕延迟
- 音轨切换、音画延迟
- 均衡器

媒体信息面板里的「当前音轨 / 当前字幕」会后拼接「**（不可切换）**」如实标注。

**文件管理页与图片查看器**：

- **分享** —— 两处都有这个菜单项，点了只弹「暂不支持分享」
  （`FileBrowserScreen.kt` 的 `TXT_TOAST_NO_SHARE` `:151`，菜单项在 `TXT_MENU_SHARE` 那个
  `SheetRow` `:567-572`；`ImageViewerScreen.kt` 的 `TXT_SHARE_UNSUPPORTED` `:120`）。
  注意它出现在「更多」菜单里，和旁边**真能用的**「重命名 / 删除」混在一起，别以为分享也生效了。

其他已知边界：

- **进度条只能拖动，点击不跳转**：`PlayerSeekBar` 把 `onScrub(offset)` 写在 `detectDragGestures`
  的 `onDragStart` 里，而 `onDragStart` 要**过了 touch slop 才触发**（Compose foundation 的官方语义），
  所以「按下即跳到该位置」的意图落空了，纯点击不会 seek。这是**既有行为，不是近期改动引入的**；
  修它要改 seek 的起手逻辑，风险不在同一量级，所以单独放着没动（`VideoPlayerScreen.PlayerSeekBar`）。
- **亮度手势已整条删除**（远程串流下调亮度没有真实语义），不要指望它还在。
- **别名绑的是 IP**：同一台设备换了 Wi-Fi，或 DHCP 续租拿到新 IP 后，别名会失配，条目会显示回原始型号。
- **USB 与无线是两套别名身份**：同一台手机插线时 serial 是 `ABCDEFG12345`，走无线时是 `IP:端口`，
  两个键不同，**别名不共享** —— 拔了线再改无线连，不会自动套用之前起的名字。
- **别名表容量上限 200 条**，超出后丢弃最早写入的条目（普通使用很难触到，但它是有的）。
- 文本、APK、压缩包类文件**不支持预览**，点击只给提示。
- 投屏参数与音频参数**不即时生效**，需下次开始投屏时才应用。
- 文件预览依赖被控端 shell 的 `tail` / `head` / `dd`；极度精简的 ROM 可能降级到整读路径，
  大于 64 MB 的文件会直接拒绝而不是 OOM。
- 本项目目前是单语言（中文）界面，未做国际化。

---

## 常见问题 / 排障

**投屏有画面，但触摸/按键没反应**
小米 / HyperOS 上「**USB 调试（安全设置）**」是**独立于「USB 调试」的第二个开关**，
`injectInputEvent` 需要它。开启后**必须重启**才生效。

**控制端是联发科（MTK）设备时投屏黑屏**
`c2.mtk.*` 解码器的 `MediaCodec.setOutputSurface` 极不可靠。
代码里已对 MTK 平台优先走 `TextureView`，若仍异常请把机型与日志一并提 issue。

**MIUI 平板投屏窗口反复重建**
对大屏 App 下发 `SENSOR_PORTRAIT` 会触发 letterbox 兼容模式，导致窗口反复重建。
在投屏页把「方向」按钮切到**横屏**即可绕开。

**不要用 scrcpy server 3.3.2**
3.3.2 在 Android 16/17 上会 `AbstractMethodError` 崩溃
（`DisplayWindowListener` stub 只实现了 Android ≤15 的回调）。请用内置的 3.3.4。

**扫描不到设备**
1. 确认两端在**同一 Wi-Fi**，且路由器没有开「AP 隔离」；
2. 确认 B 的「无线调试」已开启（Android 11+）；
3. 观察状态条：若从蓝色转为橙色并提示「端口扫描兜底中」，说明组播被过滤了，属正常降级，等它扫完；
4. 仍不行就用「手动添加 IP:端口」，注意填的是**连接端口**。

**配对总失败**
- 配对码约 1 分钟内有效，过期就在 B 上重新生成；
- 确认填的是**配对端口**，不是连接端口；
- 访客网络 / AP 隔离环境下 adb 握手无法完成，换普通网络。

**构建时 C 盘空间不足 / Gradle 守护进程锁**
把 `GRADLE_USER_HOME` 重定向到大盘（见「编译与运行」）。

---

## 许可证

**尚未指定。** 本仓库当前没有选定开源许可证，欢迎提 issue 讨论。

关于第三方组件：本 App 内置并使用了 AOSP `adb` 与 Genymobile `scrcpy` 的服务端，
二者均为 **Apache-2.0**；设置页「关于与开源许可」内有完整声明。在许可证确定之前，
请以上游项目的许可条款为准使用本仓库中的相应二进制与代码。
