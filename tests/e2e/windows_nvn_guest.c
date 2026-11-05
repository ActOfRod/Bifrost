#include <stdint.h>

extern uintptr_t nvnBootstrapLoader(const char *name);

typedef int (*DeviceInitialize)(void *device);

int main(void) {
    unsigned char device[256] = {0};
    DeviceInitialize initialize =
        (DeviceInitialize)nvnBootstrapLoader("nvnDeviceInitialize");
    if (!initialize || !initialize(device)) return 1;
    return 0;
}
