// Android NativeActivity host for a Bifrost-generated project: unpacks the guest image, RomFS and
// add-on content from the APK, pipes stdout/stderr to logcat and runs the guest on its own thread.
#include <android/asset_manager.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android_native_app_glue.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include "bf_runtime.h"

static int log_pipe[2];
static char image_path[1024], romfs_path[1024], dlc_path[1024];
static volatile int guest_done;
static volatile int guest_status;

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

static int extract(AAssetManager *am, const char *name, const char *dst, int always) {
    AAsset *a = AAssetManager_open(am, name, AASSET_MODE_STREAMING);
    if (!a) return 0;
    off64_t len = AAsset_getLength64(a);
    struct stat st;
    if (!always && stat(dst, &st) == 0 && st.st_size == len) { AAsset_close(a); return 1; }
    FILE *f = fopen(dst, "wb");
    if (!f) { AAsset_close(a); return 0; }
    static char buf[1 << 16];
    int n;
    while ((n = AAsset_read(a, buf, sizeof buf)) > 0) fwrite(buf, 1, (size_t)n, f);
    fclose(f);
    AAsset_close(a);
    return 1;
}

static void *guest_main(void *arg) {
    (void)arg;
    char *argv[] = {"bifrost", "--image", image_path, "--romfs", romfs_path, "--dlc", dlc_path, NULL};
    guest_status = bf_run(7, argv);
    fflush(NULL);
    __android_log_print(ANDROID_LOG_INFO, "bifrost", "guest exited with status %d", guest_status);
    guest_done = 1;
    return NULL;
}

static void paint(struct android_app *app) {
    ANativeWindow *w = app->window;
    if (!w) return;
    ANativeWindow_setBuffersGeometry(w, 0, 0, WINDOW_FORMAT_RGBX_8888);
    ANativeWindow_Buffer b;
    if (ANativeWindow_lock(w, &b, NULL) != 0) return;
    uint32_t colour = guest_done ? 0xFF203820u : 0xFF301810u;
    for (int y = 0; y < b.height; y++) {
        uint32_t *row = (uint32_t *)b.bits + (size_t)y * (size_t)b.stride;
        for (int x = 0; x < b.width; x++) row[x] = colour;
    }
    ANativeWindow_unlockAndPost(w);
}

static void on_cmd(struct android_app *app, int32_t cmd) {
    if (cmd == APP_CMD_INIT_WINDOW || cmd == APP_CMD_WINDOW_REDRAW_NEEDED) paint(app);
}

void android_main(struct android_app *app) {
    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
    pthread_t t;
    if (pipe(log_pipe) == 0) {
        dup2(log_pipe[1], 1);
        dup2(log_pipe[1], 2);
        pthread_create(&t, NULL, log_pump, NULL);
        pthread_detach(t);
    }
    AAssetManager *am = app->activity->assetManager;
    const char *in = app->activity->internalDataPath;
    const char *ext = app->activity->externalDataPath;
    mkdir(in, 0700);
    snprintf(image_path, sizeof image_path, "%s/guest_image.bin", in);
    extract(am, "guest_image.bin", image_path, 1);
    snprintf(romfs_path, sizeof romfs_path, "%s/romfs.bin", in);
    if (!extract(am, "romfs.bin", romfs_path, 0) && ext) snprintf(romfs_path, sizeof romfs_path, "%s/romfs.bin", ext);
    snprintf(dlc_path, sizeof dlc_path, "%s/dlc", in);
    mkdir(dlc_path, 0700);
    int bundled_dlc = 0;
    AAssetDir *d = AAssetManager_openDir(am, "dlc");
    const char *name;
    while (d && (name = AAssetDir_getNextFileName(d))) {
        char src[512], dst[1600];
        snprintf(src, sizeof src, "dlc/%s", name);
        snprintf(dst, sizeof dst, "%s/%s", dlc_path, name);
        bundled_dlc |= extract(am, src, dst, 0);
    }
    if (d) AAssetDir_close(d);
    if (!bundled_dlc && ext) snprintf(dlc_path, sizeof dlc_path, "%s/dlc", ext);
    __android_log_print(ANDROID_LOG_INFO, "bifrost", "starting %s (romfs %s)", bf_title_name, romfs_path);

    app->onAppCmd = on_cmd;
    pthread_create(&t, NULL, guest_main, NULL);
    pthread_detach(t);
    int painted_done = 0;
    while (!app->destroyRequested) {
        int events;
        struct android_poll_source *src = NULL;
        if (ALooper_pollOnce(200, NULL, &events, (void **)&src) >= 0 && src) src->process(app, src);
        if (guest_done && !painted_done) { paint(app); painted_done = 1; }
    }
    _exit(guest_done ? guest_status : 0);
}
