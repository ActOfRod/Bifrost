// Guest CPU state and the helpers that lifted code calls. Included by every generated C file.
// Generated code is plain C11 plus GNU builtins (clang or gcc; MSVC cl.exe is not supported).
#pragma once
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <math.h>

typedef union BfVec {
    uint8_t b[16];
    int8_t sb[16];
    uint16_t h[8];
    int16_t sh[8];
    uint32_t s[4];
    int32_t ss[4];
    uint64_t d[2];
    int64_t sd[2];
    float f[4];
    double fd[2];
} BfVec;

typedef struct BfCpu {
    uint64_t x[32];      // x0-x30; x[31] is a write sink for the zero register and is never read
    uint64_t sp;
    uint64_t pc;         // next guest pc when a lifted function returns to the dispatcher
    uint64_t nzcv;       // flags in bits 31..28, as the NZCV system register
    uint64_t fpcr;
    uint64_t fpsr;
    uint64_t tpidr;      // TPIDR_EL0
    uint64_t tpidrro;    // TPIDRRO_EL0: the thread's TLS page
    uint64_t excl_addr;  // exclusive monitor: address, or ~0 when clear
    uint64_t excl_val;
    uint64_t excl_val2;
    void *thread;        // runtime thread object
    uint64_t _pad[4];
    BfVec v[32];
} BfCpu;

typedef void (*BfFn)(BfCpu *c);


// ---- memory: guest address a lives at bf_membase + a
typedef uint16_t __attribute__((aligned(1), may_alias)) bf_u16u;
typedef uint32_t __attribute__((aligned(1), may_alias)) bf_u32u;
typedef uint64_t __attribute__((aligned(1), may_alias)) bf_u64u;
typedef uint8_t __attribute__((may_alias)) bf_u8a;

#ifndef BF_AS_BASE
#define BF_AS_BASE        0x2000000000ull
#define BF_AS_SIZE        (1ull << 36)
#endif
// Guest addresses are host addresses inside the reserved guest window. Anything outside it is folded back in, so a
// wild guest pointer lands on reserved (faulting) pages of the window instead of reaching host memory.
#define BF_HOST(a) ((void *)(uintptr_t)(BF_AS_BASE | ((uint64_t)(a) & (BF_AS_SIZE - 1))))
static inline int bf_guest_range_ok(uint64_t a, uint64_t n) {
    return a >= BF_AS_BASE && n <= BF_AS_SIZE && a - BF_AS_BASE <= BF_AS_SIZE - n;
}
#define LD8(a) (*(const bf_u8a *)BF_HOST(a))
#define LD16(a) (*(const bf_u16u *)BF_HOST(a))
#define LD32(a) (*(const bf_u32u *)BF_HOST(a))
#define LD64(a) (*(const bf_u64u *)BF_HOST(a))
#define ST8(a, v) (*(bf_u8a *)BF_HOST(a) = (uint8_t)(v))
#define ST16(a, v) (*(bf_u16u *)BF_HOST(a) = (uint16_t)(v))
#define ST32(a, v) (*(bf_u32u *)BF_HOST(a) = (uint32_t)(v))
#define ST64(a, v) (*(bf_u64u *)BF_HOST(a) = (uint64_t)(v))

// ---- runtime entry points (runtime/*.c)
void bf_call(BfCpu *c);                          // run the guest function at c->pc until it returns
void bf_dispatch_until(BfCpu *c, uint64_t ret);  // run from c->pc until c->pc == ret
void bf_svc(BfCpu *c, uint32_t imm, uint64_t pc);
void bf_brk(BfCpu *c, uint32_t imm, uint64_t pc);
void bf_unimpl(BfCpu *c, uint64_t pc, uint32_t word);
uint64_t bf_ticks(void);
void bf_yield(void);

// ---- vector register helpers
static inline void bf_vset(BfCpu *c, int d, const BfVec *r, int q) {
    c->v[d].d[0] = r->d[0];
    c->v[d].d[1] = q ? r->d[1] : 0;
}
static inline void bf_vzero_hi(BfCpu *c, int d) { c->v[d].d[1] = 0; }
static inline void bf_vset_s(BfCpu *c, int d, uint32_t v) { c->v[d].d[0] = v; c->v[d].d[1] = 0; }
static inline void bf_vset_d(BfCpu *c, int d, uint64_t v) { c->v[d].d[0] = v; c->v[d].d[1] = 0; }
static inline void bf_vset_h(BfCpu *c, int d, uint16_t v) { c->v[d].d[0] = v; c->v[d].d[1] = 0; }
static inline void bf_vset_b(BfCpu *c, int d, uint8_t v) { c->v[d].d[0] = v; c->v[d].d[1] = 0; }

