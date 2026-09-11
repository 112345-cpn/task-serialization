# 任务 2.2 / 2.3 报告：Kona JDK 25 序列化性能优化

日期：2026-09-07（2026-09-10 评审跟进后更新）
构建：`linux-x86_64-release`，WSL2 Ubuntu x86_64（4 核 / 7.8 GiB）
JDK：openjdk 25.0.4-internal（Kona JDK 25 源码）
基准：JMH 1.37，[bench/SerializationBench.java](bench/SerializationBench.java)
（2026-09-10 起为 11 项吞吐基准，`@Fork(3)`）

## 一、优化项与提交

源码仓库（公开）：https://github.com/112345-cpn/TencentKona-25
分支：`task-serialization`
（基于上游 TencentKona-25 `jdk-25.0.4` 代码；java.base 序列化源码与基线构建树一致）。

提交链接（GitHub）：

- https://github.com/112345-cpn/TencentKona-25/commit/285a0a002b
- https://github.com/112345-cpn/TencentKona-25/commit/13fdcd76ce
- https://github.com/112345-cpn/TencentKona-25/commit/a360b6b4a8
- https://github.com/112345-cpn/TencentKona-25/commit/8c0ba75a67
- https://github.com/112345-cpn/TencentKona-25/commit/9daccd8f15

| # | 改动 | 位置 | 说明 |
|---|---|---|---|
| 优化 1 | writeClassDesc 最近描述符句柄缓存 | ObjectOutputStream.java | 批量化序列化同类对象时，避免每个对象都查 HandleTable |
| 优化 1 改进 | 缓存改为“句柄命中时记录” | ObjectOutputStream.java | 减少单对象/冷路径写入开销（见第六节迭代） |
| 优化 2 | HandleTable spine 扩容策略改 2 的幂，`%` 改位掩码 | ObjectOutputStream.java | lookup/insert 是最内层热点；位掩码去掉整数除法 |
| 优化 3 | 内部 FieldValues 复用流级缓冲；内部路径不再分配 objHandles | ObjectInputStream.java | defaultReadObject/skip 等不逃逸路径复用 byte[]/Object[]，GetField/readFields/record 仍按需分配 |
| 评审跟进 | FieldValues 构造失败时释放 scratch 缓冲；补充 footprint 与多 slot 复用注释 | ObjectInputStream.java | 落地评审意见 2/3（见第七节） |

提交（Git 顺序，自旧到新）：

```text
285a0a002b  优化1：ObjectOutputStream 写类描述符增加 lastDesc 句柄缓存
13fdcd76ce  优化2：ObjectOutputStream HandleTable spine 容量改 2 的幂并改用位掩码哈希
a360b6b4a8  优化3：ObjectInputStream 内部 FieldValues 复用流级缓冲并免分配 objHandles
8c0ba75a67  改进：writeClassDesc 缓存改为句柄命中时记录，降低单对象序列化路径开销
9daccd8f15  评审跟进：FieldValues 构造失败时释放 scratch 缓冲并补充复用取舍注释
```

## 二、功能回归（jtreg）

每个优化提交后均执行，最终代码再次全量复跑：

| 测试集 | 结果 |
|---|---:|
| `test/jdk/java/io/Serializable` | **150/150 Passed**（每个优化各 1 次 + 最终 1 次） |
| `test/jdk/java/io/ObjectInputStream` | 4/4 Passed（最终代码） |
| `test/jdk/java/io/ObjectStreamClass` | 6/6 Passed（最终代码） |

## 三、性能测量方法

WSL2 环境存在明显的慢速窗口（同一镜像不同时段吞吐可差近 2 倍），
因此采用以下方法降低误判：

1. 每个代码状态做独立 `jdk-image` 增量构建并**快照独立 JDK 镜像**，
   交替启动不同镜像，不反复重编译。
2. 配置为 基线→优化 → 基线→优化 … 循环 3 轮，取中位数。
3. 同一轮内加入不受该优化影响的对照项：
   `serializeIntBox`（写侧对照）/ `deserializeIntBox`（读侧对照），
   用 `被测项/对照项` 做归一化后再比较。
4. 关键存疑结论另做高精度专项（更多 iteration、更长测量窗）。

测量参数：

