#version 450
#extension GL_GOOGLE_include_directive : require

layout(set = 0, binding = 0) uniform sampler2D source_image;
layout(push_constant) uniform Constants {
    uvec4 con;
} pc;
layout(location = 0) out vec4 color_out;

#define A_GPU 1
#define A_GLSL 1
#define FSR_RCAS_F 1
#include "ffx_a.h"

AF4 FsrRcasLoadF(ASU2 p) { return texelFetch(source_image, p, 0); }
void FsrRcasInputF(inout AF1 r, inout AF1 g, inout AF1 b) {}
#include "ffx_fsr1.h"

void main() {
    AF1 r, g, b;
    FsrRcasF(r, g, b, AU2(gl_FragCoord.xy), pc.con);
    color_out = vec4(r, g, b, 1.0);
}
