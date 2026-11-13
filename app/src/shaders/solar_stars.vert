#version 450
#extension GL_ARB_separate_shader_objects : enable
#extension GL_ARB_shading_language_420pack : enable
#include <data/shaders/common.glsl>
#include <data/shaders/app2vertex.glsl>
layout(location = 0) out SolarVertex {
    vec2 uv;
    vec3 localNormal;
    vec3 localPosition;
} solar;
void main() {
    App2VertexUnpacked app = getApp2VertexUnpacked();
    solar.uv = app.uv;
    solar.localNormal = app.normal;
    solar.localPosition = app.position;
    // Retain celestial rotation and radius, but follow the observer's translation.
    // Stars remain at optical infinity throughout Earth exploration and recentering.
    vec3 direction = mat3(g_PrimitiveUniform.worldFromObject) * app.position;
    gl_Position = getClipFromWorld() * vec4(getEyeCenter() + direction, 1.0);
    postprocessPosition(gl_Position);
}
