# PID Pool

This is a random idea that I had. It's the classic [object pool pattern](https://en.wikipedia.org/wiki/Object_pool_pattern) but the pool is dynamic but instead of having to set a hard size limit (which is usually just guess work and can often be hard to determine empirically). The idea is to use control theory to dynamically change it's maximum size based on the rate of object use. Ideally, this means that the pool will dynamically find the optimal speed to memory tradeoff.

In brief (and I am by no means an expert) a pid controller has a set point (the number of objects currently in use) and a process variable (the max size of our pool). The closed-loop control system calculates error and applies correction based on proportional, integral, and derivative terms. When the controller is tuned correctly it should put the system into an optimal state with minimal delay and overshoot.
