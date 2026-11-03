// LAS runtime interface (rt.h, platform.h, settings.h, ...) implemented on Bifrost's runtime, for the vendored
// NVN renderer.
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include "../bf_runtime.h"
#include "rt.h"
#include "settings.h"
#include "paths.h"
#include "romfs_build.h"

uint64_t hle_register(const char *name, HostFn fn) { return bf_hle_addr(name, fn); }
HostFn hle_lookup_impl(const char *name) { return bf_hle_find(name); }

#define GUEST_CALL_RET 0xFFFFFFFFFFFFE000ull
void guest_call(Cpu *c, uint64_t addr) {
    uint64_t lr = c->x[30];
    c->x[30] = GUEST_CALL_RET;
    c->pc = addr;
    bf_dispatch_until(c, GUEST_CALL_RET);
    c->x[30] = lr;
}

void kernel_blocking_begin(void) {}
void kernel_blocking_end(void) {}
uint64_t kernel_heap_size(void) { return bf_kernel_heap_size(); }

uint64_t g_guest_lo = BF_AS_BASE;
const char *g_romfs_dir;
Settings g_settings;

void settings_load(void) {
    memset(&g_settings, 0, sizeof g_settings);
    g_settings.volume = 1.0f;
    g_settings.res_mode = 1;
#ifdef __ANDROID__
    g_settings.fps_mode = 0;
#endif
}
void settings_save(void) {}

float plat_display_hz(void) { return 0; }
void plat_frame_work_gpu(uint64_t cpu_ns, uint64_t gpu_ns, uint64_t target_ns, uint64_t game_ns) {
    (void)cpu_ns, (void)gpu_ns, (void)target_ns, (void)game_ns;
}
uint64_t plat_now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}
void plat_sleep_until_ns(uint64_t t) {
    for (uint64_t now = plat_now_ns(); now < t; now = plat_now_ns()) {
        uint64_t d = t - now;
        struct timespec ts = {(time_t)(d / 1000000000ull), (long)(d % 1000000000ull)};
        nanosleep(&ts, NULL);
    }
}
bool plat_reset_guest(uint64_t lo, uint64_t hi) { (void)lo, (void)hi; return true; }
void plat_terminate(void) { fflush(NULL); _exit(0); }
void plat_thread_unpin(void) {}
void plat_write_tracking_enable(uint64_t lo, uint64_t hi) { (void)lo, (void)hi; }
int plat_written_pages(uint64_t addr, uint64_t size, uint64_t *pages, int max, uint64_t *page) {
    (void)addr, (void)size, (void)pages, (void)max, (void)page;
    return -1;  // no write tracking: callers fall back to comparing contents
}

RomfsVirtual *romfs_virtual_open(const char *dir, char *why, size_t why_size) {
    (void)dir;
    snprintf(why, why_size, "Bifrost reads the packed romfs.bin");
    return NULL;
}
int64_t romfs_virtual_read(RomfsVirtual *v, void *dst, uint64_t n, uint64_t off) { (void)v, (void)dst, (void)n, (void)off; return -1; }
bool romfs_link_read(const char *root, char *out, size_t size) { (void)root, (void)out, (void)size; return false; }

size_t rt_lz4_decompress(const uint8_t *src, size_t srclen, uint8_t *dst, size_t dstlen) {
    size_t i = 0, o = 0;
    while (i < srclen) {
        uint8_t tok = src[i++];
        size_t lit = tok >> 4;
        if (lit == 15) { uint8_t b; do { if (i >= srclen) return 0; b = src[i++]; lit += b; } while (b == 255); }
        if (i + lit > srclen || o + lit > dstlen) return 0;
        memcpy(dst + o, src + i, lit), i += lit, o += lit;
        if (i >= srclen) break;
        if (i + 2 > srclen) return 0;
        size_t off = src[i] | (size_t)src[i + 1] << 8;
        i += 2;
        size_t len = (tok & 15) + 4;
        if ((tok & 15) == 15) { uint8_t b; do { if (i >= srclen) return 0; b = src[i++]; len += b; } while (b == 255); }
        if (!off || off > o || o + len > dstlen) return 0;
        for (size_t k = 0; k < len; k++, o++) dst[o] = dst[o - off];
    }
    return o;
}

const char *rt_data_root(void) {
    const char *d = getenv("BF_DATA_DIR");
    return d ? d : ".";
}
void rt_fatal_message(const char *format, ...) {
    va_list ap;
    va_start(ap, format);
    char buf[1024];
    vsnprintf(buf, sizeof buf, format, ap);
    va_end(ap);
    bf_log("bifrost: renderer fatal: %s", buf);
    fflush(NULL);
    abort();
}
int rt_reboot_requested(void) { return 0; }

#include <volk.h>
void vk_android_load(void) {
    if (volkInitialize() != VK_SUCCESS) rt_fatal_message("no Vulkan loader on this device");
}
__thread uint64_t g_blocked_ns;  // read by vulkan/exec.c frame pacing; Bifrost does not account blocked time
