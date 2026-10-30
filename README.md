# SITParkourWarrior 使用指南

面向 Paper 1.21+ 的跑酷插件。支持正计时(COUNTUP) / 倒计时(COUNTDOWN)两种模式，多 PKW 世界独立赛道，节点化关卡拼装(起点/主线/岔路/支线终点/全局终点)，奖牌系统，三榜排行(标准/进阶/卓越)，倒计时单榜(得分榜)，全息榜牌，掉线接续，玩家靠近隐身等。

## 环境要求
- Java 21+
- Paper/Spigot 1.21+
- WorldEdit

## 安装
1. 将插件 jar 放入 `plugins/`。
2. WorldEdit 一并放入 `plugins/`。
3. 启动服务器。首次启动生成 `plugins/SITParkourWarrior/` 数据目录及默认配置文件。

---

## 核心理念：PKW 世界 + 节点拼装

本插件的赛道由**节点**(Node)拼装而成，每个节点可独立设计部署，通过 `course.yml` 自动判定各关卡在赛道中的角色。

### 节点类型

| 类型 | 说明 |
|---|---|
| `LEVEL` | 普通关卡（默认类型） |
| `FORK` | 岔路口（多个分支点，通向不同方向） |
| `GLOBAL_START` | 全局起点（踩上开始全程计时） |
| `GLOBAL_END` | 全局终点（踩上结算成绩） |
| `BRANCH_END` | 支线终点（踩上传回绑定的岔路口） |

### PKW 世界

只有标记为 PKW 的世界才触发跑酷玩法。其他世界纯粹用于制作和编辑，不触发任何玩法逻辑。

```
/sitpkw pkwworld add [世界名] <countup|countdown>    加入 PKW 世界并指定模式
/sitpkw pkwworld setmode [世界名] <countup|countdown>  修改模式
/sitpkw pkwworld duration [世界名] <秒数>              设定倒计时总时长
/sitpkw pkwworld remove [世界名]                      移出
/sitpkw pkwworld list                                 列出所有 PKW 世界
```

---

## 命令总览

命令前缀：`/sitpkw`（别名：`/dp`、`/dynamicparkour`）

### 关卡编辑（管理类，需 dynamicparkour.admin 权限）

| 命令 | 说明 |
|---|---|
| `create <id> [type]` | 创建关卡并进入编辑，type: level/fork/globalstart/globalend/branchend |
| `edit <id>` | 编辑已有地图 |
| `exit` | 退出编辑模式 |
| `pos1` / `pos2` | 设置选区角点（手持线工具点方块也可） |
| `setstart [pos1\|pos2]` | 设置起点（或起点区域角点） |
| `setend [pos1\|pos2]` | 设置终点（或终点区域角点） |
| `title <文本>` | 设置关卡标题 |
| `subtitle [文本]` | 设置副标题（省略参数清空） |
| `difficulty <easy\|normal\|hard\|extreme>` | 设置难度（影响标题颜色：绿/黄/红/紫） |
| `particles <on\|off>` | 开关关卡粒子效果 |
| `sound <on\|off>` | 开关关卡音效 |
| `save <序列id>` | 将当前选区保存为动态状态 schem |
| `dynamic addstate <文件名> [间隔tick]` | 添加动态状态（≥2 个状态自动启用轮换） |
| `dynamic delstate <序号\|文件名>` | 删除状态 |
| `dynamic interval <序号> <tick>` | 修改状态间隔 |
| `dynamic list` | 查看动态状态列表 |
| `addforkpoint` | 为当前 FORK 节点添加分支点 |
| `delforkpoint <序号>` | 删除 FORK 节点的分支点 |
| `setendtier <easy\|normal\|hard>` | 设置 GLOBAL_END 的终点难度 |
| `deploy [id]` | 在当前位置部署关卡 |
| `undeploy` | 回收当前位置所在部署 |
| `delete <id>` | 删除关卡 |
| `reload` | 重载所有数据 |
| `board add <榜名>` | 在当前位置创建全息榜牌 |
| `board remove` | 移除附近榜牌 |
| `board list` | 列出当前世界榜牌 |
| `record delete <世界> <榜名> <玩家名或UUID>` | 删除一条成绩 |

