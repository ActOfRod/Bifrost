// Floating-point and SIMD helpers with AArch64 semantics (included from bf_cpu.h).
// Rounding follows the host default (round to nearest even); FPCR.RMode, FZ and DN are not modelled.
#pragma once

static inline uint32_t bf_f2u(float f) { uint32_t u; memcpy(&u, &f, 4); return u; }
static inline float bf_u2f(uint32_t u) { float f; memcpy(&f, &u, 4); return f; }
static inline uint64_t bf_d2u(double f) { uint64_t u; memcpy(&u, &f, 8); return u; }
static inline double bf_u2d(uint64_t u) { double f; memcpy(&f, &u, 8); return f; }

static inline void bf_vset_f(BfCpu *c, int d, float v) { c->v[d].d[0] = bf_f2u(v); c->v[d].d[1] = 0; }
static inline void bf_vset_fd(BfCpu *c, int d, double v) { c->v[d].d[0] = bf_d2u(v); c->v[d].d[1] = 0; }

#define BF_DEFAULT_NAN_F bf_u2f(0x7FC00000u)
#define BF_DEFAULT_NAN_D bf_u2d(0x7FF8000000000000ull)

// NaN propagation: first signalling NaN, then first quiet NaN, quietened.
static inline float bf_pnan_f(float a, float b) {
    uint32_t ua = bf_f2u(a), ub = bf_f2u(b);
    int sa = isnan(a) && !(ua & 0x400000), sb = isnan(b) && !(ub & 0x400000);
    if (sa) return bf_u2f(ua | 0x400000);
    if (sb) return bf_u2f(ub | 0x400000);
    if (isnan(a)) return a;
    return b;
}
static inline double bf_pnan_d(double a, double b) {
    uint64_t ua = bf_d2u(a), ub = bf_d2u(b);
    const uint64_t q = 1ull << 51;
    int sa = isnan(a) && !(ua & q), sb = isnan(b) && !(ub & q);
    if (sa) return bf_u2d(ua | q);
    if (sb) return bf_u2d(ub | q);
    if (isnan(a)) return a;
    return b;
}

static inline int bf_issnan_f(float a) { return isnan(a) && !(bf_f2u(a) & 0x400000u); }
static inline int bf_issnan_d(double a) { return isnan(a) && !(bf_d2u(a) & (1ull << 51)); }

