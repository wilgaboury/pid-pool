package com.github.wilgaboury.pidpool;

import java.lang.ref.Cleaner;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Queue;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class PidPool<T> {
  private static final Cleaner cleaner = Cleaner.create();

  private static class State<T> {
    private final PoolLifecycle<T> lifecycle;
    private final Queue<T> queue; // synchronized
    private int maxSize; // synchronized by queue
    private final Set<T> inUse;

    public State(PoolLifecycle<T> lifecycle) {
      this.lifecycle = lifecycle;
      this.queue = new ArrayDeque<>();
      this.maxSize = 0;
      this.inUse =  Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
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

  public PidPool(PoolLifecycle<T> lifecycle) {
    this.state = new State<>(lifecycle);

    ScheduledExecutorService controlService = Executors.newSingleThreadScheduledExecutor();
    controlService.scheduleAtFixedRate(PidPool.controlLoop(state), 500, 500, TimeUnit.MILLISECONDS);

    cleaner.register(this, PidPool.cleanupPool(state, controlService));
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
    }

    state.inUse.add(obj);

    return obj;
  }

  public void give(T obj) {
    synchronized (state.queue) {
      if (state.queue.size() < state.maxSize) {
        state.queue.add(obj);
        obj = null;
      }
    }

    if (obj != null) {
      state.inUse.remove(obj);
      state.lifecycle.destroy(obj);
    }
  }

  public int getInUse() {
    return state.inUse.size();
  }

  public int getMaxSize() {
    synchronized (state.queue) {
      return state.maxSize;
    }
  }

  private static <T> Runnable controlLoop(State<T> state) {
    return () -> {
      state.setMaxSize(state.inUse.size());
    };
  }

  private static <T> Runnable cleanupPool(State<T> state, ExecutorService controlService) {
    return () -> {
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
}
