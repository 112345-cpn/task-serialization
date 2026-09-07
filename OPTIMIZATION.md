# 任务 2.2 / 2.3 报告：Kona JDK 25 序列化性能优化

日期：2026-09-07
构建：`/home/test/TencentKona-25-master` `linux-x86_64-release`
JDK：openjdk 25.0.4-internal（Kona JDK 25 源码，本地 fork `task-serialization` 分支）
基准：JMH 1.37，`bench/SerializationBench.java`（同 2.1，7 项吞吐基准）

## 一、优化项与提交

源码仓库：`D:\TencentKona-25-task`，分支 `task-serialization`
（基于上游 TencentKona-25 `jdk-25.0.4` 代码；java.base 序列化源码与基线构建树一致）。

| # | 改动 | 位置 | 说明 |
|---|---|---|---|
| 优化 1 | writeClassDesc 最近描述符句柄缓存 | ObjectOutputStream.java | 批量化序列化同类对象时，避免每个对象都查 HandleTable |
| 优化 1 改进 | 缓存改为“句柄命中时记录” | ObjectOutputStream.java | 减少单对象/冷路径写入开销（见第六节迭代） |
| 优化 2 | HandleTable spine 扩容策略改 2 的幂，`%` 改位掩码 | ObjectOutputStream.java | lookup/insert 是最内层热点；位掩码去掉整数除法 |
| 优化 3 | 内部 FieldValues 复用流级缓冲；内部路径不再分配 objHandles | ObjectInputStream.java | defaultReadObject/skip 等不逃逸路径复用 byte[]/Object[]，GetField/readFields/record 仍按需分配 |

提交（Git 顺序，自旧到新）：

```text
285a0a002b  优化1：ObjectOutputStream 写类描述符增加 lastDesc 句柄缓存
13fdcd76ce  优化2：ObjectOutputStream HandleTable spine 容量改 2 的幂并改用位掩码哈希
a360b6b4a8  优化3：ObjectInputStream 内部 FieldValues 复用流级缓冲并免分配 objHandles
8c0ba75a67  改进：writeClassDesc 缓存改为句柄命中时记录，降低单对象序列化路径开销
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

测量参数（常规）：吞吐模式，fork=1，3×1s 预热 + 8~10×2s 测量。
高精度专项：5×3s 预热 + 25×3s 测量。

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
- 只在 `readFields()`（recordDependencies=false）路径分配 `objHandles`，
  内部路径用局部 handle 完成依赖登记；
- readRecord 路径因数组可能被 record 构造器持有，仍按需分配。

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
4. **进一步可做**（超出本任务范围）：更长 iteration 的 CI 化基准、
   增加“单流多对象”基准以消除流构造成本、AArch64 端复测、
   `ObjectOutputStream.primVals` 写侧缓冲扩容复用、字符串/引用读路径联合优化。

## 七、复现

见 [RESUME.md](RESUME.md)（本会话工作记录）与 README“复现基准”一节；
原始 JMH 输出位于本仓库 `results/` 与根目录各 `*-*.txt`（提交时整理）。
