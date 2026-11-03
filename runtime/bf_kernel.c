// Horizon kernel HLE: memory regions, handles, threads, synchronization and the SVC table.
#include "bf_runtime.h"
#include <setjmp.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>

int bf_verbose;
#ifdef __ANDROID__
#include <android/log.h>
void bf_log(const char *fmt, ...) {
    va_list ap; va_start(ap, fmt); __android_log_vprint(ANDROID_LOG_INFO, "bifrost", fmt, ap); va_end(ap);
}
#else
void bf_log(const char *fmt, ...) {
    va_list ap; va_start(ap, fmt); vfprintf(stderr, fmt, ap); va_end(ap);
}
#endif

#define R_OK 0u
#define R_TIMEOUT 0xEA01u
#define R_CANCELLED 0xEC01u
#define R_BAD_HANDLE 0xE401u
#define R_BAD_STATE 0xFA01u
#define R_NOT_IMPL 0xF001u

// ---- memory regions (for svcQueryMemory)
typedef struct { uint64_t va, size; uint32_t type, perm; } BfRegion;
static BfRegion regions[4096];
static int nregions;
static uint64_t heap_size;

void bf_commit_guest(uint64_t va, uint64_t size) {
    uint64_t lo = va & ~0xFFFull, hi = (va + size + 0xFFF) & ~0xFFFull;
    if (hi > lo && !bf_plat_commit(BF_HOST(lo), hi - lo)) {
        bf_log("bifrost: cannot commit guest memory %llx+%llx\n", (unsigned long long)lo, (unsigned long long)(hi - lo));
        exit(5);
    }
}
void bf_region_add(uint64_t va, uint64_t size, uint32_t type, uint32_t perm) {
    for (int i = 0; i < nregions; i++)
        if (regions[i].va == va) { regions[i].size = size; regions[i].type = type; regions[i].perm = perm; return; }
    if (nregions < 4096) regions[nregions++] = (BfRegion){va, size, type, perm};
}
static void region_remove(uint64_t va) {
    for (int i = 0; i < nregions; i++)
        if (regions[i].va == va) { regions[i] = regions[--nregions]; return; }
}
static uint64_t misc_next = BF_MISC_BASE, tls_next = BF_TLS_BASE;
uint64_t bf_misc_alloc(uint64_t size) {
    bf_plat_klock();
    size = (size + 0xFFFF) & ~0xFFFFull;
    uint64_t va = misc_next; misc_next += size;
    bf_commit_guest(va, size);
    bf_region_add(va, size, 0x5, BF_PERM_R | BF_PERM_W);
    bf_plat_kunlock();
    return va;
}
uint64_t bf_tls_alloc(void) {
    if ((tls_next & 0xFFF) == 0) { bf_commit_guest(tls_next, 0x1000); bf_region_add(tls_next, 0x1000, 0xC, BF_PERM_R | BF_PERM_W); }
    uint64_t va = tls_next; tls_next += 0x200;
    memset(BF_HOST(va), 0, 0x200);
    return va;
}

static void query_memory(uint64_t info, uint64_t addr) {
    uint64_t base = 0, size = 0; uint32_t type = 0, perm = 0;
    for (size_t i = 0; i < bf_segment_count; i++) {
        const BfSegment *s = &bf_segments[i];
        if (addr >= s->va && addr < s->va + s->size) { base = s->va; size = s->size; type = s->mem_type; perm = s->perm; goto found; }
    }
    for (int i = 0; i < nregions; i++)
        if (addr >= regions[i].va && addr < regions[i].va + regions[i].size) {
            base = regions[i].va; size = regions[i].size; type = regions[i].type; perm = regions[i].perm; goto found;
        }
    {
        uint64_t lo = BF_AS_BASE, hi = BF_AS_BASE + BF_AS_SIZE;
        if (addr < BF_AS_BASE) { lo = 0; hi = BF_AS_BASE; }
        else if (addr >= hi) { lo = hi; hi = ~0ull; type = 0x10; }
        for (size_t i = 0; i < bf_segment_count; i++) {
            uint64_t s = bf_segments[i].va, e = s + bf_segments[i].size;
            if (e <= addr && e > lo) lo = e;
            if (s > addr && s < hi) hi = s;
        }
        for (int i = 0; i < nregions; i++) {
            uint64_t s = regions[i].va, e = s + regions[i].size;
            if (e <= addr && e > lo) lo = e;
            if (s > addr && s < hi) hi = s;
        }
        base = lo; size = hi - lo;
    }
found:
    ST64(info + 0, base); ST64(info + 8, size); ST32(info + 16, type); ST32(info + 20, 0);
    ST32(info + 24, perm); ST32(info + 28, 0); ST32(info + 32, 0); ST32(info + 36, 0);
}