#define BF_FP_BINOPS(T, S, FMA)                                                                       \
    static inline T bf_fadd_##S(T a, T b) { return (isnan(a) || isnan(b)) ? bf_pnan_##S(a, b) : a + b; } \
    static inline T bf_fsub_##S(T a, T b) { return (isnan(a) || isnan(b)) ? bf_pnan_##S(a, b) : a - b; } \
    static inline T bf_fmul_##S(T a, T b) { return (isnan(a) || isnan(b)) ? bf_pnan_##S(a, b) : a * b; } \
    static inline T bf_fdiv_##S(T a, T b) { return (isnan(a) || isnan(b)) ? bf_pnan_##S(a, b) : a / b; } \
    static inline T bf_fmax_##S(T a, T b) {                                                        \
        if (isnan(a) || isnan(b)) return bf_pnan_##S(a, b);                                         \
        if (a == 0 && b == 0) return signbit(a) ? b : a;                                           \
        return a > b ? a : b;                                                                      \
    }                                                                                              \
    static inline T bf_fmin_##S(T a, T b) {                                                        \
        if (isnan(a) || isnan(b)) return bf_pnan_##S(a, b);                                         \
        if (a == 0 && b == 0) return signbit(a) ? a : b;                                           \
        return a < b ? a : b;                                                                      \
    }                                                                                              \
    static inline T bf_fmaxnm_##S(T a, T b) {                                                      \
        if (bf_issnan_##S(a) || bf_issnan_##S(b)) return bf_pnan_##S(a, b);                         \
        if (isnan(a) && !isnan(b)) return b;                                                       \
        if (isnan(b) && !isnan(a)) return a;                                                       \
        return bf_fmax_##S(a, b);                                                                  \
    }                                                                                              \
    static inline T bf_fminnm_##S(T a, T b) {                                                      \
        if (bf_issnan_##S(a) || bf_issnan_##S(b)) return bf_pnan_##S(a, b);                         \
        if (isnan(a) && !isnan(b)) return b;                                                       \
        if (isnan(b) && !isnan(a)) return a;                                                       \
        return bf_fmin_##S(a, b);                                                                  \
    }                                                                                              \
    static inline T bf_fmulx_##S(T a, T b) {                                                       \
        if (isnan(a) || isnan(b)) return bf_pnan_##S(a, b);                                         \
        if ((isinf(a) && b == 0) || (isinf(b) && a == 0)) return (signbit(a) != signbit(b)) ? -2 : 2; \
        return a * b;                                                                              \
    }                                                                                              \
    static inline T bf_fma_##S(T a, T b, T acc) {                                                  \
        if (isnan(acc) || isnan(a) || isnan(b)) {                                                  \
            if (isnan(acc) && !isnan(a) && !isnan(b)) return bf_pnan_##S(acc, acc);                 \
            return isnan(a) || isnan(b) ? bf_pnan_##S(a, b) : acc;                                 \
        }                                                                                          \
        return FMA(a, b, acc);                                                                     \
    }                                                                                              \
    static inline T bf_frecps_##S(T a, T b) {                                                      \
        if (isnan(a) || isnan(b)) return bf_pnan_##S(a, b);                                         \
        if ((isinf(a) && b == 0) || (isinf(b) && a == 0)) return 2;                                \
        return (T)fma(-a, b, 2);                                                                   \
    }                                                                                              \
    static inline T bf_frsqrts_##S(T a, T b) {                                                     \
        if (isnan(a) || isnan(b)) return bf_pnan_##S(a, b);                                         \
        if ((isinf(a) && b == 0) || (isinf(b) && a == 0)) return (T)1.5;                           \
        return (T)(fma(-(double)a, (double)b, 3.0) / 2.0);                                         \
    }                                                                                              \
    static inline T bf_fsqrt_##S(T a) {                                                            \
        if (isnan(a)) return bf_pnan_##S(a, a);                                                     \
        if (a < 0) return BF_DEFAULT_NAN_##S##X;                                                   \
        return (T)sqrt(a);                                                                         \
    }
#define BF_DEFAULT_NAN_fX BF_DEFAULT_NAN_F
#define BF_DEFAULT_NAN_dX BF_DEFAULT_NAN_D
BF_FP_BINOPS(float, f, fmaf)
BF_FP_BINOPS(double, d, fma)
// FCMP: returns NZCV
static inline uint64_t bf_fcmp(double a, double b) {
    if (isnan(a) || isnan(b)) return 0x30000000ull;
    if (a == b) return 0x60000000ull;
    if (a < b) return 0x80000000ull;
    return 0x20000000ull;
}

// Round to integral: mode 0 nearest-even, 1 +inf, 2 -inf, 3 zero, 4 ties-away
static inline double bf_round_d(double v, int mode) {
    if (isnan(v)) return bf_pnan_d(v, v);
    switch (mode) {
    case 1: return ceil(v);
    case 2: return floor(v);
    case 3: return trunc(v);
    case 4: return round(v);
    default: return nearbyint(v);
    }
}
static inline float bf_round_f(float v, int mode) {
    if (isnan(v)) return bf_pnan_f(v, v);
    switch (mode) {
    case 1: return ceilf(v);
    case 2: return floorf(v);
    case 3: return truncf(v);
    case 4: return roundf(v);
    default: return nearbyintf(v);
    }
}

