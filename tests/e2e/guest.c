// Freestanding AArch64 test guest: exercises integer, FP, SIMD, switch tables, recursion,
// indirect calls and atomics. Prints a checksum line and returns it as the exit code.
typedef unsigned long u64;
typedef unsigned int u32;

#ifdef LINUX
static void out(const char *s, u64 n) {
    register u64 x0 __asm__("x0") = 1, x1 __asm__("x1") = (u64)s, x2 __asm__("x2") = n, x8 __asm__("x8") = 64;
    __asm__ volatile("svc 0" : "+r"(x0) : "r"(x1), "r"(x2), "r"(x8) : "memory");
}
#else
static void out(const char *s, u64 n) {
    register u64 x0 __asm__("x0") = (u64)s, x1 __asm__("x1") = n;
    __asm__ volatile("svc 0x27" : "+r"(x0), "+r"(x1) : : "memory");
}
#endif

static void put_hex(const char *label, u64 v) {
    char buf[64];
    int n = 0;
    while (*label) buf[n++] = *label++;
    buf[n++] = '=';
    for (int i = 15; i >= 0; i--) buf[n++] = "0123456789abcdef"[(v >> (i * 4)) & 15];
    buf[n++] = '\n';
    out(buf, n);
}

__attribute__((noinline)) static u64 fib(u64 n) { return n < 2 ? n : fib(n - 1) + fib(n - 2); }

__attribute__((noinline)) static int classify(int v) {
    switch (v) {
    case 0: return 11; case 1: return 23; case 2: return 37; case 3: return 41;
    case 4: return 53; case 5: return 67; case 6: return 71; case 7: return 89;
    case 8: return 97; default: return -1;
    }
}

__attribute__((noinline)) static double poly(double x) {
    double r = 0;
    for (int i = 0; i < 8; i++) r = r * x + (double)(i + 1) / 3.0;
    return r;
}

static float data_a[64], data_b[64];
static volatile u32 counter;

__attribute__((noinline)) static float dot(const float *a, const float *b, int n) {
    float s = 0;
    for (int i = 0; i < n; i++) s += a[i] * b[i];
    return s;
}

__attribute__((noinline)) static void sort(u32 *v, int n) {
    for (int i = 1; i < n; i++) {
        u32 k = v[i];
        int j = i - 1;
        while (j >= 0 && v[j] > k) { v[j + 1] = v[j]; j--; }
        v[j + 1] = k;
    }
}

typedef u64 (*op_fn)(u64, u64);
static u64 op_add(u64 a, u64 b) { return a + b; }
static u64 op_mul(u64 a, u64 b) { return a * b; }
static u64 op_xor(u64 a, u64 b) { return a ^ (b << 3); }
static u64 op_div(u64 a, u64 b) { return b ? a / b : a; }
static op_fn ops[] = {op_add, op_mul, op_xor, op_div};

u64 guest_main(void) {
    u64 h = 0xcbf29ce484222325ul;
    h ^= fib(20); h *= 0x100000001b3ul;
    for (int i = 0; i < 12; i++) { h ^= (u64)(long)classify(i); h *= 0x100000001b3ul; }
    double p = poly(1.25);
    h ^= (u64)(p * 1000000.0); h *= 0x100000001b3ul;
    for (int i = 0; i < 64; i++) { data_a[i] = (float)i * 0.5f; data_b[i] = 64.0f - (float)i; }
    float d = dot(data_a, data_b, 64);
    h ^= (u64)(d * 16.0f); h *= 0x100000001b3ul;
    u32 v[32];
    u64 seed = 12345;
    for (int i = 0; i < 32; i++) { seed = seed * 6364136223846793005ul + 1442695040888963407ul; v[i] = (u32)(seed >> 33); }
    sort(v, 32);
    for (int i = 0; i < 32; i++) { h ^= v[i]; h *= 0x100000001b3ul; }
    u64 acc = 7;
    for (int i = 0; i < 40; i++) acc = ops[(acc ^ (u64)i) & 3](acc, (u64)i + 3);
    h ^= acc; h *= 0x100000001b3ul;
    for (int i = 0; i < 100; i++) __atomic_fetch_add(&counter, (u32)i, __ATOMIC_SEQ_CST);
    h ^= counter; h *= 0x100000001b3ul;
    put_hex("fib", fib(20));
    put_hex("poly", (u64)(p * 1000000.0));
    put_hex("dot", (u64)(d * 16.0f));
    put_hex("acc", acc);
    put_hex("hash", h);
    return h & 0x7f;
}

#ifdef LINUX
void _start(void) {
    u64 r = guest_main();
    register u64 x0 __asm__("x0") = r, x8 __asm__("x8") = 93;
    __asm__ volatile("svc 0" : : "r"(x0), "r"(x8));
    for (;;) {}
}
#else
u64 _start(u64 a, u64 b) { (void)a; (void)b; return guest_main(); }
#endif
