#include "NIS_Config.h"
#include <cstring>

extern "C" bool bifrost_nis_update_config(
    void *output, float sharpness,
    uint32_t input_width, uint32_t input_height,
    uint32_t output_x, uint32_t output_y, uint32_t output_width, uint32_t output_height,
    uint32_t texture_width, uint32_t texture_height)
{
    NISConfig config{};
    if (!NVScalerUpdateConfig(config, sharpness, 0, 0, input_width, input_height,
            input_width, input_height, output_x, output_y, output_width, output_height,
            texture_width, texture_height))
        return false;
    static_assert(sizeof(config) == 256);
    std::memcpy(output, &config, sizeof(config));
    return true;
}

extern "C" void bifrost_nis_get_coefficients(float *scale, float *usm)
{
    for (size_t phase = 0; phase < kPhaseCount; ++phase) {
        for (size_t pixel = 0; pixel < 2; ++pixel) {
            for (size_t channel = 0; channel < 4; ++channel) {
                const size_t i = (phase * 2 + pixel) * 4 + channel;
                scale[i] = coef_scale[phase][pixel * 4 + channel];
                usm[i] = coef_usm[phase][pixel * 4 + channel];
            }
        }
    }
}
