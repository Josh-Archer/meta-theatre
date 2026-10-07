#version 450
#extension GL_ARB_separate_shader_objects : enable
#extension GL_ARB_shading_language_420pack : enable
#include "earth_fragment.glsl"
layout(set = 3, binding = 1) uniform sampler2D cloudMap;

void main() {
    vec4 cloud = texture(cloudMap, earth.uv);
    float elevation = dot(normalize(earth.localNormal), localSun());
    float daylight = dayWeight(elevation);
    float lighting = 0.008 + daylight * (0.16 + 0.84 * max(elevation, 0.0));
    vec3 color = vec3(lighting);
    color += vec3(0.085, 0.025, 0.005) * exp(-abs(elevation) * 22.0);
    outColor = vec4(color, cloud.a * 0.68);
}
