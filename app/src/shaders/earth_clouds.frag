#version 450
#extension GL_ARB_separate_shader_objects : enable
#extension GL_ARB_shading_language_420pack : enable
#include "earth_fragment.glsl"
layout(set = 3, binding = 1) uniform sampler2D cloudMap;

void main() {
    vec4 cloud = texture(cloudMap, earth.uv);
    float density = cloud.a;
    vec3 profile = cloudProfile(cloud);
    vec2 texel = 1.0 / vec2(textureSize(cloudMap, 0));
    vec3 normal = normalize(earth.localNormal);
    mat3 frame = earthTangentFrame(normal);
    vec2 gradient = vec2(
        texture(cloudMap, earth.uv - vec2(texel.x, 0.0)).a - texture(cloudMap, earth.uv + vec2(texel.x, 0.0)).a,
        texture(cloudMap, earth.uv - vec2(0.0, texel.y)).a - texture(cloudMap, earth.uv + vec2(0.0, texel.y)).a);
    vec3 billow = normalize(frame * normalize(vec3(gradient * (1.3 + profile.x * 2.2), 1.0)));
    vec3 sun = localSun();
    float elevation = dot(normal, sun);
    float daylight = dayWeight(elevation);
    vec2 offset = cloudSunOffset(sun, frame, elevation) * (0.4 + profile.x);
    float ahead = texture(cloudMap, earth.uv + offset * 0.45).a;
    float farAhead = texture(cloudMap, earth.uv + offset).a;
    float selfShadow = exp(-(max(ahead - density, 0.0) * 2.0 + max(farAhead - density, 0.0) * 1.5));
    float direct = max(dot(billow, sun), 0.0) * selfShadow;
    float multipleScattering = 0.18 + 0.16 * (1.0 - exp(-density * 2.0));
    float lighting = 0.002 + daylight * (multipleScattering + 0.78 * direct);
    vec3 color = mix(vec3(0.68, 0.76, 0.90), vec3(1.0), clamp(direct + 0.35, 0.0, 1.0)) * lighting;
    // Forward scattering at thin edges gives sunlit cirrus and storm anvils a soft silver lining.
    float forwardScatter = pow(max(dot(localView(), -sun), 0.0), 12.0);
    color += vec3(0.55, 0.62, 0.72) * forwardScatter * (1.0 - density) * daylight * (0.25 + profile.y * 0.3);
    color += vec3(0.075, 0.022, 0.005) * exp(-abs(elevation) * 24.0);
    float opticalOpacity = (1.0 - exp(-density * (2.4 + profile.y * 0.9))) * 0.96;
    outColor = vec4(color, opticalOpacity);
}
