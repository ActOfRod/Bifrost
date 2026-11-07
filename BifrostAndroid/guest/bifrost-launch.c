#define WIN32_LEAN_AND_MEAN
#define _WIN32_WINNT 0x0600
#define DIRECTINPUT_VERSION 0x0800

#include <windows.h>
#include <dinput.h>
#include <shellapi.h>
#include <stdarg.h>
#include <stdio.h>
#include <wchar.h>
#include <wctype.h>

#define MAX_COMMAND_CHARS 32768

static const wchar_t *LOG_PATH = L"C:\\windows\\bifrost\\launch.log";
static const wchar_t *READY_PATH = L"C:\\windows\\bifrost\\gamepad-ready";
static HANDLE log_file = INVALID_HANDLE_VALUE;
static ULONGLONG started_at;

static void log_event(const wchar_t *format, ...)
{
    if (log_file == INVALID_HANDLE_VALUE) return;

    wchar_t event[ MAX_COMMAND_CHARS ];
    wchar_t line[ MAX_COMMAND_CHARS ];
    va_list args;
    va_start(args, format);
    vswprintf(event, sizeof(event) / sizeof(event[0]), format, args);
    va_end(args);
    event[MAX_COMMAND_CHARS - 1] = L'\0';

    swprintf(line, sizeof(line) / sizeof(line[0]), L"%ls t=%llu\r\n",
        event, (unsigned long long)(GetTickCount64() - started_at));
    line[MAX_COMMAND_CHARS - 1] = L'\0';

    int length = WideCharToMultiByte(CP_UTF8, 0, line, -1, NULL, 0, NULL, NULL);
    if (length <= 1) return;
    char *utf8 = (char *)HeapAlloc(GetProcessHeap(), 0, (SIZE_T)length);
    if (!utf8) return;
    if (WideCharToMultiByte(CP_UTF8, 0, line, -1, utf8, length, NULL, NULL) > 0) {
        DWORD written;
        WriteFile(log_file, utf8, (DWORD)(length - 1), &written, NULL);
    }
    HeapFree(GetProcessHeap(), 0, utf8);
}

static const wchar_t *skip_first_argument(const wchar_t *command_line)
{
    const wchar_t *cursor = command_line;
    while (iswspace(*cursor)) cursor++;
    if (*cursor == L'"') {
        cursor++;
        while (*cursor && *cursor != L'"') cursor++;
        if (*cursor == L'"') cursor++;
    }
    else {
        while (*cursor && !iswspace(*cursor)) cursor++;
    }
    while (iswspace(*cursor)) cursor++;
    return cursor;
}

static BOOL get_first_token(const wchar_t *command, wchar_t *token, size_t token_capacity,
    const wchar_t **arguments)
{
    const wchar_t *cursor = command;
    while (iswspace(*cursor)) cursor++;

    const wchar_t *start;
    size_t length;
    if (*cursor == L'"') {
        start = ++cursor;
        while (*cursor && *cursor != L'"') cursor++;
        length = (size_t)(cursor - start);
        if (*cursor == L'"') cursor++;
    }
    else {
        start = cursor;
        while (*cursor && !iswspace(*cursor)) cursor++;
        length = (size_t)(cursor - start);
    }

    if (length == 0 || length >= token_capacity) return FALSE;
    wmemcpy(token, start, length);
    token[length] = L'\0';
    while (iswspace(*cursor)) cursor++;
    *arguments = cursor;
    return TRUE;
}

static BOOL read_ready_flag(wchar_t *value)
{
    HANDLE file = CreateFileW(READY_PATH, GENERIC_READ,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, NULL, OPEN_EXISTING,
        FILE_ATTRIBUTE_NORMAL, NULL);
    if (file == INVALID_HANDLE_VALUE) return FALSE;

    BYTE byte = 0;
    DWORD bytes_read = 0;
    BOOL read_ok = ReadFile(file, &byte, 1, &bytes_read, NULL);
    CloseHandle(file);
    if (!read_ok || bytes_read != 1 || (byte != '0' && byte != '1')) return FALSE;
    *value = (wchar_t)byte;
    return TRUE;
}

static BOOL CALLBACK count_gamepad(const DIDEVICEINSTANCEW *device, void *context)
{
    (void)device;
    (*(DWORD *)context)++;
    return DIENUM_CONTINUE;
}

static DWORD wait_for_gamepad_device(void)
{
    LPDIRECTINPUT8W input = NULL;
    DWORD device_count = 0;
    ULONGLONG deadline = GetTickCount64() + 3000;
    if (SUCCEEDED(DirectInput8Create(GetModuleHandleW(NULL), DIRECTINPUT_VERSION,
        &IID_IDirectInput8W, (void **)&input, NULL))) {
        do {
            device_count = 0;
            input->lpVtbl->EnumDevices(input, DI8DEVCLASS_GAMECTRL, count_gamepad,
                &device_count, DIEDFL_ATTACHEDONLY);
            if (device_count > 0 || GetTickCount64() >= deadline) break;
            Sleep(100);
        } while (TRUE);
        input->lpVtbl->Release(input);
    }
    else {
        while (GetTickCount64() < deadline) Sleep(100);
    }
    return device_count;
}

