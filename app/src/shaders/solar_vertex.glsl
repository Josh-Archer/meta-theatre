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
    gl_Position = getClipFromWorld() * g_PrimitiveUniform.worldFromObject * vec4(app.position, 1.0);
    postprocessPosition(gl_Position);
}
