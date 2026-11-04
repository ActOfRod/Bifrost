// Program entry: maps the guest image, starts the main guest thread and dispatches guest code.
#include "bf_runtime.h"
#include <stdlib.h>
#include <string.h>

char bf_romfs_path[1024];
char bf_dlc_dir[1024];

static BfFn lookup(uint64_t pc) {
    size_t lo = 0, hi = bf_entry_count;
    while (lo < hi) {
        size_t mid = (lo + hi) / 2;
        if (bf_entries[mid].addr < pc) lo = mid + 1; else hi = mid;
    }
    return lo < bf_entry_count && bf_entries[lo].addr == pc ? bf_entries[lo].fn : NULL;
}

const char *bf_import_name(uint64_t pc) {
    for (size_t i = 0; i < bf_import_count; i++) if (bf_imports[i].sentinel == pc) return bf_imports[i].name;
    return "?";
}

static void missing(BfCpu *c) {
    bf_log("bifrost: no recompiled code for guest address %llx (lr=%llx)\n", (unsigned long long)c->pc,
           (unsigned long long)c->x[30]);
    FILE *f = fopen("bifrost_missing.txt", "a");
    if (f) { fprintf(f, "0x%llx\n", (unsigned long long)c->pc); fclose(f); }
    bf_log("bifrost: address appended to bifrost_missing.txt; rebuild with --extra-entries bifrost_missing.txt\n");
    fflush(NULL);
    exit(4);
}

// Host implementations of guest-callable functions. Entries are immutable once published: a writer fills
// hle_tab[n] under hle_lock and then release-stores hle_count, so lock-free readers that acquire-load the count only
// ever see complete entries. (The renderer registers NVN entry points from guest threads while others dispatch.)
typedef struct { const char *name; BfFn fn; } BfHleEntry;
#define BF_HLE_MAX 8192
static BfHleEntry hle_tab[BF_HLE_MAX];
static size_t hle_count;
static int hle_lock;
static void hle_acquire(void) { while (__atomic_exchange_n(&hle_lock, 1, __ATOMIC_ACQUIRE)) {} }
static void hle_release(void) { __atomic_store_n(&hle_lock, 0, __ATOMIC_RELEASE); }
static size_t hle_published(void) { return __atomic_load_n(&hle_count, __ATOMIC_ACQUIRE); }

// Appends {name, fn} unless fn is already present; returns its index, or BF_HLE_MAX if the table is full.
static size_t hle_insert(const char *name, BfFn fn) {
    hle_acquire();
    size_t n = hle_count, i = 0;
    while (i < n && hle_tab[i].fn != fn) i++;
    if (i == n && n < BF_HLE_MAX) {
        hle_tab[n] = (BfHleEntry){name, fn};
        __atomic_store_n(&hle_count, n + 1, __ATOMIC_RELEASE);
    }
    hle_release();
    return i < BF_HLE_MAX ? i : BF_HLE_MAX;
}

void bf_hle_add(const char *name, BfFn fn) { if (fn) hle_insert(name, fn); }

BfFn bf_hle_find(const char *name) {
    size_t n = hle_published();
    for (size_t i = 0; i < n; i++) if (!strcmp(hle_tab[i].name, name)) return hle_tab[i].fn;
    return NULL;
}

uint64_t bf_hle_addr(const char *name, BfFn fn) {
    if (!fn) return 0;
    size_t i = hle_insert(name, fn);
    return i < BF_HLE_MAX ? BF_HLE_BASE + i * 16 : 0;
}

