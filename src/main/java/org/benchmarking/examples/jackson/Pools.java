package org.benchmarking.examples.jackson;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.util.BufferRecycler;
import com.fasterxml.jackson.core.util.JsonRecyclerPools;
import com.fasterxml.jackson.core.util.RecyclerPool;
import org.jctools.queues.MpmcUnboundedXaddArrayQueue;
import org.jctools.util.UnsafeAccess;

public class Pools {
    enum PoolStrategy {
        NO_OP(JsonRecyclerPools.nonRecyclingPool()),
        THREAD_LOCAL(JsonRecyclerPools.threadLocalPool()),
        CONCURRENT_DEQUEUE(JsonRecyclerPools.sharedConcurrentDequePool()),
        LOCK_FREE(JsonRecyclerPools.sharedLockFreePool()),
        JCTOOLS(JCToolsPool.INSTANCE),
        HYBRID_JCTOOLS(HybridJCToolsPool.INSTANCE),
        HYBRID_LOCK_FREE(HybridLockFreePool.INSTANCE),
        HYBRID_JACKSON_POOL(HybridJacksonPool.getInstance());

        private final RecyclerPool<BufferRecycler> pool;

        PoolStrategy(RecyclerPool pool) {
            this.pool = pool;
        }

        public RecyclerPool<BufferRecycler> getPool() {
            return pool;
        }
    }

    static class JCToolsPool implements RecyclerPool<BufferRecycler> {

        static final RecyclerPool INSTANCE = new JCToolsPool();

        private final MpmcUnboundedXaddArrayQueue<BufferRecycler> queue = new MpmcUnboundedXaddArrayQueue<>(256);

        @Override
        public BufferRecycler acquirePooled() {
            BufferRecycler bufferRecycler = queue.poll();
            return bufferRecycler != null ? bufferRecycler : new BufferRecycler();
        }

        @Override
        public void releasePooled(BufferRecycler recycler) {
            queue.offer(recycler);
        }
    }

    static class VirtualPredicate {
        private static final MethodHandle virtualMh = findVirtualMH();

        private static MethodHandle findVirtualMH() {
            try {
                return MethodHandles.publicLookup().findVirtual(Thread.class, "isVirtual", MethodType.methodType(boolean.class));
            } catch (Exception e) {
                return null;
            }
        }

        private static Predicate<Thread> findIsVirtualPredicate() {
            return virtualMh != null ? t -> {
                try {
                    return (boolean) virtualMh.invokeExact(t);
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
            } : t -> false;
        }
    }

    private record XorShiftThreadProbe(int mask) {

        public int index() {
            return probe() & mask;
        }

        private int probe() {
            // Multiplicative Fibonacci hashing implementation
            // 0x9e3779b9 is the integral part of the Golden Ratio's fractional part 0.61803398875… (sqrt(5)-1)/2
            // multiplied by 2^32, which has the best possible scattering properties.
            int probe = (int) ((Thread.currentThread().getId() * 0x9e3779b9) & Integer.MAX_VALUE);
            // xorshift
            probe ^= probe << 13;
            probe ^= probe >>> 17;
            probe ^= probe << 5;
            return probe;
        }
    }

    static class HybridJCToolsPool implements RecyclerPool<BufferRecycler> {

        static final RecyclerPool<BufferRecycler> INSTANCE = new HybridJCToolsPool();

        private static final Predicate<Thread> isVirtual = VirtualPredicate.findIsVirtualPredicate();

        private final RecyclerPool<BufferRecycler> nativePool = JsonRecyclerPools.threadLocalPool();

        static class VirtualPoolHolder {
            // Lazy on-demand initialization
            private static final RecyclerPool<BufferRecycler> virtualPool = new StripedJCToolsPool();
        }

        @Override
        public BufferRecycler acquirePooled() {
            return isVirtual.test(Thread.currentThread()) ?
                    VirtualPoolHolder.virtualPool.acquirePooled() :
                    nativePool.acquirePooled();
        }

        @Override
        public void releasePooled(BufferRecycler bufferRecycler) {
            if (bufferRecycler instanceof VThreadBufferRecycler) {
                // if it is a PooledBufferRecycler it has been acquired by a virtual thread, so it has to be released to the same pool
                VirtualPoolHolder.virtualPool.releasePooled(bufferRecycler);
            }
            // the native thread pool is based on ThreadLocal, so it doesn't have anything to do on release
        }
    }

    static class StripedJCToolsPool implements RecyclerPool<BufferRecycler> {

        private final XorShiftThreadProbe threadProbe;

        private final MpmcUnboundedXaddArrayQueue<BufferRecycler>[] queues;

        public StripedJCToolsPool() {
            this(Runtime.getRuntime().availableProcessors());
        }