### 玩家命令（默认所有人可用）

| 命令 | 说明 |
|---|---|
| `quit` | 放弃当前跑酷 |
| `top <standard\|advance\|expect\|countdown> [世界名]` | 查看排行榜前 10 |
| `stats [玩家名]` | 查看正计时三榜成绩计数 |

---

## 快速上手

### 一、创建 PKW 世界

```
/sitpkw pkwworld add world_parkour countup
```
加入后该世界自动设置 gamerule（摔落/火焰/冰冻伤害关、命令反馈关）。

### 二、制作节点（在任意世界编辑）

```
/sitpkw create my_lv1 level
```
用 `pos1`/`pos2` 设区域 → `setstart`/`setend` → `title 第一关` → `difficulty easy`

FORK 节点：
```
/sitpkw create fork_mid fork
```
设区域后，站到岔路口的各个分支方向点 → 逐个 `/sitpkw addforkpoint`

GLOBAL_START / GLOBAL_END：
```
/sitpkw create gs globalstart
/sitpkw create ge_end globalend
/sitpkw setendtier hard
```

### 三、部署到 PKW 世界

切换到 PKW 世界 → 站到想要的位置 → `/sitpkw deploy <id>`

### 四、跑酷开始

踩 GLOBAL_START → action bar 开始计时 → 跑主线/支线关卡 → 最终到达 GLOBAL_END 结算。

---

## 正计时(COUNTUP)规则

- 奖牌：主线关 +1，支线关 +1，最终关不计牌；结尾按终点难度追加（简单+1/普通+2/困难+3）
- 满分 21（9主线 + 9支线 + 结尾最高3）
- 全程用时 = 踩 GLOBAL_START 到 GLOBAL_END 的墙钟时间（支持掉线暂停接续）
- 三榜归档（向下兼容）：满21进 expect+advance+standard，≥16进 advance+standard，其余进 standard；各榜独立保留个人最佳

## 倒计时(COUNTDOWN)规则

- 奖牌分类：主线→石牌，支线第1/2/3关→铜/银/金牌（按 course 判定角色）
- 倒计时期内累计奖牌，到达 GLOBAL_END 或时间归零结算
- 总分 = (石牌分 + 铜/银/金累进分) × (1 + 终点倍率)；归零结算无倍率（×1）
- 单榜按总分降序，个人最佳制

---

## 配置文件

| 文件 | 说明 |
|---|---|
| `config.yml` | PKW 世界列表、计时模式、倒计时时长 |
| `course.yml` | 各世界赛道组装判定结果（自动生成） |
| `records.yml` | 排行榜 + 进行中局存档 |
| `boards.yml` | 全息榜牌位置持久化 |
| `countdown-scoring.yml` | 倒计时数值配置（自动生成默认值） |
| `maps/<id>/map.yml` | 各关卡模板数据 |

---

## 交互物品

进入 PKW 世界自动发放两个物品：

| 物品 | 材质 | 栏位 | 功能 |
|---|---|---|---|
| 返回岔路口 | Recovery Compass | 0（最左） | 右键传回最近的岔路口 |
| 放弃跑酷 | Barrier | 8（最右） | 右键放弃并传回出生点 |

物品锁定在固定栏位，不可丢弃/移动。

---

## 全息榜牌

```
/sitpkw board add <standard|advance|expect|countdown>
```
在当前 PKW 世界立常驻榜牌，TextDisplay 实体，重启持久保留。10 格内最近玩家若不在前十但有记录，自动追加一行显示"你的排名"。

---

## 特殊功能

- **掉线接续**：掉线自动保存进行中局，重连恢复计时/奖牌
- **Segment 特殊模式兼容**：玩家进入 SITSegment 练习/旁观模式时自动放弃当前局并静默，退出后恢复正常
- **玩家靠近隐身**：PKW 世界跑酷中玩家距离 ≤6 格互相隐身+发光轮廓，>9 格恢复（6-9 迟滞防闪烁）
- **编辑器选区粒子**：创造模式手持 String 编辑时显示选区绿色粒子边框
- **动态建筑切换**：≥2 个 schem 状态的关卡自动轮换，带粒子+音效
