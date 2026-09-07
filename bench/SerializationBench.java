package com.kona.bench;

import org.openjdk.jmh.annotations.*;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Kona JDK 25 序列化性能基准（JMH 1.37）。
 *
 * 覆盖四个典型场景：
 *  - 单个 POJO（小对象，元数据/反射路径占比高）
 *  - 千级订单列表（对象图遍历 + 类描述符复用）
 *  - int[] 原始数组（块数据 block-data 路径）
 *  - 序列化 / 反序列化分离度量 + 往返
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class SerializationBench {

    // ---------- 对象模型 ----------

    public static class Order implements Serializable {
        private static final long serialVersionUID = 1L;
        long id;
        String customer;
        double amount;
        int itemCount;
        String status;

        Order(long id, String customer, double amount, int itemCount, String status) {
            this.id = id;
            this.customer = customer;
            this.amount = amount;
            this.itemCount = itemCount;
            this.status = status;
        }
    }

    /** 32 元素的 int[]，走块数据写出路径。 */
    public static class IntBox implements Serializable {
        private static final long serialVersionUID = 1L;
        int[] data;
        IntBox(int n) {
            data = new int[n];
            for (int i = 0; i < n; i++) data[i] = i;
        }
    }

    // ---------- 参数 ----------

    @Param({"1000"})
    int listSize;

    @Param({"32"})
    int arrayLen;

    // ---------- 状态 ----------

    Order singleOrder;
    byte[] singleBlob;

    List<Order> orders;
    byte[] ordersBlob;

    IntBox intBox;
    byte[] intBoxBlob;

    @Param({"65536"})
    int bosInitialCap;

    @Setup
    public void setup() throws Exception {
        singleOrder = new Order(42L, "customer-42", 199.99, 3, "PAID");
        singleBlob = serialize(singleOrder);

        orders = new ArrayList<>(listSize);
        for (int i = 0; i < listSize; i++) {
            orders.add(new Order(i, "customer-" + i, i * 1.5, i % 10, (i % 2 == 0) ? "PAID" : "PENDING"));
        }
        ordersBlob = serialize(orders);

        intBox = new IntBox(arrayLen);
        intBoxBlob = serialize(intBox);
    }

    static byte[] serialize(Object o) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(o);
        }
        return bos.toByteArray();
    }

    static Object deserialize(byte[] blob) throws IOException, ClassNotFoundException {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(blob))) {
            return ois.readObject();
        }
    }

    // ---------- 序列化（写路径） ----------

    @Benchmark
    public byte[] serializeSingle() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(singleOrder);
        }
        return bos.toByteArray();
    }

    @Benchmark
    public byte[] serializeOrders() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(bosInitialCap);
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(orders);
        }
        return bos.toByteArray();
    }

    @Benchmark
    public byte[] serializeIntBox() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(intBox);
        }
        return bos.toByteArray();
    }

    // ---------- 反序列化（读路径） ----------

    @Benchmark
    public Object deserializeSingle() throws Exception {
        return deserialize(singleBlob);
    }

    @Benchmark
    public Object deserializeOrders() throws Exception {
        return deserialize(ordersBlob);
    }

    @Benchmark
    public Object deserializeIntBox() throws Exception {
        return deserialize(intBoxBlob);
    }

    // ---------- 往返 ----------

    @Benchmark
    public Object roundtripOrders() throws Exception {
        return deserialize(serialize(orders));
    }
}
