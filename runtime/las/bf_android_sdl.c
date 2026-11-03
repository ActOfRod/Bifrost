// Android host for titles built with the NVN → Vulkan renderer: SDL3's activity runs SDL_main, which unpacks the
// bundled assets, starts the guest on its own thread and runs the window / Vulkan / input loop (ui_run) here.
#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>
#include <SDL3/SDL_system.h>
#include <android/log.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include "../bf_runtime.h"

void settings_load(void);
int ui_run(void);

static char image_path[1024], romfs_path[1024], dlc_path[1024];
static int log_pipe[2];

// The runtime and the guest (svcOutputDebugString) write to stdout / stderr; forward them to logcat.
static void *log_pump(void *arg) {
    (void)arg;
    char buf[1024];
    ssize_t n;
    while ((n = read(log_pipe[0], buf, sizeof buf - 1)) > 0) {
        buf[n] = 0;
        __android_log_write(ANDROID_LOG_INFO, "bifrost", buf);
    }
    return NULL;
}

static int extract(const char *name, const char *dst, int always) {
    SDL_IOStream *in = SDL_IOFromFile(name, "rb");  // (APK assets on Android)
    if (!in) return 0;
    Sint64 len = SDL_GetIOSize(in);
    struct stat st;
    if (!always && stat(dst, &st) == 0 && st.st_size == len) { SDL_CloseIO(in); return 1; }
    char tmp[1100];
    snprintf(tmp, sizeof tmp, "%s.part", dst);
    FILE *f = fopen(tmp, "wb");
    if (!f) { SDL_CloseIO(in); return 0; }
    static char buf[1 << 16];
    size_t n;
    while ((n = SDL_ReadIO(in, buf, sizeof buf)) > 0) fwrite(buf, 1, n, f);
    fclose(f);
    SDL_CloseIO(in);
    return rename(tmp, dst) == 0;
}

static void read_small(const char *path, char *out, size_t size, int asset) {
    out[0] = 0;
    SDL_IOStream *in = asset ? SDL_IOFromFile(path, "rb") : NULL;
    FILE *f = asset ? NULL : fopen(path, "rb");
    size_t n = in ? SDL_ReadIO(in, out, size - 1) : f ? fread(out, 1, size - 1, f) : 0;
    out[n] = 0;
    if (in) SDL_CloseIO(in);
    if (f) fclose(f);
}

// Each APK build carries a unique bifrost.stamp; a mismatch with the unpacked copy means the APK was replaced.
static int assets_changed(const char *dir) {
    char want[64], have[64], path[1100];
    snprintf(path, sizeof path, "%s/bifrost.stamp", dir);
    read_small("bifrost.stamp", want, sizeof want, 1);
    read_small(path, have, sizeof have, 0);
    return !want[0] || strcmp(want, have) != 0;
}

static void *guest_main(void *arg) {
    (void)arg;
    char *argv[] = {"bifrost", "--image", image_path, "--romfs", romfs_path, "--dlc", dlc_path, NULL};
    int status = bf_run(7, argv);
    fflush(NULL);
    __android_log_print(ANDROID_LOG_INFO, "bifrost", "guest exited with status %d", status);
    return NULL;
}

int main(int argc, char **argv) {
    (void)argc, (void)argv;
    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
    if (pipe(log_pipe) == 0) {
        pthread_t lt;
        dup2(log_pipe[1], 1);
        dup2(log_pipe[1], 2);
        pthread_create(&lt, NULL, log_pump, NULL);
        pthread_detach(lt);
    }
    const char *in = SDL_GetAndroidInternalStoragePath();
    const char *ext = SDL_GetAndroidExternalStoragePath();
    if (!in) { __android_log_print(ANDROID_LOG_ERROR, "bifrost", "no internal storage: %s", SDL_GetError()); return 1; }
    mkdir(in, 0700);
    int fresh = assets_changed(in);
    snprintf(image_path, sizeof image_path, "%s/guest_image.bin", in);
    extract("guest_image.bin", image_path, fresh);
    snprintf(romfs_path, sizeof romfs_path, "%s/romfs.bin", in);
    if (!extract("romfs.bin", romfs_path, fresh) && ext) snprintf(romfs_path, sizeof romfs_path, "%s/romfs.bin", ext);
    snprintf(dlc_path, sizeof dlc_path, "%s/dlc", in);
    mkdir(dlc_path, 0700);
    int bundled_dlc = 0;
    int ndlc = 0;
    char **names = SDL_GlobDirectory("dlc", NULL, 0, &ndlc);  // (APK asset dirs)
    for (int i = 0; names && i < ndlc; i++) {
        char src[512], dst[1600];
        snprintf(src, sizeof src, "dlc/%s", names[i]);
        snprintf(dst, sizeof dst, "%s/%s", dlc_path, names[i]);
        bundled_dlc |= extract(src, dst, fresh);
    }
    SDL_free(names);
    if (fresh) {
        char stamp[1100];
        snprintf(stamp, sizeof stamp, "%s/bifrost.stamp", in);
        extract("bifrost.stamp", stamp, 1);
    }
    if (!bundled_dlc && ext) snprintf(dlc_path, sizeof dlc_path, "%s/dlc", ext);
    __android_log_print(ANDROID_LOG_INFO, "bifrost", "starting %s with the Vulkan renderer (romfs %s)", bf_title_name, romfs_path);

    settings_load();
    pthread_t t;
    pthread_create(&t, NULL, guest_main, NULL);
    pthread_detach(t);
    ui_run();  // window, Vulkan device and input on this thread; the guest's NVN calls wait for it (vk_wait_ready)
    return 0;
}
