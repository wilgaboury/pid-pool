package com.github.wilgaboury.pidpool;

import java.lang.ref.Cleaner;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

public class PidPool<T> {
  private static final Cleaner cleaner = Cleaner.create();

  private final PoolLifecycle<T> lifecycle;
  private final Queue<T> queue;
  private final Ref<Integer> maxSize;
  private final ReferenceQueue<T> referenceQueue;
  private final AtomicInteger inUse;

  public PidPool(PoolLifecycle<T> lifecycle) {
    this.lifecycle = lifecycle;
    this.queue = new ArrayDeque<>();
    this.maxSize = new Ref<>(0);
    this.referenceQueue = new ReferenceQueue<>();
    this.inUse = new AtomicInteger(0);

    Thread referenceThread = new Thread(PidPool.referenceThreadLoop(lifecycle, referenceQueue, queue, maxSize));
    referenceThread.setDaemon(true);
    referenceThread.start();

    cleaner.register(this, PidPool.cleanup(referenceThread));
  }

  public T take() {
    T obj = null;

    synchronized (queue) {
      if (!queue.isEmpty()) {
        obj = queue.poll();
      }
    }

    if (obj != null) {
      lifecycle.onDequeue(obj);
    } else {
      obj = lifecycle.create();
      new WeakReference<>(obj, referenceQueue);
    }

    inUse.incrementAndGet();
    return obj;
  }

  public void give(T obj) {
    synchronized (queue) {
      if (queue.size() < maxSize.get()) {
        queue.add(obj);
        obj = null;
      }
    }

    if (obj != null) {
      lifecycle.destroy(obj);
    }

    inUse.decrementAndGet();
  }

  public int getInUse() {
    return inUse.get();
  }

  public int getMaxSize() {
    synchronized (queue) {
      return maxSize.get();
    }
  }

  public void setMaxSize(int newMaxSize) {
    ArrayList<T> destroy = null;

    synchronized (queue) {
      maxSize.set(newMaxSize);
      int diff = queue.size() - newMaxSize;
      if (diff > 0) {
        destroy = new ArrayList<>(maxSize.get());
        for (; diff > 0; diff--) {
          destroy.add(queue.poll());
        }
      }
    }

    if (destroy != null) {
      for (var obj : destroy) {
        lifecycle.destroy(obj);
      }
    }
  }

  private static Runnable cleanup(Thread referenceThread) {
    return referenceThread::interrupt;
  }

  private static <T> Runnable referenceThreadLoop(PoolLifecycle<T> lifecycle, ReferenceQueue<T> referenceQueue, Queue<T> queue, Ref<Integer> maxSize) {
    return () -> {
      try {
        while (true) {
          var objRef = referenceQueue.remove();
          T obj = null;
          synchronized (queue) {
            if (queue.size() < maxSize.get()) {
              obj = objRef.get();
              if (obj != null) {
                queue.add(obj);
              }
            }
          }

          if (obj != null) {
            var obj0 = obj;
            Thread.ofVirtual().factory().newThread(() -> lifecycle.onEnqueue(obj0));
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    };
  }
}
