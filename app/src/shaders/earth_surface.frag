#version 450
#extension GL_ARB_separate_shader_objects : enable
#extension GL_ARB_shading_language_420pack : enable
#include "earth_fragment.glsl"

layout(set = 3, binding = 1) uniform sampler2D dayMap;
layout(set = 3, binding = 2) uniform sampler2D nightMap;
layout(set = 3, binding = 3) uniform sampler2D normalMap;
layout(set = 3, binding = 4) uniform sampler2D oceanMap;

vec3 terrainNormal(vec3 normal) {
    // Derivative basis supports this sphere's real UVs without a tangent vertex stream.
    vec3 px = dFdx(earth.localPosition), py = dFdy(earth.localPosition);
    vec2 tx = dFdx(earth.uv), ty = dFdy(earth.uv);
    float determinant = tx.x * ty.y - tx.y * ty.x;
    if (abs(determinant) < 1e-10) return normal;
    vec3 tangent = (px * ty.y - py * tx.y) / determinant;
    vec3 bitangent = (py * tx.x - px * ty.x) / determinant;
    tangent = normalize(tangent - normal * dot(tangent, normal));
    bitangent = normalize(bitangent - normal * dot(bitangent, normal));
    vec3 bump = decodeData(texture(normalMap, earth.uv).xyz) * 2.0 - 1.0;
    // Authored OpenGL normal's +Y points north, while glTF's increasing V points south.
    bump.y = -bump.y;
    bump.xy *= 0.28;
    return normalize(mat3(tangent, bitangent, normal) * normalize(bump));
}

void main() {
    vec3 normal = normalize(earth.localNormal);
    vec3 sun = localSun();
    float elevation = dot(normal, sun);
    float daylight = dayWeight(elevation);
    vec3 bumpNormal = terrainNormal(normal);
    float diffuse = 0.08 + 0.92 * max(dot(bumpNormal, sun), 0.0);
    vec3 day = texture(dayMap, earth.uv).rgb;
    vec3 city = texture(nightMap, earth.uv).rgb;
    float darkness = 1.0 - smoothstep(-0.12, 0.02, elevation);
    vec3 color = day * (0.002 + daylight * diffuse) + city * 2.8 * darkness;
    vec3 view = localView();
    vec3 halfVector = sun + view;
    // At midnight the two vectors can be opposite; prevent a zero normalization.
    if (dot(halfVector, halfVector) > 1e-6) {
        float highlight = pow(max(dot(bumpNormal, normalize(halfVector)), 0.0), 72.0);
        color += vec3(0.42) * linear_to_srgb(texture(oceanMap, earth.uv).r) * highlight * daylight;
    }
    float rim = pow(1.0 - clamp(dot(normal, view), 0.0, 1.0), 2.0);
    color += vec3(0.075, 0.018, 0.004) * exp(-abs(elevation) * 22.0) * rim;
    // The SDK's render target expects linear output (same convention as stock PBR).
    outColor = vec4(color, 1.0);
}
