#include <data/shaders/common.glsl>

layout(std140, set = 3, binding = 0) uniform EarthUniform {
    vec4 sunPosition;
} earthUniform;

layout(location = 0) in EarthVertex {
    vec2 uv;
    vec3 localNormal;
    vec3 localPosition;
} earth;
layout(location = 0) out vec4 outColor;

vec3 localView() {
    vec3 eye = (g_PrimitiveUniform.objectFromWorld * vec4(getEyeCenter(), 1.0)).xyz;
    return normalize(eye - earth.localPosition);
}

float dayWeight(float elevation) { return smoothstep(-0.08, 0.08, elevation); }

// SDK 0.14 GraphicsHSR SceneTextureCreateTexture uses VK_FORMAT_R8G8B8A8_SRGB.
// Colour samplers therefore already return linear RGB. Data maps need the inverse
// conversion to recover their authored numeric normal/mask values; alpha is unchanged.
vec3 decodeData(vec3 sampleValue) { return linear_to_srgb(sampleValue); }

vec3 localSun() {
    // Use the renderer's actual imported mesh transform, including glTF handedness and node transforms.
    vec3 sun = (g_PrimitiveUniform.objectFromWorld * earthUniform.sunPosition).xyz;
    return normalize(sun - earth.localPosition);
}
