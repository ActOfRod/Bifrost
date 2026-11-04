// HIPC/CMIF message handling and high-level service implementations.
#include "bf_runtime.h"
#include <stdlib.h>
#include <string.h>
#include <time.h>

typedef struct BfObj { char name[40]; } BfObj;
typedef struct BfSession {
    BfObj *root;
    int is_domain;
    BfObj *objs[512];
} BfSession;

typedef struct {
    BfSession *sess;
    BfObj *obj;
    uint32_t cmd;
    const uint8_t *in; uint32_t in_size;
    uint64_t a_addr[4], a_size[4], b_addr[4], b_size[4], x_addr[4], x_size[4], c_addr, c_size;
    int na, nb, nx;
    uint32_t in_handles[8]; int nin_handles;
    uint8_t out[0x100]; uint32_t out_size;
    BfObj *out_objs[8]; int nout_objs;
    uint32_t copy_handles[8]; int ncopy;
    uint32_t result;
} BfCtx;

static BfObj *obj_new(const char *name) {
    BfObj *o = calloc(1, sizeof *o);
    snprintf(o->name, sizeof o->name, "%s", name);
    return o;
}
static uint32_t session_new(BfObj *root) {
    BfSession *s = calloc(1, sizeof *s);
    s->root = root;
    return bf_handle_new(BF_H_SESSION, s);
}

uint32_t bf_ipc_connect(const char *name) { return session_new(obj_new(name)); }
void bf_ipc_close(uint32_t handle) { (void)handle; }

static void out_u8(BfCtx *x, uint8_t v) { x->out[x->out_size++] = v; }
static void out_u32(BfCtx *x, uint32_t v) { memcpy(x->out + x->out_size, &v, 4); x->out_size += 4; }
static void out_u64(BfCtx *x, uint64_t v) { x->out_size = (x->out_size + 7) & ~7u; memcpy(x->out + x->out_size, &v, 8); x->out_size += 8; }
static void out_obj(BfCtx *x, const char *name) { if (x->nout_objs < 8) x->out_objs[x->nout_objs++] = obj_new(name); }
static uint64_t in_u64(BfCtx *x, uint32_t off) { uint64_t v = 0; if (off + 8 <= x->in_size) memcpy(&v, x->in + off, 8); return v; }
static void out_copy_handle(BfCtx *x, uint32_t h) { if (x->ncopy < 8) x->copy_handles[x->ncopy++] = h; }
static int is(const BfCtx *x, const char *name) { return !strcmp(x->obj->name, name); }
static int obj_prefix(const BfCtx *x, const char *p) { return !strncmp(x->obj->name, p, strlen(p)); }

static void out_buffer(BfCtx *x, uint64_t *addr, uint64_t *size) {
    if (x->nb) { *addr = x->b_addr[0]; *size = x->b_size[0]; }
    else { *addr = x->c_addr; *size = x->c_size; }
}

// Add-on content lives in <dlc dir>/<index>.romfs, index 1..N, with data ids title_id + 0x1000 + index.
static void dlc_path(char *out, size_t n, uint32_t index) { snprintf(out, n, "%s/%u.romfs", bf_dlc_dir, index); }
static uint32_t dlc_count(void) {
    static int cached = -1;
    if (cached < 0) {
        cached = 0;
        for (uint32_t i = 1; i < 2000; i++) {
            char p[1100]; dlc_path(p, sizeof p, i);
            FILE *f = fopen(p, "rb");
            if (!f) break;
            fclose(f); cached = (int)i;
        }
    }
    return (uint32_t)cached;
}
static uint64_t aoc_base_id(void) { return (bf_title_id & ~0xFFFull) + 0x1000; }

static FILE *open_storage(const char *obj) {
    if (!strcmp(obj, "IStorage:romfs")) return fopen(bf_romfs_path, "rb");
    if (!strncmp(obj, "IStorage:aoc", 12)) {
        char p[1100]; dlc_path(p, sizeof p, (uint32_t)strtoul(obj + 12, NULL, 10));
        return fopen(p, "rb");
    }
    return NULL;
}
static int64_t storage_size(const char *obj) {
    FILE *f = open_storage(obj);
    if (!f) return 0;
    fseek(f, 0, SEEK_END);
#ifdef _WIN32
    int64_t n = _ftelli64(f);
#else
    int64_t n = ftello(f);
#endif
    fclose(f);
    return n;
}

