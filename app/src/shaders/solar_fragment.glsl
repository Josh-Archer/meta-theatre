#include <data/shaders/common.glsl>
layout(std140, set = 3, binding = 0) uniform SolarUniform {
    vec4 sunPosition; // Shared Sun in GraphicsHSR shader world coordinates.
    vec4 parameters; // Emission, Saturn flag, ring inner radius, ring outer radius.
} solarUniform;
layout(location = 0) in SolarVertex {
    vec2 uv;
    vec3 localNormal;
    vec3 localPosition;
} solar;
layout(location = 0) out vec4 outColor;
vec3 localSun() {
    vec3 sun = (g_PrimitiveUniform.objectFromWorld * solarUniform.sunPosition).xyz;
    return normalize(sun - solar.localPosition);
}
vec3 localView() {
    vec3 eye = (g_PrimitiveUniform.objectFromWorld * vec4(getEyeCenter(), 1.0)).xyz;
    return normalize(eye - solar.localPosition);
}