// ---- integer helpers
static inline uint32_t bf_nzcv(uint32_t n, uint32_t z, uint32_t cf, uint32_t v) {
    return (n << 31) | (z << 30) | (cf << 29) | (v << 28);
}
static inline uint64_t bf_adc64(BfCpu *c, uint64_t a, uint64_t b, uint64_t carry, int setf) {
    unsigned __int128 wide = (unsigned __int128)a + b + carry;
    uint64_t r = (uint64_t)wide;
    if (setf) {
        int64_t sr = (int64_t)r;
        uint32_t v = (((a ^ r) & (b ^ r)) >> 63) & 1;
        c->nzcv = bf_nzcv(sr < 0, r == 0, (uint32_t)(wide >> 64), v);
    }
    return r;
}
static inline uint32_t bf_adc32(BfCpu *c, uint32_t a, uint32_t b, uint32_t carry, int setf) {
    uint64_t wide = (uint64_t)a + b + carry;
    uint32_t r = (uint32_t)wide;
    if (setf) {
        uint32_t v = (((a ^ r) & (b ^ r)) >> 31) & 1;
        c->nzcv = bf_nzcv(r >> 31, r == 0, (uint32_t)(wide >> 32), v);
    }
    return r;
}
#define BF_CARRY(c) ((uint32_t)((c)->nzcv >> 29) & 1)
#define bf_adds64(c, a, b) bf_adc64((c), (a), (b), 0, 1)
#define bf_subs64(c, a, b) bf_adc64((c), (a), ~(uint64_t)(b), 1, 1)
#define bf_adds32(c, a, b) bf_adc32((c), (uint32_t)(a), (uint32_t)(b), 0, 1)
#define bf_subs32(c, a, b) bf_adc32((c), (uint32_t)(a), ~(uint32_t)(b), 1, 1)
static inline void bf_logic_flags64(BfCpu *c, uint64_t r) { c->nzcv = bf_nzcv(r >> 63, r == 0, 0, 0); }
static inline void bf_logic_flags32(BfCpu *c, uint32_t r) { c->nzcv = bf_nzcv(r >> 31, r == 0, 0, 0); }

static inline int bf_cond(uint64_t nzcv, unsigned cond) {
    unsigned n = (nzcv >> 31) & 1, z = (nzcv >> 30) & 1, cf = (nzcv >> 29) & 1, v = (nzcv >> 28) & 1;
    int r;
    switch (cond >> 1) {
    case 0: r = z; break;
    case 1: r = cf; break;
    case 2: r = n; break;
    case 3: r = v; break;
    case 4: r = cf && !z; break;
    case 5: r = n == v; break;
    case 6: r = !z && n == v; break;
    default: r = 1; break;
    }
    if ((cond & 1) && cond != 0xF) r = !r;
    return r;
}
#define BF_COND(c, cond) bf_cond((c)->nzcv, (cond))

static inline uint64_t bf_ror64(uint64_t v, unsigned s) { s &= 63; return s ? (v >> s) | (v << (64 - s)) : v; }
static inline uint32_t bf_ror32(uint32_t v, unsigned s) { s &= 31; return s ? (v >> s) | (v << (32 - s)) : v; }
static inline uint64_t bf_asr64(uint64_t v, unsigned s) { return (uint64_t)((int64_t)v >> (s & 63)); }
static inline uint32_t bf_asr32(uint32_t v, unsigned s) { return (uint32_t)((int32_t)v >> (s & 31)); }
static inline uint64_t bf_clz64(uint64_t v) { return v ? (uint64_t)__builtin_clzll(v) : 64; }
static inline uint32_t bf_clz32(uint32_t v) { return v ? (uint32_t)__builtin_clz(v) : 32; }
static inline uint64_t bf_cls64(uint64_t v) { return bf_clz64(v ^ (uint64_t)((int64_t)v >> 1)) - 1; }
static inline uint32_t bf_cls32(uint32_t v) { return bf_clz32(v ^ (uint32_t)((int32_t)v >> 1)) - 1; }
static inline uint64_t bf_rbit64(uint64_t v) {
    v = ((v >> 1) & 0x5555555555555555ull) | ((v & 0x5555555555555555ull) << 1);
    v = ((v >> 2) & 0x3333333333333333ull) | ((v & 0x3333333333333333ull) << 2);
    v = ((v >> 4) & 0x0F0F0F0F0F0F0F0Full) | ((v & 0x0F0F0F0F0F0F0F0Full) << 4);
    return __builtin_bswap64(v);
}
static inline uint32_t bf_rbit32(uint32_t v) { return (uint32_t)(bf_rbit64(v) >> 32); }
static inline uint64_t bf_rev16_64(uint64_t v) {
    return ((v >> 8) & 0x00FF00FF00FF00FFull) | ((v & 0x00FF00FF00FF00FFull) << 8);
}
static inline uint64_t bf_rev32_64(uint64_t v) {
    uint64_t r = __builtin_bswap64(v);
    return (r >> 32) | (r << 32);
}
static inline uint64_t bf_udiv64(uint64_t a, uint64_t b) { return b ? a / b : 0; }
static inline uint32_t bf_udiv32(uint32_t a, uint32_t b) { return b ? a / b : 0; }
static inline uint64_t bf_sdiv64(uint64_t a, uint64_t b) {
    if (!b) return 0;
    if ((int64_t)a == INT64_MIN && (int64_t)b == -1) return a;
    return (uint64_t)((int64_t)a / (int64_t)b);
}
static inline uint32_t bf_sdiv32(uint32_t a, uint32_t b) {
    if (!b) return 0;
    if ((int32_t)a == INT32_MIN && (int32_t)b == -1) return a;
    return (uint32_t)((int32_t)a / (int32_t)b);
}
static inline uint64_t bf_umulh(uint64_t a, uint64_t b) { return (uint64_t)(((unsigned __int128)a * b) >> 64); }
static inline uint64_t bf_smulh(uint64_t a, uint64_t b) { return (uint64_t)(((__int128)(int64_t)a * (int64_t)b) >> 64); }

