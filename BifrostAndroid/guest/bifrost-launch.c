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

typedef struct {
    wchar_t value;
    BOOL has_product_ids;
    WORD vendor_id;
    WORD product_id;
} READY_FLAG;

typedef struct {
    DWORD count;
    BOOL match_product_id;
    WORD vendor_id;
    WORD product_id;
} GAMEPAD_ENUM_CONTEXT;

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

static BOOL is_flag_whitespace(BYTE value)
{
    return value == ' ' || value == '\t' || value == '\r' || value == '\n';
}

static int hex_digit(BYTE value)
{
    if (value >= '0' && value <= '9') return value - '0';
    if (value >= 'a' && value <= 'f') return value - 'a' + 10;
    if (value >= 'A' && value <= 'F') return value - 'A' + 10;
    return -1;
}

static BOOL parse_hex_word(const BYTE *contents, DWORD length, DWORD *position, WORD *value)
{
    if (*position > length || length - *position < 4) return FALSE;
    WORD parsed = 0;
    for (DWORD i = 0; i < 4; i++) {
        int digit = hex_digit(contents[*position + i]);
        if (digit < 0) return FALSE;
        parsed = (WORD)((parsed << 4) | digit);
    }
    *position += 4;
    *value = parsed;
    return TRUE;
}

static BOOL parse_ready_product_ids(const BYTE *contents, DWORD length, WORD *vendor_id,
    WORD *product_id)
{
    DWORD position = 1;
    if (position >= length || !is_flag_whitespace(contents[position])) return FALSE;
    while (position < length && is_flag_whitespace(contents[position])) position++;
    if (!parse_hex_word(contents, length, &position, vendor_id)) return FALSE;
    if (position >= length || !is_flag_whitespace(contents[position])) return FALSE;
    while (position < length && is_flag_whitespace(contents[position])) position++;
    if (!parse_hex_word(contents, length, &position, product_id)) return FALSE;
    while (position < length && is_flag_whitespace(contents[position])) position++;
    return position == length;
}

static BOOL read_ready_flag(READY_FLAG *flag)
{
    HANDLE file = CreateFileW(READY_PATH, GENERIC_READ,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, NULL, OPEN_EXISTING,
        FILE_ATTRIBUTE_NORMAL, NULL);
    if (file == INVALID_HANDLE_VALUE) return FALSE;

    BYTE contents[32];
    DWORD bytes_read = 0;
    BOOL read_ok = ReadFile(file, contents, sizeof(contents), &bytes_read, NULL);
    CloseHandle(file);
    if (!read_ok || bytes_read == 0 || (contents[0] != '0' && contents[0] != '1')) return FALSE;

    ZeroMemory(flag, sizeof(*flag));
    flag->value = (wchar_t)contents[0];
    if (flag->value == L'1') {
        flag->has_product_ids = parse_ready_product_ids(contents, bytes_read,
            &flag->vendor_id, &flag->product_id);
    }
    return TRUE;
}

static BOOL CALLBACK count_gamepad(const DIDEVICEINSTANCEW *device, void *context)
{
    GAMEPAD_ENUM_CONTEXT *gamepads = (GAMEPAD_ENUM_CONTEXT *)context;
    if (!gamepads->match_product_id ||
        device->guidProduct.Data1 == (DWORD)MAKELONG(gamepads->vendor_id, gamepads->product_id)) {
        gamepads->count++;
    }
    return DIENUM_CONTINUE;
}

static DWORD wait_for_gamepad_device(const READY_FLAG *flag)
{
    LPDIRECTINPUT8W input = NULL;
    GAMEPAD_ENUM_CONTEXT gamepads;
    ZeroMemory(&gamepads, sizeof(gamepads));
    gamepads.match_product_id = flag->has_product_ids;
    gamepads.vendor_id = flag->vendor_id;
    gamepads.product_id = flag->product_id;

    ULONGLONG deadline = GetTickCount64() + 3000;
    if (SUCCEEDED(DirectInput8Create(GetModuleHandleW(NULL), DIRECTINPUT_VERSION,
        &IID_IDirectInput8W, (void **)&input, NULL))) {
        do {
            input->lpVtbl->EnumDevices(input, DI8DEVCLASS_GAMECTRL, count_gamepad,
                &gamepads, DIEDFL_ATTACHEDONLY);
            if (gamepads.count > 0 || GetTickCount64() >= deadline) break;
            gamepads.count = 0;
            Sleep(100);
        } while (TRUE);
        input->lpVtbl->Release(input);
    }
    else {
        while (GetTickCount64() < deadline) Sleep(100);
    }
    return gamepads.count;
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

    READY_FLAG ready_flag;
    ZeroMemory(&ready_flag, sizeof(ready_flag));
    ULONGLONG ready_deadline = started_at + 8000;
    BOOL ready_received = FALSE;
    while (GetTickCount64() < ready_deadline) {
        if (read_ready_flag(&ready_flag)) {
            ready_received = TRUE;
            break;
        }
        Sleep(50);
    }
    if (ready_received && ready_flag.value == L'1' && ready_flag.has_product_ids) {
        log_event(L"READY value=1 vid=%04x pid=%04x",
            (unsigned int)ready_flag.vendor_id, (unsigned int)ready_flag.product_id);
    }
    else if (ready_received) log_event(L"READY value=%lc", ready_flag.value);
    else log_event(L"READY timeout");

    DWORD device_count = ready_received && ready_flag.value == L'1'
        ? wait_for_gamepad_device(&ready_flag) : 0;
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
