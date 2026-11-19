package de.synvyropic.app.render

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ShaderException(message: String) : RuntimeException(message)

/** Kompiliertes Shader-Programm mit Uniform-Cache. */
class GlProgram(vertex: String, fragment: String, name: String) {
    val id: Int
    private val locations = HashMap<String, Int>()

    init {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertex, "$name/vertex")
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragment, "$name/fragment")
        id = GLES30.glCreateProgram()
        GLES30.glAttachShader(id, vs)
        GLES30.glAttachShader(id, fs)
        GLES30.glLinkProgram(id)
        val status = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, status, 0)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(id)
            GLES30.glDeleteProgram(id)
            throw ShaderException("Shader $name: Linken fehlgeschlagen: $log")
        }
    }

    fun use() = GLES30.glUseProgram(id)

    fun loc(name: String): Int = locations.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }

    fun set1i(name: String, v: Int) = GLES30.glUniform1i(loc(name), v)
    fun set1f(name: String, v: Float) = GLES30.glUniform1f(loc(name), v)
    fun set2f(name: String, x: Float, y: Float) = GLES30.glUniform2f(loc(name), x, y)
    fun set3f(name: String, x: Float, y: Float, z: Float) = GLES30.glUniform3f(loc(name), x, y, z)

    fun release() = GLES30.glDeleteProgram(id)

    private fun compile(type: Int, src: String, label: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val status = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(s)
            GLES30.glDeleteShader(s)
            throw ShaderException("Shader $label: Kompilieren fehlgeschlagen: $log")
        }
        return s
    }
}

/** Framebuffer mit RGBA-Textur. */
class Fbo(val width: Int, val height: Int) {
    val texture: Int
    val framebuffer: Int

    init {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        texture = t[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        // Gespiegelte Wiederholung: Zoom-out/Pans zeigen am Rand keine harten Kanten
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_MIRRORED_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_MIRRORED_REPEAT)
        val f = IntArray(1)
        GLES30.glGenFramebuffers(1, f, 0)
        framebuffer = f[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    fun bind() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glViewport(0, 0, width, height)
    }

    fun release() {
        GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
    }
}

/** Vollbild-Rechteck als Triangle-Strip. */
class FullscreenQuad {
    private val vbo: Int
    private val vao: Int

    init {
        val data = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val buf = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data)
        buf.position(0)
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        vao = ids[0]
        GLES30.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, buf, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glBindVertexArray(0)
    }

    fun draw() {
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
        GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
    }
}

/** Bild-Textur mit Abmessungen. */
class ImageTexture(val id: Int, val width: Int, val height: Int) {
    companion object {
        fun upload(bitmap: Bitmap): ImageTexture {
            val t = IntArray(1)
            GLES30.glGenTextures(1, t, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            return ImageTexture(t[0], bitmap.width, bitmap.height)
        }

        fun solid(argb: Int): ImageTexture {
            val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            bmp.setPixel(0, 0, argb)
            return upload(bmp).also { bmp.recycle() }
        }
    }

    fun release() = GLES30.glDeleteTextures(1, intArrayOf(id), 0)
}