// Bitfield move as UBFM/SBFM/BFM define it. wmask/tmask are computed by the lifter.
static inline uint64_t bf_bfm(uint64_t dst, uint64_t src, unsigned r, unsigned s, uint64_t wmask, uint64_t tmask,
                              int kind, unsigned width) {
    uint64_t rot = width == 64 ? bf_ror64(src, r) : bf_ror32((uint32_t)src, r);
    uint64_t bot = (kind == 1 ? (dst & ~wmask) : 0) | (rot & wmask);   // kind: 0 sbfm, 1 bfm, 2 ubfm
    uint64_t top;
    if (kind == 0) {
        uint64_t sbit = (src >> s) & 1;
        top = sbit ? ~(uint64_t)0 : 0;
    } else if (kind == 1) {
        top = dst;
    } else {
        top = 0;
    }
    uint64_t r64 = (top & ~tmask) | (bot & tmask);
    return width == 64 ? r64 : (uint32_t)r64;
}

// CRC32 (polynomial 0x04C11DB7 reflected) and CRC32C (0x1EDC6F41 reflected)
static inline uint32_t bf_crc32(uint32_t acc, uint64_t val, int bytes, int castagnoli) {
    uint32_t poly = castagnoli ? 0x82F63B78u : 0xEDB88320u;
    for (int i = 0; i < bytes; i++) {
        acc ^= (uint8_t)(val >> (8 * i));
        for (int k = 0; k < 8; k++) acc = (acc >> 1) ^ (poly & (0u - (acc & 1)));
    }
    return acc;
}

// ---- exclusive monitor, emulated with compare-and-swap on the host address
#define BF_EXCL(N, T)                                                                    \
    static inline uint64_t bf_ldx##N(BfCpu *c, uint64_t a) {                             \
        T v = __atomic_load_n((T *)BF_HOST(a), __ATOMIC_ACQUIRE);                        \
        c->excl_addr = a;                                                                \
        c->excl_val = v;                                                                 \
        return v;                                                                        \
    }                                                                                    \
    static inline uint32_t bf_stx##N(BfCpu *c, uint64_t a, uint64_t v) {                 \
        if (c->excl_addr != a) return 1;                                                 \
        c->excl_addr = ~0ull;                                                            \
        T expected = (T)c->excl_val;                                                     \
        return !__atomic_compare_exchange_n((T *)BF_HOST(a), &expected, (T)v, 0,         \
                                            __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);         \
    }                                                                                    \
    static inline uint64_t bf_lda##N(uint64_t a) { return __atomic_load_n((T *)BF_HOST(a), __ATOMIC_ACQUIRE); } \
    static inline void bf_stl##N(uint64_t a, uint64_t v) { __atomic_store_n((T *)BF_HOST(a), (T)v, __ATOMIC_RELEASE); }
BF_EXCL(8, uint8_t)
BF_EXCL(16, uint16_t)
BF_EXCL(32, uint32_t)
BF_EXCL(64, uint64_t)

// Exclusive pair: 2x32 is one 64-bit exclusive; 2x64 uses a 128-bit compare-and-swap.
static inline void bf_ldxp64(BfCpu *c, uint64_t a, uint64_t *lo, uint64_t *hi) {
    unsigned __int128 v;
    __atomic_load((unsigned __int128 *)BF_HOST(a), &v, __ATOMIC_ACQUIRE);
    c->excl_addr = a;
    c->excl_val = (uint64_t)v;
    c->excl_val2 = (uint64_t)(v >> 64);
    *lo = (uint64_t)v;
    *hi = (uint64_t)(v >> 64);
}
static inline uint32_t bf_stxp64(BfCpu *c, uint64_t a, uint64_t lo, uint64_t hi) {
    if (c->excl_addr != a) return 1;
    c->excl_addr = ~0ull;
    unsigned __int128 expected = ((unsigned __int128)c->excl_val2 << 64) | c->excl_val;
    unsigned __int128 want = ((unsigned __int128)hi << 64) | lo;
    return !__atomic_compare_exchange((unsigned __int128 *)BF_HOST(a), &expected, &want, 0, __ATOMIC_SEQ_CST,
                                      __ATOMIC_SEQ_CST);
}

#define BF_DMB() __atomic_thread_fence(__ATOMIC_SEQ_CST)
#define BF_CNTFRQ 19200000ull

static inline void bf_dc_zva(uint64_t a) { memset(BF_HOST(a & ~63ull), 0, 64); }

#include "bf_fp.h"
#include "bf_crypto.h"
