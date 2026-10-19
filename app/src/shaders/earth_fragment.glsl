#include <data/shaders/common.glsl>

layout(std140, set = 3, binding = 0) uniform EarthUniform {
    vec4 sunPosition;
    vec4 cloudStyle;
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

// Derivative frame respects imported UV orientation and remains finite at the polar caps.
mat3 earthTangentFrame(vec3 normal) {
    vec3 px = dFdx(earth.localPosition), py = dFdy(earth.localPosition);
    vec2 tx = dFdx(earth.uv), ty = dFdy(earth.uv);
    float determinant = tx.x * ty.y - tx.y * ty.x;
    vec3 fallback = abs(normal.y) < 0.95 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    vec3 tangent = normalize(cross(fallback, normal));
    vec3 bitangent = cross(normal, tangent);
    if (abs(determinant) > 1e-10) {
        vec3 t = (px * ty.y - py * tx.y) / determinant;
        vec3 b = (py * tx.x - px * ty.x) / determinant;
        t -= normal * dot(t, normal);
        b -= normal * dot(b, normal);
        if (dot(t, t) > 1e-10 && dot(b, b) > 1e-10) {
            tangent = normalize(t); bitangent = normalize(b);
        }
    }
    return mat3(tangent, bitangent, normal);
}

vec2 cloudSunOffset(vec3 sun, mat3 frame, float elevation) {
    float latitudeScale = max(0.12, sqrt(max(0.0, 1.0 - frame[2].y * frame[2].y)));
    return vec2(dot(sun, frame[0]) / (6.2831853 * latitudeScale), dot(sun, frame[1]) / 3.1415927)
        * 0.0035 / max(0.14, elevation);
}

// Live maps encode cloud height, convection and low-cloud fraction in RGB.
// The original map remains an alpha-only source, with a middle-altitude deck.
vec3 cloudProfile(vec4 sampleValue) {
    return mix(vec3(0.5, 0.0, 0.0), decodeData(sampleValue.rgb), earthUniform.cloudStyle.x);
}