// ---- threads and handles
struct BfThread {
    BfCpu cpu;
    uint32_t handle;
    uint64_t id;
    int32_t prio;
    int exited, cancel;
    int wait_kind; // 1 mutex, 2 condvar, 3 address
    uint64_t wait_addr, wait_key;
    uint32_t wait_tag;
    int woken;
    jmp_buf exit_jmp;
};
typedef struct { int signaled; } BfEvent;
typedef struct { int kind; void *obj; } BfHandle;
static BfHandle handles[8192];
static BfThread *threads[1024];
static int nthreads;
static uint64_t next_thread_id = 0x50;
static _Thread_local BfThread *cur;
static int live_threads;

uint32_t bf_handle_new(int kind, void *obj) {
    for (uint32_t i = 1; i < 8192; i++)
        if (!handles[i].kind) { handles[i].kind = kind; handles[i].obj = obj; return 0x10000u | i; }
    bf_log("bifrost: handle table exhausted\n");
    exit(6);
}
static BfHandle *handle_get(uint32_t h) {
    if (h == 0xFFFF8001u && cur) h = cur->handle;
    uint32_t i = h & 0xFFFF;
    if ((h >> 16) != 1 || i >= 8192 || !handles[i].kind) return NULL;
    return &handles[i];
}
void *bf_handle_obj(uint32_t h, int kind) {
    BfHandle *e = handle_get(h);
    return e && e->kind == kind ? e->obj : NULL;
}
uint32_t bf_event_new(void) { return bf_handle_new(BF_H_EVENT, calloc(1, sizeof(BfEvent))); }
void bf_event_signal(uint32_t h) {
    bf_plat_klock();
    BfEvent *e = bf_handle_obj(h, BF_H_EVENT);
    if (e) { e->signaled = 1; bf_plat_kbroadcast(); }
    bf_plat_kunlock();
}

BfCpu *bf_kernel_create_thread(uint64_t entry, uint64_t arg, uint64_t sp, uint32_t *handle) {
    BfThread *t = calloc(1, sizeof *t);
    t->cpu.pc = entry; t->cpu.x[0] = arg; t->cpu.sp = sp & ~15ull; t->cpu.x[30] = BF_THREAD_EXIT;
    t->cpu.tpidrro = bf_tls_alloc();
    t->cpu.thread = t;
    t->id = next_thread_id++;
    t->prio = 44;
    t->handle = bf_handle_new(BF_H_THREAD, t);
    if (nthreads < 1024) threads[nthreads++] = t;
    *handle = t->handle;
    return &t->cpu;
}
static void thread_main(void *p) {
    BfThread *t = p;
    cur = t;
    if (!setjmp(t->exit_jmp)) bf_dispatch_until(&t->cpu, BF_THREAD_EXIT);
    bf_plat_klock();
    t->exited = 1;
    live_threads--;
    bf_plat_kbroadcast();
    bf_plat_kunlock();
}
void bf_kernel_start_thread(uint32_t handle) {
    BfThread *t = bf_handle_obj(handle, BF_H_THREAD);
    if (!t) return;
    bf_plat_klock(); live_threads++; bf_plat_kunlock();
    if (bf_plat_thread(thread_main, t)) { bf_log("bifrost: cannot create host thread\n"); exit(6); }
}
void bf_kernel_wait_exit(void) {
    BfThread *main = threads[0];
    bf_plat_klock();
    while (!main->exited) bf_plat_kwait(-1);
    bf_plat_kunlock();
}
void bf_kernel_init(void) {}