typedef struct { const char *svc; uint32_t cmd; const char *child; } BfChild;
static const BfChild children[] = {
    {"appletOE", 0, "IApplicationProxy"},
    {"appletAE", 200, "IApplicationProxy"}, {"appletAE", 100, "IApplicationProxy"},
    {"IApplicationProxy", 0, "ICommonStateGetter"}, {"IApplicationProxy", 1, "ISelfController"},
    {"IApplicationProxy", 2, "IWindowController"}, {"IApplicationProxy", 3, "IAudioController"},
    {"IApplicationProxy", 4, "IDisplayController"}, {"IApplicationProxy", 10, "IProcessWindingController"},
    {"IApplicationProxy", 11, "ILibraryAppletCreator"}, {"IApplicationProxy", 20, "IApplicationFunctions"},
    {"IApplicationProxy", 1000, "IDebugFunctions"},
    {"fsp-srv", 18, "IFileSystem"}, {"fsp-srv", 51, "IFileSystem"}, {"fsp-srv", 52, "IFileSystem"},
    {"fsp-srv", 53, "IFileSystem"}, {"fsp-srv", 200, "IStorage:romfs"}, {"fsp-srv", 202, "IStorage"},
    {"fsp-srv", 203, "IStorage:romfs"}, {"fsp-srv", 1, NULL},
    {"time:", 0, "ISystemClock"}, {"time:", 1, "ISystemClock"}, {"time:", 2, "ISteadyClock"},
    {"time:", 3, "ITimeZoneService"}, {"time:", 4, "ISystemClock"},
    {"hid", 0, "IAppletResource"}, {"irs", 0, NULL},
    {"vi:", 2, "IApplicationDisplayService"}, {"vi:", 1, "IApplicationDisplayService"},
    {"vi:", 0, "IApplicationDisplayService"},
    {"IApplicationDisplayService", 100, "IHOSBinderDriver"}, {"IApplicationDisplayService", 101, "ISystemDisplayService"},
    {"IApplicationDisplayService", 102, "IManagerDisplayService"}, {"IApplicationDisplayService", 103, "IHOSBinderDriver"},
    {"acc:", 101, "IManagerForApplication"}, {"acc:", 5, "IProfile"}, {"acc:", 140, NULL},
    {"pctl", 0, "IParentalControlService"}, {"pctl", 1, "IParentalControlService"},
    {"friend:", 0, "IFriendService"}, {"friend:", 1, "INotificationService"},
    {"nifm:", 4, "IGeneralService"}, {"nifm:", 5, "IGeneralService"},
    {"IGeneralService", 4, "IRequest"},
    {"bcat:", 0, "IBcatService"}, {"bcat:", 1, "IDeliveryCacheStorageService"},
    {"ssl", 0, "ISslContext"}, {"lm", 0, "ILogger"},
    {"audren:u", 0, "IAudioRenderer"}, {"audren:u", 2, "IAudioDevice"}, {"audout:u", 1, "IAudioOut"},
    {"audin:u", 1, "IAudioIn"}, {"prepo:", 10100, NULL},
    {"nfp:user", 0, "IUser"}, {"mm:u", 0, NULL},
    {"ns:am2", 7996, "IApplicationManagerInterface"}, {"ns:am2", 7988, "IDynamicRightsInterface"},
    {"ILibraryAppletCreator", 0, "ILibraryAppletAccessor"},
    {"IFileSystem", 8, "IFile"}, {"IFileSystem", 9, "IDirectory"},
    {"IApplicationFunctions", 1, "IStorage"}, {"IApplicationFunctions", 22, NULL},
    {"ISelfController", 40, NULL}, {"IHOSBinderDriver", 0, NULL},
    {"IAppletResource", 0, NULL},
};

static int stub_logged(const char *name, uint32_t cmd) {
    static struct { char n[40]; uint32_t c; } seen[2048];
    static int nseen;
    for (int i = 0; i < nseen; i++) if (seen[i].c == cmd && !strcmp(seen[i].n, name)) return 1;
    if (nseen < 2048) { snprintf(seen[nseen].n, 40, "%s", name); seen[nseen++].c = cmd; }
    return 0;
}

