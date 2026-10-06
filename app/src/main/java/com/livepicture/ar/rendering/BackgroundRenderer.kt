package com.livepicture.ar.rendering

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame

/** نمایش تصویر زندهٔ دوربین به‌عنوان پس‌زمینه. */
class BackgroundRenderer {

    var textureId = -1
        private set

    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexture = 0

    private val ndcQuad = ShaderUtil.floatBuffer(
        floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    )
    private val texCoords = ShaderUtil.floatBuffer(8)

    fun createOnGlThread() {
        textureId = ShaderUtil.createOesTexture()
        program = ShaderUtil.createProgram(VERTEX, FRAGMENT)
        aPosition = GLES20.glGetAttribLocation(program, "a_Position")
        aTexCoord = GLES20.glGetAttribLocation(program, "a_TexCoord")
        uTexture = GLES20.glGetUniformLocation(program, "u_Texture")
    }

    fun draw(frame: Frame) {
        if (frame.hasDisplayGeometryChanged()) {
            ndcQuad.position(0)
            texCoords.position(0)
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, ndcQuad,
                Coordinates2d.TEXTURE_NORMALIZED, texCoords
            )
        }
        if (frame.timestamp == 0L) return

        ndcQuad.position(0)
        texCoords.position(0)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(uTexture, 0)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, ndcQuad)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
        GLES20.glDepthMask(true)
    }

    companion object {
        private const val VERTEX = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """
        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES u_Texture;
            void main() {
                gl_FragColor = texture2D(u_Texture, v_TexCoord);
            }
        """
    }
}