        public StripedJCToolsPool(int stripesCount) {
            if (stripesCount <= 0) {
                throw new IllegalArgumentException("Expecting a stripesCount that is larger than 0");
            }

            int size = roundToPowerOfTwo(stripesCount);
            this.threadProbe = new XorShiftThreadProbe(size - 1);

            this.queues = new MpmcUnboundedXaddArrayQueue[size];
            for (int i = 0; i < size; i++) {
                this.queues[i] = new MpmcUnboundedXaddArrayQueue<>(128);
            }
        }

        @Override
        public BufferRecycler acquirePooled() {
            int index = threadProbe.index();
            BufferRecycler bufferRecycler = queues[index].poll();
            return bufferRecycler != null ? bufferRecycler : new VThreadBufferRecycler(index);
        }

        @Override
        public void releasePooled(BufferRecycler recycler) {
            queues[((VThreadBufferRecycler) recycler).slot].offer(recycler);
        }
    }

    static class VThreadBufferRecycler extends BufferRecycler {
        private final int slot;

        VThreadBufferRecycler(int slot) {
            this.slot = slot;
        }
    }

    static class HybridLockFreePool implements RecyclerPool<BufferRecycler> {

        static final RecyclerPool INSTANCE = new HybridLockFreePool();

        private static final Predicate<Thread> isVirtual = VirtualPredicate.findIsVirtualPredicate();

        private final RecyclerPool<BufferRecycler> nativePool = JsonRecyclerPools.threadLocalPool();

        static class VirtualPoolHolder {
            // Lazy on-demand initialization
            private static final RecyclerPool<BufferRecycler> virtualPool = new StripedLockFreePool();
        }

        @Override
        public BufferRecycler acquirePooled() {
            return isVirtual.test(Thread.currentThread()) ?
                    VirtualPoolHolder.virtualPool.acquirePooled() :
                    nativePool.acquirePooled();
        }

        @Override
        public void releasePooled(BufferRecycler bufferRecycler) {
            if (bufferRecycler instanceof VThreadBufferRecycler) {
                // if it is a PooledBufferRecycler it has been acquired by a virtual thread, so it has to be released to the same pool
                VirtualPoolHolder.virtualPool.releasePooled(bufferRecycler);
            }
            // the native thread pool is based on ThreadLocal, so it doesn't have anything to do on release
        }
    }

    static class StripedLockFreePool implements RecyclerPool<BufferRecycler> {

        private static final int CACHE_LINE_SHIFT = 4;

        private static final int CACHE_LINE_PADDING = 1 << CACHE_LINE_SHIFT;

        private final XorShiftThreadProbe threadProbe;

        private final AtomicReferenceArray<Node> heads;

        public StripedLockFreePool() {
            this(Runtime.getRuntime().availableProcessors());
        }

        public StripedLockFreePool(int stripesCount) {
            if (stripesCount <= 0) {
                throw new IllegalArgumentException("Expecting a stripesCount that is larger than 0");
            }

            int size = roundToPowerOfTwo(stripesCount);
            this.heads = new AtomicReferenceArray<>(size * CACHE_LINE_PADDING);

            int mask = (size - 1) << CACHE_LINE_SHIFT;
            this.threadProbe = new XorShiftThreadProbe(mask);
        }

        @Override
        public BufferRecycler acquirePooled() {
            int index = threadProbe.index();

            Node currentHead = heads.get(index);
            while (true) {
                if (currentHead == null) {
                    return new VThreadBufferRecycler(index);
                }

                Node witness = heads.compareAndExchange(index, currentHead, currentHead.next);
                if (witness == currentHead) {
                    currentHead.next = null;
                    return currentHead.value;
                } else {
                    currentHead = witness;
                }
            }
        }

        @Override
        public void releasePooled(BufferRecycler recycler) {
            VThreadBufferRecycler vThreadBufferRecycler = (VThreadBufferRecycler) recycler;
            Node newHead = new Node(vThreadBufferRecycler);

            Node next = heads.get(vThreadBufferRecycler.slot);
            while (true) {
                Node witness = heads.compareAndExchange(vThreadBufferRecycler.slot, next, newHead);
                if (witness == next) {
                    newHead.next = next;
                    return;
                } else {
                    next = witness;
                }
            }
        }

        private static class Node {
            final VThreadBufferRecycler value;
            Node next;

            Node(VThreadBufferRecycler value) {
                this.value = value;
            }
        }
    }

    public static final int MAX_POW2 = 1 << 30;

    public static int roundToPowerOfTwo(final int value) {
        if (value > MAX_POW2) {
            throw new IllegalArgumentException("There is no larger power of 2 int for value:"+value+" since it exceeds 2^31.");
        }
        if (value < 0) {
            throw new IllegalArgumentException("Given value:"+value+". Expecting value >= 0.");
        }
        final int nextPow2 = 1 << (32 - Integer.numberOfLeadingZeros(value - 1));
        return nextPow2;
    }
}
