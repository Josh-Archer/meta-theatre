#version 450
#extension GL_ARB_separate_shader_objects : enable
#extension GL_ARB_shading_language_420pack : enable
#include "earth_fragment.glsl"

void main() {
    vec3 normal = normalize(earth.localNormal);
    float elevation = dot(normal, localSun());
    float limb = pow(1.0 - clamp(dot(normal, localView()), 0.0, 1.0), 3.5);
    float twilight = exp(-abs(elevation) * 18.0);
    vec3 color = mix(vec3(0.065, 0.28, 0.65), vec3(0.58, 0.095, 0.018), twilight * 0.7);
    float alpha = limb * 0.42 * (0.12 + 0.88 * dayWeight(elevation));
    outColor = vec4(color, alpha);
}