// Saturating float -> integer conversions (NaN -> 0)
static inline uint64_t bf_cvt_s64(double v, int mode) {
    if (isnan(v)) return 0;
    v = bf_round_d(v, mode);
    if (v >= 9223372036854775808.0) return (uint64_t)INT64_MAX;
    if (v < -9223372036854775808.0) return (uint64_t)INT64_MIN;
    return (uint64_t)(int64_t)v;
}
static inline uint64_t bf_cvt_u64(double v, int mode) {
    if (isnan(v)) return 0;
    v = bf_round_d(v, mode);
    if (v >= 18446744073709551616.0) return UINT64_MAX;
    if (v <= 0) return 0;
    return (uint64_t)v;
}
static inline uint64_t bf_cvt_s32(double v, int mode) {
    if (isnan(v)) return 0;
    v = bf_round_d(v, mode);
    if (v >= 2147483648.0) return (uint32_t)INT32_MAX;
    if (v < -2147483648.0) return (uint32_t)INT32_MIN;
    return (uint32_t)(int32_t)v;
}
static inline uint64_t bf_cvt_u32(double v, int mode) {
    if (isnan(v)) return 0;
    v = bf_round_d(v, mode);
    if (v >= 4294967296.0) return UINT32_MAX;
    if (v <= 0) return 0;
    return (uint32_t)v;
}
// fixed-point variants scale by 2^fbits before rounding toward zero
#define bf_cvt_fix(fn, v, fbits) fn(ldexp((double)(v), (fbits)), 3)
static inline uint64_t bf_cvt_s64_f(float v, int mode) {
    // 64-bit conversions of float values: double holds every float exactly
    return bf_cvt_s64((double)v, mode);
}
static inline uint64_t bf_cvt_s16(double v, int mode) {
    if (isnan(v)) return 0;
    v = bf_round_d(v, mode);
    if (v >= 32767.0) return 0x7FFF;
    if (v <= -32768.0) return 0x8000;
    return (uint16_t)(int16_t)v;
}

// integer -> float with exactly one rounding
static inline float bf_u64_to_f(uint64_t v) { return (float)v; }
static inline float bf_s64_to_f(int64_t v) { return (float)v; }

// half precision
static inline float bf_h2f(uint16_t h) {
    uint32_t sign = (uint32_t)(h >> 15) << 31, exp = (h >> 10) & 0x1F, frac = h & 0x3FF;
    if (exp == 0x1F) return bf_u2f(sign | 0x7F800000u | (frac << 13) | (frac ? 0x400000u : 0));
    if (exp == 0) {
        if (!frac) return bf_u2f(sign);
        float f = ldexpf((float)frac, -24);
        return sign ? -f : f;
    }
    return bf_u2f(sign | ((exp + 112) << 23) | (frac << 13));
}
static inline uint16_t bf_f2h(float f) {
    uint32_t u = bf_f2u(f);
    uint16_t sign = (uint16_t)((u >> 16) & 0x8000);
    if (isnan(f)) return sign | 0x7E00 | (uint16_t)((u >> 13) & 0x3FF);
    float a = fabsf(f);
    if (a >= 65520.0f) return sign | 0x7C00;
    if (a < 6.103515625e-05f) {
        // subnormal half: round to nearest even multiple of 2^-24
        float q = nearbyintf(a * 16777216.0f);
        return sign | (uint16_t)q;
    }
    uint32_t au = bf_f2u(a);
    uint32_t exp = (au >> 23) - 112, mant = au & 0x7FFFFF;
    uint32_t h = (exp << 10) | (mant >> 13);
    uint32_t rem = mant & 0x1FFF;
    if (rem > 0x1000 || (rem == 0x1000 && (h & 1))) h++;
    return sign | (uint16_t)h;
}
static inline uint16_t bf_d2h(double d) { return bf_f2h((float)d); }

