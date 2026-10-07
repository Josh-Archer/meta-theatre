#version 450
#extension GL_ARB_separate_shader_objects : enable
#extension GL_ARB_shading_language_420pack : enable
#include "solar_fragment.glsl"
layout(set = 3, binding = 1) uniform sampler2D ringMap;

float planetShadow(vec3 sun) {
    // Ray from ring fragment to the same point Sun; Saturn is the unit sphere at this mesh's origin.
    float closestDistance = -dot(solar.localPosition, sun);
    if (closestDistance < 0.0) return 1.0;
    float impact = length(solar.localPosition + sun * closestDistance);
    return smoothstep(0.97, 1.03, impact);
}
void main() {
    // Analytic globe occlusion also avoids coarse far-distance depth precision showing back rings through Saturn.
    vec3 view = localView();
    float closestToEye = -dot(solar.localPosition, view);
    if (closestToEye > 0.0 && length(solar.localPosition + view * closestToEye) < 1.0) discard;
    float radius = length(solar.localPosition.xz);
    float u = (radius - solarUniform.parameters.z) / (solarUniform.parameters.w - solarUniform.parameters.z);
    vec4 rings = texture(ringMap, vec2(clamp(u, 0.0, 1.0), 0.5));
    vec3 sun = localSun();
    float direct = abs(dot(normalize(solar.localNormal), sun));
    float shadow = planetShadow(sun);
    // Thin, dusty rings scatter on both faces; no animated or screen-space sparkle.
    vec3 color = rings.rgb * (0.008 + (0.13 + 0.87 * direct) * shadow);
    outColor = vec4(color, rings.a);
}
