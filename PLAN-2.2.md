# 任务 2.2 优化方案：Kona JDK 25 序列化性能优化

日期：2026-08-28
依据：JMH 基线（BASELINE.md）+ stack profiler 实测热点 + 源码走读

## 一、热点实测（profiler 数据）

`-prof stack` 采样（Orders 1000 场景，RUNNABLE 栈占比，扣除 JIT 噪声后）：

**写路径（serializeOrders）**
| 热点 | 占比 | 栈路径 |
|---|---:|---|
| `HandleTable.lookup` | **~11.7%** | writeClassDesc 路径 6.1% + defaultWriteFields 字段对象查重 5.6% |
| `FieldReflector.getPrimFieldValues` | 5.5% | 原始字段逐个 unsafe 写出 |
| `BlockDataOutputStream.setBlockDataMode` | 5.5% | 每对象进出模式切换 |
| `BlockDataOutputStream.write` | 5.0% | primVals 块写出 |

**读路径（deserializeOrders）**
| 热点 | 占比 | 栈路径 |
|---|---:|---|
| `readUTF`（含 readUTFBody） | **~11.4%** | 字符串字段读取 |
| `readFully/readHandle` | 9.2% | TC_REFERENCE 引用解析（协议必须，不可省） |
| `FieldValues.<init>` | **~8.4%** | 每对象分配 byte[]/Object[]/int[] 并读字段 |

**已排除的方向**（上游 JDK 25 已做过，不重复）：
- 写侧 UTF：已有 `countNonZeroAscii` 向量化快路径
- 读侧 UTF：已有 `uncheckedCountPositives` + ISO-8859-1 直构 + `uncheckedInflateBytesToChars` 快路径

## 二、优化项（按性价比排序）

### 优化 1：writeClassDesc 增加最近描述符句柄缓存（写侧，低风险）

**改动**：`ObjectOutputStream` 增加两个字段 `ObjectStreamClass lastDesc / int lastDescHandle`；
`writeClassDesc` 入口处若 `desc == lastDesc` 直接 `writeHandle(lastDescHandle)`；写非代理描述符
成功后记录。约 +10 行代码。

**原理**：写 1000 个同类 Order 时，每个对象都要 `writeClassDesc(desc)` → `handles.lookup(desc)`
走一遍哈希查找，而结果 1000 次都相同。同类对象批量序列化（DAO 批量、缓存批量）是最常见的
生产模式，命中率天然高。

**预期收益**：写侧 5~8%（消除 desc 重复哈希查找）。
**风险**：低。handle 语义不变（仍写出 TC_REFERENCE），仅需正确维护 assign 与缓存一致性。

### 优化 2：HandleTable 哈希取模改位掩码（写侧，低风险）

**改动**：`growSpine` 容量策略改为 2 的幂；`lookup/insert` 的 `hash(obj) % spine.length`
改为 `hash(obj) & (spine.length - 1)`；构造时初始容量向上取 2 幂。约 +6 行改动。

**原理**：lookup 是每对象多次执行的最内层操作（当前 11.7% 热点）。取模运算在运行时长度
非常数时无法被 JIT 优化成乘法，位掩码恒定快。

**预期收益**：写侧 2~4%（与优化 1 叠加）。
**风险**：低。纯内部数据结构替换，等价重散列逻辑已有现成 `growSpine` 循环。

### 优化 3：defaultReadObject 路径 FieldValues 数组复用（读侧，中风险）

**改动**：`ObjectInputStream` 增加流级复用缓冲（`byte[] primBuf / Object[] objBuf / int[] handleBuf`），
`readSerialData`/`defaultReadObject` 走的 `FieldValues` 构造接受外部复用数组；仅
`readFields()`（GetField 交给用户持有）保持每次分配。约 +25 行。

**原理**：读 1000 个 Order = 3000 个小数组分配。defaultReadObject 路径的 FieldValues 用完即弃
（值已 set 回对象字段），数组不逃逸，可安全复用；GetField 路径数组被用户持有，绝不能复用。

**预期收益**：读侧 3~6%（减分配 + 减 GC 压力；roundtrip 也受益）。
**风险**：中。必须严格区分"数组逃逸"（readFields）与"不逃逸"（defaultReadObject）两条路径，
漏判会引入数据竞争级别的正确性 bug。jtreg 的 readFields/defaultReadObject/validate 类测试是安全网。

## 三、验证流程（每项优化独立执行）

1. 改 `src/java.base/share/classes/java/io/` 对应文件
2. 增量构建 release（`make jdk-image CONF=linux-x86_64-release`，java.base 改动很快）
3. jtreg：`make test TEST='jtreg:test/jdk/java/io/Serializable' CONF=linux-x86_64-release`
   —— **150/150 必须全绿**，否则回滚该优化
4. JMH 复跑基线 7 项（相同参数），与 BASELINE.md 对比
5. 记录每项独立的收益/回归数据，写入 OPTIMIZATION.md

## 四、实施顺序

优化 1 → 2 → 3（写侧两个先行，读侧最后）。每项独立提交，任一项 jtreg 失败可单独回滚。

## 五、基线噪声控制

基线误差 14~27%（WSL2 抖动）。对比规则：单项变化 < 5% 视为噪声区间内；
显著优化需复跑一次确认方向一致。