// Reciprocal (square root) estimates, bit-exact with the ARMv8.0 pseudocode
static inline uint32_t bf_recip_est_int(uint32_t a) { a = a * 2 + 1; uint32_t b = (1u << 19) / a; return (b + 1) / 2; }
static inline uint32_t bf_rsqrt_est_int(uint32_t a) {
    if (a < 256) a = a * 2 + 1; else { a = (a >> 1) << 1; a = (a + 1) * 2; }
    uint32_t b = 512;
    while ((uint64_t)a * (b + 1) * (b + 1) < (1ull << 28)) b++;
    return (b + 1) / 2;
}
// generic over N bits (32/64) via 64-bit fraction field
static inline uint64_t bf_frecpe_bits(uint64_t op, int n) {
    int ebits = n == 32 ? 8 : 11, fbits = n == 32 ? 23 : 52;
    uint64_t sign = op >> (n - 1);
    int64_t exp = (int64_t)((op >> fbits) & ((1u << ebits) - 1));
    uint64_t frac = (op & ((1ull << fbits) - 1)) << (52 - fbits);
    uint64_t emax = (1u << ebits) - 1;
    if (exp == (int64_t)emax) {
        if (frac) return op | (n == 32 ? 0x400000ull : (1ull << 51));
        return sign << (n - 1);
    }
    if (exp == 0 && frac == 0) return (sign << (n - 1)) | (emax << fbits);
    // tiny values overflow to infinity
    if (exp == 0 && (frac >> 50) == 0) return (sign << (n - 1)) | (emax << fbits);
    if (exp == 0) {
        if (!((frac >> 51) & 1)) { exp = -1; frac = (frac << 2) & ((1ull << 52) - 1); }
        else frac = (frac << 1) & ((1ull << 52) - 1);
    }
    uint32_t scaled = (uint32_t)(0x100 | (frac >> 44));
    int64_t rexp = (n == 32 ? 253 : 2045) - exp;
    uint64_t est = bf_recip_est_int(scaled);
    uint64_t rfrac = (est & 0xFF) << 44;
    if (rexp == 0) rfrac = (1ull << 51) | (rfrac >> 1);
    else if (rexp == -1) { rfrac = (1ull << 50) | (rfrac >> 2); rexp = 0; }
    return (sign << (n - 1)) | ((uint64_t)rexp << fbits) | (rfrac >> (52 - fbits));
}
static inline uint64_t bf_frsqrte_bits(uint64_t op, int n) {
    int ebits = n == 32 ? 8 : 11, fbits = n == 32 ? 23 : 52;
    uint64_t sign = op >> (n - 1);
    int64_t exp = (int64_t)((op >> fbits) & ((1u << ebits) - 1));
    uint64_t frac = (op & ((1ull << fbits) - 1)) << (52 - fbits);
    uint64_t emax = (1u << ebits) - 1;
    uint64_t dnan = n == 32 ? 0x7FC00000ull : 0x7FF8000000000000ull;
    if (exp == (int64_t)emax && frac) return op | (n == 32 ? 0x400000ull : (1ull << 51));
    if (exp == 0 && frac == 0) return (sign << (n - 1)) | (emax << fbits);
    if (sign) return dnan;
    if (exp == (int64_t)emax) return 0;
    if (exp == 0) {
        while (!((frac >> 51) & 1)) { frac = (frac << 1) & ((1ull << 52) - 1); exp--; }
        frac = (frac << 1) & ((1ull << 52) - 1);
    }
    uint32_t scaled = (exp & 1) == 0 ? (uint32_t)(0x100 | (frac >> 44)) : (uint32_t)(0x80 | (frac >> 45));
    int64_t rexp = n == 32 ? (380 - exp) / 2 : (3068 - exp) / 2;
    uint64_t est = bf_rsqrt_est_int(scaled);
    return ((uint64_t)rexp << fbits) | (((est & 0xFF) << 44) >> (52 - fbits));
}
static inline float bf_frecpe_f(float a) { return bf_u2f((uint32_t)bf_frecpe_bits(bf_f2u(a), 32)); }
static inline double bf_frecpe_d(double a) { return bf_u2d(bf_frecpe_bits(bf_d2u(a), 64)); }
static inline float bf_frsqrte_f(float a) { return bf_u2f((uint32_t)bf_frsqrte_bits(bf_f2u(a), 32)); }
static inline double bf_frsqrte_d(double a) { return bf_u2d(bf_frsqrte_bits(bf_d2u(a), 64)); }
static inline float bf_frecpx_f(float a) {
    uint32_t u = bf_f2u(a);
    if (isnan(a)) return bf_pnan_f(a, a);
    uint32_t e = (u >> 23) & 0xFF;
    return bf_u2f((u & 0x80000000u) | ((e ? (~e & 0xFF) : 0xFE) << 23));
}
static inline double bf_frecpx_d(double a) {
    uint64_t u = bf_d2u(a);
    if (isnan(a)) return bf_pnan_d(a, a);
    uint64_t e = (u >> 52) & 0x7FF;
    return bf_u2d((u & (1ull << 63)) | ((e ? (~e & 0x7FF) : 0x7FE) << 52));
}
static inline uint32_t bf_urecpe(uint32_t a) {
    if (!(a >> 31)) return 0xFFFFFFFFu;
    return bf_recip_est_int(a >> 23) << 23;
}
static inline uint32_t bf_ursqrte(uint32_t a) {
    if (!(a >> 30)) return 0xFFFFFFFFu;
    return bf_rsqrt_est_int(a >> 23) << 23;
}

