#include <data/shaders/common.glsl>
#include <data/shaders/app2vertex.glsl>

layout(location = 0) out EarthVertex {
    vec2 uv;
    vec3 localNormal;
    vec3 localPosition;
} earth;

void main() {
    App2VertexUnpacked app = getApp2VertexUnpacked();
    vec4 world = g_PrimitiveUniform.worldFromObject * vec4(app.position, 1.0);
    earth.uv = app.uv;
    earth.localNormal = app.normal;
    earth.localPosition = app.position;
    gl_Position = getClipFromWorld() * world;
    postprocessPosition(gl_Position);
}
