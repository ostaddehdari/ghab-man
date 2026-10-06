package com.livepicture.ar.rendering

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix

/**
 * یک مستطیل دقیقاً هم‌اندازهٔ تصویر ردیابی‌شده می‌کشد و فریم ویدیو را روی آن می‌گذارد.
 * دستگاه مختصات تصویر در ARCore: محور X به راست تصویر، Z به پایین تصویر، Y عمود بر سطح.
 */
class VideoQuadRenderer {

    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uMvp = 0
    private var uSt = 0
    private var uTexture = 0
    private var uAlpha = 0

    // ترتیب TRIANGLE_STRIP: بالا-چپ، پایین-چپ، بالا-راست، پایین-راست (مستطیل واحد در صفحهٔ XZ)
    private val vertices = ShaderUtil.floatBuffer(
        floatArrayOf(
            -0.5f, 0f, -0.5f,
            -0.5f, 0f, 0.5f,
            0.5f, 0f, -0.5f,
            0.5f, 0f, 0.5f,
        )
    )
    // مختصات بافت به قرارداد OpenGL (v=1 بالای تصویر)؛ سپس در ماتریس SurfaceTexture ضرب می‌شود
    private val uvs = ShaderUtil.floatBuffer(
        floatArrayOf(
            0f, 1f, 0f, 1f,
            0f, 0f, 0f, 1f,
            1f, 1f, 0f, 1f,
            1f, 0f, 0f, 1f,
        )
    )

    private val scaledModel = FloatArray(16)
    private val modelView = FloatArray(16)
    private val mvp = FloatArray(16)

    fun createOnGlThread() {
        program = ShaderUtil.createProgram(VERTEX, FRAGMENT)
        aPosition = GLES20.glGetAttribLocation(program, "a_Position")
        aTexCoord = GLES20.glGetAttribLocation(program, "a_TexCoord")
        uMvp = GLES20.glGetUniformLocation(program, "u_Mvp")
        uSt = GLES20.glGetUniformLocation(program, "u_StMatrix")
        uTexture = GLES20.glGetUniformLocation(program, "u_Texture")
        uAlpha = GLES20.glGetUniformLocation(program, "u_Alpha")
    }

    fun draw(
        model: FloatArray, view: FloatArray, projection: FloatArray,
        width: Float, height: Float,
        stMatrix: FloatArray, textureId: Int, alpha: Float,
    ) {
        Matrix.scaleM(scaledModel, 0, model, 0, width, 1f, height)
        Matrix.multiplyMM(modelView, 0, view, 0, scaledModel, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)

        vertices.position(0)
        uvs.position(0)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(uTexture, 0)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uSt, 1, false, stMatrix, 0)
        GLES20.glUniform1f(uAlpha, alpha)

        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glVertexAttribPointer(aTexCoord, 4, GLES20.GL_FLOAT, false, 0, uvs)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)

        GLES20.glDisable(GLES20.GL_BLEND)
    }

    companion object {
        private const val VERTEX = """
            uniform mat4 u_Mvp;
            uniform mat4 u_StMatrix;
            attribute vec4 a_Position;
            attribute vec4 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = u_Mvp * a_Position;
                v_TexCoord = (u_StMatrix * a_TexCoord).xy;
            }
        """
        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES u_Texture;
            uniform float u_Alpha;
            void main() {
                vec4 c = texture2D(u_Texture, v_TexCoord);
                gl_FragColor = vec4(c.rgb, c.a * u_Alpha);
            }
        """
    }
}