// ---- synchronization (all under the kernel lock)
static int64_t deadline_of(int64_t timeout) { return timeout < 0 ? -1 : (int64_t)(bf_plat_ns() + (uint64_t)timeout); }
static int kwait_until(int64_t deadline) { // returns 0 on timeout
    if (deadline < 0) { bf_plat_kwait(-1); return 1; }
    int64_t left = deadline - (int64_t)bf_plat_ns();
    if (left <= 0) return 0;
    bf_plat_kwait(left);
    return 1;
}
static void mutex_release(uint64_t addr) {
    BfThread *next = NULL; int others = 0;
    for (int i = 0; i < nthreads; i++) {
        BfThread *t = threads[i];
        if (t->wait_kind != 1 || t->wait_addr != addr) continue;
        if (!next || t->prio < next->prio) { if (next) others++; next = t; } else others++;
    }
    if (!next) { ST32(addr, 0); return; }
    ST32(addr, next->wait_tag | (others ? 0x40000000u : 0));
    next->wait_kind = 0; next->woken = 1;
    bf_plat_kbroadcast();
}
static uint32_t arbitrate_lock(uint32_t owner, uint64_t addr, uint32_t tag) {
    bf_plat_klock();
    if (LD32(addr) != (owner | 0x40000000u)) { bf_plat_kunlock(); return R_OK; }
    cur->wait_kind = 1; cur->wait_addr = addr; cur->wait_tag = tag; cur->woken = 0;
    while (!cur->woken) bf_plat_kwait(-1);
    bf_plat_kunlock();
    return R_OK;
}
static uint32_t cv_wait(uint64_t mutex, uint64_t key, uint32_t tag, int64_t timeout) {
    bf_plat_klock();
    mutex_release(mutex);
    cur->wait_kind = 2; cur->wait_addr = mutex; cur->wait_key = key; cur->wait_tag = tag; cur->woken = 0;
    ST32(key, 1);
    int64_t dl = deadline_of(timeout);
    uint32_t rc = R_OK;
    while (!cur->woken) {
        if (cur->wait_kind == 2 && !kwait_until(dl)) { cur->wait_kind = 0; rc = R_TIMEOUT; break; }
        if (cur->wait_kind == 1 && dl >= 0 && !cur->woken) bf_plat_kwait(-1);
    }
    bf_plat_kunlock();
    return rc;
}
static void cv_signal(uint64_t key, int32_t count) {
    bf_plat_klock();
    int n = 0, remaining = 0;
    for (int i = 0; i < nthreads; i++) {
        BfThread *t = threads[i];
        if (t->wait_kind != 2 || t->wait_key != key) continue;
        if (count > 0 && n >= count) { remaining++; continue; }
        n++;
        uint32_t v = LD32(t->wait_addr);
        if (v == 0) { ST32(t->wait_addr, t->wait_tag); t->wait_kind = 0; t->woken = 1; }
        else { ST32(t->wait_addr, v | 0x40000000u); t->wait_kind = 1; }
    }
    if (!remaining) ST32(key, 0);
    bf_plat_kbroadcast();
    bf_plat_kunlock();
}
static int addr_waiters(uint64_t addr) {
    int n = 0;
    for (int i = 0; i < nthreads; i++) n += threads[i]->wait_kind == 3 && threads[i]->wait_addr == addr;
    return n;
}
static void addr_wake(uint64_t addr, int32_t count) {
    int n = 0;
    for (int i = 0; i < nthreads; i++) {
        BfThread *t = threads[i];
        if (t->wait_kind == 3 && t->wait_addr == addr && (count <= 0 || n < count)) { t->wait_kind = 0; t->woken = 1; n++; }
    }
    bf_plat_kbroadcast();
}
static uint32_t wait_for_address(uint64_t addr, uint32_t type, int32_t value, int64_t timeout) {
    bf_plat_klock();
    int32_t v = (int32_t)LD32(addr);
    int ok = type == 2 ? v == value : v < value;
    if (!ok) { bf_plat_kunlock(); return R_BAD_STATE; }
    if (type == 1) ST32(addr, (uint32_t)(v - 1));
    if (timeout == 0) { bf_plat_kunlock(); return R_TIMEOUT; }
    cur->wait_kind = 3; cur->wait_addr = addr; cur->woken = 0;
    int64_t dl = deadline_of(timeout);
    uint32_t rc = R_OK;
    while (!cur->woken) if (!kwait_until(dl)) { cur->wait_kind = 0; rc = R_TIMEOUT; break; }
    bf_plat_kunlock();
    return rc;
}
static uint32_t signal_to_address(uint64_t addr, uint32_t type, int32_t value, int32_t count) {
    bf_plat_klock();
    if (type != 0) {
        int32_t v = (int32_t)LD32(addr);
        if (v != value) { bf_plat_kunlock(); return R_BAD_STATE; }
        if (type == 1) ST32(addr, (uint32_t)(value + 1));
        else {
            int n = addr_waiters(addr);
            int32_t nv = n == 0 ? value + 1 : (count <= 0 || count >= n) ? value - 1 : value;
            ST32(addr, (uint32_t)nv);
        }
    }
    addr_wake(addr, count);
    bf_plat_kunlock();
    return R_OK;
}
static int handle_signaled(uint32_t h) {
    BfHandle *e = handle_get(h);
    if (!e) return -1;
    if (e->kind == BF_H_EVENT) return ((BfEvent *)e->obj)->signaled;
    if (e->kind == BF_H_THREAD) return ((BfThread *)e->obj)->exited;
    return 0;
}
static uint32_t wait_sync(BfCpu *c, uint64_t list, int32_t count, int64_t timeout) {
    bf_plat_klock();
    int64_t dl = deadline_of(timeout);
    uint32_t rc;
    for (;;) {
        if (cur->cancel) { cur->cancel = 0; rc = R_CANCELLED; break; }
        int hit = -1;
        for (int i = 0; i < count; i++) {
            int s = handle_signaled(LD32(list + 4 * (uint64_t)i));
            if (s < 0) { bf_plat_kunlock(); return R_BAD_HANDLE; }
            if (s) { hit = i; break; }
        }
        if (hit >= 0) { c->x[1] = (uint64_t)hit; rc = R_OK; break; }
        if (timeout == 0 || !kwait_until(dl)) { rc = R_TIMEOUT; break; }
    }
    bf_plat_kunlock();
    return rc;
}

