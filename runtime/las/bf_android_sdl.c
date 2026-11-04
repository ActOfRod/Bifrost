// Android host for titles built with the NVN → Vulkan renderer: SDL3's activity runs SDL_main, which unpacks the
// bundled assets, starts the guest on its own thread and runs the window / Vulkan / input loop (ui_run) here.
#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>
#include <SDL3/SDL_system.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <jni.h>
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

// APK assets are read through the AAssetManager: SDL_IOFromFile resolves relative names against internal storage
// first, which would return the previously unpacked copies instead of the assets of a newly installed APK.
static AAssetManager *assets;

static AAssetManager *asset_manager(void) {
    JNIEnv *env = (JNIEnv *)SDL_GetAndroidJNIEnv();
    jobject activity = (jobject)SDL_GetAndroidActivity();
    if (!env || !activity) return NULL;
    jclass cls = (*env)->GetObjectClass(env, activity);
    jmethodID get = (*env)->GetMethodID(env, cls, "getAssets", "()Landroid/content/res/AssetManager;");
    jobject am = get ? (*env)->CallObjectMethod(env, activity, get) : NULL;
    AAssetManager *mgr = am ? AAssetManager_fromJava(env, (*env)->NewGlobalRef(env, am)) : NULL;
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, activity);
    return mgr;
}

static int extract(const char *name, const char *dst, int always) {
    AAsset *in = AAssetManager_open(assets, name, AASSET_MODE_STREAMING);
    if (!in) return 0;
    off64_t len = AAsset_getLength64(in);
    struct stat st;
    if (!always && stat(dst, &st) == 0 && st.st_size == len) { AAsset_close(in); return 1; }
    char tmp[1100];
    snprintf(tmp, sizeof tmp, "%s.part", dst);
    FILE *f = fopen(tmp, "wb");
    if (!f) { AAsset_close(in); return 0; }
    static char buf[1 << 16];
    int n;
    while ((n = AAsset_read(in, buf, sizeof buf)) > 0) fwrite(buf, 1, (size_t)n, f);
    fclose(f);
    AAsset_close(in);
    return rename(tmp, dst) == 0;
}

static void read_small(const char *path, char *out, size_t size, int asset) {
    out[0] = 0;
    AAsset *in = asset ? AAssetManager_open(assets, path, AASSET_MODE_BUFFER) : NULL;
    FILE *f = asset ? NULL : fopen(path, "rb");
    int n = in ? AAsset_read(in, out, size - 1) : f ? (int)fread(out, 1, size - 1, f) : 0;
    out[n > 0 ? n : 0] = 0;
    if (in) AAsset_close(in);
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
    if (!(assets = asset_manager())) { __android_log_print(ANDROID_LOG_ERROR, "bifrost", "no asset manager"); return 1; }
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
    AAssetDir *dir = AAssetManager_openDir(assets, "dlc");
    for (const char *name; dir && (name = AAssetDir_getNextFileName(dir));) {
        char src[512], dst[1600];
        snprintf(src, sizeof src, "dlc/%s", name);
        snprintf(dst, sizeof dst, "%s/%s", dlc_path, name);
        bundled_dlc |= extract(src, dst, fresh);
    }
    if (dir) AAssetDir_close(dir);
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
