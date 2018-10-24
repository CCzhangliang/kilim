package kilim;

import java.lang.reflect.Method;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kilim.timerservice.Timer;
import kilim.timerservice.TimerService;

/*
    testing with this scheduler:
        ant testcompile
        cp=$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/fd/1)
        java -ea -cp target/classes:$cp kilim.tools.Kilim kilim.ForkJoinScheduler \
            junit.textui.TestRunner kilim.test.AllWoven
*/


public class ForkJoinScheduler extends Scheduler
        implements TimerService.WatchdogContext {
    ForkJoinPool pool;
    private TimerService timerService;
    private AtomicInteger count = new AtomicInteger(0);

    public ForkJoinScheduler(int numThreads) {
        numThreads = numThreads < 0 ? numThreads : Scheduler.defaultNumberThreads;
        pool = new ForkJoinPool(numThreads);
        timerService = new TimerService(this);
    }

    public void publish(TimerService.WatchdogTask dog) {
        publish((Runnable) dog);
    }
    
    public boolean isEmpty() {
        return count.get()==0;
    }

    public boolean isPinnable() { return false; }
    
    public void schedule(int index,Task task) {
        assert index < 0 : "attempt to pin task to FJS";
        publish(task);
     }
    public void publish(Runnable task) {
        ForkJoinPool current = ForkJoinTask.getPool();
        ForkedRunnable fajita = new ForkedRunnable(task);
        count.incrementAndGet();
        if (current==pool)
            fajita.fork();
        else
            pool.submit(fajita);
    }

    public boolean isEmptyish() {
        return ! pool.hasQueuedSubmissions();
    }

    public int numThreads() {
        return pool.getParallelism();
    }

    public void scheduleTimer(Timer t) {
        timerService.submit(t);
    }

    private void noop() {}
    
    public void idledown() {
        while (! pool.awaitQuiescence(100,TimeUnit.MILLISECONDS))
            noop();
        shutdown();
    }

    final class ForkedRunnable<V> extends ForkJoinTask<V> {
        Runnable task;
        public ForkedRunnable(Runnable task) { this.task = task; }
        public V getRawResult() { return null; }
        protected void setRawResult(V value) {}
        protected boolean exec() {
            if (task instanceof Task) {
                int tid = ((ForkJoinWorkerThread) Thread.currentThread()).getPoolIndex();
                ((Task) task).setTid(tid);
            }
            task.run();
            timerService.trigger(ForkJoinScheduler.this);
            count.decrementAndGet();
            return true;
        }
    }
    public void shutdown() {
        super.shutdown();
        pool.shutdown();
        timerService.shutdown();
    }
    private static String[] processArgs(String[] args,int offset) {
        String[] ret = new String[args.length-offset];
        if (ret.length > 0) 
            System.arraycopy(args, offset, ret, 0, ret.length);
        return ret;
    }
    private static Integer parseNum(String [] args,int index) {
        try {
            return Integer.parseInt(args[index]);
        }
        catch (Throwable ex) { return null; }
    }
    private static void run(String className,String method,String ... args) throws Exception {
        Class<?> mainClass = ForkJoinScheduler.class.getClassLoader().loadClass(className);
        Method mainMethod = mainClass.getMethod(method, new Class[]{String[].class});
        mainMethod.invoke(null,new Object[] {args});
    }
    /** run the main method from another class using this scheduler as the default scheduler */
    public static void main(String[] args) throws Exception {
        Integer numThreads = parseNum(args,0);
        if (args.length < 2 | numThreads != null & args.length < 3) {
            System.out.println(
                    "usage:\n"
                    + "  java kilim.ForkJoinScheduler [numThreads] class [args]\n"
                    + "call the main method of the specified class and pass the remaining arguments,\n"
                    + "  using `new ForkJoinScheduler(numThreads)` as the default scheduler"
            );
            System.exit(1);
        }
        int num = numThreads==null || numThreads <= 0 ? Scheduler.defaultNumberThreads : numThreads;
        Scheduler sched = new ForkJoinScheduler(num);
        Scheduler.setDefaultScheduler(sched);
        String className = args[1];
        args = processArgs(args,2);
        run(className,"main",args);
    }
    
}