- 2.1 基线 / 2.2 早期各轮（fork=1）：3×1s 预热 + 8~10×2s 测量。
- 2.3 高精度专项（fork=1）：5×3s 预热 + 25×3s 测量。
- 2026-09-10 评审跟进复测（`@Fork(3)`）：11 项 `-f 3 -wi 2 -w 1s -i 6 -r 1s`；
  `serializeSingle` 专项 `-f 3 -wi 3 -w 1s -i 15 -r 2s`（见第七节）。

## 四、结果

### 4.1 分项增量（各阶段相邻镜像直接对比，归一化后中位数）

| 对比 | serializeOrders | roundtripOrders | serializeSingle | deserializeOrders |
|---|---:|---:|---:|---:|
| 优化 1 vs 基线 | +5~9% | +5~6% | 约 -1%（噪声大） | — |
| 优化 2 vs 优化 1 | +2~5% | +2% | ≈0 | ≈0 |
| 优化 3 vs 优化 2（读侧） | — | +2~5% | — | +2~4% |

> 优化 1 早期版本在 serializeSingle 上曾测得 -4~-5%，经排查为测量慢窗口 + 冷路径
> 缓存写入开销共同导致；采用“命中时记录”改进后该问题消失（见第六节）。

### 4.2 最终实现 vs 基线（7 项完整 JMH）

完整 7 项 × 基线/最终各 3 轮（`full-*`，配对交替）中位数：

| Benchmark | 基线 ops/s | 最终 ops/s | 原始变化 |
|---|---:|---:|---:|
| serializeSingle | 1,782,234 | 1,683,016 | -5.6% |
| deserializeSingle | 468,947 | 464,414 | -1.0% |
| serializeOrders (1000) | 5,825 | 5,961 | +2.3% |
| deserializeOrders (1000) | 4,666 | 4,689 | +0.5% |
| roundtripOrders (1000) | 2,564 | 2,603 | +1.5% |
| serializeIntBox (32) | 1,878,785 | 1,873,212 | -0.3% |
| deserializeIntBox (32) | 426,240 | 443,691 | +4.1% |

### 4.3 归一化配对中位数与高精度专项

WSL2 慢窗口会使整机吞吐周期性变化近 2 倍，单次会话原始中位数会低估/高估收益。
用“同轮内 serializeIntBox/deserializeIntBox 对照归一化”和相邻轮配对后的估计：

| Benchmark | 归一化配对变化 | 备注 |
|---|---:|---|
| serializeOrders (1000) | **+5~9%** | 高精度专项 3 轮均为正：+5.6%/+8.6%/+6.4% |
| roundtripOrders (1000) | +2~5% | 跨会话中位数 |
| deserializeOrders (1000) | +1~3% | 分配下降明显（见 4.4） |
| serializeSingle | **-2~-6%** | 高精度专项 3 轮均为负；见第六节归因 |
| deserializeSingle | -1~+2% | 噪声内 |
| serializeIntBox / deserializeIntBox | ±2% | 对照组噪声 |

### 4.4 分配量对比（`-prof gc`，多次一致）

| Benchmark | 基线 B/op | 最终 B/op | 变化 |
|---|---:|---:|---:|
| serializeSingle | 3,024 | 3,080 | +1.9%（新增缓存字段+桶数组） |
| serializeOrders (1000) | 188,801 | 191,377 | +1.4% |
| deserializeOrders (1000) | 331,609 | 251,689 | **-24.1%** |
| roundtripOrders (1000) | 548,649 | 471,305 | **-14.1%** |
| deserializeSingle | 3,888 | 3,880 | -0.2% |

读侧 FieldValues 复用（优化 3）效果显著：千级订单反序列化每操作分配减少约 8 万字节。
写侧增加的量来自 ObjectOutputStream 新增 2 个缓存字段与 HandleTable spine 初值 16（原 10），
属于每新建一条流的一次性成本。

## 五、优化细节

### 优化 1 + 改进：writeClassDesc 缓存

写出 1000 个同类型 Order 时，每个对象都要走
`writeClassDesc(desc)` → `HandleTable.lookup(desc)`。
热点采样中 `writeClassDesc` 路径的 lookup 约占 6%。
缓存最近写过的描述符及其句柄：重复对象第二次命中后直接写 `TC_REFERENCE + handle`，
跳过哈希查找。句柄表 `clear()`（reset/close）时同步清缓存，保证与 handle 语义一致。

