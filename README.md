# StatusChime（状态提示音）

给 Android 手机加一套「状态提示音」：插电、拔电、无线充、充满、过热、低电量三档、熄屏、解锁，每种事件一个声音，可各自替换。

不联网、不上传、不读屏幕内容，只监听系统电池广播。

## 事件一览

| 事件 | 触发时机 | 默认音效 |
|---|---|---|
| 插电音 | 接上电源 | `plug_in.wav` |
| 拔电音 | 断开电源 | `plug_out.wav` |
| 无线充音 | 放上无线充电板 | `wireless.wav` |
| 充满音 | 电量到达 100% | `full.wav` |
| 过热音 | 电池温度过高 | `overheat.wav` |
| 低电量 21% | 电量降到 21% | `low21.wav` |
| 低电量 11% | 电量降到 11% | `low11.wav` |
| 低电量 3% | 电量降到 3% | `low3.wav` |
| 熄屏音 | 屏幕熄灭 | `screen_off.wav` |
| 解锁音 | 解锁成功 | `unlock.wav` |

每个事件都可以单独换铃声：主页点一下试听，长按进入铃声筛选页。

## 需要的权限（逐条说明）

应用声明了 7 个普通权限 + 1 个特殊权限（无障碍），逐个说明用途，以及拒绝之后会怎样。

### 一、普通权限（安装即授予，不会弹窗）

| 权限 | 中文名 | 用途 | 拒绝会怎样 | 是否必须 |
|---|---|---|---|---|
| `FOREGROUND_SERVICE` | 前台服务 | 用一条常驻通知撑住进程，保证电池广播一直能收到 | 系统不允许建常驻服务，后台几秒后就被清理 | 必须 |
| `POST_NOTIFICATIONS` | 发送通知（Android 13+ 弹窗） | 前台服务必须挂一条通知，否则会被系统判定为非法后台服务 | 服务起不来，提示音失效 | 必须 |
| `RECEIVE_BOOT_COMPLETED` | 开机自启 | 重启后自动恢复监听，不用手动打开 App | 重启后要手动打开一次 App | 可选（想要开机生效就要） |
| `VIBRATE` | 震动 | 部分提示音事件可配合短震（非必需，仅用于触感反馈） | 只有声音，没有震动 | 可选 |
| `READ_MEDIA_AUDIO` | 读取音频（Android 13+） | **只在替换铃声时用**：让你从手机里挑一个音频当提示音 | 只能使用内置的 10 个音效，无法自定义 | 可选 |
| `READ_EXTERNAL_STORAGE`（maxSdk 32） | 读取存储（Android 12 及以下） | 同上，老系统上读音频文件用 | 同上 | 可选 |
| `MANAGE_EXTERNAL_STORAGE` | 所有文件访问（特殊，需手动开） | 同上，且支持选到媒体库以外的目录（如下载目录里的音频） | 同上，且部分目录里的音频选不到 | 可选 |

> 只用内置音效的话，只需要前三个权限（前台服务、通知、开机自启）。

### 二、无障碍服务（特殊权限，必须手动开启）

| 项目 | 说明 |
|---|---|
| 权限名 | `BIND_ACCESSIBILITY_SERVICE`（系统级绑定，不是普通 uses-permission） |
| 开启方式 | 设置 → 无障碍 → 已下载的服务 → 「充电提示音（常驻）」→ 打开 |
| 用途 | **只为保活**。系统绑定的服务优先级高于普通前台服务，进程更不容易被回收，电池广播才能持续收到 |
| 是否读屏幕 | **不读**。配置文件写死 `android:canRetrieveWindowContent="false"`，且不订阅任何界面事件 |
| 是否注入操作 | 不注入，不模拟点击，不读输入法内容 |
| 关闭会怎样 | App 仍可用，但后台存活时间变短，部分机型锁屏后就不再触发提示音 |

配置文件在 `app/src/main/res/xml/a11y_service.xml`，可以直接核对：

```xml
<accessibility-service
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagDefault"
    android:canRetrieveWindowContent="false"
    android:notificationTimeout="0" />
```

### 三、权限与数据流向

```
所有逻辑都在本机完成：
  电池广播（系统） → 判断事件 → 播放 res/raw 或你选的音频

没有网络权限，没有联网代码，不上传任何数据，不收集设备信息。
应用没有申请 INTERNET 权限。
```

## 使用

1. 安装后打开应用，授予通知权限
2. 在系统「无障碍」里开启 StatusChime 的服务（保活用）
3. 如需重启后仍生效，允许自启动
4. 在主界面点任意事件试听；长按进入铃声列表，选中即替换

音量走独立的「提示音」通道，不跟随媒体音量 —— 主界面可切换通道。

## 构建

```bash
git clone https://github.com/laitang1111-exe/StatusChime.git
cd StatusChime
./gradlew :app:assembleDebug
```

环境：JDK 11+、Android SDK 35、Gradle Wrapper（随仓库提供）。

## 内置音效来源

`app/src/main/res/raw/` 下的 10 个 `.wav` 全部为本项目自制（程序生成的合成提示音），
不取自任何第三方素材库，随项目以 MIT 许可一并提供，可自由使用与替换。

## 已知限制

- 部分厂商系统（MIUI / HyperOS 等）会清理后台，需要在电池设置里给本应用「无限制」并允许自启动
- 无障碍服务被系统关闭后，保活失效，需要重新开启
- 只监听系统电池广播，不读取充电器型号等信息

## 许可

[MIT](LICENSE)
