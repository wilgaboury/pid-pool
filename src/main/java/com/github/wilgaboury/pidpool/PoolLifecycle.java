package com.github.wilgaboury.pidpool;

@FunctionalInterface
public interface PoolLifecycle<T> {
  T create();
  default void onDequeue(T value) {}
  default void onEnqueue(T value) {}
  default void destroy(T value) {}
}