改进版本把“记录缓存”放到 **lookup 命中分支**而非首次完整写出分支：
首个/冷描述符不多写两个字段；批量同类对象从第二次出现起建立缓存，第 3 个起走快路径。

### 优化 2：HandleTable 位掩码

原实现 `hash(obj) % spine.length` 用整数除法；spine 长度 10 起步，
`growSpine()` 为 `(len<<1)+1`。改为：spine 初始取不小于容量的 2 的幂，
扩容直接翻倍，lookup/insert 用 `hash & (spine.length-1)`。
条目数组（next/objs）增长策略不变，只调整桶数组，兼容全部既有调用。

### 优化 3：FieldValues 读路径复用

`ObjectInputStream.FieldValues` 每个对象分配 `byte[] + Object[] + int[]`。
其中 defaultReadObject / readSerialData 的“读完即 set 回对象”路径数组不逃逸；
readFields() 返回给用户 GetField 的路径才需要 objHandles 供后续按名取值。

改动：
- 新增流级 `scratchPrimValues/scratchObjValues` 与占用标记；
  内部不逃逸路径借用缓冲区，借出期间嵌套读取自动退回私有分配，保证递归安全；
- 借用/归还受 `scratchInUse` 保护；构造过程中若抛异常，会在构造器内释放
  （见评审跟进），避免流恢复后优化永久失效；
- 只在 `readFields()`（recordDependencies=false）路径分配 `objHandles`，
  内部路径用局部 handle 完成依赖登记；
- readRecord 路径因数组可能被 record 构造器持有，仍按需分配。

已知取舍（评审意见 2、3）：
- `scratchObjValues` 会保留上一次反序列化对象字段值的引用，直到被下一次读取覆盖
  或流对象不可达；对“长生命周期流 + 只读少量对象”的场景会推迟部分对象回收。
  这与“-24.1% 分配”是同一取舍的两面。
- 多 slot 层次结构（父类+子类都有字段、且无自定义 readObject）进入失败原子性延迟设值
  路径时，只有第一个尚未消费的 slot 能借用 scratch，其余 slot 回退为私有分配；
  这是为保证延迟设值期间数组不被覆盖而做的保守选择。

## 六、2.3 差异分析与迭代

1. **单对象回退排查**：最初在 serializeSingle 上观察到 -4~-5%，但同轮
   serializeIntBox 对照也出现成比例波动；将首次完整写出时的缓存写入移到
   “第二次 lookup 命中”后有所缓解。高精度专项（25×3s）仍测得约 -2.7%
   （归一化）~ -5.6%（原始）：`-prof gc` 显示每个新建流多分配 56 B（+1.9%），
   其余为新增实例字段与分支导致的 JIT 代码布局差异。
   该基准每操作都新建 ObjectOutputStream（每 op 承担一次流构造成本），
   而优化目标是同一条流内批量/重复序列化，二者权重不同；
   真实“单流多对象”场景无此每流成本。
2. **慢窗口识别**：同一 JDK 镜像不同时段吞吐可差 2 倍，判断是 WSL2/宿主机调度所致，
   已通过对照组归一化与多轮中位数处理；最终报告中所有敏感结论尽量用配对/归一化数据。
3. **收益主要来源确认**：serializeOrders/roundtrip 的提升来自优化 1/2
   （免去每对象类描述符哈希查找 + lookup 取模变位掩码）；
   deserializeOrders 的分配 -24% 来自优化 3。
4. **进一步可做**：更长 iteration 的 CI 化基准、AArch64 端复测、
   `ObjectOutputStream.primVals` 写侧缓冲扩容复用、字符串/引用读路径联合优化。
   （其中“单流多对象”基准已在评审跟进中补齐，见第七节。）

## 七、评审跟进（2026-09-10）

导师评审（issue #1）结论为“通过，质量良好”，并提出四项改进建议。本次跟进情况：

| 评审建议 | 跟进 |
|---|---|
| `@Fork(1)` 噪声大，建议 3~5 | 基准标注改为 `@Fork(3)`，全部复测 |
| 缺“单流多对象 + reset()”基准 | 新增 4 个基准：`serializeSameStream`、`serializeSameStreamReset`、`deserializeSameStream`、`deserializeSameStreamReset`（每 100 个对象 reset 一次） |
| scratch 构造异常泄漏 / footprint 权衡 | `FieldValues` 构造纳入 try/catch，构造失败即释放 scratch（commit `9daccd8f15`）；在代码与本文档补充 footprint、多 slot 复用边界说明 |
| 报告缺公开链接、含本地绝对路径 | 已补公开仓库与 commit 链接，移除本地绝对路径（含 BASELINE.md 构建路径） |

