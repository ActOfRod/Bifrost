// Bifrost generated-program runtime: loader, dispatch, Horizon kernel and service HLE.
#pragma once
#include "bf_cpu.h"
#include <stdio.h>

typedef struct BfEntry { uint64_t addr; BfFn fn; } BfEntry;
extern const BfEntry bf_entries[];
extern const size_t bf_entry_count;

enum { BF_PERM_R = 1, BF_PERM_W = 2, BF_PERM_X = 4 };
typedef struct BfSegment {
    uint64_t va, size;      // mapped range (page aligned)
    uint64_t file_off, file_size;
    uint32_t perm, mem_type; // Horizon MemoryType (3 CodeStatic, 4 CodeMutable)
    const char *module;
} BfSegment;
extern const BfSegment bf_segments[];
extern const size_t bf_segment_count;
extern const uint64_t bf_entry_point;
extern const int bf_entry_is_nro;
extern const char bf_title_name[];
extern const uint64_t bf_title_id;
typedef struct BfImport { uint64_t sentinel; const char *name; } BfImport;
extern const BfImport bf_imports[];
extern const size_t bf_import_count;

// guest address-space layout. The default mirrors a 39-bit Horizon application process; BF_COMPACT_AS
// shrinks it to 64 GiB for hosts with a 39-bit user address space (most Android kernels).
#ifdef BF_COMPACT_AS
#define BF_AS_SIZE        (1ull << 36)
#define BF_CODE_BASE      0x0008000000ull
#define BF_HEAP_BASE      0x0100000000ull
#define BF_HEAP_SIZE      0x0400000000ull
#define BF_STACK_BASE     0x0500000000ull
#define BF_STACK_SIZE     0x0100000000ull
#define BF_TLS_BASE       0x0600000000ull
#define BF_TLS_SIZE       0x0010000000ull
#define BF_MISC_BASE      0x0700000000ull
#define BF_MISC_SIZE      0x0100000000ull
#define BF_ALIAS_BASE     0x0800000000ull
#define BF_ALIAS_SIZE     0x0100000000ull
#else
#define BF_AS_SIZE        (1ull << 39)
#define BF_CODE_BASE      0x0008000000ull
#define BF_HEAP_BASE      0x1100000000ull
#define BF_HEAP_SIZE      0x1800000000ull
#define BF_STACK_BASE     0x2900000000ull
#define BF_STACK_SIZE     0x0800000000ull
#define BF_TLS_BASE       0x3100000000ull
#define BF_TLS_SIZE       0x0010000000ull
#define BF_MISC_BASE      0x3200000000ull
#define BF_MISC_SIZE      0x1000000000ull
#define BF_ALIAS_BASE     0x4400000000ull
#define BF_ALIAS_SIZE     0x1000000000ull
#endif
#define BF_THREAD_EXIT    0xFFFFFFFFFFFFF000ull

// platform layer (bf_platform.c)
void *bf_plat_reserve(uint64_t size);
int bf_plat_commit(void *p, uint64_t size);
void bf_plat_decommit(void *p, uint64_t size);
typedef struct BfThreadHost BfThreadHost;
int bf_plat_thread(void (*fn)(void *), void *arg);
void bf_plat_sleep_ns(int64_t ns);
uint64_t bf_plat_ns(void);
void bf_plat_klock(void);
void bf_plat_kunlock(void);
void bf_plat_kwait(int64_t timeout_ns); // wait on the kernel condition (klock held); <0 = forever
void bf_plat_kbroadcast(void);

// kernel (bf_kernel.c)
typedef struct BfThread BfThread;
void bf_kernel_init(void);
BfCpu *bf_kernel_create_thread(uint64_t entry, uint64_t arg, uint64_t sp, uint32_t *handle);
void bf_kernel_start_thread(uint32_t handle);
void bf_kernel_wait_exit(void);
const char *bf_import_name(uint64_t pc);
void bf_commit_guest(uint64_t va, uint64_t size);
uint64_t bf_misc_alloc(uint64_t size);
uint32_t bf_handle_new(int kind, void *obj);
void *bf_handle_obj(uint32_t h, int kind);
enum { BF_H_THREAD = 1, BF_H_EVENT, BF_H_SESSION, BF_H_OTHER };
uint32_t bf_event_new(void);
void bf_event_signal(uint32_t h);
uint64_t bf_tls_alloc(void);
void bf_region_add(uint64_t va, uint64_t size, uint32_t type, uint32_t perm);
void bf_log(const char *fmt, ...);
extern int bf_verbose;

// IPC (bf_ipc.c)
uint32_t bf_ipc_connect(const char *name);
uint32_t bf_ipc_request(uint64_t msg, uint32_t handle);
void bf_ipc_close(uint32_t handle);
extern char bf_romfs_path[1024];
extern char bf_dlc_dir[1024];
int bf_run(int argc, char **argv);
