#version 450
#extension GL_ARB_separate_shader_objects : enable
#extension GL_ARB_shading_language_420pack : enable
#include "solar_fragment.glsl"
layout(set = 3, binding = 1) uniform sampler2D colorMap;
void main() {
    // Stable catalog panorama, independent of the Sun and civil-time changes.
    outColor = vec4(texture(colorMap, solar.uv).rgb * 0.8, 1.0);
}
