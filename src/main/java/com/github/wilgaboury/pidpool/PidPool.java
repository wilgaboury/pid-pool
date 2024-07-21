package com.github.wilgaboury.pidpool;

import java.lang.ref.Cleaner;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class PidPool<T> {
  private static final Cleaner cleaner = Cleaner.create();

  private static class State<T> {
    private final PoolLifecycle<T> lifecycle;
    private final Queue<T> queue; // synchronized
    private int maxSize; // synchronized by queue

    private final Object inUseLock;
    private int inUseSkip;
    private int inUse;

    public State(PoolLifecycle<T> lifecycle) {
      this.lifecycle = lifecycle;
      this.queue = new ArrayDeque<>();
      this.maxSize = 0;
      this.inUseLock = new Object();
      this.inUseSkip = 0;
      this.inUse = 0;
    }

    public void setMaxSize(int newMaxSize) {
      ArrayList<T> destroy = null;

      synchronized (queue) {
        maxSize = newMaxSize;
        int diff = queue.size() - newMaxSize;
        if (diff > 0) {
          destroy = new ArrayList<>(maxSize);
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
  }

  private final State<T> state;
  private final ReferenceQueue<T> referenceQueue;

  public PidPool(PoolLifecycle<T> lifecycle) {
    this.state = new State<>(lifecycle);
    this.referenceQueue = new ReferenceQueue<>();

    Thread referenceThread = new Thread(PidPool.referenceThreadLoop(state, referenceQueue));
    referenceThread.setDaemon(true);
    referenceThread.start();

    ScheduledExecutorService controlService = Executors.newSingleThreadScheduledExecutor();
    controlService.scheduleAtFixedRate(PidPool.controlLoop(state), 500, 500, TimeUnit.MILLISECONDS);

    cleaner.register(this, PidPool.cleanup(state, referenceThread, controlService));
  }

  public T take() {
    T obj = null;

    synchronized (state.queue) {
      if (!state.queue.isEmpty()) {
        obj = state.queue.poll();
      }
    }

    if (obj != null) {
      state.lifecycle.onDequeue(obj);
    } else {
      obj = state.lifecycle.create();
      new WeakReference<>(obj, referenceQueue);
    }

    synchronized (state.inUseLock) {
      state.inUse++;
    }
    return obj;
  }

  public void give(T obj) {
    synchronized (state.queue) {
      if (state.queue.size() < state.maxSize) {
        state.queue.add(obj);
        obj = null;
      }
    }

    synchronized (state.inUseLock) {
      state.inUseSkip++;
      state.inUse--;
    }

    if (obj != null) {
      state.lifecycle.destroy(obj);
    }
  }

  public int getInUse() {
    synchronized (state.inUseLock) {
      return state.inUse;
    }
  }

  public int getMaxSize() {
    synchronized (state.queue) {
      return state.maxSize;
    }
  }

  private static <T> Runnable cleanup(State<T> state, Thread referenceThread, ExecutorService controlService) {
    return () -> {
      referenceThread.interrupt();
      controlService.shutdown();
      try {
        controlService.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      while (!state.queue.isEmpty()) {
        var obj = state.queue.poll();
        state.lifecycle.destroy(obj);
      }
    };
  }

  private static <T> Runnable controlLoop(State<T> state) {
    return () -> {
      int inUse;
      synchronized (state.inUseLock) {
        inUse = state.inUse;
      }
      state.setMaxSize(inUse);
    };
  }

  private static <T> Runnable referenceThreadLoop(State<T> state, ReferenceQueue<T> referenceQueue) {
    return () -> {
      try {
        while (true) {
          referenceQueue.remove();
          synchronized (state.inUseLock) {
            if (state.inUseSkip > 0) {
              state.inUseSkip--;
            } else {
              state.inUse--;
            }
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    };
  }
}
