#version 300 es
/*
 * Copyright 2017 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;

uniform samplerExternalOES u_CameraColorTexture;
/* 1.0 = no zoom, >1.0 zooms in (expects BackgroundRenderer to set it each frame) */
uniform float u_Zoom;

in vec2 v_CameraTexCoord;

layout(location = 0) out vec4 o_FragColor;

void main() {
  // Scale UVs about the center so the view "zooms" without stretching.
  vec2 center = vec2(0.5, 0.5);
  float z = max(u_Zoom, 1.0);         // clamp at runtime, but guard here too
  vec2 uv = (v_CameraTexCoord - center) / z + center;

  o_FragColor = texture(u_CameraColorTexture, uv);
}
