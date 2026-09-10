# task-serialization

腾讯犀牛鸟第二阶段任务二：Kona JDK 25 序列化性能优化。

目标：在 [TencentKona-25](https://github.com/Tencent/TencentKona-25) 上，先建立可复现的
序列化性能基准（任务 2.1），再基于 profiler 实测热点对 `java.io` 序列化实现做定向优化
（任务 2.2），并对比基线数据量化收益、持续迭代（任务 2.3）。

JDK 源码改动在 Kona fork 仓库进行，本仓库存放基准、数据与方案文档。

## 当前状态

- **任务 2.1（获取基准）：已完成** —— JMH 基线 7 项 + jtreg 功能验证 150/150 + profiler 热点分析
- **任务 2.2（优化实现）：已完成** —— 三项优化全部落地，Serializable/ObjectInputStream/ObjectStreamClass jtreg 全绿
- **任务 2.3（对比与迭代）：已完成** —— 多轮 JMH 对比、单对象回退排查与惰性缓存改进、GC 分配分析

## 仓库内容

- `BASELINE.md`：任务 2.1 基线报告——基准设计、基线数据、profiler 实测热点、jtreg 结果
- `PLAN-2.2.md`：任务 2.2 优化方案——基于实测热点定稿的三个优化项及验证流程
- `OPTIMIZATION.md`：任务 2.2/2.3 优化报告——改动、逐项验证、JMH 结果与迭代分析
- `bench/SerializationBench.java`：JMH 1.37 基准程序（7 个 benchmark，序列化/反序列化/往返分离）
- `baseline-release.txt`：JMH 基线原始输出（release 构建）
- `profile-stack.txt`：JMH `-prof stack` 采样原始输出
- `jtreg-serializable.txt`：jtreg Serializable 测试摘要（150/150 Passed）
- `results/2.2/`：任务 2.2/2.3 各轮 JMH 原始输出

JDK 源码改动位于 Kona fork 仓库的 [`task-serialization`](https://github.com/112345-cpn/TencentKona-25/tree/task-serialization)
分支（4 个提交），提交号见 `OPTIMIZATION.md`。

## 优化结论速览

- 批量写（serializeOrders / 单流多对象 1000）：归一化后约 **+5~10%**（`reset()` 变体约 +10%）
- 往返（roundtripOrders 1000）：约 **+2~7%**
- 反序列化（deserializeOrders / 单流多对象）：吞吐约 +3~4%，**每操作分配 -24.1%**
- serializeSingle：`@Fork(3)` 复测后归一化约 **-0.2%（中性）**；fork=1 时的负值是跨进程 JIT 噪声，见 OPTIMIZATION.md 第七节
- jtreg：Serializable 150/150、ObjectInputStream 4/4、ObjectStreamClass 6/6 全部通过

基准自 2026-09-10 起为 11 项、`@Fork(3)`，新增
`serializeSameStream`、`serializeSameStreamReset`、`deserializeSameStream`、
`deserializeSameStreamReset` 四个单流稳态场景。

## 环境

- Kona JDK 25 源码构建：`linux-x86_64-release`（openjdk 25.0.4-internal）
- JMH 1.37（jmh-core + generator-annprocess）
- WSL2 Ubuntu x86_64，4 核 / 7.8 GiB 内存

## 复现基准

```bash
# 1. 构建 JDK（首次）
bash configure && make jdk-image CONF=linux-x86_64-release

# 2. 编译基准（javac 需启用注解处理器）
javac -proc:full -cp jmh-core-1.37.jar SerializationBench.java

# 3. 运行基线（吞吐模式，fork=1，3×1s 预热 + 5×1s 测量）
java -cp jmh-core-1.37.jar:classes org.openjdk.jmh.Main SerializationBench

# 4. 热点采样（注意选项用分号分隔）
java -cp jmh-core-1.37.jar:classes org.openjdk.jmh.Main SerializationBench -prof 'stack:lines=3;top=6'

# 5. 功能回归
make test TEST="jtreg:test/jdk/java/io/Serializable" CONF=linux-x86_64-release
```

## 基线速览（release，ops/s，详见 BASELINE.md）

| Benchmark | Score |
|---|---:|
| serializeSingle | 1,619,127 |
| deserializeSingle | 416,980 |
| serializeOrders (1000) | 5,255 |
| deserializeOrders (1000) | 4,221 |
| roundtripOrders (1000) | 2,274 |
| serializeIntBox (32) | 1,701,152 |
| deserializeIntBox (32) | 401,455 |
