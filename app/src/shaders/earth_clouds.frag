#version 450
#extension GL_ARB_separate_shader_objects : enable
#extension GL_ARB_shading_language_420pack : enable
#include "earth_fragment.glsl"
layout(set = 3, binding = 1) uniform sampler2D cloudMap;

void main() {
    float density = texture(cloudMap, earth.uv).a;
    vec2 texel = 1.0 / vec2(textureSize(cloudMap, 0));
    vec3 normal = normalize(earth.localNormal);
    mat3 frame = earthTangentFrame(normal);
    vec2 gradient = vec2(
        texture(cloudMap, earth.uv - vec2(texel.x, 0.0)).a - texture(cloudMap, earth.uv + vec2(texel.x, 0.0)).a,
        texture(cloudMap, earth.uv - vec2(0.0, texel.y)).a - texture(cloudMap, earth.uv + vec2(0.0, texel.y)).a);
    vec3 billow = normalize(frame * normalize(vec3(gradient * 2.1, 1.0)));
    vec3 sun = localSun();
    float elevation = dot(normal, sun);
    float daylight = dayWeight(elevation);
    float ahead = texture(cloudMap, earth.uv + cloudSunOffset(sun, frame, elevation) * 0.5).a;
    float selfShadow = exp(-max(ahead - density, 0.0) * 2.8);
    float direct = max(dot(billow, sun), 0.0) * selfShadow;
    float lighting = 0.0015 + daylight * (0.20 + 0.80 * direct);
    vec3 color = vec3(lighting);
    color += vec3(0.075, 0.022, 0.005) * exp(-abs(elevation) * 24.0);
    float opticalOpacity = (1.0 - exp(-density * 2.5)) * 0.96;
    outColor = vec4(color, opticalOpacity);
}
