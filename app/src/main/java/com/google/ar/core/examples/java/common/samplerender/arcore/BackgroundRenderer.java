/*
 * Copyright 2020 Google LLC
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
package com.google.ar.core.examples.java.common.samplerender.arcore;

import android.media.Image;
import android.opengl.GLES30;

import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import com.google.ar.core.examples.java.common.samplerender.Framebuffer;
import com.google.ar.core.examples.java.common.samplerender.Mesh;
import com.google.ar.core.examples.java.common.samplerender.SampleRender;
import com.google.ar.core.examples.java.common.samplerender.Shader;
import com.google.ar.core.examples.java.common.samplerender.Texture;
import com.google.ar.core.examples.java.common.samplerender.VertexBuffer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;

/**
 * Renders the AR camera background and composites the virtual scene.
 * Background can be camera image or depth visualization; virtual scene can enable depth occlusion.
 * Adds a simple digital zoom for the live camera feed (uniform u_Zoom in shaders).
 */
public class BackgroundRenderer {
  private static final String TAG = BackgroundRenderer.class.getSimpleName();

  // components_per_vertex * number_of_vertices * float_size
  private static final int COORDS_BUFFER_SIZE = 2 * 4 * 4;

  private static final FloatBuffer NDC_QUAD_COORDS_BUFFER =
          ByteBuffer.allocateDirect(COORDS_BUFFER_SIZE).order(ByteOrder.nativeOrder()).asFloatBuffer();

  private static final FloatBuffer VIRTUAL_SCENE_TEX_COORDS_BUFFER =
          ByteBuffer.allocateDirect(COORDS_BUFFER_SIZE).order(ByteOrder.nativeOrder()).asFloatBuffer();

  static {
    NDC_QUAD_COORDS_BUFFER.put(new float[] {
            /*0:*/ -1f, -1f, /*1:*/ +1f, -1f, /*2:*/ -1f, +1f, /*3:*/ +1f, +1f,
    });
    VIRTUAL_SCENE_TEX_COORDS_BUFFER.put(new float[] {
            /*0:*/ 0f, 0f, /*1:*/ 1f, 0f, /*2:*/ 0f, 1f, /*3:*/ 1f, 1f,
    });
  }

  private final FloatBuffer cameraTexCoords =
          ByteBuffer.allocateDirect(COORDS_BUFFER_SIZE).order(ByteOrder.nativeOrder()).asFloatBuffer();

  private final Mesh mesh;
  private final VertexBuffer cameraTexCoordsVertexBuffer;
  private Shader backgroundShader;
  private Shader occlusionShader;
  private final Texture cameraDepthTexture;
  private final Texture cameraColorTexture;
  private Texture depthColorPaletteTexture;

  private boolean useDepthVisualization;
  private boolean useOcclusion;
  private float aspectRatio;

  // === Digital zoom for the live camera background (1.0 = no zoom) ===
  private float zoom = 1.0f; // Clamp 1..4 to avoid over-stretching
  public void setZoom(float z) {
    zoom = Math.max(1.0f, Math.min(z, 4.0f));
  }

  /** Allocates GL resources. Call from GL thread, typically in onSurfaceCreated(). */
  public BackgroundRenderer(SampleRender render) {
    cameraColorTexture =
            new Texture(render, Texture.Target.TEXTURE_EXTERNAL_OES, Texture.WrapMode.CLAMP_TO_EDGE, false);
    cameraDepthTexture =
            new Texture(render, Texture.Target.TEXTURE_2D, Texture.WrapMode.CLAMP_TO_EDGE, false);

    // Mesh with: screen NDC coords, camera UVs, virtual-scene UVs
    VertexBuffer screenCoordsVertexBuffer =
            new VertexBuffer(render, 2, NDC_QUAD_COORDS_BUFFER);
    cameraTexCoordsVertexBuffer =
            new VertexBuffer(render, 2, /*entries=*/ null);
    VertexBuffer virtualSceneTexCoordsVertexBuffer =
            new VertexBuffer(render, 2, VIRTUAL_SCENE_TEX_COORDS_BUFFER);
    VertexBuffer[] vertexBuffers = {
            screenCoordsVertexBuffer, cameraTexCoordsVertexBuffer, virtualSceneTexCoordsVertexBuffer,
    };
    mesh = new Mesh(render, Mesh.PrimitiveMode.TRIANGLE_STRIP, null, vertexBuffers);
  }

