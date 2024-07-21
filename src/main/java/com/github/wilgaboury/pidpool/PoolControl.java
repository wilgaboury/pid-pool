package com.github.wilgaboury.pidpool;

@FunctionalInterface
public interface PoolControl {
  int getMaxSize(int numInUse);
}