// ---- integer SIMD helpers
static inline int64_t bf_sat_s(__int128 v, int bits) {
    __int128 hi = ((__int128)1 << (bits - 1)) - 1, lo = -((__int128)1 << (bits - 1));
    return (int64_t)(v > hi ? hi : v < lo ? lo : v);
}
static inline uint64_t bf_sat_u(__int128 v, int bits) {
    __int128 hi = bits == 64 ? (__int128)UINT64_MAX : (((__int128)1 << bits) - 1);
    return (uint64_t)(v > hi ? hi : v < 0 ? 0 : v);
}
// SSHL/USHL family: shift by signed low byte of s; flags select rounding and saturation.
static inline uint64_t bf_vshl(uint64_t a, int8_t s, int bits, int is_signed, int round, int sat) {
    __int128 v;
    if (is_signed) {
        int64_t x = (int64_t)(a << (64 - bits)) >> (64 - bits);
        v = x;
    } else {
        v = (__int128)(bits == 64 ? a : (a & ((1ull << bits) - 1)));
    }
    int sh = s;
    if (sh >= 0) {
        if (sat) {
            if (v != 0 && sh >= bits) v = v > 0 ? ((__int128)1 << 100) : -((__int128)1 << 100);
            else v = v * ((__int128)1 << sh);
        } else {
            v = sh >= bits ? 0 : (__int128)((unsigned __int128)v << sh);
        }
    } else {
        int n = -sh;
        if (n >= 120) v = round ? 0 : (v < 0 ? -1 : 0);
        else { if (round) v += (__int128)1 << (n - 1); v >>= n; }
    }
    if (sat) return is_signed ? (uint64_t)bf_sat_s(v, bits) : bf_sat_u(v, bits);
    return (uint64_t)v;
}
static inline uint8_t bf_pmul8(uint8_t a, uint8_t b) {
    uint8_t r = 0;
    for (int i = 0; i < 8; i++) if ((b >> i) & 1) r ^= (uint8_t)(a << i);
    return r;
}
static inline void bf_pmull64(uint64_t a, uint64_t b, uint64_t *lo, uint64_t *hi) {
    uint64_t l = 0, h = 0;
    for (int i = 0; i < 64; i++)
        if ((b >> i) & 1) { l ^= a << i; if (i) h ^= a >> (64 - i); }
    *lo = l; *hi = h;
}
static inline uint16_t bf_pmull8(uint8_t a, uint8_t b) {
    uint16_t r = 0;
    for (int i = 0; i < 8; i++) if ((b >> i) & 1) r ^= (uint16_t)((uint16_t)a << i);
    return r;
}
