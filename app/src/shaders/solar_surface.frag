#version 450
#extension GL_ARB_separate_shader_objects : enable
#extension GL_ARB_shading_language_420pack : enable
#include "solar_fragment.glsl"
layout(set = 3, binding = 1) uniform sampler2D colorMap;
layout(set = 3, binding = 2) uniform sampler2D ringMap;

float ringShadow(vec3 lightDirection) {
    if (solarUniform.parameters.y < 0.5 || abs(lightDirection.y) < 1e-5) return 1.0;
    float distance = -solar.localPosition.y / lightDirection.y;
    if (distance <= 0.002) return 1.0;
    vec3 crossing = solar.localPosition + lightDirection * distance;
    float radius = length(crossing.xz);
    float inner = solarUniform.parameters.z, outer = solarUniform.parameters.w;
    if (radius <= inner || radius >= outer) return 1.0;
    float opacity = texture(ringMap, vec2((radius - inner) / (outer - inner), 0.5)).a;
    // Grazing rays pass through more ring material. Ring gaps retain their measured transparency.
    return exp(-opacity * 2.2 / max(abs(lightDirection.y), 0.18));
}

void main() {
    vec3 normal = normalize(solar.localNormal);
    vec3 color = texture(colorMap, solar.uv).rgb; // SDK SRGB sampler already returns linear color.
    if (solarUniform.parameters.x > 0.5) {
        float limb = mix(0.50, 1.0, pow(max(dot(normal, localView()), 0.0), 0.45));
        outColor = vec4(color * 2.2 * limb, 1.0);
        return;
    }
    vec3 sun = localSun();
    float diffuse = max(dot(normal, sun), 0.0);
    // Very dark reflected starlight keeps a faint night silhouette without creating a second light.
    color *= 0.002 + 1.05 * diffuse * ringShadow(sun);
    outColor = vec4(color, 1.0);
}