  /** Toggle depth visualization as the background (instead of the camera feed). */
  public void setUseDepthVisualization(SampleRender render, boolean useDepthVisualization)
          throws IOException {
    if (backgroundShader != null) {
      if (this.useDepthVisualization == useDepthVisualization) return;
      backgroundShader.close();
      backgroundShader = null;
    }
    this.useDepthVisualization = useDepthVisualization;

    if (useDepthVisualization) {
      depthColorPaletteTexture =
              Texture.createFromAsset(
                      render,
                      "models/depth_color_palette.png",
                      Texture.WrapMode.CLAMP_TO_EDGE,
                      Texture.ColorFormat.LINEAR);
      backgroundShader =
              Shader.createFromAssets(
                              render,
                              "shaders/background_show_depth_color_visualization.vert",
                              "shaders/background_show_depth_color_visualization.frag",
                              null)
                      .setTexture("u_CameraDepthTexture", cameraDepthTexture)
                      .setTexture("u_ColorMap", depthColorPaletteTexture)
                      .setDepthTest(false)
                      .setDepthWrite(false);
    } else {
      backgroundShader =
              Shader.createFromAssets(
                              render,
                              "shaders/background_show_camera.vert",
                              "shaders/background_show_camera.frag",
                              null)
                      .setTexture("u_CameraColorTexture", cameraColorTexture)
                      .setDepthTest(false)
                      .setDepthWrite(false);
    }
  }

  /** Toggle depth occlusion in the compositor. Rebuilds the shader with appropriate defines. */
  public void setUseOcclusion(SampleRender render, boolean useOcclusion) throws IOException {
    if (occlusionShader != null) {
      if (this.useOcclusion == useOcclusion) return;
      occlusionShader.close();
      occlusionShader = null;
    }
    this.useOcclusion = useOcclusion;

    HashMap<String, String> defines = new HashMap<>();
    defines.put("USE_OCCLUSION", useOcclusion ? "1" : "0");

    // Build compositor program.
    occlusionShader =
            Shader.createFromAssets(render, "shaders/occlusion.vert", "shaders/occlusion.frag", defines)
                    .setDepthTest(false)
                    .setDepthWrite(false)
                    .setBlend(Shader.BlendFactor.SRC_ALPHA, Shader.BlendFactor.ONE_MINUS_SRC_ALPHA);

    // Always provide the camera color feed to the compositor (used by occlusion.frag).
    occlusionShader.setTexture("u_CameraColorTexture", cameraColorTexture);

    if (useOcclusion) {
      occlusionShader
              .setTexture("u_CameraDepthTexture", cameraDepthTexture)
              .setFloat("u_DepthAspectRatio", aspectRatio);
    }

    android.util.Log.i(TAG,
            "setUseOcclusion done. useOcclusion=" + useOcclusion + " occlusionShader=" + (occlusionShader != null));
  }

  /** Must be called every frame before draw calls to refresh camera UVs when display geometry changes. */
  public void updateDisplayGeometry(Frame frame) {
    if (frame.hasDisplayGeometryChanged()) {
      frame.transformCoordinates2d(
              Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
              NDC_QUAD_COORDS_BUFFER,
              Coordinates2d.TEXTURE_NORMALIZED,
              cameraTexCoords);
      cameraTexCoordsVertexBuffer.set(cameraTexCoords);
    }
  }

  /** Update depth texture with Image contents. */
  public void updateCameraDepthTexture(Image image) {
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cameraDepthTexture.getTextureId());
    GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RG8,
            image.getWidth(),
            image.getHeight(),
            0,
            GLES30.GL_RG,
            GLES30.GL_UNSIGNED_BYTE,
            image.getPlanes()[0].getBuffer());
    if (useOcclusion && occlusionShader != null) {
      aspectRatio = (float) image.getWidth() / (float) image.getHeight();
      occlusionShader.setFloat("u_DepthAspectRatio", aspectRatio);
    }
  }

  /**
   * Draws the AR background image (camera or depth viz).
   * Virtual content should then be drawn using the camera's view/projection matrices.
   */
  public void drawBackground(SampleRender render) {
    if (backgroundShader == null) return;
    // Pass zoom uniform each frame. If shader doesn't define it (e.g., depth viz), ignore.
    try { backgroundShader.setFloat("u_Zoom", zoom); } catch (Throwable ignored) {}
    render.draw(mesh, backgroundShader);
  }

  /** Composites the virtual scene over the camera background. Honors occlusion when enabled. */
  public void drawVirtualScene(
          SampleRender render, Framebuffer virtualSceneFramebuffer, float zNear, float zFar) {
    if (occlusionShader == null) return;

    occlusionShader.setTexture("u_VirtualSceneColorTexture", virtualSceneFramebuffer.getColorTexture());

    if (useOcclusion) {
      occlusionShader
              .setTexture("u_VirtualSceneDepthTexture", virtualSceneFramebuffer.getDepthTexture())
              .setFloat("u_ZNear", zNear)
              .setFloat("u_ZFar", zFar);
    }

    // Zoom also affects the camera feed sampled inside the compositor.
    try { occlusionShader.setFloat("u_Zoom", zoom); } catch (Throwable ignored) {}

    render.draw(mesh, occlusionShader);
  }

  /** @return camera OES texture (for Session#setCameraTextureNames) */
  public Texture getCameraColorTexture() {
    return cameraColorTexture;
  }

  /** @return camera depth texture */
  public Texture getCameraDepthTexture() {
    return cameraDepthTexture;
  }

  public boolean isVirtualSceneInitialized() {
    return occlusionShader != null && cameraColorTexture != null;
  }
}
