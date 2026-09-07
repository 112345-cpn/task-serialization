# 任务 2.1 基线报告：Kona JDK 25 序列化性能基准

日期：2026-08-28
构建：`/home/test/TencentKona-25-master` `linux-x86_64-release`（openjdk 25.0.4-internal）
基准：JMH 1.37，`SerializationBench.java`（吞吐模式，3×1s 预热 + 5×1s 测量，Fork 1）
环境：WSL2 x86_64，4 核 / 7.8 GiB 内存

## 基准场景设计

| Benchmark | 场景 | 说明 |
|---|---|---|
| serializeSingle | 单 POJO 序列化 | 元数据/反射路径占比高 |
| deserializeSingle | 单 POJO 反序列化 | 类描述符解析 + 实例构建 |
| serializeOrders | 1000 个 Order 的 ArrayList 序列化 | 对象图遍历 + handle 表压力 |
| deserializeOrders | 同上反序列化 | 对象图重建 |
| roundtripOrders | 序列化+反序列化往返 | 端到端 |
| serializeIntBox | 32 元素 int[] 的包装类 | block-data 块写出路径 |
| deserializeIntBox | 同上反序列化 | 块读取路径 |

## 基线数据（Kona JDK 25 release，ops/s）

| Benchmark | Score | Error (99.9% CI) |
|---|---:|---:|
| serializeSingle | 1,619,127 | ± 211,219 |
| deserializeSingle | 416,980 | ± 108,824 |
| serializeOrders | 5,255 | ± 1,442 |
| deserializeOrders | 4,221 | ± 903 |
| roundtripOrders | 2,274 | ± 436 |
| serializeIntBox | 1,701,152 | ± 372,469 |
| deserializeIntBox | 401,455 | ± 57,362 |

原始输出：[baseline-release.txt](baseline-release.txt)（本仓库）

## Profiler 实测热点（JMH `-prof stack`，Orders 1000 场景）

原始输出：[profile-stack.txt](profile-stack.txt)（本仓库）；结论与优化方案详见 [PLAN-2.2.md](PLAN-2.2.md)。

**写路径（serializeOrders，RUNNABLE 栈占比，扣除 JIT 噪声后）**

| 热点 | 占比 | 栈路径 |
|---|---:|---|
| `HandleTable.lookup` | ~11.7% | writeClassDesc 路径 6.1% + defaultWriteFields 字段对象查重 5.6% |
| `FieldReflector.getPrimFieldValues` | 5.5% | 原始字段逐个 unsafe 写出 |
| `BlockDataOutputStream.setBlockDataMode` | 5.5% | 每对象进出模式切换 |
| `BlockDataOutputStream.write` | 5.0% | primVals 块写出 |

**读路径（deserializeOrders）**

| 热点 | 占比 | 栈路径 |
|---|---:|---|
| `readUTF`（含 readUTFBody） | ~11.4% | 字符串字段读取 |
| `readFully/readHandle` | 9.2% | TC_REFERENCE 引用解析（协议必须，不可省） |
| `FieldValues.<init>` | ~8.4% | 每对象分配 byte[]/Object[]/int[] 并读字段 |

已排除方向（上游 JDK 25 已做过，不重复）：写侧 UTF 的 `countNonZeroAscii` 向量化快路径；读侧 UTF 的 `uncheckedCountPositives` + ISO-8859-1 直构 + `uncheckedInflateBytesToChars` 快路径。

## 初步观察

1. **反序列化比序列化慢约 4 倍**（single：162万 vs 42万 ops/s；orders：5255 vs 4221 ops/s）——读侧是更大的优化空间。
2. 千级订单列表：序列化 5255 ops/s ≈ 每秒可序列化 525 万个 Order；单个 Order 序列化成本约 190ns（含流头）。
3. 误差 14%~27% 偏大（WSL2 环境抖动）。后续优化对比时应增加迭代次数或使用配对运行降低噪声。

## 源码走读发现的热点候选（profiler 实测前的静态分析）

JDK 25 序列化实现已较新（`BlockDataOutputStream` 已合并为 `ObjectOutputStream` 内部类，UTF 写出有 `countNonZeroAscii` 向量化快路径），但仍存在可优化点：

| # | 位置 | 现状 | 优化候选 |
|---|---|---|---|
| 1 | `ObjectOutputStream.defaultWriteFields` | 每个对象分配 `new Object[numObjFields]`（objVals） | 流级复用缓冲，按最大 numObjFields 扩容 |
| 2 | `ObjectInputStream.FieldValues` | 每对象分配 `byte[]` + `Object[]` + `int[]` 三个数组 | 读侧同样有复用空间 |
| 3 | `HandleTable` / `ReplaceTable` | 对象句柄表 spine 链查找 + 扩容拷贝 | 数据结构/初始容量调优 |
| 4 | `readObject0` 类描述符路径 | 每条流重复写/读类描述符（协议要求，不易改） | 低优先 |

> 注：本表为 profiler 实测前的静态候选，其中候选 2、3 已被上节 profiler 数据证实为主要热点（HandleTable.lookup ~11.7%、FieldValues ~8.4%），最终三项优化方案见 [PLAN-2.2.md](PLAN-2.2.md)。

## jtreg 功能验证

**结果：150/150 全部通过**（2026-08-28，`linux-x86_64-release`）。测试明细见 [jtreg-serializable.txt](jtreg-serializable.txt)。

命令：`make test TEST="jtreg:test/jdk/java/io/Serializable" CONF=linux-x86_64-release`
范围：`test/jdk/java/io/Serializable` 共 231 个测试文件，实际执行 150 个测试，`Test results: passed: 150`，exitcode 0，无新增失败。