static BOOL resolve_application_path(const wchar_t *token, wchar_t *path, size_t path_capacity)
{
    BOOL is_absolute = (token[0] == L'\\' || token[0] == L'/'
        || (token[0] && token[1] == L':'));

    if (is_absolute) {
        if (wcslen(token) >= path_capacity) return FALSE;
        wcscpy(path, token);
    }
    else {
        wchar_t working_directory[MAX_COMMAND_CHARS];
        DWORD length = GetCurrentDirectoryW(MAX_COMMAND_CHARS, working_directory);
        if (length == 0 || length >= MAX_COMMAND_CHARS) return FALSE;
        int written = swprintf(path, path_capacity, L"%ls\\%ls", working_directory, token);
        if (written < 0 || (size_t)written >= path_capacity) return FALSE;
    }

    DWORD attributes = GetFileAttributesW(path);
    return attributes != INVALID_FILE_ATTRIBUTES && !(attributes & FILE_ATTRIBUTE_DIRECTORY);
}

static DWORD wait_for_process(HANDLE process)
{
    DWORD exit_code = 1;
    if (process) {
        WaitForSingleObject(process, INFINITE);
        GetExitCodeProcess(process, &exit_code);
        CloseHandle(process);
    }
    return exit_code;
}

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE previous, PWSTR command_line, int show_command)
{
    (void)instance;
    (void)previous;
    (void)command_line;
    (void)show_command;

    started_at = GetTickCount64();
    CreateDirectoryW(L"C:\\windows\\bifrost", NULL);
    log_file = CreateFileW(LOG_PATH, FILE_APPEND_DATA,
        FILE_SHARE_READ | FILE_SHARE_WRITE, NULL, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);

    const wchar_t *command = skip_first_argument(GetCommandLineW());
    log_event(L"START cmd=%ls", command);

    wchar_t ready_value = 0;
    ULONGLONG ready_deadline = started_at + 8000;
    BOOL ready_received = FALSE;
    while (GetTickCount64() < ready_deadline) {
        if (read_ready_flag(&ready_value)) {
            ready_received = TRUE;
            break;
        }
        Sleep(50);
    }
    if (ready_received) log_event(L"READY value=%lc", ready_value);
    else log_event(L"READY timeout");

    DWORD device_count = ready_received && ready_value == L'1'
        ? wait_for_gamepad_device() : 0;
    log_event(L"DINPUT devices=%lu", (unsigned long)device_count);

    wchar_t token[MAX_COMMAND_CHARS];
    const wchar_t *arguments = NULL;
    DWORD exit_code = 1;
    if (!get_first_token(command, token, MAX_COMMAND_CHARS, &arguments)) {
        log_event(L"EXEC fail err=%lu", (unsigned long)ERROR_INVALID_PARAMETER);
        log_event(L"EXIT code=1");
        if (log_file != INVALID_HANDLE_VALUE) CloseHandle(log_file);
        return 1;
    }

    wchar_t application_path[MAX_COMMAND_CHARS * 2];
    LPCWSTR application_name = resolve_application_path(token, application_path,
        sizeof(application_path) / sizeof(application_path[0])) ? application_path : NULL;

    wchar_t mutable_command[MAX_COMMAND_CHARS];
    wcsncpy(mutable_command, command, MAX_COMMAND_CHARS - 1);
    mutable_command[MAX_COMMAND_CHARS - 1] = L'\0';

    STARTUPINFOW startup_info;
    PROCESS_INFORMATION process_info;
    ZeroMemory(&startup_info, sizeof(startup_info));
    ZeroMemory(&process_info, sizeof(process_info));
    startup_info.cb = sizeof(startup_info);

    if (CreateProcessW(application_name, mutable_command, NULL, NULL, FALSE, 0,
        NULL, NULL, &startup_info, &process_info)) {
        log_event(L"EXEC ok pid=%lu", (unsigned long)process_info.dwProcessId);
        CloseHandle(process_info.hThread);
        exit_code = wait_for_process(process_info.hProcess);
    }
    else {
        DWORD create_error = GetLastError();
        log_event(L"EXEC fail err=%lu", (unsigned long)create_error);

        SHELLEXECUTEINFOW execute_info;
        ZeroMemory(&execute_info, sizeof(execute_info));
        execute_info.cbSize = sizeof(execute_info);
        execute_info.fMask = SEE_MASK_NOCLOSEPROCESS;
        execute_info.lpVerb = L"open";
        execute_info.lpFile = token;
        execute_info.lpParameters = arguments;
        execute_info.nShow = SW_SHOWNORMAL;

        if (ShellExecuteExW(&execute_info)) {
            DWORD process_id = execute_info.hProcess ? GetProcessId(execute_info.hProcess) : 0;
            log_event(L"EXEC ok pid=%lu", (unsigned long)process_id);
            exit_code = wait_for_process(execute_info.hProcess);
        }
        else {
            DWORD shell_error = GetLastError();
            log_event(L"EXEC fail err=%lu", (unsigned long)shell_error);
            exit_code = 1;
        }
    }

    log_event(L"EXIT code=%lu", (unsigned long)exit_code);
    if (log_file != INVALID_HANDLE_VALUE) CloseHandle(log_file);
    return (int)exit_code;
}
