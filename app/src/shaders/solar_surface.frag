#version 450
#extension GL_ARB_separate_shader_objects : enable
#extension GL_ARB_shading_language_420pack : enable
#include "solar_fragment.glsl"
layout(set = 3, binding = 1) uniform sampler2D colorMap;
layout(set = 3, binding = 2) uniform sampler2D ringMap;
layout(set = 3, binding = 3) uniform sampler2D normalMap;

vec3 surfaceNormal(vec3 normal) {
    if (solarUniform.surfaceParameters.z == 0.0) return normal;
    // SceneTexture is sRGB, even for data: undo its automatic colour decode.
    vec3 bump = linear_to_srgb(texture(normalMap, solar.uv).rgb) * 2.0 - 1.0;
    bump.xy *= solarUniform.surfaceParameters.z;
    bump.y = -bump.y; // OpenGL north-facing +Y versus glTF's south-facing +V.
    vec3 px = dFdx(solar.localPosition), py = dFdy(solar.localPosition);
    vec2 tx = dFdx(solar.uv), ty = dFdy(solar.uv);
    float determinant = tx.x * ty.y - tx.y * ty.x;
    if (abs(determinant) < 1e-10) return normal;
    vec3 t = (px * ty.y - py * tx.y) / determinant;
    vec3 b = (py * tx.x - px * ty.x) / determinant;
    t -= normal * dot(t, normal);
    b -= normal * dot(b, normal);
    if (dot(t, t) < 1e-10 || dot(b, b) < 1e-10) return normal;
    return normalize(mat3(normalize(t), normalize(b), normal) * normalize(bump));
}

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
    vec3 view = localView();
    vec3 reliefNormal = surfaceNormal(normal);
    float incidence = max(dot(reliefNormal, sun), 0.0);
    float emission = max(dot(reliefNormal, view), 0.0);
    // Bounded Lommel-Seeliger/Lambert blend for particulate rock and lunar dust.
    // Cloud decks use a restrained Minnaert limb response instead of the same rock BRDF.
    float particulate = 2.0 * incidence / max(incidence + emission, 0.08);
    float cloud = incidence;
    if (solarUniform.surfaceParameters.y != 1.0) {
        cloud *= pow(max(incidence * emission, 0.01), solarUniform.surfaceParameters.y - 1.0);
    }
    float diffuse = mix(cloud, particulate, solarUniform.surfaceParameters.x);
    // A tilted terrain normal cannot receive sunlight through the solid globe.
    float horizon = smoothstep(-0.006, 0.006, dot(normal, sun));
    float transmission = ringShadow(sun);
    // Very dark reflected starlight keeps a faint night silhouette without creating a second light.
    float rim = 1.0 - clamp(dot(normal, view), 0.0, 1.0);
    float haze = solarUniform.surfaceParameters.w * rim * rim * rim * incidence;
    vec3 hazeColor = mix(color, vec3(dot(color, vec3(0.2126, 0.7152, 0.0722))), 0.35);
    color = color * (0.002 + 1.05 * diffuse * horizon * transmission)
        + hazeColor * haze * horizon * transmission;
    outColor = vec4(color, 1.0);
}
