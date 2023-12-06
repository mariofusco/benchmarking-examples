`Benchmark                                            (objectSize)  (parallelTasks)       (poolStrategy)  (useVirtualThreads)   Mode  Cnt      Score   Error  Units
JacksonMultithreadWriteBenchmark.writePojoMediaItem         small              100                NO_OP                 true  thrpt    2   6161.030          ops/s
JacksonMultithreadWriteBenchmark.writePojoMediaItem         small              100                NO_OP                false  thrpt    2   6925.112          ops/s
JacksonMultithreadWriteBenchmark.writePojoMediaItem         small              100         THREAD_LOCAL                 true  thrpt    2   6004.822          ops/s
JacksonMultithreadWriteBenchmark.writePojoMediaItem         small              100         THREAD_LOCAL                false  thrpt    2  28973.385          ops/s
JacksonMultithreadWriteBenchmark.writePojoMediaItem         small              100              JCTOOLS                 true  thrpt    2   9432.998          ops/s
JacksonMultithreadWriteBenchmark.writePojoMediaItem         small              100              JCTOOLS                false  thrpt    2  25732.841          ops/s
JacksonMultithreadWriteBenchmark.writePojoMediaItem         small              100            LOCK_FREE                 true  thrpt    2   9505.897          ops/s
JacksonMultithreadWriteBenchmark.writePojoMediaItem         small              100            LOCK_FREE                false  thrpt    2  22817.310          ops/s
JacksonMultithreadWriteBenchmark.writePojoMediaItem         small              100       HYBRID_JCTOOLS                 true  thrpt    2   9645.357          ops/s
JacksonMultithreadWriteBenchmark.writePojoMediaItem         small              100       HYBRID_JCTOOLS                false  thrpt    2  28480.603          ops/s
JacksonMultithreadWriteBenchmark.writePojoMediaItem         small              100     HYBRID_LOCK_FREE                 true  thrpt    2   9646.710          ops/s
JacksonMultithreadWriteBenchmark.writePojoMediaItem         small              100     HYBRID_LOCK_FREE                false  thrpt    2  29090.609          ops/s
JacksonMultithreadWriteBenchmark.writePojoMediaItem         small              100  HYBRID_JACKSON_POOL                 true  thrpt    2   9524.990          ops/s
JacksonMultithreadWriteBenchmark.writePojoMediaItem         small              100  HYBRID_JACKSON_POOL                false  thrpt    2  28423.190          ops/s`