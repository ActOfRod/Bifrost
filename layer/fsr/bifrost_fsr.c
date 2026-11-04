#define _GNU_SOURCE
#define _CRT_SECURE_NO_WARNINGS
#include <vulkan/vulkan.h>
#include <vulkan/vk_layer.h>
#include <stdint.h>
#include <stdio.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#if !defined(_WIN32_WINNT) || _WIN32_WINNT < 0x0600
#undef _WIN32_WINNT
#define _WIN32_WINNT 0x0600
#endif
#include <windows.h>
#include <psapi.h>
#else
#include <dlfcn.h>
#include <pthread.h>
#include <unistd.h>
#endif

#define A_CPU 1
#define A_GCC 1
#if defined(__GNUC__)
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wignored-qualifiers"
#pragma GCC diagnostic ignored "-Wunused-function"
#endif
#include "ffx_a.h"
#include "ffx_fsr1.h"
#if defined(__GNUC__)
#pragma GCC diagnostic pop
#endif
#include "easu.frag.h"
#include "rcas.frag.h"
#include "fullscreen.vert.h"

#ifdef _WIN32
static int fake_width, fake_height;
static char fake_device[128];
#endif

typedef struct Config {
    float scale;
    float sharpness;
    int log_enabled;
    int fake_mode_switch;
    char directory[1024];
} Config;
static Config config = {1.0f, 0.2f, 0, 1, "."};

static void log_message(const char *fmt, ...) {
    if (!config.log_enabled) return;
    char path[1200];
    snprintf(path, sizeof(path), "%s/bifrost_fsr.log", config.directory);
    FILE *f = fopen(path, "a");
    if (!f) return;
    va_list ap;
    va_start(ap, fmt);
    vfprintf(f, fmt, ap);
    va_end(ap);
    fputc('\n', f);
    fclose(f);
}

static void module_directory(char *out, size_t size) {
#ifdef _WIN32
    HMODULE module = NULL;
    wchar_t wide[MAX_PATH];
    char utf8[MAX_PATH * 3];
    if (GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                           (LPCWSTR)(uintptr_t)&vkNegotiateLoaderLayerInterfaceVersion, &module) &&
        GetModuleFileNameW(module, wide, MAX_PATH)) {
        WideCharToMultiByte(CP_UTF8, 0, wide, -1, utf8, sizeof(utf8), NULL, NULL);
        char *slash = strrchr(utf8, '\\');
        if (!slash) slash = strrchr(utf8, '/');
        if (slash) *slash = 0;
        snprintf(out, size, "%s", utf8);
        return;
    }
#else
    Dl_info info;
    if (dladdr((void *)&vkNegotiateLoaderLayerInterfaceVersion, &info) && info.dli_fname) {
        snprintf(out, size, "%s", info.dli_fname);
        char *slash = strrchr(out, '/');
        if (slash) *slash = 0;
        return;
    }
#endif
    snprintf(out, size, ".");
}

static void load_config(void) {
    char layer_dir[1024], path[1200];
    module_directory(layer_dir, sizeof(layer_dir));
    const char *override = getenv("BIFROST_FSR_CONFIG_DIR");
    snprintf(config.directory, sizeof(config.directory), "%s", override && *override ? override : layer_dir);
    snprintf(path, sizeof(path), "%s/bifrost_fsr.ini", config.directory);
    FILE *f = fopen(path, "r");
    if (!f) return;
    char line[512], section[64] = "";
    while (fgets(line, sizeof(line), f)) {
        char *p = line;
        while (*p == ' ' || *p == '\t') ++p;
        if (*p == '[') {
            char *end = strchr(p, ']');
            if (end) { *end = 0; snprintf(section, sizeof(section), "%s", p + 1); }
            continue;
        }
        if (strcmp(section, "fsr") != 0 || *p == '#' || *p == ';' || !*p) continue;
        char key[128], value[256];
        if (sscanf(p, " %127[^=]=%255[^\r\n]", key, value) != 2) continue;
        char *v = value;
        while (*v == ' ' || *v == '\t') ++v;
        char *end = v + strlen(v);
        while (end > v && (end[-1] == ' ' || end[-1] == '\t')) *--end = 0;
        if (strcmp(key, "sharpness") == 0) {
            char *tail; float n = strtof(v, &tail);
            if (tail != v && isfinite(n) && n >= 0.0f) config.sharpness = n;
        } else if (strcmp(key, "scale") == 0) {
            if (strcmp(v, "auto") == 0) config.scale = 1.0f;
            else {
                char *tail; float n = strtof(v, &tail);
                if (tail != v && *tail == 0 && isfinite(n) && n >= 1.0f) config.scale = n;
            }
        } else if (strcmp(key, "log") == 0) config.log_enabled = strcmp(v, "true") == 0 || strcmp(v, "1") == 0;
        else if (strcmp(key, "fake_mode_switch") == 0)
            config.fake_mode_switch = strcmp(v, "false") != 0 && strcmp(v, "0") != 0;
    }
    fclose(f);
}

#ifdef _WIN32
typedef LONG (WINAPI *ChangeWFn)(LPCWSTR, DEVMODEW *, HWND, DWORD, LPVOID);
typedef LONG (WINAPI *ChangeAFn)(LPCSTR, DEVMODEA *, HWND, DWORD, LPVOID);
typedef BOOL (WINAPI *EnumWFn)(LPCWSTR, DWORD, DEVMODEW *);
static ChangeWFn real_change_w;
static ChangeAFn real_change_a;
static EnumWFn real_enum_w;
static LONG WINAPI fake_change_w(LPCWSTR device, DEVMODEW *mode, HWND hwnd, DWORD flags, LPVOID param) {
    (void)hwnd; (void)flags; (void)param;
    if (!mode) { fake_width = fake_height = 0; fake_device[0] = 0; return DISP_CHANGE_SUCCESSFUL; }
    if ((mode->dmFields & (DM_PELSWIDTH | DM_PELSHEIGHT)) == (DM_PELSWIDTH | DM_PELSHEIGHT)) {
        fake_width = (int)mode->dmPelsWidth; fake_height = (int)mode->dmPelsHeight;
        WideCharToMultiByte(CP_UTF8, 0, device ? device : L"", -1, fake_device, sizeof(fake_device), NULL, NULL);
        log_message("fake display mode: %dx%d", fake_width, fake_height);
        return DISP_CHANGE_SUCCESSFUL;
    }
    return real_change_w ? real_change_w(device, mode, hwnd, flags, param) : DISP_CHANGE_FAILED;
}
static LONG WINAPI fake_change_a(LPCSTR device, DEVMODEA *mode, HWND hwnd, DWORD flags, LPVOID param) {
    (void)hwnd; (void)flags; (void)param;
    if (!mode) { fake_width = fake_height = 0; fake_device[0] = 0; return DISP_CHANGE_SUCCESSFUL; }
    if ((mode->dmFields & (DM_PELSWIDTH | DM_PELSHEIGHT)) == (DM_PELSWIDTH | DM_PELSHEIGHT)) {
        fake_width = (int)mode->dmPelsWidth; fake_height = (int)mode->dmPelsHeight;
        snprintf(fake_device, sizeof(fake_device), "%s", device ? device : "");
        return DISP_CHANGE_SUCCESSFUL;
    }
    return real_change_a ? real_change_a(device, mode, hwnd, flags, param) : DISP_CHANGE_FAILED;
}
static BOOL WINAPI fake_enum_w(LPCWSTR device, DWORD mode, DEVMODEW *out) {
    if (!real_enum_w || !real_enum_w(device, mode, out)) return FALSE;
    if (fake_width > 0 && mode == ENUM_CURRENT_SETTINGS) {
        out->dmPelsWidth = (DWORD)fake_width; out->dmPelsHeight = (DWORD)fake_height;
        out->dmFields |= DM_PELSWIDTH | DM_PELSHEIGHT;
    }
    return TRUE;
}
static void patch_iat(HMODULE module, const char *name, void *replacement, void **original) {
    /* Patch only the named DXVK module's USER32 import, leaving other process modules untouched. */
    BYTE *base = (BYTE *)module;
    IMAGE_DOS_HEADER *dos = (IMAGE_DOS_HEADER *)base;
    if (!dos || dos->e_magic != IMAGE_DOS_SIGNATURE) return;
    IMAGE_NT_HEADERS *nt = (IMAGE_NT_HEADERS *)(base + dos->e_lfanew);
    DWORD rva = nt->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_IMPORT].VirtualAddress;
    if (!rva) return;
    IMAGE_IMPORT_DESCRIPTOR *desc = (IMAGE_IMPORT_DESCRIPTOR *)(base + rva);
    for (; desc->Name; ++desc) {
        const char *dll = (const char *)(base + desc->Name);
        if (_stricmp(dll, "USER32.dll") != 0 && _stricmp(dll, "USER32") != 0) continue;
        IMAGE_THUNK_DATA *names = (IMAGE_THUNK_DATA *)(base + (desc->OriginalFirstThunk ? desc->OriginalFirstThunk : desc->FirstThunk));
        IMAGE_THUNK_DATA *iat = (IMAGE_THUNK_DATA *)(base + desc->FirstThunk);
        for (; names->u1.AddressOfData; ++names, ++iat) {
            if (IMAGE_SNAP_BY_ORDINAL(names->u1.Ordinal)) continue;
            IMAGE_IMPORT_BY_NAME *entry = (IMAGE_IMPORT_BY_NAME *)(base + names->u1.AddressOfData);
            if (strcmp((const char *)entry->Name, name) != 0) continue;
            DWORD old_protect;
            if (VirtualProtect(&iat->u1.Function, sizeof(void *), PAGE_READWRITE, &old_protect)) {
                if ((void *)(uintptr_t)iat->u1.Function != replacement) {
                    if (original && !*original) *original = (void *)(uintptr_t)iat->u1.Function;
                    iat->u1.Function = (ULONG_PTR)replacement;
                }
                VirtualProtect(&iat->u1.Function, sizeof(void *), old_protect, &old_protect);
            }
        }
    }
}
static void install_fake_mode_hooks(void) {
    if (!config.fake_mode_switch) return;
    HMODULE user32 = GetModuleHandleW(L"user32.dll");
    if (!user32) return;
    /* These are USER32 exports; patching DXVK module IAT slots does not alter them. */
    FARPROC proc = GetProcAddress(user32, "ChangeDisplaySettingsExW");
    if(proc&&!real_change_w)memcpy(&real_change_w, &proc, sizeof(real_change_w));
    proc = GetProcAddress(user32, "ChangeDisplaySettingsExA");
    if(proc&&!real_change_a)memcpy(&real_change_a, &proc, sizeof(real_change_a));
    proc = GetProcAddress(user32, "EnumDisplaySettingsW");
    if(proc&&!real_enum_w)memcpy(&real_enum_w, &proc, sizeof(real_enum_w));
    HMODULE modules[1024]; DWORD needed = 0;
    if (!EnumProcessModules(GetCurrentProcess(), modules, sizeof(modules), &needed)) return;
    DWORD count = needed / sizeof(HMODULE); if (count > 1024) count = 1024;
    for (DWORD i = 0; i < count; ++i) {
        wchar_t name[MAX_PATH]; if (!GetModuleBaseNameW(GetCurrentProcess(), modules[i], name, MAX_PATH)) continue;
        if (_wcsicmp(name,L"d3d8.dll") && _wcsicmp(name,L"d3d9.dll") && _wcsicmp(name,L"d3d10core.dll") &&
            _wcsicmp(name,L"d3d11.dll") && _wcsicmp(name,L"dxgi.dll")) continue;
        patch_iat(modules[i], "ChangeDisplaySettingsExW", (void *)fake_change_w, (void **)&real_change_w);
        patch_iat(modules[i], "ChangeDisplaySettingsExA", (void *)fake_change_a, (void **)&real_change_a);
        patch_iat(modules[i], "EnumDisplaySettingsW", (void *)fake_enum_w, (void **)&real_enum_w);
    }
}
#endif