static void dispatch(BfCtx *x) {
    uint32_t cmd = x->cmd;
    if (is(x, "sm:")) {
        if (cmd == 1) {
            char name[9] = {0};
            uint64_t v = in_u64(x, 0);
            memcpy(name, &v, 8);
            if (bf_verbose) bf_log("bifrost: sm GetService(%s)\n", name);
            out_obj(x, name);
        }
        return;
    }
    if (obj_prefix(x, "aoc:")) {
        if (cmd == 2 || cmd == 0) out_u32(x, dlc_count());
        else if (cmd == 3 || cmd == 1) {
            uint32_t off = (uint32_t)in_u64(x, 0), want = (uint32_t)(in_u64(x, 0) >> 32), n = 0;
            uint64_t addr, cap;
            out_buffer(x, &addr, &cap);
            for (uint32_t i = off + 1; i <= dlc_count() && n < want && (n + 1) * 4 <= cap; i++, n++) ST32(addr + n * 4, i);
            out_u32(x, n);
        } else if (cmd == 5) out_u64(x, aoc_base_id());
        else if (cmd == 8) { uint32_t h = bf_event_new(); out_copy_handle(x, h); }
        return;
    }
    if (is(x, "fsp-srv") && cmd == 202) {
        uint64_t data_id = in_u64(x, 8);
        char name[40];
        if (data_id > aoc_base_id() && data_id - aoc_base_id() <= dlc_count())
            snprintf(name, sizeof name, "IStorage:aoc%u", (unsigned)(data_id - aoc_base_id()));
        else snprintf(name, sizeof name, "IStorage");
        out_obj(x, name);
        return;
    }
    if (obj_prefix(x, "IStorage:")) {
        if (cmd == 0) {
            uint64_t off = in_u64(x, 0), size = in_u64(x, 8), addr, cap;
            out_buffer(x, &addr, &cap);
            if (size > cap) size = cap;
            FILE *f = open_storage(x->obj->name);
            if (!f) { x->result = 0x7D402; return; }
#ifdef _WIN32
            _fseeki64(f, (int64_t)off, SEEK_SET);
#else
            fseeko(f, (off_t)off, SEEK_SET);
#endif
            size_t n = fread(BF_HOST(addr), 1, (size_t)size, f);
            if (n < size) memset((uint8_t *)BF_HOST(addr) + n, 0, (size_t)(size - n));
            fclose(f);
        } else if (cmd == 4) out_u64(x, (uint64_t)storage_size(x->obj->name));
        return;
    }
    if (is(x, "ISystemClock") && cmd == 0) { out_u64(x, (uint64_t)time(NULL)); return; }
    if (is(x, "ISteadyClock") && cmd == 0) {
        out_u64(x, bf_plat_ns() / 1000000000ull); memset(x->out + x->out_size, 0, 16); x->out_size += 16; return;
    }
    if (is(x, "ICommonStateGetter")) {
        if (cmd == 0) { uint32_t h = bf_event_new(); bf_event_signal(h); out_copy_handle(x, h); }
        else if (cmd == 1) x->result = 0x680;
        else if (cmd == 5) out_u8(x, 0);
        else if (cmd == 6) out_u32(x, 0);
        else if (cmd == 9) out_u8(x, 1);
        return;
    }
    if (is(x, "IAppletResource") && cmd == 0) {
        uint64_t va = bf_misc_alloc(0x40000);
        (void)va;
        out_copy_handle(x, bf_handle_new(BF_H_OTHER, NULL));
        return;
    }
    if (obj_prefix(x, "set") && cmd == 1) { out_u64(x, 0x53552D6E65ull); return; }
    if (is(x, "IFileSystem") && (cmd == 7 || cmd == 8 || cmd == 9)) { x->result = 0x202; return; }
    if (obj_prefix(x, "apm") && cmd == 0) { out_obj(x, "ISession"); return; }
    for (size_t i = 0; i < sizeof children / sizeof children[0]; i++) {
        const BfChild *k = &children[i];
        size_t n = strlen(k->svc);
        int match = k->svc[n - 1] == ':' ? !strncmp(x->obj->name, k->svc, n) : !strcmp(x->obj->name, k->svc);
        if (match && k->cmd == cmd) { if (k->child) out_obj(x, k->child); return; }
    }
    if (!stub_logged(x->obj->name, cmd) && bf_verbose)
        bf_log("bifrost: stubbed service call %s #%u\n", x->obj->name, cmd);
}

static uint64_t desc_addr(uint32_t w1, uint32_t w2) {
    return w1 | ((uint64_t)((w2 >> 28) & 0xF) << 32) | ((uint64_t)((w2 >> 2) & 0x7) << 36);
}

