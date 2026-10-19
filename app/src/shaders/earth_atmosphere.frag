#version 450
#extension GL_ARB_separate_shader_objects : enable
#extension GL_ARB_shading_language_420pack : enable
#include "earth_fragment.glsl"

void main() {
    vec3 normal = normalize(earth.localNormal);
    float elevation = dot(normal, localSun());
    float viewCos = clamp(dot(normal, localView()), 0.0, 1.0);
    // Finite shell chord: concentrates scattering near the surface's horizon instead of a painted rim.
    float shellRadius = length(earth.localPosition);
    float impact = shellRadius * sqrt(max(0.0, 1.0 - viewCos * viewCos));
    float outerChord = sqrt(max(0.0, shellRadius * shellRadius - impact * impact));
    float innerChord = sqrt(max(0.0, 1.0 - impact * impact));
    float path = max(0.0, outerChord - innerChord);
    float altitude = max(0.0, impact - 1.0);
    float opticalDepth = path * exp(-altitude / 0.003) * 3.2;
    float twilight = exp(-abs(elevation) * 20.0);
    float rayleighPhase = 0.75 * (1.0 + pow(dot(localView(), localSun()), 2.0));
    vec3 color = mix(vec3(0.09, 0.30, 0.72), vec3(0.75, 0.17, 0.035), twilight * 0.8) * rayleighPhase;
    float alpha = (1.0 - exp(-opticalDepth)) * (0.025 + 0.975 * smoothstep(-0.16, 0.10, elevation));
    outColor = vec4(color, alpha);
}