typedef struct InstanceState InstanceState;
typedef struct DeviceState DeviceState;
typedef struct Swapchain Swapchain;
typedef struct QueueEntry { VkQueue queue; uint32_t family; struct QueueEntry *next; } QueueEntry;
struct InstanceState {
    VkInstance handle; void *key;
    PFN_vkGetInstanceProcAddr next_gipa;
    PFN_vkGetDeviceProcAddr next_gdpa;
    PFN_vkDestroyInstance DestroyInstance;
    PFN_vkCreateDevice CreateDevice;
    PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR GetCaps;
    PFN_vkGetPhysicalDeviceSurfaceCapabilities2KHR GetCaps2;
    PFN_vkGetPhysicalDeviceSurfaceSupportKHR GetSurfaceSupport;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties GetQueueFamilies;
    PFN_vkGetPhysicalDeviceMemoryProperties GetMemoryProperties;
    InstanceState *next;
};
struct DeviceState {
    VkDevice handle; void *key; VkPhysicalDevice physical;
    PFN_vkGetDeviceProcAddr next_gdpa;
    PFN_vkSetDeviceLoaderData set_loader_data;
    PFN_vkDestroyDevice DestroyDevice;
    PFN_vkGetDeviceQueue GetDeviceQueue;
    PFN_vkGetDeviceQueue2 GetDeviceQueue2;
    PFN_vkQueuePresentKHR QueuePresent;
    PFN_vkQueueSubmit QueueSubmit;
    PFN_vkDeviceWaitIdle DeviceWaitIdle;
    PFN_vkQueueWaitIdle QueueWaitIdle;
    PFN_vkCreateSwapchainKHR CreateSwapchain;
    PFN_vkDestroySwapchainKHR DestroySwapchain;
    PFN_vkGetSwapchainImagesKHR GetSwapchainImages;
    PFN_vkAcquireNextImageKHR Acquire;
    PFN_vkAcquireNextImage2KHR Acquire2;
    PFN_vkCreateImage CreateImage; PFN_vkDestroyImage DestroyImage;
    PFN_vkGetImageMemoryRequirements GetImageMemoryRequirements;
    PFN_vkAllocateMemory AllocateMemory; PFN_vkFreeMemory FreeMemory; PFN_vkBindImageMemory BindImageMemory;
    PFN_vkCreateImageView CreateImageView; PFN_vkDestroyImageView DestroyImageView;
    PFN_vkCreateSampler CreateSampler; PFN_vkDestroySampler DestroySampler;
    PFN_vkCreateRenderPass CreateRenderPass; PFN_vkDestroyRenderPass DestroyRenderPass;
    PFN_vkCreateFramebuffer CreateFramebuffer; PFN_vkDestroyFramebuffer DestroyFramebuffer;
    PFN_vkCreateShaderModule CreateShaderModule; PFN_vkDestroyShaderModule DestroyShaderModule;
    PFN_vkCreateDescriptorSetLayout CreateDescriptorSetLayout; PFN_vkDestroyDescriptorSetLayout DestroyDescriptorSetLayout;
    PFN_vkCreatePipelineLayout CreatePipelineLayout; PFN_vkDestroyPipelineLayout DestroyPipelineLayout;
    PFN_vkCreateGraphicsPipelines CreateGraphicsPipelines; PFN_vkDestroyPipeline DestroyPipeline;
    PFN_vkCreateDescriptorPool CreateDescriptorPool; PFN_vkDestroyDescriptorPool DestroyDescriptorPool;
    PFN_vkAllocateDescriptorSets AllocateDescriptorSets; PFN_vkUpdateDescriptorSets UpdateDescriptorSets;
    PFN_vkCreateCommandPool CreateCommandPool; PFN_vkDestroyCommandPool DestroyCommandPool;
    PFN_vkAllocateCommandBuffers AllocateCommandBuffers; PFN_vkFreeCommandBuffers FreeCommandBuffers;
    PFN_vkBeginCommandBuffer BeginCommandBuffer; PFN_vkEndCommandBuffer EndCommandBuffer;
    PFN_vkCmdPipelineBarrier CmdPipelineBarrier; PFN_vkCmdBeginRenderPass CmdBeginRenderPass;
    PFN_vkCmdEndRenderPass CmdEndRenderPass; PFN_vkCmdBindPipeline CmdBindPipeline;
    PFN_vkCmdBindDescriptorSets CmdBindDescriptorSets; PFN_vkCmdPushConstants CmdPushConstants;
    PFN_vkCmdSetViewport CmdSetViewport; PFN_vkCmdSetScissor CmdSetScissor; PFN_vkCmdDraw CmdDraw;
    PFN_vkCreateFence CreateFence; PFN_vkDestroyFence DestroyFence; PFN_vkWaitForFences WaitForFences;
    PFN_vkResetFences ResetFences; PFN_vkCreateSemaphore CreateSemaphore; PFN_vkDestroySemaphore DestroySemaphore;
    PFN_vkReleaseSwapchainImagesEXT ReleaseImages;
    QueueEntry *queues;
    VkPhysicalDeviceMemoryProperties memory;
    VkQueueFamilyProperties *families; uint32_t family_count;
    DeviceState *next;
};
typedef struct Frame {
    VkImage app, intermediate, real;
    VkDeviceMemory app_memory, intermediate_memory;
    VkImageView app_view, intermediate_view, real_view;
    VkFramebuffer easu_fb, rcas_fb;
    VkDescriptorSet easu_set, rcas_set;
    VkCommandBuffer command;
    VkFence fence;
    VkSemaphore done;
    int real_initialized;
} Frame;
struct Swapchain {
    VkSwapchainKHR app, real; DeviceState *device; VkFormat format;
    VkExtent2D app_extent, output_extent; VkImageUsageFlags app_usage; uint32_t count;
    Frame *frames; int scaled; uint32_t pool_family;
    VkCommandPool pool; VkSampler sampler; VkRenderPass easu_pass, rcas_pass;
    VkDescriptorSetLayout set_layout; VkPipelineLayout pipeline_layout;
    VkPipeline easu_pipeline, rcas_pipeline; VkDescriptorPool descriptor_pool;
    Swapchain *next;
};
static InstanceState *instances;
static DeviceState *devices;
static Swapchain *swapchains;
static PFN_vkGetInstanceProcAddr global_next_gipa;
static int config_loaded;

#ifdef _WIN32
static SRWLOCK state_mutex = SRWLOCK_INIT;
#else
static pthread_mutex_t state_mutex = PTHREAD_MUTEX_INITIALIZER;
#endif
static void state_lock(void) {
#ifdef _WIN32
    AcquireSRWLockExclusive(&state_mutex);
#else
    pthread_mutex_lock(&state_mutex);
#endif
}
static void state_unlock(void) {
#ifdef _WIN32
    ReleaseSRWLockExclusive(&state_mutex);
#else
    pthread_mutex_unlock(&state_mutex);
#endif
}

