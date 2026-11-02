#if defined(_WIN32) && !defined(_WIN32_WINNT)
#define _WIN32_WINNT 0x0601
#endif
#include "bf_runtime.h"
#include <stdlib.h>

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>

void *bf_plat_reserve(uint64_t size) { return VirtualAlloc(NULL, (SIZE_T)size, MEM_RESERVE, PAGE_NOACCESS); }
int bf_plat_commit(void *p, uint64_t size) { return VirtualAlloc(p, (SIZE_T)size, MEM_COMMIT, PAGE_READWRITE) != NULL; }
void bf_plat_decommit(void *p, uint64_t size) { VirtualFree(p, (SIZE_T)size, MEM_DECOMMIT); }

typedef struct { void (*fn)(void *); void *arg; } BfStart;
static DWORD WINAPI bf_thread_tramp(LPVOID p) { BfStart s = *(BfStart *)p; free(p); s.fn(s.arg); return 0; }
int bf_plat_thread(void (*fn)(void *), void *arg) {
    BfStart *s = malloc(sizeof *s); s->fn = fn; s->arg = arg;
    HANDLE h = CreateThread(NULL, 8u << 20, bf_thread_tramp, s, 0, NULL);
    if (!h) return -1;
    CloseHandle(h);
    return 0;
}
void bf_plat_sleep_ns(int64_t ns) { if (ns <= 0) SwitchToThread(); else Sleep((DWORD)((ns + 999999) / 1000000)); }
uint64_t bf_plat_ns(void) {
    static LARGE_INTEGER f; LARGE_INTEGER t;
    if (!f.QuadPart) QueryPerformanceFrequency(&f);
    QueryPerformanceCounter(&t);
    return (uint64_t)((double)t.QuadPart * 1e9 / (double)f.QuadPart);
}
static SRWLOCK klock = SRWLOCK_INIT;
static CONDITION_VARIABLE kcond = CONDITION_VARIABLE_INIT;
void bf_plat_klock(void) { AcquireSRWLockExclusive(&klock); }
void bf_plat_kunlock(void) { ReleaseSRWLockExclusive(&klock); }
void bf_plat_kwait(int64_t t) {
    SleepConditionVariableSRW(&kcond, &klock, t < 0 ? INFINITE : (DWORD)((t + 999999) / 1000000), 0);
}
void bf_plat_kbroadcast(void) { WakeAllConditionVariable(&kcond); }
#else
#include <pthread.h>
#include <sched.h>
#include <sys/mman.h>
#include <time.h>

void *bf_plat_reserve(uint64_t size) {
    void *p = mmap(NULL, size, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE, -1, 0);
    return p == MAP_FAILED ? NULL : p;
}
int bf_plat_commit(void *p, uint64_t size) { return mprotect(p, size, PROT_READ | PROT_WRITE) == 0; }
void bf_plat_decommit(void *p, uint64_t size) { madvise(p, size, MADV_DONTNEED); }

typedef struct { void (*fn)(void *); void *arg; } BfStart;
static void *bf_thread_tramp(void *p) { BfStart s = *(BfStart *)p; free(p); s.fn(s.arg); return NULL; }
int bf_plat_thread(void (*fn)(void *), void *arg) {
    BfStart *s = malloc(sizeof *s); s->fn = fn; s->arg = arg;
    pthread_attr_t a; pthread_attr_init(&a); pthread_attr_setstacksize(&a, 8u << 20);
    pthread_attr_setdetachstate(&a, PTHREAD_CREATE_DETACHED);
    pthread_t t; int r = pthread_create(&t, &a, bf_thread_tramp, s);
    pthread_attr_destroy(&a);
    return r ? -1 : 0;
}
void bf_plat_sleep_ns(int64_t ns) {
    if (ns <= 0) { sched_yield(); return; }
    struct timespec ts = {ns / 1000000000, ns % 1000000000};
    nanosleep(&ts, NULL);
}
uint64_t bf_plat_ns(void) { struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts); return (uint64_t)ts.tv_sec * 1000000000ull + ts.tv_nsec; }
static pthread_mutex_t klock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t kcond = PTHREAD_COND_INITIALIZER;
void bf_plat_klock(void) { pthread_mutex_lock(&klock); }
void bf_plat_kunlock(void) { pthread_mutex_unlock(&klock); }
void bf_plat_kwait(int64_t t) {
    if (t < 0) { pthread_cond_wait(&kcond, &klock); return; }
    struct timespec ts; clock_gettime(CLOCK_REALTIME, &ts);
    uint64_t ns = (uint64_t)ts.tv_nsec + (uint64_t)t;
    ts.tv_sec += ns / 1000000000; ts.tv_nsec = ns % 1000000000;
    pthread_cond_timedwait(&kcond, &klock, &ts);
}
void bf_plat_kbroadcast(void) { pthread_cond_broadcast(&kcond); }
#endif
