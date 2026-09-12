# AutoDrill (埃里克尔优化版)

[![GitHub Release](https://img.shields.io/github/v/release/jexjws/AutoDrill?include_prereleases&style=flat-square)](https://github.com/jexjws/AutoDrill/releases)
[![GitHub Downloads](https://img.shields.io/github/downloads/jexjws/AutoDrill/total?style=flat-square)](https://github.com/jexjws/AutoDrill/releases)
[![Mindustry Version](https://img.shields.io/badge/Mindustry-v158%2B-orange?style=flat-square)](https://github.com/Anuken/Mindustry)
[![License](https://img.shields.io/badge/License-GPL--3.0-blue?style=flat-square)](LICENSE)

**AutoDrill (埃里克尔优化版)** 是基于原版 [AutoDrill](https://github.com/Pointifix/AutoDrill) 深度重构与升级的 Mindustry 自动布设钻机模组。

本版本专门针对 **埃里克尔（Erekir）** 星球独特的岩壁激光钻采机制（等离子钻机 / 大型等离子钻机）进行了全面的算法级重构与几何物理优化，彻底解决了原版在埃里克尔采矿中出现的**斜向散乱步进、无法开采垂直拐角、管道悬空打转形成空心大拱门、电力节点在钻机夹缝中乱塞乱连、钻机相互遮挡激光被完全挡死**等痛点问题。

---

## 🌟 埃里克尔优化版核心升级

### 1. 智能全向采矿识别（废除机械单选弹窗）
- **告别手动选方向**：点击等离子钻机直接一键全自动智能规划，彻底移除了原版机械且容易误判的“上下左右”单选弹窗；
- **多向协同开采**：全方位扫描矿脉二维不规则轮廓，自动组合不同朝向的钻机（例如水平矿壁朝下开采，突出岩角垂直面朝左/右开采），实现真正的立体全向采掘。

### 2. 真实有效产率最大化（Real Yield Optimization）
- 依据 Mindustry 底层物理引擎，以真实的有效激光产矿速率（单束 0.37/s、双束 0.75/s、大型三束 1.125/s）为主导目标函数；
- 算法优先锁定并拉满双束高产富矿位，获得最大化单位时间矿物产出。

### 3. 占地面积极小化（紧凑对齐，告别斜向散乱）
- 引入**包围盒面积与周长极小化惩罚项（Minimal Footprint Optimization）**；
- 彻底修复了底层几何碰撞判定的边界假阳性 Bug，驱使钻机自发寻找**同一横排对齐、同一纵列对齐、紧贴岩角的紧凑工整布局（如紧密对称的 L 型或直线阵列）**。

### 4. 零产出与被挡死钻机自然淘汰
- 结合机身成本惩罚，钻机物理实心机身阻挡前路被完全挡死（产出为 0）的情况在目标函数中会被严重扣分；
- 算法在数学求解中**自然杜绝并剔除任何被挡红温的废弃钻机**，无需生硬人工假定规则。

### 5. 背脊贴身单管流水线（Zero-Loop Spine Pipeline）
- **拒绝悬空拱门与空心大回环**：采用增量树状背脊单管模型，出口严格定在整排钻机链条末端开阔地向外引出，绝不向高空旷地乱飞；
- 管道仅在每台钻机正后背贴身串联，随地形阶梯平滑下沉导出，100% 单向流畅汇流，绝对无环、无死锁。

### 6. 规整外部供电母线（Zero-Clutter Powering）
- 彻底杜绝在钻机作业区夹缝或矿壁边缘胡乱塞放电力节点；
- 电力节点统一规范排布在最外围开阔地，利用激光穿透非实心管道的特性垂直直击机身供电，清爽整洁。

---

## 🎮 塞普罗经典功能 (Serpulo Features)

保留了原版在塞普罗星球的优秀自动铺设功能：
- **机械钻机 / 气动钻机**：一键铺设钻机并自动生成桥接输送带（Bridge Conveyor）网络。
- **激光钻机 / 爆破钻机**：自动优化寻找矿石覆盖最多的铺设方式，并自动为每个钻机就近配置水泵（Water Extractor）与电力节点。

![](showcase/bridge-drill.gif)
![](showcase/optimization-drill.gif)

---

## ⚙️ 使用说明与快捷键

1. 默认快捷键 `H` 即可开启/关闭模组工具栏（也可点击小地图旁的快捷切换按钮）；
2. 开启后点击地图上任意矿石瓦片，即可呼出可用的钻机工具栏；
3. 点击 **等离子钻机（Plasma Bore）** 或 **大型等离子钻机（Large Plasma Bore）**，算法将瞬间完成全局启发式求解，自动布置钻机、管道与电力节点；
4. 建造完毕后，只需将外部母线接上主电网，并将主出口管道接至工厂或核心即可。

### 模组设置项
在游戏内置的“模组配置”中可调整：
- **切换按键 (Activation Key)**：默认 `H`，可自定义绑定；
- **显示悬浮按钮 (Display Toggle Button)**：是否在界面小地图旁显示图标；
- **最大搜索格数 (Max Tiles)**：矿脉连通搜索上限，优化版已默认支持大矿脉；
- **放置水泵与电力节点 (Place Water Extractors and Power Nodes)**：塞普罗钻机是否自动搭配水电机。

![](showcase/settings.png)

---

## 📥 下载与安装

1. 前往 **[Releases 页面](https://github.com/jexjws/AutoDrill/releases)**；
2. 下载最新版本的 `AutoDrillDesktop.jar`（或 `AutoDrill.jar`）；
3. 打开 Mindustry 游戏主界面 $\to$ **模组 (Mods)** $\to$ **导入模组 (Import Mod)** $\to$ 选择下载的 `.jar` 文件（或直接放入游戏目录的 `mods` 文件夹）；
4. 重新加载游戏即可畅享体验！

---

## 🛠️ 自动化编译与持续集成 (CI/CD)

本项目配置了完整的 GitHub Actions 自动化工作流（位于 `.github/workflows/`）：

1. **自动编译发布 (`release.yml`)**：
   - 只要向仓库推送版本标签（例如 `git tag v2.1 && git push origin v2.1`），GitHub Actions 将自动执行 JDK 17 与 Android 构建环境打包；
   - 自动编译生成全平台 Mod JAR 文件，并自动在 GitHub 创建正式 Release 发布。
2. **手动一键发布 (workflow_dispatch)**：
   - 在 GitHub 仓库页面的 **Actions $\to$ Build & Release $\to$ Run workflow**，可直接手动触发打包与发布。
3. **提交与 PR 校验 (`commitTest.yml` / `prTest.yml`)**：
   - 针对每次代码提交与合并请求自动进行完整编译校验与 Artifact 打包归档。

---

## 🤝 鸣谢与声明

- 原始项目作者：[Pointifix/AutoDrill](https://github.com/Pointifix/AutoDrill)
- 埃里克尔重构优化：[jexjws](https://github.com/jexjws/AutoDrill)
- 本模组遵循 **GPL-3.0** 开源协议。欢迎提交 Issue 或 Pull Request 共同改进！