/* The caller holds state_lock; returned entries remain valid only until it unlocks. */
static void *dispatch_key(void *object) { return object ? *(void **)object : NULL; }
static InstanceState *find_instance(VkInstance i) {
    void *key = dispatch_key(i);
    for (InstanceState *s = instances; s; s = s->next) if (s->key == key) return s;
    return NULL;
}
static DeviceState *find_device(void *object) {
    void *key = dispatch_key(object);
    for (DeviceState *s = devices; s; s = s->next) if (s->key == key) return s;
    return NULL;
}
static Swapchain *find_swapchain(VkSwapchainKHR h) {
    for (Swapchain *s = swapchains; s; s = s->next) if (s->app == h) return s;
    return NULL;
}

static VkExtent2D render_extent(VkExtent2D window) {
#ifdef _WIN32
    if (fake_width > 0 && fake_height > 0) return (VkExtent2D){(uint32_t)fake_width,(uint32_t)fake_height};
#endif
    if (config.scale > 1.0f) {
        uint32_t w = (uint32_t)fmaxf(1.0f, floorf(window.width / config.scale + 0.5f));
        uint32_t h = (uint32_t)fmaxf(1.0f, floorf(window.height / config.scale + 0.5f));
        return (VkExtent2D){w,h};
    }
    return window;
}
static int scaling_enabled(void) {
#ifdef _WIN32
    if (fake_width > 0 && fake_height > 0) return 1;
#endif
    return config.scale > 1.0f;
}
static void update_caps(VkSurfaceCapabilitiesKHR *caps) {
    if (!scaling_enabled() || caps->currentExtent.width == UINT32_MAX) return;
    VkExtent2D r = render_extent(caps->currentExtent);
    caps->currentExtent = r;
    caps->minImageExtent.width = caps->minImageExtent.width < r.width ? caps->minImageExtent.width : r.width;
    caps->minImageExtent.height = caps->minImageExtent.height < r.height ? caps->minImageExtent.height : r.height;
    caps->maxImageExtent.width = caps->maxImageExtent.width > r.width ? caps->maxImageExtent.width : r.width;
    caps->maxImageExtent.height = caps->maxImageExtent.height > r.height ? caps->maxImageExtent.height : r.height;
}

static VkResult VKAPI_CALL layerGetCaps(VkPhysicalDevice p, VkSurfaceKHR s, VkSurfaceCapabilitiesKHR *c) {
    state_lock();
    InstanceState *i = find_instance((VkInstance)p);
    PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR get_caps=i?i->GetCaps:NULL;
    state_unlock();
    if (!get_caps) return VK_ERROR_INITIALIZATION_FAILED;
    VkResult r = get_caps(p,s,c); if (r == VK_SUCCESS) update_caps(c); return r;
}
static VkResult VKAPI_CALL layerGetCaps2(VkPhysicalDevice p, const VkPhysicalDeviceSurfaceInfo2KHR *info, VkSurfaceCapabilities2KHR *c) {
    state_lock();
    InstanceState *i = find_instance((VkInstance)p);
    PFN_vkGetPhysicalDeviceSurfaceCapabilities2KHR get_caps=i?i->GetCaps2:NULL;
    state_unlock();
    if (!get_caps) return VK_ERROR_INITIALIZATION_FAILED;
    VkResult r = get_caps(p,info,c); if (r == VK_SUCCESS) update_caps(&c->surfaceCapabilities); return r;
}

