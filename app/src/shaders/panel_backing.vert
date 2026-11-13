#version 450
#extension GL_ARB_separate_shader_objects : enable
#extension GL_ARB_shading_language_420pack : enable
#include <data/shaders/common.glsl>
#include <data/shaders/app2vertex.glsl>
layout(location = 0) out vec2 panelUv;
void main() {
    App2VertexUnpacked app = getApp2VertexUnpacked();
    panelUv = app.uv;
    gl_Position = getClipFromWorld() * g_PrimitiveUniform.worldFromObject * vec4(app.position, 1.0);
    postprocessPosition(gl_Position);
}