static uint64_t get_info(uint32_t id, uint64_t sub, uint32_t *rc) {
    *rc = R_OK;
    switch (id) {
    case 0: return 0xF;
    case 1: return 0xFFFFFFFFFFFFFFFFull;
    case 2: return BF_ALIAS_BASE;
    case 3: return BF_ALIAS_SIZE;
    case 4: return BF_HEAP_BASE;
    case 5: return BF_HEAP_SIZE;
    case 6: case 21: return 0xCD500000ull;
    case 7: case 22: return heap_size + 0x10000000ull;
    case 8: case 9: case 10: case 16: case 17: case 20: return 0;
    case 11: return 0x9E3779B97F4A7C15ull * (sub + 1) ^ bf_plat_ns();
    case 12: return BF_CODE_BASE;
    case 13: return BF_AS_BASE + BF_AS_SIZE - BF_CODE_BASE;
    case 14: return BF_STACK_BASE;
    case 15: return BF_STACK_SIZE;
    case 18: return bf_title_id;
    case 23: return 1;
    default:
        if (bf_verbose) bf_log("bifrost: svcGetInfo(%u, %llu) unknown\n", id, (unsigned long long)sub);
        *rc = 0xF001u;
        return 0;
    }
}

void bf_svc(BfCpu *c, uint32_t imm, uint64_t pc) {
    uint32_t rc = R_OK;
    if (!cur) cur = c->thread;
    if (bf_verbose > 1) bf_log("svc 0x%02x at %llx\n", imm, (unsigned long long)pc);
    switch (imm) {
    case 0x01: { // SetHeapSize
        uint64_t size = c->x[1];
        if (size > BF_HEAP_SIZE) { rc = 0xD401u; break; }
        bf_plat_klock();
        if (size > heap_size) bf_commit_guest(BF_HEAP_BASE + heap_size, size - heap_size);
        heap_size = size;
        if (size) bf_region_add(BF_HEAP_BASE, size, 0x5, BF_PERM_R | BF_PERM_W); else region_remove(BF_HEAP_BASE);
        bf_plat_kunlock();
        c->x[1] = BF_HEAP_BASE;
        break;
    }
    case 0x02: case 0x03: case 0x2A: case 0x2B: case 0x5F: case 0x32: case 0x0F: case 0x0D: case 0x2D: case 0x14: break;
    case 0x04: // MapMemory(dst, src, size)
        bf_plat_klock();
        bf_commit_guest(c->x[0], c->x[2]);
        memcpy(BF_HOST(c->x[0]), BF_HOST(c->x[1]), c->x[2]);
        bf_region_add(c->x[0], c->x[2], 0xB, BF_PERM_R | BF_PERM_W);
        bf_plat_kunlock();
        break;
    case 0x05: // UnmapMemory(dst, src, size)
        bf_plat_klock();
        memcpy(BF_HOST(c->x[1]), BF_HOST(c->x[0]), c->x[2]);
        region_remove(c->x[0]);
        bf_plat_kunlock();
        break;
    case 0x06: bf_plat_klock(); query_memory(c->x[0], c->x[2]); bf_plat_kunlock(); c->x[1] = 0; break;
    case 0x07: fflush(NULL); exit(0);
    case 0x08: { // CreateThread(_, entry, arg, stack_top, prio, core)
        uint32_t h;
        bf_plat_klock();
        BfCpu *t = bf_kernel_create_thread(c->x[1], c->x[2], c->x[3], &h);
        ((BfThread *)t->thread)->prio = (int32_t)c->x[4];
        bf_plat_kunlock();
        c->x[1] = h;
        break;
    }
    case 0x09: bf_kernel_start_thread((uint32_t)c->x[0]); break;
    case 0x0A: longjmp(cur->exit_jmp, 1);
    case 0x0B: bf_plat_sleep_ns((int64_t)c->x[0]); break;
    case 0x0C: { BfThread *t = bf_handle_obj((uint32_t)c->x[1], BF_H_THREAD); c->x[1] = t ? (uint64_t)t->prio : 44; break; }
    case 0x0E: c->x[1] = 0; c->x[2] = 0xF; break;
    case 0x10: c->x[0] = 0; return;
    case 0x11: bf_event_signal((uint32_t)c->x[0]); break;
    case 0x12: case 0x17: {
        bf_plat_klock();
        BfEvent *e = bf_handle_obj((uint32_t)c->x[0], BF_H_EVENT);
        if (e) { if (imm == 0x17 && !e->signaled) rc = R_BAD_STATE; e->signaled = 0; } else rc = R_BAD_HANDLE;
        bf_plat_kunlock();
        break;
    }
    case 0x13: // MapSharedMemory(handle, addr, size, perm)
        bf_plat_klock(); bf_commit_guest(c->x[1], c->x[2]); bf_region_add(c->x[1], c->x[2], 0x6, (uint32_t)c->x[3]); bf_plat_kunlock();
        break;
    case 0x15: c->x[1] = bf_handle_new(BF_H_OTHER, NULL); break;
    case 0x16: {
        uint32_t h = (uint32_t)c->x[0];
        BfHandle *e = handle_get(h);
        if (!e) { rc = R_BAD_HANDLE; break; }
        if (e->kind == BF_H_SESSION) bf_ipc_close(h);
        if (e->kind != BF_H_THREAD) { e->kind = 0; e->obj = NULL; }
        break;
    }
    case 0x18: rc = wait_sync(c, c->x[1], (int32_t)c->x[2], (int64_t)c->x[3]); break;
    case 0x19: {
        bf_plat_klock();
        BfThread *t = bf_handle_obj((uint32_t)c->x[0], BF_H_THREAD);
        if (t) { t->cancel = 1; bf_plat_kbroadcast(); } else rc = R_BAD_HANDLE;
        bf_plat_kunlock();
        break;
    }
    case 0x1A: rc = arbitrate_lock((uint32_t)c->x[0], c->x[1], (uint32_t)c->x[2]); break;
    case 0x1B: bf_plat_klock(); mutex_release(c->x[0]); bf_plat_kunlock(); break;
    case 0x1C: rc = cv_wait(c->x[0], c->x[1], (uint32_t)c->x[2], (int64_t)c->x[3]); break;
    case 0x1D: cv_signal(c->x[0], (int32_t)c->x[1]); break;
    case 0x1E: c->x[0] = bf_ticks(); return;
    case 0x1F: {
        char name[13] = {0};
        memcpy(name, BF_HOST(c->x[1]), 12);
        c->x[1] = bf_ipc_connect(name);
        break;
    }
    case 0x21: rc = bf_ipc_request(c->tpidrro, (uint32_t)c->x[0]); break;
    case 0x22: rc = bf_ipc_request(c->x[0], (uint32_t)c->x[2]); break;
    case 0x24: c->x[1] = 0x51; break;
    case 0x25: { BfThread *t = bf_handle_obj((uint32_t)c->x[1], BF_H_THREAD); c->x[1] = t ? t->id : 0; break; }
    case 0x26:
        bf_log("bifrost: guest svcBreak(reason=%llx, %llx, %llx) at %llx\n", (unsigned long long)c->x[0],
               (unsigned long long)c->x[1], (unsigned long long)c->x[2], (unsigned long long)pc);
        if (c->x[0] & 0x80000000ull) break;
        fflush(NULL); exit(7);
    case 0x27: fwrite(BF_HOST(c->x[0]), 1, (size_t)c->x[1], stderr); break;
    case 0x29: c->x[1] = get_info((uint32_t)c->x[1], c->x[3], &rc); break;
    case 0x2C: bf_plat_klock(); bf_commit_guest(c->x[0], c->x[1]); bf_region_add(c->x[0], c->x[1], 0x5, 3); bf_plat_kunlock(); break;
    case 0x30: c->x[1] = 0x100000000ull; break;
    case 0x31: c->x[1] = 0; break;
    case 0x34: rc = wait_for_address(c->x[0], (uint32_t)c->x[1], (int32_t)c->x[2], (int64_t)c->x[3]); break;
    case 0x35: rc = signal_to_address(c->x[0], (uint32_t)c->x[1], (int32_t)c->x[2], (int32_t)c->x[3]); break;
    case 0x45: { uint32_t h = bf_event_new(); c->x[1] = h; c->x[2] = h; break; }
    default:
        bf_log("bifrost: unimplemented svc 0x%02x at %llx\n", imm, (unsigned long long)pc);
        rc = R_NOT_IMPL;
    }
    c->x[0] = rc;
}
uint64_t bf_kernel_heap_size(void) { return heap_size; }