static uint32_t memory_type(DeviceState *d, uint32_t bits) {
    for (uint32_t i = 0; i < d->memory.memoryTypeCount; ++i)
        if ((bits & (1u << i)) && (d->memory.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) return i;
    for (uint32_t i = 0; i < d->memory.memoryTypeCount; ++i) if (bits & (1u << i)) return i;
    return UINT32_MAX;
}

static uint32_t choose_graphics_family(DeviceState *d, VkSurfaceKHR surface,
                                       PFN_vkGetPhysicalDeviceSurfaceSupportKHR get_support) {
    if (!get_support || !d->families || !d->family_count) return UINT32_MAX;
    uint32_t *preferred=calloc(d->family_count,sizeof(*preferred));
    uint8_t *seen=calloc(d->family_count,sizeof(*seen));
    if(!preferred||!seen){free(preferred);free(seen);return UINT32_MAX;}
    uint32_t count=0;
    state_lock();
    for(QueueEntry *q=d->queues;q;q=q->next) {
        uint32_t family=q->family;
        if(family<d->family_count && !seen[family] &&
           (d->families[family].queueFlags&VK_QUEUE_GRAPHICS_BIT)) {
            preferred[count++]=family;
            seen[family]=1;
        }
    }
    state_unlock();
    for(uint32_t i=0;i<count;i++) {
        VkBool32 supported=VK_FALSE;
        if(get_support(d->physical,preferred[i],surface,&supported)==VK_SUCCESS && supported) {
            uint32_t family=preferred[i];free(preferred);free(seen);return family;
        }
    }
    for(uint32_t family=0;family<d->family_count;family++) {
        if(seen[family] || !(d->families[family].queueFlags&VK_QUEUE_GRAPHICS_BIT)) continue;
        VkBool32 supported=VK_FALSE;
        if(get_support(d->physical,family,surface,&supported)==VK_SUCCESS && supported) {
            free(preferred);free(seen);return family;
        }
    }
    free(preferred);free(seen);
    return UINT32_MAX;
}
static int make_image(DeviceState *d, VkFormat format, VkExtent2D extent, VkImageUsageFlags usage,
                      VkImage *image, VkDeviceMemory *memory, VkImageView *view) {
    VkImageCreateInfo ci = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ci.imageType = VK_IMAGE_TYPE_2D; ci.format = format; ci.extent = (VkExtent3D){extent.width,extent.height,1};
    ci.mipLevels = 1; ci.arrayLayers = 1; ci.samples = VK_SAMPLE_COUNT_1_BIT;
    ci.tiling = VK_IMAGE_TILING_OPTIMAL; ci.usage = usage; ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (d->CreateImage(d->handle,&ci,NULL,image) != VK_SUCCESS) return 0;
    VkMemoryRequirements req; d->GetImageMemoryRequirements(d->handle,*image,&req);
    uint32_t type = memory_type(d,req.memoryTypeBits);
    if (type == UINT32_MAX) return 0;
    VkMemoryAllocateInfo ai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    ai.allocationSize = req.size; ai.memoryTypeIndex = type;
    if (d->AllocateMemory(d->handle,&ai,NULL,memory) != VK_SUCCESS) return 0;
    if (d->BindImageMemory(d->handle,*image,*memory,0) != VK_SUCCESS) return 0;
    VkImageViewCreateInfo vi = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vi.image = *image; vi.viewType = VK_IMAGE_VIEW_TYPE_2D; vi.format = format;
    vi.subresourceRange = (VkImageSubresourceRange){VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
    return d->CreateImageView(d->handle,&vi,NULL,view) == VK_SUCCESS;
}
static VkRenderPass make_render_pass(DeviceState *d, VkFormat format, VkAttachmentLoadOp load,
                                    VkImageLayout initial, VkImageLayout final) {
    VkAttachmentDescription attachment = {0};
    attachment.format = format; attachment.samples = VK_SAMPLE_COUNT_1_BIT; attachment.loadOp = load;
    attachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE; attachment.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    attachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE; attachment.initialLayout = initial;
    attachment.finalLayout = final;
    VkAttachmentReference ref = {0,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription sub = {0}; sub.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    sub.colorAttachmentCount = 1; sub.pColorAttachments = &ref;
    VkSubpassDependency deps[2] = {{0}};
    deps[0].srcSubpass = VK_SUBPASS_EXTERNAL; deps[0].dstSubpass = 0;
    deps[0].srcStageMask = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    deps[0].dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    deps[0].srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    deps[0].dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    deps[1].srcSubpass = 0; deps[1].dstSubpass = VK_SUBPASS_EXTERNAL;
    deps[1].srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    deps[1].dstStageMask = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
    deps[1].srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT; deps[1].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    VkRenderPassCreateInfo ri = {VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
    ri.attachmentCount = 1; ri.pAttachments = &attachment; ri.subpassCount = 1; ri.pSubpasses = &sub;
    ri.dependencyCount = 2; ri.pDependencies = deps;
    VkRenderPass pass = VK_NULL_HANDLE;
    return d->CreateRenderPass(d->handle,&ri,NULL,&pass) == VK_SUCCESS ? pass : VK_NULL_HANDLE;
}
static VkPipeline make_pipeline(DeviceState *d, VkPipelineLayout layout, VkRenderPass pass, const uint32_t *spv, size_t bytes) {
    VkShaderModuleCreateInfo smi = {VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    smi.codeSize = bytes; smi.pCode = spv;
    VkShaderModule shader, vertex;
    if (d->CreateShaderModule(d->handle,&smi,NULL,&shader) != VK_SUCCESS) return VK_NULL_HANDLE;
    smi.codeSize = sizeof(fullscreen_vert_spv); smi.pCode = fullscreen_vert_spv;
    if (d->CreateShaderModule(d->handle,&smi,NULL,&vertex) != VK_SUCCESS) {
        d->DestroyShaderModule(d->handle,shader,NULL); return VK_NULL_HANDLE;
    }
    VkPipelineShaderStageCreateInfo stages[2] = {
        {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO},
        {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO}
    };
    stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT; stages[0].module = vertex; stages[0].pName = "main";
    stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT; stages[1].module = shader; stages[1].pName = "main";
    VkPipelineVertexInputStateCreateInfo vi = {VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    VkPipelineInputAssemblyStateCreateInfo ia = {VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
    ia.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    VkPipelineViewportStateCreateInfo vp = {VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
    vp.viewportCount = 1; vp.scissorCount = 1;
    VkDynamicState dyn[2] = {VK_DYNAMIC_STATE_VIEWPORT,VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo ds = {VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
    ds.dynamicStateCount = 2; ds.pDynamicStates = dyn;
    VkPipelineRasterizationStateCreateInfo rs = {VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
    rs.polygonMode = VK_POLYGON_MODE_FILL; rs.cullMode = VK_CULL_MODE_NONE; rs.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE; rs.lineWidth = 1.0f;
    VkPipelineMultisampleStateCreateInfo ms = {VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
    ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
    VkPipelineColorBlendAttachmentState ba = {0}; ba.colorWriteMask = 0xf;
    VkPipelineColorBlendStateCreateInfo cb = {VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
    cb.attachmentCount = 1; cb.pAttachments = &ba;
    VkGraphicsPipelineCreateInfo pi = {VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
    pi.stageCount = 2; pi.pStages = stages; pi.pVertexInputState = &vi; pi.pInputAssemblyState = &ia;
    pi.pViewportState = &vp; pi.pRasterizationState = &rs; pi.pMultisampleState = &ms;
    pi.pColorBlendState = &cb; pi.pDynamicState = &ds; pi.layout = layout; pi.renderPass = pass;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkResult r = d->CreateGraphicsPipelines(d->handle,VK_NULL_HANDLE,1,&pi,NULL,&pipeline);
    d->DestroyShaderModule(d->handle,shader,NULL);
    d->DestroyShaderModule(d->handle,vertex,NULL);
    return r == VK_SUCCESS ? pipeline : VK_NULL_HANDLE;
}
static void destroy_swapchain_resources(Swapchain *s) {
    DeviceState *d = s->device;
    if (s->pool) d->DeviceWaitIdle(d->handle);
    for (uint32_t i = 0; s->frames && i < s->count; ++i) {
        Frame *f = &s->frames[i];
        if (f->command && s->pool) d->FreeCommandBuffers(d->handle,s->pool,1,&f->command);
        if (f->fence) d->DestroyFence(d->handle,f->fence,NULL);
        if (f->done) d->DestroySemaphore(d->handle,f->done,NULL);
        if (f->easu_fb) d->DestroyFramebuffer(d->handle,f->easu_fb,NULL);
        if (f->rcas_fb) d->DestroyFramebuffer(d->handle,f->rcas_fb,NULL);
        if (f->app_view) d->DestroyImageView(d->handle,f->app_view,NULL);
        if (f->real_view) d->DestroyImageView(d->handle,f->real_view,NULL);
        if (f->intermediate_view) d->DestroyImageView(d->handle,f->intermediate_view,NULL);
        if (f->app) d->DestroyImage(d->handle,f->app,NULL);
        if (f->intermediate) d->DestroyImage(d->handle,f->intermediate,NULL);
        if (f->app_memory) d->FreeMemory(d->handle,f->app_memory,NULL);
        if (f->intermediate_memory) d->FreeMemory(d->handle,f->intermediate_memory,NULL);
    }
    free(s->frames);
    if (s->descriptor_pool) d->DestroyDescriptorPool(d->handle,s->descriptor_pool,NULL);
    if (s->easu_pipeline) d->DestroyPipeline(d->handle,s->easu_pipeline,NULL);
    if (s->rcas_pipeline) d->DestroyPipeline(d->handle,s->rcas_pipeline,NULL);
    if (s->pipeline_layout) d->DestroyPipelineLayout(d->handle,s->pipeline_layout,NULL);
    if (s->set_layout) d->DestroyDescriptorSetLayout(d->handle,s->set_layout,NULL);
    if (s->easu_pass) d->DestroyRenderPass(d->handle,s->easu_pass,NULL);
    if (s->rcas_pass) d->DestroyRenderPass(d->handle,s->rcas_pass,NULL);
    if (s->sampler) d->DestroySampler(d->handle,s->sampler,NULL);
    if (s->pool) d->DestroyCommandPool(d->handle,s->pool,NULL);
}

static int setup_swapchain(Swapchain *s,uint32_t family) {
    DeviceState *d = s->device;
    if(family>=d->family_count || !(d->families[family].queueFlags&VK_QUEUE_GRAPHICS_BIT)) return 0;
    VkImage *real_images = calloc(s->count,sizeof(*real_images));
    if (!real_images) return 0;
    uint32_t n=s->count;
    if (d->GetSwapchainImages(d->handle,s->real,&n,real_images)!=VK_SUCCESS || n!=s->count) { free(real_images); return 0; }
    s->frames=calloc(n,sizeof(*s->frames)); if (!s->frames) { free(real_images); return 0; }
    s->count=n;
    s->easu_pass=make_render_pass(d,VK_FORMAT_R16G16B16A16_SFLOAT,VK_ATTACHMENT_LOAD_OP_CLEAR,VK_IMAGE_LAYOUT_UNDEFINED,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    s->rcas_pass=make_render_pass(d,s->format,VK_ATTACHMENT_LOAD_OP_DONT_CARE,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
    if (!s->easu_pass || !s->rcas_pass) { free(real_images); return 0; }
    VkDescriptorSetLayoutBinding bind={0}; bind.binding=0; bind.descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    bind.descriptorCount=1; bind.stageFlags=VK_SHADER_STAGE_FRAGMENT_BIT;
    VkDescriptorSetLayoutCreateInfo dli={VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO}; dli.bindingCount=1; dli.pBindings=&bind;
    if (d->CreateDescriptorSetLayout(d->handle,&dli,NULL,&s->set_layout)!=VK_SUCCESS) { free(real_images); return 0; }
    VkPushConstantRange range={VK_SHADER_STAGE_FRAGMENT_BIT,0,80};
    VkPipelineLayoutCreateInfo pli={VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO}; pli.setLayoutCount=1; pli.pSetLayouts=&s->set_layout;
    pli.pushConstantRangeCount=1; pli.pPushConstantRanges=&range;
    if (d->CreatePipelineLayout(d->handle,&pli,NULL,&s->pipeline_layout)!=VK_SUCCESS) { free(real_images); return 0; }
    VkSamplerCreateInfo si={VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO}; si.magFilter=VK_FILTER_NEAREST; si.minFilter=VK_FILTER_NEAREST;
    si.mipmapMode=VK_SAMPLER_MIPMAP_MODE_NEAREST; si.addressModeU=si.addressModeV=si.addressModeW=VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    si.maxLod=0.0f;
    if (d->CreateSampler(d->handle,&si,NULL,&s->sampler)!=VK_SUCCESS) { free(real_images); return 0; }
    s->easu_pipeline=make_pipeline(d,s->pipeline_layout,s->easu_pass,easu_spv,sizeof(easu_spv));
    s->rcas_pipeline=make_pipeline(d,s->pipeline_layout,s->rcas_pass,rcas_spv,sizeof(rcas_spv));
    if (!s->easu_pipeline || !s->rcas_pipeline) { free(real_images); return 0; }
    VkDescriptorPoolSize ps={VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,n*2};
    VkDescriptorPoolCreateInfo dpi={VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO}; dpi.maxSets=n*2; dpi.poolSizeCount=1; dpi.pPoolSizes=&ps;
    if (d->CreateDescriptorPool(d->handle,&dpi,NULL,&s->descriptor_pool)!=VK_SUCCESS) { free(real_images); return 0; }
    VkDescriptorSetLayout *layouts=calloc(n*2,sizeof(*layouts)); VkDescriptorSet *sets=calloc(n*2,sizeof(*sets));
    if (!layouts||!sets) { free(layouts);free(sets);free(real_images);return 0; }
    for (uint32_t i=0;i<n*2;i++) layouts[i]=s->set_layout;
    VkDescriptorSetAllocateInfo dai={VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO}; dai.descriptorPool=s->descriptor_pool; dai.descriptorSetCount=n*2; dai.pSetLayouts=layouts;
    if (d->AllocateDescriptorSets(d->handle,&dai,sets)!=VK_SUCCESS) { free(layouts);free(sets);free(real_images);return 0; }
    s->pool_family=family;
    VkCommandPoolCreateInfo cpi={VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO}; cpi.flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT; cpi.queueFamilyIndex=family;
    if (d->CreateCommandPool(d->handle,&cpi,NULL,&s->pool)!=VK_SUCCESS) { free(layouts);free(sets);free(real_images);return 0; }
    VkCommandBuffer *commands=calloc(n,sizeof(*commands));
    if (!commands) { free(layouts);free(sets);free(real_images);return 0; }
    VkCommandBufferAllocateInfo cai={VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO}; cai.commandPool=s->pool; cai.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY; cai.commandBufferCount=n;
    if (d->AllocateCommandBuffers(d->handle,&cai,commands)!=VK_SUCCESS) { free(commands);free(layouts);free(sets);free(real_images);return 0; }
    VkImageViewCreateInfo rvi={VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO}; rvi.viewType=VK_IMAGE_VIEW_TYPE_2D; rvi.format=s->format;
    rvi.subresourceRange=(VkImageSubresourceRange){VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
    for (uint32_t i=0;i<n;i++) {
        Frame *f=&s->frames[i]; f->real=real_images[i]; f->command=commands[i];
        if (d->set_loader_data && d->set_loader_data(d->handle,(void *)f->command)!=VK_SUCCESS) { free(commands);free(layouts);free(sets);free(real_images);return 0; }
        rvi.image=f->real; if (d->CreateImageView(d->handle,&rvi,NULL,&f->real_view)!=VK_SUCCESS) { free(commands);free(layouts);free(sets);free(real_images);return 0; }
        /* We sample and render through each image's own format; Vulkan performs sRGB conversion when requested. */
        if (!make_image(d,s->format,s->app_extent,s->app_usage|VK_IMAGE_USAGE_SAMPLED_BIT,&f->app,&f->app_memory,&f->app_view)) { free(commands);free(layouts);free(sets);free(real_images);return 0; }
        if (!make_image(d,VK_FORMAT_R16G16B16A16_SFLOAT,s->output_extent,VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT|VK_IMAGE_USAGE_SAMPLED_BIT,&f->intermediate,&f->intermediate_memory,&f->intermediate_view)) { free(commands);free(layouts);free(sets);free(real_images);return 0; }
        VkFramebufferCreateInfo fbi={VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO}; fbi.width=s->output_extent.width;fbi.height=s->output_extent.height;fbi.layers=1;
        fbi.renderPass=s->easu_pass;fbi.attachmentCount=1;fbi.pAttachments=&f->intermediate_view;
        if (d->CreateFramebuffer(d->handle,&fbi,NULL,&f->easu_fb)!=VK_SUCCESS) { free(commands);free(layouts);free(sets);free(real_images);return 0; }
        fbi.renderPass=s->rcas_pass;fbi.pAttachments=&f->real_view;
        if (d->CreateFramebuffer(d->handle,&fbi,NULL,&f->rcas_fb)!=VK_SUCCESS) { free(commands);free(layouts);free(sets);free(real_images);return 0; }
        VkFenceCreateInfo fci={VK_STRUCTURE_TYPE_FENCE_CREATE_INFO}; fci.flags=VK_FENCE_CREATE_SIGNALED_BIT;
        if (d->CreateFence(d->handle,&fci,NULL,&f->fence)!=VK_SUCCESS) { free(commands);free(layouts);free(sets);free(real_images);return 0; }
        VkSemaphoreCreateInfo sci={VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        if (d->CreateSemaphore(d->handle,&sci,NULL,&f->done)!=VK_SUCCESS) { free(commands);free(layouts);free(sets);free(real_images);return 0; }
        f->easu_set=sets[i*2]; f->rcas_set=sets[i*2+1];
        VkDescriptorImageInfo ei={s->sampler,f->app_view,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
        VkDescriptorImageInfo ri={s->sampler,f->intermediate_view,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
        VkWriteDescriptorSet writes[2]={{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET},{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET}};
        writes[0].dstSet=f->easu_set; writes[0].dstBinding=0; writes[0].descriptorCount=1; writes[0].descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; writes[0].pImageInfo=&ei;
        writes[1].dstSet=f->rcas_set; writes[1].dstBinding=0; writes[1].descriptorCount=1; writes[1].descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; writes[1].pImageInfo=&ri;
        d->UpdateDescriptorSets(d->handle,2,writes,0,NULL);
    }
    free(commands);free(layouts);free(sets);free(real_images);
    return 1;
}

static VkResult VKAPI_CALL layerCreateInstance(const VkInstanceCreateInfo *, const VkAllocationCallbacks *, VkInstance *);
static void VKAPI_CALL layerDestroyInstance(VkInstance, const VkAllocationCallbacks *);
static VkResult VKAPI_CALL layerCreateDevice(VkPhysicalDevice, const VkDeviceCreateInfo *, const VkAllocationCallbacks *, VkDevice *);
static VkResult VKAPI_CALL layerCreateSwapchain(VkDevice, const VkSwapchainCreateInfoKHR *, const VkAllocationCallbacks *, VkSwapchainKHR *);
static void VKAPI_CALL layerDestroySwapchain(VkDevice, VkSwapchainKHR, const VkAllocationCallbacks *);
static VkResult VKAPI_CALL layerGetSwapchainImages(VkDevice, VkSwapchainKHR, uint32_t *, VkImage *);
static VkResult VKAPI_CALL layerAcquire(VkDevice, VkSwapchainKHR, uint64_t, VkSemaphore, VkFence, uint32_t *);
static VkResult VKAPI_CALL layerAcquire2(VkDevice, const VkAcquireNextImageInfoKHR *, uint32_t *);
static VkResult VKAPI_CALL layerPresent(VkQueue, const VkPresentInfoKHR *);
static VkResult VKAPI_CALL layerReleaseImages(VkDevice, const VkReleaseSwapchainImagesInfoEXT *);
static void VKAPI_CALL layerGetDeviceQueue(VkDevice,uint32_t,uint32_t,VkQueue *);
static void VKAPI_CALL layerGetDeviceQueue2(VkDevice,const VkDeviceQueueInfo2 *,VkQueue *);
static PFN_vkVoidFunction VKAPI_CALL layerGetInstanceProcAddr(VkInstance,const char *);
static PFN_vkVoidFunction VKAPI_CALL layerGetDeviceProcAddr(VkDevice,const char *);

#if defined(_WIN32)
#define FSR_EXPORT __declspec(dllexport)
#else
#define FSR_EXPORT __attribute__((visibility("default")))
#endif
FSR_EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkNegotiateLoaderLayerInterfaceVersion(VkNegotiateLayerInterface *p) {
    if (!p || p->sType != LAYER_NEGOTIATE_INTERFACE_STRUCT) return VK_ERROR_INITIALIZATION_FAILED;
    if (p->loaderLayerInterfaceVersion > CURRENT_LOADER_LAYER_INTERFACE_VERSION)
        p->loaderLayerInterfaceVersion = CURRENT_LOADER_LAYER_INTERFACE_VERSION;
    p->pfnGetInstanceProcAddr = layerGetInstanceProcAddr;
    p->pfnGetDeviceProcAddr = layerGetDeviceProcAddr;
    p->pfnGetPhysicalDeviceProcAddr = NULL;
    return VK_SUCCESS;
}

static VkResult VKAPI_CALL layerCreateInstance(const VkInstanceCreateInfo *ci, const VkAllocationCallbacks *a, VkInstance *out) {
    VkLayerInstanceCreateInfo *link_info=NULL;
    for (VkBaseInStructure *p=(VkBaseInStructure *)ci->pNext;p;p=(VkBaseInStructure *)p->pNext) {
        if (p->sType==VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO) {
            VkLayerInstanceCreateInfo *l=(VkLayerInstanceCreateInfo *)p;
            if (l->function==VK_LAYER_LINK_INFO) { link_info=l; break; }
        }
    }
    if (!link_info || !link_info->u.pLayerInfo) return VK_ERROR_INITIALIZATION_FAILED;
    VkLayerInstanceLink *link=link_info->u.pLayerInfo;
    PFN_vkGetInstanceProcAddr next=link->pfnNextGetInstanceProcAddr;
    state_lock();
    global_next_gipa=next;
    state_unlock();
    link_info->u.pLayerInfo=link->pNext;
    PFN_vkCreateInstance create=(PFN_vkCreateInstance)next(VK_NULL_HANDLE,"vkCreateInstance");
    if (!create) return VK_ERROR_INITIALIZATION_FAILED;
    VkResult r=create(ci,a,out); if (r!=VK_SUCCESS) return r;
    InstanceState *s=calloc(1,sizeof(*s)); if (!s) return VK_ERROR_OUT_OF_HOST_MEMORY;
    s->handle=*out; s->key=dispatch_key(*out); s->next_gipa=next;
    s->next_gdpa=(PFN_vkGetDeviceProcAddr)next(*out,"vkGetDeviceProcAddr");
#define ILOAD(field,name) s->field=(PFN_vk##name)next(*out,"vk" #name)
    ILOAD(DestroyInstance,DestroyInstance); ILOAD(CreateDevice,CreateDevice);
    ILOAD(GetCaps,GetPhysicalDeviceSurfaceCapabilitiesKHR);
    ILOAD(GetCaps2,GetPhysicalDeviceSurfaceCapabilities2KHR);
    ILOAD(GetSurfaceSupport,GetPhysicalDeviceSurfaceSupportKHR);
    ILOAD(GetQueueFamilies,GetPhysicalDeviceQueueFamilyProperties);
    ILOAD(GetMemoryProperties,GetPhysicalDeviceMemoryProperties);
#undef ILOAD
    state_lock();
    s->next=instances; instances=s;
    if(!config_loaded){load_config();config_loaded=1;}
#ifdef _WIN32
    install_fake_mode_hooks();
#endif
    log_message("VK_LAYER_BIFROST_fsr1 active (scale=%s)",config.scale>1.0f?"numeric":"auto");
    state_unlock();
    return VK_SUCCESS;
}

static void VKAPI_CALL layerDestroyInstance(VkInstance inst,const VkAllocationCallbacks *a) {
    state_lock();
    InstanceState **p=&instances;
    while (*p && (*p)->handle!=inst) p=&(*p)->next;
    InstanceState *s=*p;
    if(s)*p=s->next;
    state_unlock();
    if (s) { if(s->DestroyInstance)s->DestroyInstance(inst,a);free(s); }
}

static VkResult VKAPI_CALL layerCreateDevice(VkPhysicalDevice phys,const VkDeviceCreateInfo *ci,const VkAllocationCallbacks *a,VkDevice *out) {
    state_lock();
    InstanceState *is=find_instance((VkInstance)phys);
    PFN_vkGetInstanceProcAddr instance_gipa=is?is->next_gipa:NULL;
    VkInstance instance=is?is->handle:VK_NULL_HANDLE;
    PFN_vkGetPhysicalDeviceMemoryProperties get_memory=is?is->GetMemoryProperties:NULL;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties get_families=is?is->GetQueueFamilies:NULL;
    state_unlock();
    if (!is) return VK_ERROR_INITIALIZATION_FAILED;
    VkLayerDeviceCreateInfo *link_info=NULL; PFN_vkSetDeviceLoaderData set_data=NULL;
    for (VkBaseInStructure *p=(VkBaseInStructure *)ci->pNext;p;p=(VkBaseInStructure *)p->pNext) {
        if (p->sType!=VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO) continue;
        VkLayerDeviceCreateInfo *l=(VkLayerDeviceCreateInfo *)p;
        if (l->function==VK_LAYER_LINK_INFO) link_info=l;
        else if (l->function==VK_LOADER_DATA_CALLBACK) set_data=l->u.pfnSetDeviceLoaderData;
    }
    if (!link_info || !link_info->u.pLayerInfo) return VK_ERROR_INITIALIZATION_FAILED;
    VkLayerDeviceLink *link=link_info->u.pLayerInfo;
    PFN_vkGetDeviceProcAddr next_gdpa=link->pfnNextGetDeviceProcAddr;
    link_info->u.pLayerInfo=link->pNext;
    PFN_vkCreateDevice create=(PFN_vkCreateDevice)instance_gipa(instance,"vkCreateDevice");
    if (!create) return VK_ERROR_INITIALIZATION_FAILED;
    VkResult r=create(phys,ci,a,out);if(r!=VK_SUCCESS)return r;
    DeviceState *d=calloc(1,sizeof(*d));if(!d)return VK_ERROR_OUT_OF_HOST_MEMORY;
    d->handle=*out;d->key=dispatch_key(*out);d->physical=phys;d->next_gdpa=next_gdpa;d->set_loader_data=set_data;
    if(get_memory)get_memory(phys,&d->memory);
    if(get_families){get_families(phys,&d->family_count,NULL);d->families=calloc(d->family_count,sizeof(*d->families));if(d->families)get_families(phys,&d->family_count,d->families);}
#define DLOAD(name) d->name=(PFN_vk##name)next_gdpa(*out,"vk" #name)
    DLOAD(DestroyDevice);DLOAD(GetDeviceQueue);DLOAD(GetDeviceQueue2);DLOAD(QueueSubmit);
    DLOAD(DeviceWaitIdle);DLOAD(QueueWaitIdle);
    d->QueuePresent=(PFN_vkQueuePresentKHR)next_gdpa(*out,"vkQueuePresentKHR");
    d->CreateSwapchain=(PFN_vkCreateSwapchainKHR)next_gdpa(*out,"vkCreateSwapchainKHR");
    d->DestroySwapchain=(PFN_vkDestroySwapchainKHR)next_gdpa(*out,"vkDestroySwapchainKHR");
    d->GetSwapchainImages=(PFN_vkGetSwapchainImagesKHR)next_gdpa(*out,"vkGetSwapchainImagesKHR");
    d->Acquire=(PFN_vkAcquireNextImageKHR)next_gdpa(*out,"vkAcquireNextImageKHR");
    d->Acquire2=(PFN_vkAcquireNextImage2KHR)next_gdpa(*out,"vkAcquireNextImage2KHR");
    DLOAD(CreateImage);DLOAD(DestroyImage);DLOAD(GetImageMemoryRequirements);DLOAD(AllocateMemory);DLOAD(FreeMemory);DLOAD(BindImageMemory);
    DLOAD(CreateImageView);DLOAD(DestroyImageView);DLOAD(CreateSampler);DLOAD(DestroySampler);
    DLOAD(CreateRenderPass);DLOAD(DestroyRenderPass);DLOAD(CreateFramebuffer);DLOAD(DestroyFramebuffer);
    DLOAD(CreateShaderModule);DLOAD(DestroyShaderModule);DLOAD(CreateDescriptorSetLayout);DLOAD(DestroyDescriptorSetLayout);
    DLOAD(CreatePipelineLayout);DLOAD(DestroyPipelineLayout);DLOAD(CreateGraphicsPipelines);DLOAD(DestroyPipeline);
    DLOAD(CreateDescriptorPool);DLOAD(DestroyDescriptorPool);DLOAD(AllocateDescriptorSets);DLOAD(UpdateDescriptorSets);
    DLOAD(CreateCommandPool);DLOAD(DestroyCommandPool);DLOAD(AllocateCommandBuffers);DLOAD(FreeCommandBuffers);
    DLOAD(BeginCommandBuffer);DLOAD(EndCommandBuffer);DLOAD(CmdPipelineBarrier);DLOAD(CmdBeginRenderPass);
    DLOAD(CmdEndRenderPass);DLOAD(CmdBindPipeline);DLOAD(CmdBindDescriptorSets);DLOAD(CmdPushConstants);
    DLOAD(CmdSetViewport);DLOAD(CmdSetScissor);DLOAD(CmdDraw);DLOAD(CreateFence);DLOAD(DestroyFence);
    DLOAD(WaitForFences);DLOAD(ResetFences);DLOAD(CreateSemaphore);DLOAD(DestroySemaphore);
    d->ReleaseImages=(PFN_vkReleaseSwapchainImagesEXT)next_gdpa(*out,"vkReleaseSwapchainImagesEXT");
#undef DLOAD
    state_lock();
    d->next=devices;devices=d;
    state_unlock();
    return VK_SUCCESS;
}

static void VKAPI_CALL layerGetDeviceQueue(VkDevice dev,uint32_t family,uint32_t index,VkQueue *queue) {
    state_lock();
    DeviceState *d=find_device(dev);
    PFN_vkGetDeviceQueue get_queue=d?d->GetDeviceQueue:NULL;
    state_unlock();
    if(!get_queue)return;
    get_queue(dev,family,index,queue);
    if(*queue){
        QueueEntry *q=calloc(1,sizeof(*q));
        if(q){q->queue=*queue;q->family=family;state_lock();d=find_device(dev);if(d){q->next=d->queues;d->queues=q;q=NULL;}state_unlock();free(q);}
    }
}
static void VKAPI_CALL layerGetDeviceQueue2(VkDevice dev,const VkDeviceQueueInfo2 *info,VkQueue *queue) {
    state_lock();
    DeviceState *d=find_device(dev);
    PFN_vkGetDeviceQueue2 get_queue=d?d->GetDeviceQueue2:NULL;
    state_unlock();
    if(!get_queue)return;
    get_queue(dev,info,queue);
    if(*queue){
        QueueEntry *q=calloc(1,sizeof(*q));
        if(q){q->queue=*queue;q->family=info->queueFamilyIndex;state_lock();d=find_device(dev);if(d){q->next=d->queues;d->queues=q;q=NULL;}state_unlock();free(q);}
    }
}

static VkResult VKAPI_CALL layerCreateSwapchain(VkDevice dev,const VkSwapchainCreateInfoKHR *ci,const VkAllocationCallbacks *a,VkSwapchainKHR *out) {
    state_lock();
    DeviceState *d=find_device(dev);
    VkPhysicalDevice physical=d?d->physical:VK_NULL_HANDLE;
    PFN_vkCreateSwapchainKHR create_swapchain=d?d->CreateSwapchain:NULL;
    PFN_vkDestroySwapchainKHR destroy_swapchain=d?d->DestroySwapchain:NULL;
    PFN_vkGetSwapchainImagesKHR get_images=d?d->GetSwapchainImages:NULL;
    InstanceState *is=find_instance((VkInstance)physical);
    PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR get_caps=is?is->GetCaps:NULL;
    PFN_vkGetPhysicalDeviceSurfaceSupportKHR get_support=is?is->GetSurfaceSupport:NULL;
    state_unlock();
    if(!d||!create_swapchain)return VK_ERROR_INITIALIZATION_FAILED;
    VkSurfaceCapabilitiesKHR caps={0};
    if(!get_caps||get_caps(physical,ci->surface,&caps)!=VK_SUCCESS||!scaling_enabled()) {
        log_message("pass-through swapchain %ux%u",ci->imageExtent.width,ci->imageExtent.height);
        return create_swapchain(dev,ci,a,out);
    }
    if(caps.currentExtent.width==UINT32_MAX) {
        log_message("surface has no fixed extent (e.g. Wayland); FSR needs a fixed-size window; passing through");
        return create_swapchain(dev,ci,a,out);
    }
    VkExtent2D real_extent=caps.currentExtent;
    VkExtent2D expected=render_extent(real_extent);
    if(ci->imageExtent.width!=expected.width||ci->imageExtent.height!=expected.height||
       (ci->imageExtent.width==real_extent.width&&ci->imageExtent.height==real_extent.height)||
       !(caps.supportedUsageFlags&VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)) {
        log_message("pass-through swapchain %ux%u",ci->imageExtent.width,ci->imageExtent.height);
        return create_swapchain(dev,ci,a,out);
    }
    uint32_t family=choose_graphics_family(d,ci->surface,get_support);
    if(family==UINT32_MAX) {
        log_message("graphics queue family cannot present to this surface; passing through");
        return create_swapchain(dev,ci,a,out);
    }
    VkSwapchainCreateInfoKHR real_ci=*ci;real_ci.imageExtent=real_extent;
    real_ci.imageUsage|=VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    state_lock();
    Swapchain *old=find_swapchain(ci->oldSwapchain);
    if(old)real_ci.oldSwapchain=old->real;
    state_unlock();
    VkResult r=create_swapchain(dev,&real_ci,a,out);if(r!=VK_SUCCESS)return r;
    Swapchain *s=calloc(1,sizeof(*s));if(!s){destroy_swapchain(dev,*out,a);return VK_ERROR_OUT_OF_HOST_MEMORY;}
    s->app=s->real=*out;s->device=d;s->format=ci->imageFormat;s->app_extent=ci->imageExtent;s->output_extent=real_extent;
    s->app_usage=ci->imageUsage;s->scaled=1;
    uint32_t n=0;r=get_images(dev,s->real,&n,NULL);
    if(r!=VK_SUCCESS||!n){destroy_swapchain(dev,*out,a);free(s);return r==VK_SUCCESS?VK_ERROR_INITIALIZATION_FAILED:r;}
    s->count=n;
    if(!setup_swapchain(s,family)){destroy_swapchain_resources(s);destroy_swapchain(dev,*out,a);free(s);return VK_ERROR_INITIALIZATION_FAILED;}
    state_lock();
    s->next=swapchains;swapchains=s;
    VkExtent2D app_extent=s->app_extent, output_extent=s->output_extent;
    state_unlock();
    log_message("upscaling engaged: %ux%u -> %ux%u",app_extent.width,app_extent.height,output_extent.width,output_extent.height);
    return VK_SUCCESS;
}

static void VKAPI_CALL layerDestroySwapchain(VkDevice dev,VkSwapchainKHR h,const VkAllocationCallbacks *a) {
    state_lock();
    DeviceState *d=find_device(dev);Swapchain **p=&swapchains;
    while(*p&&(*p)->app!=h)p=&(*p)->next;
    Swapchain *s=*p;
    if(s)*p=s->next;
    PFN_vkDestroySwapchainKHR destroy=d?d->DestroySwapchain:NULL;
    state_unlock();
    if(s){if(s->scaled)destroy_swapchain_resources(s);if(destroy)destroy(dev,s->real,a);free(s);return;}
    if(destroy)destroy(dev,h,a);
}
static VkResult VKAPI_CALL layerGetSwapchainImages(VkDevice dev,VkSwapchainKHR h,uint32_t *count,VkImage *images) {
    state_lock();
    Swapchain *s=find_swapchain(h);DeviceState *d=find_device(dev);
    PFN_vkGetSwapchainImagesKHR get_images=d?d->GetSwapchainImages:NULL;
    if(!s||!s->scaled){state_unlock();return get_images?get_images(dev,h,count,images):VK_ERROR_INITIALIZATION_FAILED;}
    if(!images){*count=s->count;state_unlock();return VK_SUCCESS;}
    uint32_t n=*count<s->count?*count:s->count;
    for(uint32_t i=0;i<n;i++)images[i]=s->frames[i].app;
    VkResult result=n<s->count?VK_INCOMPLETE:VK_SUCCESS;
    *count=n;state_unlock();return result;
}
static VkResult VKAPI_CALL layerAcquire(VkDevice dev,VkSwapchainKHR h,uint64_t timeout,VkSemaphore sem,VkFence fence,uint32_t *index) {
    state_lock();
    DeviceState *d=find_device(dev);Swapchain *s=find_swapchain(h);
    PFN_vkAcquireNextImageKHR acquire=d?d->Acquire:NULL;VkSwapchainKHR real=s?s->real:h;
    state_unlock();
    return acquire?acquire(dev,real,timeout,sem,fence,index):VK_ERROR_INITIALIZATION_FAILED;
}
static VkResult VKAPI_CALL layerAcquire2(VkDevice dev,const VkAcquireNextImageInfoKHR *info,uint32_t *index) {
    state_lock();
    DeviceState *d=find_device(dev);Swapchain *s=find_swapchain(info->swapchain);
    PFN_vkAcquireNextImage2KHR acquire=d?d->Acquire2:NULL;VkAcquireNextImageInfoKHR copy=*info;if(s)copy.swapchain=s->real;
    state_unlock();
    return acquire?acquire(dev,&copy,index):VK_ERROR_INITIALIZATION_FAILED;
}
static VkResult VKAPI_CALL layerReleaseImages(VkDevice dev,const VkReleaseSwapchainImagesInfoEXT *info) {
    state_lock();
    DeviceState *d=find_device(dev);Swapchain *s=find_swapchain(info->swapchain);
    PFN_vkReleaseSwapchainImagesEXT release=d?d->ReleaseImages:NULL;VkReleaseSwapchainImagesInfoEXT copy=*info;if(s)copy.swapchain=s->real;
    state_unlock();
    return release?release(dev,&copy):VK_ERROR_EXTENSION_NOT_PRESENT;
}

static VkResult record_and_submit(Swapchain *s,uint32_t index,VkQueue queue,
                                  uint32_t wait_count,const VkSemaphore *waits) {
    DeviceState *d=s->device;Frame *f=&s->frames[index];
    if(d->WaitForFences(d->handle,1,&f->fence,VK_TRUE,UINT64_MAX)!=VK_SUCCESS)return VK_ERROR_DEVICE_LOST;
    if(d->ResetFences(d->handle,1,&f->fence)!=VK_SUCCESS)return VK_ERROR_DEVICE_LOST;
    VkCommandBufferBeginInfo bi={VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};bi.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    VkResult r=d->BeginCommandBuffer(f->command,&bi);if(r!=VK_SUCCESS)return r;
    VkImageMemoryBarrier app={VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    app.srcAccessMask=VK_ACCESS_MEMORY_WRITE_BIT;app.dstAccessMask=VK_ACCESS_SHADER_READ_BIT;
    /* The app treats this as a swapchain image and must return it to PRESENT_SRC_KHR before vkQueuePresentKHR, so it is in that layout here. */
    app.oldLayout=VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;app.newLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    app.srcQueueFamilyIndex=app.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;app.image=f->app;
    app.subresourceRange=(VkImageSubresourceRange){VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
    d->CmdPipelineBarrier(f->command,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,0,0,NULL,0,NULL,1,&app);

    uint32_t rw=s->output_extent.width,rh=s->output_extent.height;
    float factor=fminf((float)rw/s->app_extent.width,(float)rh/s->app_extent.height);
    uint32_t fitw=(uint32_t)fmaxf(1.0f,floorf(s->app_extent.width*factor+0.5f));
    uint32_t fith=(uint32_t)fmaxf(1.0f,floorf(s->app_extent.height*factor+0.5f));
    uint32_t ox=(rw-fitw)/2,oy=(rh-fith)/2;
    uint32_t c0[4],c1[4],c2[4],c3[4],rc[4];
    FsrEasuCon(c0,c1,c2,c3,(float)s->app_extent.width,(float)s->app_extent.height,
               (float)s->app_extent.width,(float)s->app_extent.height,(float)fitw,(float)fith);
    FsrRcasCon(rc,config.sharpness);
    uint32_t push[20]={0};
    memcpy(push,c0,16);memcpy(push+4,c1,16);memcpy(push+8,c2,16);memcpy(push+12,c3,16);
    push[16]=ox;push[17]=oy;
    VkClearValue clear={.color={{0,0,0,1}}};
    VkRenderPassBeginInfo rp={VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};rp.renderPass=s->easu_pass;
    rp.framebuffer=f->easu_fb;rp.renderArea=(VkRect2D){{0,0},{rw,rh}};rp.clearValueCount=1;rp.pClearValues=&clear;
    d->CmdBeginRenderPass(f->command,&rp,VK_SUBPASS_CONTENTS_INLINE);
    VkViewport viewport={(float)ox,(float)oy,(float)fitw,(float)fith,0.0f,1.0f};
    VkRect2D scissor={{(int32_t)ox,(int32_t)oy},{fitw,fith}};
    d->CmdSetViewport(f->command,0,1,&viewport);d->CmdSetScissor(f->command,0,1,&scissor);
    d->CmdBindPipeline(f->command,VK_PIPELINE_BIND_POINT_GRAPHICS,s->easu_pipeline);
    d->CmdBindDescriptorSets(f->command,VK_PIPELINE_BIND_POINT_GRAPHICS,s->pipeline_layout,0,1,&f->easu_set,0,NULL);
    d->CmdPushConstants(f->command,s->pipeline_layout,VK_SHADER_STAGE_FRAGMENT_BIT,0,80,push);
    d->CmdDraw(f->command,3,1,0,0);d->CmdEndRenderPass(f->command);

    VkImageMemoryBarrier real={VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    real.srcAccessMask=0;real.dstAccessMask=VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    real.oldLayout=f->real_initialized?VK_IMAGE_LAYOUT_PRESENT_SRC_KHR:VK_IMAGE_LAYOUT_UNDEFINED;
    real.newLayout=VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;real.srcQueueFamilyIndex=real.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
    real.image=f->real;real.subresourceRange=(VkImageSubresourceRange){VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
    d->CmdPipelineBarrier(f->command,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,0,0,NULL,0,NULL,1,&real);
    rp.renderPass=s->rcas_pass;rp.framebuffer=f->rcas_fb;rp.clearValueCount=0;rp.pClearValues=NULL;
    d->CmdBeginRenderPass(f->command,&rp,VK_SUBPASS_CONTENTS_INLINE);
    viewport=(VkViewport){0,0,(float)rw,(float)rh,0,1};scissor=(VkRect2D){{0,0},{rw,rh}};
    d->CmdSetViewport(f->command,0,1,&viewport);d->CmdSetScissor(f->command,0,1,&scissor);
    d->CmdBindPipeline(f->command,VK_PIPELINE_BIND_POINT_GRAPHICS,s->rcas_pipeline);
    d->CmdBindDescriptorSets(f->command,VK_PIPELINE_BIND_POINT_GRAPHICS,s->pipeline_layout,0,1,&f->rcas_set,0,NULL);
    d->CmdPushConstants(f->command,s->pipeline_layout,VK_SHADER_STAGE_FRAGMENT_BIT,0,16,rc);
    d->CmdDraw(f->command,3,1,0,0);d->CmdEndRenderPass(f->command);
    VkImageMemoryBarrier back[2]={app,real};
    back[0].srcAccessMask=VK_ACCESS_SHADER_READ_BIT;back[0].dstAccessMask=0;
    back[0].oldLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;back[0].newLayout=VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    back[1].srcAccessMask=VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;back[1].dstAccessMask=0;
    back[1].oldLayout=VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;back[1].newLayout=VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    d->CmdPipelineBarrier(f->command,VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT|VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                          VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,0,0,NULL,0,NULL,2,back);
    r=d->EndCommandBuffer(f->command);if(r!=VK_SUCCESS)return r;
    VkPipelineStageFlags stage=VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    VkSubmitInfo si={VK_STRUCTURE_TYPE_SUBMIT_INFO};si.waitSemaphoreCount=wait_count;si.pWaitSemaphores=waits;
    si.pWaitDstStageMask=wait_count?&stage:NULL;si.commandBufferCount=1;si.pCommandBuffers=&f->command;
    si.signalSemaphoreCount=1;si.pSignalSemaphores=&f->done;
    r=d->QueueSubmit(queue,1,&si,f->fence);if(r==VK_SUCCESS)f->real_initialized=1;
    return r;
}

static VkResult VKAPI_CALL layerPresent(VkQueue queue,const VkPresentInfoKHR *info) {
    if(!info)return VK_ERROR_INITIALIZATION_FAILED;
    Swapchain **mapped=calloc(info->swapchainCount,sizeof(*mapped));
    VkSwapchainKHR *handles=calloc(info->swapchainCount,sizeof(*handles));
    if(!mapped||!handles){free(mapped);free(handles);return VK_ERROR_OUT_OF_HOST_MEMORY;}
    state_lock();
    DeviceState *d=find_device(queue);
    if(!d||!d->QueuePresent){state_unlock();free(mapped);free(handles);return VK_ERROR_INITIALIZATION_FAILED;}
    PFN_vkQueuePresentKHR queue_present=d->QueuePresent;
    uint32_t family=UINT32_MAX;
    for(QueueEntry *q=d->queues;q;q=q->next)if(q->queue==queue){family=q->family;break;}
    int scaled=0,graphics=family<d->family_count&&(d->families[family].queueFlags&VK_QUEUE_GRAPHICS_BIT);
    for(uint32_t i=0;i<info->swapchainCount;i++){
        mapped[i]=find_swapchain(info->pSwapchains[i]);handles[i]=mapped[i]?mapped[i]->real:info->pSwapchains[i];
        if(mapped[i]&&mapped[i]->scaled){scaled=1;if(mapped[i]->pool_family!=family)graphics=0;}
    }
    if(!scaled||!graphics){
        if(scaled)log_message("app presented on a different queue family than the FSR pass uses; the frame is not upscaled");
        VkPresentInfoKHR pass=*info;pass.pSwapchains=handles;
        if(!scaled)state_unlock();
        VkResult result=queue_present(queue,&pass);
        if(scaled)state_unlock();
        free(mapped);free(handles);return result;
    }
    /* Hold the global lock through submit/present so destroy cannot free a mapped swapchain. */
    VkSemaphore previous=VK_NULL_HANDLE;int first=1;VkResult result=VK_SUCCESS;
    for(uint32_t i=0;i<info->swapchainCount;i++)if(mapped[i]&&mapped[i]->scaled){
        uint32_t index=info->pImageIndices[i];if(index>=mapped[i]->count){result=VK_ERROR_OUT_OF_DATE_KHR;break;}
        uint32_t wc=first?info->waitSemaphoreCount:(previous?1:0);
        const VkSemaphore *ws=first?info->pWaitSemaphores:(previous?&previous:NULL);
        result=record_and_submit(mapped[i],index,queue,wc,ws);
        if(result!=VK_SUCCESS)break;
        previous=mapped[i]->frames[index].done;first=0;
    }
    if(result==VK_SUCCESS){
        VkPresentInfoKHR pass=*info;pass.pSwapchains=handles;
        if(!first){pass.waitSemaphoreCount=1;pass.pWaitSemaphores=&previous;}
        result=queue_present(queue,&pass);
    }
    state_unlock();
    free(mapped);free(handles);return result;
}

static void VKAPI_CALL layerDestroyDevice(VkDevice dev,const VkAllocationCallbacks *a) {
    state_lock();
    DeviceState **p=&devices;while(*p&&(*p)->handle!=dev)p=&(*p)->next;
    DeviceState *d=*p;
    QueueEntry *queues=d?d->queues:NULL;
    if(d)*p=d->next;
    if(d)d->queues=NULL;
    state_unlock();
    if(d){if(d->DeviceWaitIdle)d->DeviceWaitIdle(dev);
        while(queues){QueueEntry *q=queues;queues=q->next;free(q);}
        free(d->families);if(d->DestroyDevice)d->DestroyDevice(dev,a);free(d);return;}
}

static PFN_vkVoidFunction VKAPI_CALL layerGetInstanceProcAddr(VkInstance inst,const char *name) {
    if(!name)return NULL;
#define INSTANCE_FN(n,f) if(strcmp(name,"vk" #n)==0)return (PFN_vkVoidFunction)f
    INSTANCE_FN(GetInstanceProcAddr,layerGetInstanceProcAddr);
    INSTANCE_FN(GetDeviceProcAddr,layerGetDeviceProcAddr);
    INSTANCE_FN(CreateInstance,layerCreateInstance);
    INSTANCE_FN(NegotiateLoaderLayerInterfaceVersion,vkNegotiateLoaderLayerInterfaceVersion);
    if(inst){
        INSTANCE_FN(DestroyInstance,layerDestroyInstance);
        INSTANCE_FN(CreateDevice,layerCreateDevice);
        INSTANCE_FN(GetPhysicalDeviceSurfaceCapabilitiesKHR,layerGetCaps);
        INSTANCE_FN(GetPhysicalDeviceSurfaceCapabilities2KHR,layerGetCaps2);
        state_lock();
        InstanceState *s=find_instance(inst);
        PFN_vkGetInstanceProcAddr next=s?s->next_gipa:NULL;
        state_unlock();
        if(next)return next(inst,name);
    }
#undef INSTANCE_FN
    state_lock();
    PFN_vkGetInstanceProcAddr next=global_next_gipa;
    state_unlock();
    return next ? next(inst,name) : NULL;
}
static PFN_vkVoidFunction VKAPI_CALL layerGetDeviceProcAddr(VkDevice dev,const char *name) {
    if(!name)return NULL;
#define DEVICE_FN(n,f) if(strcmp(name,"vk" #n)==0)return (PFN_vkVoidFunction)f
    DEVICE_FN(GetDeviceProcAddr,layerGetDeviceProcAddr);
    DEVICE_FN(DestroyDevice,layerDestroyDevice);DEVICE_FN(GetDeviceQueue,layerGetDeviceQueue);DEVICE_FN(GetDeviceQueue2,layerGetDeviceQueue2);
    DEVICE_FN(CreateSwapchainKHR,layerCreateSwapchain);DEVICE_FN(DestroySwapchainKHR,layerDestroySwapchain);
    DEVICE_FN(GetSwapchainImagesKHR,layerGetSwapchainImages);DEVICE_FN(AcquireNextImageKHR,layerAcquire);
    DEVICE_FN(AcquireNextImage2KHR,layerAcquire2);DEVICE_FN(QueuePresentKHR,layerPresent);
    DEVICE_FN(ReleaseSwapchainImagesEXT,layerReleaseImages);
    state_lock();
    DeviceState *d=find_device(dev);
    PFN_vkGetDeviceProcAddr next=d?d->next_gdpa:NULL;
    state_unlock();
    if(next)return next(dev,name);
#undef DEVICE_FN
    return NULL;
}
