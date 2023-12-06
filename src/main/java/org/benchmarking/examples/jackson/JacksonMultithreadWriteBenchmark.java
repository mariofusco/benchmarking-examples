package org.benchmarking.examples.jackson;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.benchmarking.examples.jackson.model.MediaItems;
import org.benchmarking.examples.jackson.model.Person;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@State(value = Scope.Benchmark)
@Warmup(iterations = 1)
@Measurement(iterations = 2)
@Fork(1)
public class JacksonMultithreadWriteBenchmark {
    private JsonFactory jsonFactory;
    private ObjectMapper objectMapper;

    private Object item;
    private int expectedSize;

    private Consumer<Runnable> runner;

    @Param({"true", "false"})
    private boolean useVirtualThreads;

//    @Param({"10", "100", "1000"})
    @Param({"100"})
    private int parallelTasks;

//    @Param({"large", "small"})
    @Param({"small"})
    private String objectSize;

    @Param({"NO_OP", "THREAD_LOCAL", "JCTOOLS", "LOCK_FREE", "HYBRID_JCTOOLS", "HYBRID_LOCK_FREE", "HYBRID_JACKSON_POOL"})
    private String poolStrategy;

    @Setup
    public void setup() {
        this.jsonFactory = JsonFactory.builder().recyclerPool(Pools.PoolStrategy.valueOf(poolStrategy).getPool()).build();
        this.objectMapper = new ObjectMapper(jsonFactory);
        this.runner = createRunner();
        if (objectSize.equalsIgnoreCase("large")) {
            this.item = MediaItems.stdMediaItem();
            this.expectedSize = 489;
        } else {
            this.item = new Person("Mario", "Fusco", 49);
            this.expectedSize = 49;
        }
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void writePojoMediaItem(Blackhole bh) {
        CountDownLatch countDown = new CountDownLatch(parallelTasks);

        for (int i = 0; i < parallelTasks; i++) {
            runner.accept(() -> {
                bh.consume(write(item));
                countDown.countDown();
            });
        }

        try {
            countDown.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private int write(Object value) {
        NopOutputStream out = new NopOutputStream();
        try (JsonGenerator jsonGenerator = jsonFactory.createGenerator(out)) {
            jsonGenerator.setCodec(objectMapper);
            jsonGenerator.writeObject(value);
            jsonGenerator.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        int size = out.size();
        if (size != expectedSize) {
            throw new IllegalStateException("Unexpected size: " + size);
        }
        return size;
    }

    private Consumer<Runnable> createRunner() {
        if (useVirtualThreads) {
            return Thread::startVirtualThread;
        } else {
            return Executors.newWorkStealingPool()::execute;
        }
    }

    public static void main(String[] args) {
        JacksonMultithreadWriteBenchmark benchmark = new JacksonMultithreadWriteBenchmark();
        benchmark.poolStrategy = "HYBRID_LOCK_FREE";
        benchmark.objectSize = "small";
        benchmark.parallelTasks = 100;
        benchmark.useVirtualThreads = true;
        benchmark.setup();

        System.out.println( benchmark.write(benchmark.item) );

//        Blackhole bh = new Blackhole("Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.");
//        for (int i = 0; i < 10; i++) {
//            benchmark.writePojoMediaItem(bh);
//        }
    }
}
