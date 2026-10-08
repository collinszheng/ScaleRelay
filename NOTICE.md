# ScaleRelay 的第三方代码与许可证

本文件按各上游许可证的要求，登记本项目包含或派生的第三方代码。

---

## 1. WeightDiary（MIT）—— **设计系统派生**

| 项 | 内容 |
|---|---|
| 项目 | 体重日记 / WeightDiary |
| 位置 | `E:\adev\WeightDiary` |
| 许可证 | MIT |

**派生范围**：`app/src/main/java/com/example/scalerelay/ui/theme/` 下的
`Color.kt` / `Type.kt` / `Dimens.kt` / `Shape.kt` / `Theme.kt`，
以及 `ui/components/` 下取自其设置页的 `SectionLabel` / `SettingsGroup` / `GroupDivider` /
`SettingsRow` / `ChevronRight` / `BackArrowButton`。

**为什么派生而不是重写**：本项目的明确要求是「界面风格参考体重日记，保证风格统一」。
共用同一份设计 token 定义，比照设计规范重写一遍可靠得多 ——
重写必然漂移，而漂移是渐进的、不易察觉的。

**许可证方向说明**：MIT 允许其代码进入 GPL-3.0 工程，反向不允许。
所以「WeightDiary（MIT）→ ScaleRelay（GPL-3.0）」这个方向是合规的；
本项目**绝不**把 GPL 代码放进 WeightDiary。

**未派生**：WeightDiary 的图表、Room 数据库、导入导出、BMI 计算**一律没有**搬过来 ——
ScaleRelay 只做「配置 → 收数 → 写 Health Connect」三件事。

---

## 2. 协议实现的知识来源

`data/S400GattProtocol.kt` / `data/S400Crypto.kt` / `data/S400Measurement.kt`
是**按公开协议描述独立重写**的，不是拷贝任何 GPL 源码。

| 来源 | 许可证 | 用途 |
|---|---|---|
| [nokistin/xiaomi-s400-live](https://github.com/nokistin/xiaomi-s400-live) | Apache-2.0 | Mi Home v2 GATT 协议描述（特征 UUID、指令字面量、登录流程） |
| [1260er/ScaleLauncher](https://github.com/1260er/ScaleLauncher) | GPL-3.0 | **仅作行为对照与排错参考**。未拷贝其代码；其 README 中「测量不走 BLE 广播、必须 GATT 认证」这一结论被本项目真机实测独立证实 |
| [Bluetooth-Devices/xiaomi-ble](https://github.com/Bluetooth-Devices/xiaomi-ble) | MIT | MiBeacon 广播格式（本项目最终**未采用**该路线，见下） |

### 关于 MiBeacon 广播

本项目曾实现并**离线验证通过**了 MiBeacon 广播的解密（AES-CCM / 12 字节 nonce /
associatedData `0x11`），但**真机实测证明 S400 从不通过广播发送测量数据**
（206 个广播样本里 object 位恒为 0）。相关代码因此**未进入本 App**，
只留在验证工具 `E:\adev\ScaleProbe` 里作为记录。

---

## 3. 本项目自身的许可证

**GPL-3.0**（见 `LICENSE`）。

理由：本项目派生自 GPL-3.0 的协议知识来源，且（若将来移植体成分公式则更明确地）
与 openScale 的 GPL 代码同源。自用不分发时 GPL 义务不触发，但按 GPL 发布没有任何实际损失，
却免除了「将来想发布时要回头审计整条链」的麻烦。

**对 WeightDiary 的影响：无。** 两者是独立 App、独立包名、独立仓库，
只通过 Health Connect（系统数据库）通信。WeightDiary 保持 MIT。
