#version 450
#extension GL_GOOGLE_include_directive : require

layout(set = 0, binding = 0) uniform sampler2D source_image;
layout(push_constant) uniform Constants {
    uvec4 con0;
    uvec4 con1;
    uvec4 con2;
    uvec4 con3;
    uvec4 viewport;
} pc;
layout(location = 0) out vec4 color_out;

#define A_GPU 1
#define A_GLSL 1
#define FSR_EASU_F 1
#include "ffx_a.h"

AF4 FsrEasuRF(AF2 p) { return textureGather(source_image, p, 0); }
AF4 FsrEasuGF(AF2 p) { return textureGather(source_image, p, 1); }
AF4 FsrEasuBF(AF2 p) { return textureGather(source_image, p, 2); }
#include "ffx_fsr1.h"

void main() {
    AF3 c;
    FsrEasuF(c, AU2(gl_FragCoord.xy) - pc.viewport.xy, pc.con0, pc.con1, pc.con2, pc.con3);
    color_out = vec4(c, 1.0);
}
