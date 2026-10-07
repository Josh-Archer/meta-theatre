#version 450
#extension GL_ARB_separate_shader_objects : enable
#extension GL_ARB_shading_language_420pack : enable
#include "solar_fragment.glsl"
void main() {
    float rim = pow(1.0 - clamp(dot(normalize(solar.localNormal), localView()), 0.0, 1.0), 8.0);
    outColor = vec4(vec3(0.8, 0.28, 0.045), 0.09 * rim);
}
