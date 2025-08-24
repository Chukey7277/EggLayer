uniform mat4 u_ModelViewProjection;

attribute vec4 a_Position;

void main() {
    gl_Position = u_ModelViewProjection * a_Position;
}
