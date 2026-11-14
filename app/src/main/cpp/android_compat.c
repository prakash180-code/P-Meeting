/* Android compatibility stubs for ggml-cpu.c Linux-specific APIs */
#include <stddef.h>

typedef struct { unsigned long __bits[16]; } cpu_set_t;

int sched_setaffinity(int pid, size_t size, const cpu_set_t *mask) {
    (void)pid; (void)size; (void)mask;
    return 0;
}

#define CPU_ZERO(cpuset) ((cpuset)->__bits[0] = 0)
#define CPU_SET(cpu, cpuset) ((cpuset)->__bits[(cpu) / (8 * sizeof(unsigned long))] |= (1UL << ((cpu) % (8 * sizeof(unsigned long)))))
