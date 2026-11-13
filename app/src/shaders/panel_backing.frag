#version 450
#extension GL_ARB_separate_shader_objects : enable
#extension GL_ARB_shading_language_420pack : enable
layout(std140, set = 3, binding = 0) uniform PanelUniform { vec4 corner; } panelUniform;
layout(location = 0) in vec2 panelUv;
layout(location = 0) out vec4 outColor;
void main() {
    vec2 edge = min(panelUv, 1.0 - panelUv);
    vec2 corner = max(vec2(0.0), (panelUniform.corner.xy - edge) / panelUniform.corner.xy);
    if (dot(corner, corner) > 1.0) discard;
    outColor = vec4(0.006, 0.008, 0.012, 1.0);
}
