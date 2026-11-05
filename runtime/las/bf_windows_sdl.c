#define SDL_MAIN_HANDLED
#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>
#include <windows.h>
#include <process.h>
#include <io.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

int bf_run(int argc, char **argv);
void settings_load(void);
int ui_run(void);

static HANDLE log_pipe_read;
static HANDLE console_out;
static HANDLE console_err;
static HANDLE log_file;

static HANDLE duplicate_handle(HANDLE handle) {
    HANDLE copy = NULL;
    if (handle && handle != INVALID_HANDLE_VALUE)
        DuplicateHandle(GetCurrentProcess(), handle, GetCurrentProcess(), &copy, 0, FALSE, DUPLICATE_SAME_ACCESS);
    return copy;
}

static unsigned __stdcall log_pump(void *unused) {
    (void)unused;
    char buf[4096];
    DWORD count;
    while (ReadFile(log_pipe_read, buf, sizeof buf, &count, NULL) && count) {
        DWORD written;
        if (console_err) WriteFile(console_err, buf, count, &written, NULL);
        else if (console_out) WriteFile(console_out, buf, count, &written, NULL);
        if (log_file) WriteFile(log_file, buf, count, &written, NULL);
    }
    return 0;
}

static void tee_logs(const char *exe) {
    char path[4096];
    size_t n = strlen(exe);
    if (n >= sizeof path) return;
    memcpy(path, exe, n + 1);
    char *slash = strrchr(path, '\\');
    char *forward = strrchr(path, '/');
    if (forward && (!slash || forward > slash)) slash = forward;
    if (slash) slash[1] = '\0';
    strncat(path, "bifrost.log", sizeof path - strlen(path) - 1);
    log_file = CreateFileA(path, FILE_APPEND_DATA, FILE_SHARE_READ | FILE_SHARE_WRITE, NULL,
                           OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    console_out = duplicate_handle(GetStdHandle(STD_OUTPUT_HANDLE));
    console_err = duplicate_handle(GetStdHandle(STD_ERROR_HANDLE));
    HANDLE write_pipe;
    SECURITY_ATTRIBUTES sa = {sizeof sa, NULL, TRUE};
    if (!CreatePipe(&log_pipe_read, &write_pipe, &sa, 0)) return;
    SetHandleInformation(log_pipe_read, HANDLE_FLAG_INHERIT, 0);
    int fd = _open_osfhandle((intptr_t)write_pipe, _O_WRONLY | _O_BINARY);
    if (fd < 0) return;
    fflush(stdout);
    fflush(stderr);
    _dup2(fd, _fileno(stdout));
    _dup2(fd, _fileno(stderr));
    _close(fd);
    _beginthreadex(NULL, 0, log_pump, NULL, 0, NULL);
}

static unsigned __stdcall run_guest(void *arg) {
    char **argv = arg;
    bf_run(1, argv);
    SDL_Delay(1000);
    SDL_Event event = {.type = SDL_EVENT_QUIT};
    SDL_PushEvent(&event);
    return 0;
}

int main(int argc, char **argv) {
    (void)argc;
    char exe[4096];
    DWORD length = GetModuleFileNameA(NULL, exe, sizeof exe);
    if (!length || length >= sizeof exe) return 1;
    exe[length] = '\0';
    tee_logs(exe);
    SDL_SetMainReady();
    settings_load();
    char *guest_argv[] = {exe, NULL};
    HANDLE guest = (HANDLE)_beginthreadex(NULL, 0, run_guest, guest_argv, 0, NULL);
    if (!guest) {
        fprintf(stderr, "Could not start guest thread (Windows error %lu).\n", GetLastError());
        return 1;
    }
    CloseHandle(guest);
    return ui_run();
}