// Per-import cache of the host implementation. Only hits are cached, so an implementation registered after the
// first call is still found on the next one.
static BfFn import_impl(uint64_t pc) {
    static BfFn *cache;
    size_t i = 0;
    while (i < bf_import_count && bf_imports[i].sentinel != pc) i++;
    if (i >= bf_import_count) return NULL;
    BfFn *tab = __atomic_load_n(&cache, __ATOMIC_ACQUIRE);
    if (!tab) {
        BfFn *fresh = calloc(bf_import_count + 1, sizeof *fresh);
        if (!fresh) return bf_hle_find(bf_imports[i].name);
        BfFn *expected = NULL;
        if (__atomic_compare_exchange_n(&cache, &expected, fresh, 0, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) tab = fresh;
        else free(fresh), tab = expected;
    }
    BfFn fn = __atomic_load_n(&tab[i], __ATOMIC_ACQUIRE);
    if (!fn && (fn = bf_hle_find(bf_imports[i].name))) __atomic_store_n(&tab[i], fn, __ATOMIC_RELEASE);
    return fn;
}

void bf_call(BfCpu *c) {
    BfFn fn = lookup(c->pc);
    if (fn) { fn(c); return; }
    if (c->pc >= BF_HLE_BASE && c->pc < 0xFFFFFF0000000000ull) {
        size_t i = (size_t)((c->pc - BF_HLE_BASE) / 16);
        if (i < hle_published()) {
            BfFn host = hle_tab[i].fn;
            c->pc = c->x[30];
            host(c);
            return;
        }
    }
    if (c->pc >= 0xFFFFFF0000000000ull) {
        BfFn impl = import_impl(c->pc);
        if (!impl) bf_log("bifrost: call to unresolved import %s (returning 0)\n", bf_import_name(c->pc));
        c->pc = c->x[30];
        if (impl) { impl(c); return; }
        c->x[0] = 0;
        return;
    }
    missing(c);
}

void bf_dispatch_until(BfCpu *c, uint64_t ret) {
    while (c->pc != ret) bf_call(c);
}

void bf_brk(BfCpu *c, uint32_t imm, uint64_t pc) {
    bf_log("bifrost: guest BRK #%u at %llx\n", imm, (unsigned long long)pc);
    (void)c;
    fflush(NULL);
    exit(3);
}

void bf_unimpl(BfCpu *c, uint64_t pc, uint32_t word) {
    bf_log("bifrost: unsupported instruction %08x at %llx\n", word, (unsigned long long)pc);
    (void)c;
    fflush(NULL);
    exit(3);
}

uint64_t bf_ticks(void) { return (uint64_t)((double)bf_plat_ns() * 0.0192); }
void bf_yield(void) { bf_plat_sleep_ns(0); }

static void dir_of(const char *path, char *out, size_t n) {
    snprintf(out, n, "%s", path);
    char *slash = strrchr(out, '/'), *bs = strrchr(out, '\\');
    if (bs > slash) slash = bs;
    if (slash) slash[1] = 0; else out[0] = 0;
}

int bf_run(int argc, char **argv) {
    char dir[900], image[1024];
    dir_of(argv[0], dir, sizeof dir);
    snprintf(image, sizeof image, "%sguest_image.bin", dir);
    snprintf(bf_romfs_path, sizeof bf_romfs_path, "%sromfs.bin", dir);
    snprintf(bf_dlc_dir, sizeof bf_dlc_dir, "%sdlc", dir);
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "-v")) bf_verbose++;
        else if (!strcmp(argv[i], "--image") && i + 1 < argc) snprintf(image, sizeof image, "%s", argv[++i]);
        else if (!strcmp(argv[i], "--romfs") && i + 1 < argc) snprintf(bf_romfs_path, sizeof bf_romfs_path, "%s", argv[++i]);
        else if (!strcmp(argv[i], "--dlc") && i + 1 < argc) snprintf(bf_dlc_dir, sizeof bf_dlc_dir, "%s", argv[++i]);
    }
    if (!bf_plat_reserve_at(BF_AS_BASE, BF_AS_SIZE)) {
        bf_log("bifrost: cannot reserve the guest address space at %llx+%llx\n", (unsigned long long)BF_AS_BASE,
               (unsigned long long)BF_AS_SIZE);
        return 5;
    }
    FILE *f = fopen(image, "rb");
    if (!f) { bf_log("bifrost: cannot open %s\n", image); return 5; }
    for (size_t i = 0; i < bf_segment_count; i++) {
        const BfSegment *s = &bf_segments[i];
        bf_commit_guest(s->va, s->size);
        if (s->file_size) {
            fseek(f, (long)s->file_off, SEEK_SET);
            if (fread(BF_HOST(s->va), 1, (size_t)s->file_size, f) != s->file_size) {
                bf_log("bifrost: %s is truncated\n", image); return 5;
            }
        }
    }
    fclose(f);
    bf_kernel_init();
    const uint64_t stack_size = 1u << 20;
    uint64_t stack = BF_STACK_BASE;
    bf_commit_guest(stack, stack_size);
    bf_region_add(stack, stack_size, 0xB, BF_PERM_R | BF_PERM_W);
    uint32_t handle;
    BfCpu *c = bf_kernel_create_thread(bf_entry_point, 0, stack + stack_size, &handle);
    if (bf_entry_is_nro) {
        uint64_t cfg = bf_misc_alloc(0x1000);
        ST32(cfg + 0, 1); ST64(cfg + 8, handle);  // EntryType_MainThreadHandle
        ST32(cfg + 24, 0);                        // EntryType_EndOfList
        c->x[0] = cfg;
        c->x[1] = ~0ull;
    } else {
        c->x[0] = 0;
        c->x[1] = handle;
    }
    if (bf_verbose) bf_log("bifrost: %s entry %llx\n", bf_title_name, (unsigned long long)bf_entry_point);
    bf_kernel_start_thread(handle);
    bf_kernel_wait_exit();
    fflush(NULL);
    return (int)(c->x[0] & 0xFF);
}

#ifndef BF_NO_MAIN
int main(int argc, char **argv) { return bf_run(argc, argv); }
#endif