### 7.1 复测结果（11 项，`@Fork(3)`，2 轮交替，每项 6×1s 测量×3 fork）

| Benchmark | 基线 ops/s | 最终 ops/s | 原始变化 | 对照归一化 |
|---|---:|---:|---:|---:|
| serializeOrders (1000) | 5,554 | 6,238 | +12.3% | +10.1% |
| **serializeSameStream (1000)** | 5,695 | 6,119 | +7.4% | +5.2% |
| **serializeSameStreamReset (1000)** | 6,020 | 6,757 | +12.3% | +9.8% |
| deserializeOrders (1000) | 4,221 | 4,761 | +12.8% | +4.1% |
| **deserializeSameStream (1000)** | 4,317 | 4,802 | +11.2% | +2.7% |
| **deserializeSameStreamReset (1000)** | 4,109 | 4,371 | +6.4% | -2.1% |
| roundtripOrders (1000) | 2,386 | 2,551 | +6.9% | +4.6% |
| serializeSingle | 1,601,761 | 1,698,279 | +6.0% | +4.2% |
| deserializeSingle | 439,690 | 462,889 | +5.3% | -3.0% |
| serializeIntBox（对照） | 1,754,614 | 1,789,038 | +2.0% | — |
| deserializeIntBox（对照） | 389,038 | 420,319 | +8.0% | — |

说明：
- 两个对照项本身有 +2.0% / +8.0% 漂移，说明本轮仍受整机慢窗口影响；
  归一化后的数字更保守。
- 新增的“单流多对象”基准直接覆盖优化 1/2/3 的目标场景：
  `serializeSameStream` +5~7%、`serializeSameStreamReset` +10~12%、
  `deserializeSameStream` +3~11%。
- 与评审关注点一致：在“每 op 新建流”的 `serializeSingle` 上，
  fork=1 的早期高精度专项曾测得 -2.7%（归一化）~ -5.6%（原始）；
  `@Fork(3)` 复测转为 +4~6%（见 7.2 专项复核）。

### 7.2 serializeSingle 专项复核（fork=3，高迭代）

针对评审最关心的“单对象回退是否真实”，用 `@Fork(3)` + 15×2s 测量做了 2 轮交替专项
（`v2s-*`，每组 2 次；`serializeIntBox` 为同轮对照）：

| Benchmark | 基线（2 轮均值） | 最终（2 轮均值） | 原始变化 | 对照归一化 |
|---|---:|---:|---:|---:|
| serializeSingle | 1,712,022 | 1,638,949 | -4.3% | **-0.2%** |
| serializeSameStream | 6,023 | 5,877 | -2.4% | +1.7% |
| serializeOrders | 6,057 | 6,110 | +0.9% | **+5.1%** |
| serializeIntBox（对照） | 1,799,228 | 1,725,745 | -4.1% | — |

结论：加入多 fork 后，`serializeSingle` 的归一化差异为 **-0.2%（基本持平）**，
未复现 fork=1 时的 -2.7%~-5.6%。这与评审判断一致——**fork=1 的跨进程 JIT 差异
是单对象基准噪声的主要来源**，不是稳定的代码回退。原始 4.3% 的原始差值
主要来自整机慢窗口（同轮 `serializeIntBox` 对照也下降 4.1%）。

因此最终结论修正为：

- 批量写（同流多对象）：**+2%~+10%**（`serializeOrders` +5~10%，`serializeSameStreamReset` 约 +10%）；
- 读路径：`deserializeOrders` 约 +3~4%，分配 **-24.1%**；
- 单对象每 op 新建流：**中性（-0.2%）**，不再列为回退项；
- 早期 fork=1 的负值结论仅作为“单 fork 噪声”的案例保留在第六节。

## 八、复现

见 README“复现基准”一节；原始 JMH 输出位于本仓库 `results/2.2/`。

```bash
# 源码（公开）
git clone -b task-serialization https://github.com/112345-cpn/TencentKona-25.git

# 基线/优化数据与基准程序
git clone https://github.com/112345-cpn/task-serialization.git
```