uint32_t bf_ipc_request(uint64_t msg, uint32_t handle) {
    BfSession *sess = bf_handle_obj(handle, BF_H_SESSION);
    if (!sess) return 0xE401;
    uint32_t w[64];
    memcpy(w, BF_HOST(msg), sizeof w);
    uint32_t type = w[0] & 0xFFFF;
    uint32_t nx = (w[0] >> 16) & 0xF, na = (w[0] >> 20) & 0xF, nb = (w[0] >> 24) & 0xF, nw = (w[0] >> 28) & 0xF;
    uint32_t ndata = w[1] & 0x3FF, cflags = (w[1] >> 10) & 0xF;
    uint32_t i = 2;
    BfCtx *x = calloc(1, sizeof *x);
    x->sess = sess;
    if (w[1] >> 31) {
        uint32_t sp = w[i++];
        if (sp & 1) i += 2;
        uint32_t ncopy = (sp >> 1) & 0xF, nmove = (sp >> 5) & 0xF;
        for (uint32_t k = 0; k < ncopy + nmove && k < 8; k++) x->in_handles[x->nin_handles++] = w[i + k];
        i += ncopy + nmove;
    }
    for (uint32_t k = 0; k < nx; k++, i += 2)
        if (k < 4) {
            x->x_addr[k] = w[i + 1] | ((uint64_t)((w[i] >> 12) & 0xF) << 32) | ((uint64_t)((w[i] >> 6) & 0x7) << 36);
            x->x_size[k] = w[i] >> 16; x->nx++;
        }
    for (uint32_t k = 0; k < na; k++, i += 3)
        if (k < 4) { x->a_addr[k] = desc_addr(w[i + 1], w[i + 2]); x->a_size[k] = w[i] | ((uint64_t)((w[i + 2] >> 24) & 0xF) << 32); x->na++; }
    for (uint32_t k = 0; k < nb; k++, i += 3)
        if (k < 4) { x->b_addr[k] = desc_addr(w[i + 1], w[i + 2]); x->b_size[k] = w[i] | ((uint64_t)((w[i + 2] >> 24) & 0xF) << 32); x->nb++; }
    i += 3 * nw;
    uint32_t data_start = (i * 4 + 15) & ~15u;
    if (cflags >= 2) {
        uint32_t ci = i + ndata;
        if (ci + 1 < 64) { x->c_addr = w[ci] | ((uint64_t)(w[ci + 1] & 0xFFFF) << 32); x->c_size = w[ci + 1] >> 16; }
    }
    const uint8_t *data = (const uint8_t *)w + data_start;
    uint32_t avail = data_start < 256 ? 256 - data_start : 0;
    if (type == 2) { free(x); return 0; }
    int domain = sess->is_domain && (type == 4 || type == 6);
    uint32_t domain_obj = 0;
    if (domain) {
        uint8_t dtype = data[0];
        memcpy(&domain_obj, data + 4, 4);
        data += 16; avail -= 16;
        if (dtype == 2) {
            if (domain_obj && domain_obj <= 512) sess->objs[domain_obj - 1] = NULL;
            x->obj = sess->root;
            goto reply;
        }
        x->obj = domain_obj && domain_obj <= 512 && sess->objs[domain_obj - 1] ? sess->objs[domain_obj - 1] : sess->root;
    } else x->obj = sess->root;
    memcpy(&x->cmd, data + 8, 4);
    x->in = data + 16;
    x->in_size = avail > 16 ? avail - 16 : 0;
    if (type == 5 || type == 7) {
        if (x->cmd == 0) { sess->is_domain = 1; sess->objs[0] = sess->root; out_u32(x, 1); }
        else if (x->cmd == 2 || x->cmd == 4) {
            BfSession *ns = calloc(1, sizeof *ns);
            *ns = *sess;
            x->out_objs[x->nout_objs++] = NULL;
            x->copy_handles[x->ncopy++] = 0;
            x->ncopy--; x->nout_objs--;
            uint32_t h = bf_handle_new(BF_H_SESSION, ns);
            x->copy_handles[x->ncopy++] = h;
        } else if (x->cmd == 3) out_u32(x, 0x8000);
        domain = 0;
    } else {
        dispatch(x);
    }
reply:;
    uint32_t r[64] = {0};
    uint32_t moves[8]; int nmoves = 0;
    if (!domain) for (int k = 0; k < x->nout_objs; k++) moves[nmoves++] = session_new(x->out_objs[k]);
    uint32_t o = 2;
    if (x->ncopy || nmoves) {
        r[1] |= 1u << 31;
        r[o++] = ((uint32_t)x->ncopy << 1) | ((uint32_t)nmoves << 5);
        for (int k = 0; k < x->ncopy; k++) r[o++] = x->copy_handles[k];
        for (int k = 0; k < nmoves; k++) r[o++] = moves[k];
    }
    uint32_t ds = (o * 4 + 15) & ~15u;
    uint8_t *rd = (uint8_t *)r + ds;
    uint32_t pos = 0;
    if (domain) {
        uint32_t n = (uint32_t)x->nout_objs;
        memcpy(rd, &n, 4); pos = 16;
    }
    uint32_t hdr[4] = {0x4F434653u, 0, x->result, 0};
    memcpy(rd + pos, hdr, 16); pos += 16;
    if (!x->result) { memcpy(rd + pos, x->out, x->out_size); pos += (x->out_size + 3) & ~3u; }
    if (domain)
        for (int k = 0; k < x->nout_objs; k++) {
            uint32_t id = 0;
            for (uint32_t s = 0; s < 512; s++) if (!sess->objs[s]) { sess->objs[s] = x->out_objs[k]; id = s + 1; break; }
            memcpy(rd + pos, &id, 4); pos += 4;
        }
    r[1] |= ((ds - o * 4) + pos + 16) / 4;
    memcpy(BF_HOST(msg), r, sizeof r);
    free(x);
    return 0;
}
