#version 450
#extension GL_ARB_separate_shader_objects : enable
#extension GL_ARB_shading_language_420pack : enable
#include "earth_fragment.glsl"

layout(set = 3, binding = 1) uniform sampler2D dayMap;
layout(set = 3, binding = 2) uniform sampler2D nightMap;
layout(set = 3, binding = 3) uniform sampler2D normalMap;
layout(set = 3, binding = 4) uniform sampler2D oceanMap;
layout(set = 3, binding = 5) uniform sampler2D cloudMap;

vec2 regionUv() {
    vec4 region = earthUniform.detailRegion;
    return vec2(mod(earth.uv.x * 360.0 - 180.0 - region.x + 360.0, 360.0) / max(region.z, 0.001),
        (region.y - (90.0 - earth.uv.y * 180.0)) / max(region.w, 0.001));
}

vec3 terrainNormal(vec3 normal, mat3 frame) {
    vec4 region = earthUniform.terrainRegion;
    vec2 uv = earth.uv;
    bool regional = region.z > 0.0;
    if (regional) {
        float latitude = radians(clamp(90.0 - earth.uv.y * 180.0, -85.0, 85.0));
        float y = (1.0 - log(tan(0.785398163 + latitude * 0.5)) / 3.141592654) * 0.5;
        uv = vec2(mod(earth.uv.x - region.x + 1.0, 1.0) / region.z, (y-region.y)/region.w);
        if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) return normal;
    }
    vec3 bump = decodeData(texture(normalMap, uv).xyz) * 2.0 - 1.0;
    // Authored OpenGL normal's +Y points north, while glTF's increasing V points south.
    bump.y = -bump.y;
    bump.xy *= regional ? 2.0 : 0.28;
    return normalize(frame * normalize(bump));
}

void main() {
    vec2 detailUv = regionUv();
    if (earthUniform.detailRegion.z <= 0.0 || any(lessThan(detailUv, vec2(0.0))) || any(greaterThan(detailUv, vec2(1.0)))) discard;
    vec2 edge = min(detailUv, 1.0 - detailUv);
    float edgeAlpha = smoothstep(0.0, 0.12, min(edge.x, edge.y));
    vec3 normal = normalize(earth.localNormal);
    vec3 sun = localSun();
    float elevation = dot(normal, sun);
    float daylight = dayWeight(elevation);
    mat3 frame = earthTangentFrame(normal);
    vec3 bumpNormal = terrainNormal(normal, frame);
    vec2 transmission = surfaceCloudTransmission(cloudMap, sun, frame, elevation);
    float cloudTransmission = transmission.x;
    float diffuse = 0.065 + 0.935 * max(dot(bumpNormal, sun), 0.0) * cloudTransmission;
    vec3 day = texture(dayMap, detailUv).rgb;
    vec3 city = texture(nightMap, earth.uv).rgb;
    float darkness = 1.0 - smoothstep(-0.12, 0.02, elevation);
    vec3 color = day * (0.004 + daylight * diffuse) + city * 3.2 * darkness * transmission.y;
    vec3 view = localView();
    vec3 halfVector = sun + view;
    // At midnight the two vectors can be opposite; prevent a zero normalization.
    if (dot(halfVector, halfVector) > 1e-6) {
        float highlight = pow(max(dot(bumpNormal, normalize(halfVector)), 0.0), 160.0);
        float fresnel = 0.035 + 0.965 * pow(1.0 - max(dot(normal, view), 0.0), 5.0);
        color += vec3(2.4) * fresnel * linear_to_srgb(texture(oceanMap, earth.uv).r) * highlight * daylight * cloudTransmission;
    }
    float rim = pow(1.0 - clamp(dot(normal, view), 0.0, 1.0), 2.0);
    color = mix(color, vec3(0.12, 0.25, 0.48), rim * daylight * 0.065);
    color += vec3(0.075, 0.018, 0.004) * exp(-abs(elevation) * 22.0) * rim;
    // The SDK's render target expects linear output (same convention as stock PBR).
    outColor = vec4(color, edgeAlpha);
}
