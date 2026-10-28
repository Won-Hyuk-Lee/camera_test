package com.example.camera2study.util

import android.graphics.SurfaceTexture
import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.camera.core.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor

/** One SDR transform shared by the preview and encoder, including headless recording. */
class VideoLookProcessor(private val failure: (Throwable) -> Unit) : SurfaceProcessor {
    private val thread = HandlerThread("VideoLook").apply { start() }
    private val handler = Handler(thread.looper)
    val executor = Executor { handler.post(it) }
    @Volatile var look = VideoLook()
    private var closing = false
    private var inputs = 0
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer = EGL14.EGL_NO_SURFACE
    private var program = 0
    private val outputs = linkedMapOf<SurfaceOutput, EGLSurface>()
    private val vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f,-1f,0f,0f, 1f,-1f,1f,0f, -1f,1f,0f,1f, 1f,1f,1f,1f)); position(0)
    }
    val effect: CameraEffect = object : CameraEffect(CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE, executor, this, androidx.core.util.Consumer { failure(it) }) {}

    private fun initialize() {
        if (display != EGL14.EGL_NO_DISPLAY) return
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        val configs = arrayOfNulls<EGLConfig>(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,
            0x3142,1,EGL14.EGL_NONE),0,configs,0,1,IntArray(1),0))
        config = checkNotNull(configs[0])
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
        check(context != EGL14.EGL_NO_CONTEXT)
        pbuffer = EGL14.eglCreatePbufferSurface(display,config,intArrayOf(EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE),0)
        current(pbuffer)
        fun shader(type: Int, source: String): Int {
            val id = GLES20.glCreateShader(type); GLES20.glShaderSource(id,source); GLES20.glCompileShader(id)
            val ok = IntArray(1); GLES20.glGetShaderiv(id,GLES20.GL_COMPILE_STATUS,ok,0)
            check(ok[0] != 0) { GLES20.glGetShaderInfoLog(id) }; return id
        }
        val vs = shader(GLES20.GL_VERTEX_SHADER, "attribute vec2 position; attribute vec2 texcoord; uniform mat4 transform; varying vec2 uv; void main(){gl_Position=vec4(position,0.,1.); uv=(transform*vec4(texcoord,0.,1.)).xy;}")
        val ps = shader(GLES20.GL_FRAGMENT_SHADER, """#extension GL_OES_EGL_image_external : require
precision highp float; uniform samplerExternalOES image; varying vec2 uv; uniform vec3 gain; uniform vec4 tone; void main(){vec3 src=texture2D(image,uv).rgb; vec3 c=(pow(clamp(src,0.,1.),vec3(tone.z))+tone.w*(1.-src))*gain; float y=dot(c,vec3(.2126,.7152,.0722)); c=mix(vec3(y),c,tone.y); c=(c-.5)*tone.x+.5; gl_FragColor=vec4(clamp(c,0.,1.),1.);}""")
        program = GLES20.glCreateProgram(); GLES20.glAttachShader(program,vs); GLES20.glAttachShader(program,ps); GLES20.glLinkProgram(program)
        val ok = IntArray(1); GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,ok,0)
        check(ok[0] != 0) { GLES20.glGetProgramInfoLog(program) }
        GLES20.glDeleteShader(vs); GLES20.glDeleteShader(ps)
    }
    private fun current(surface: EGLSurface) { check(EGL14.eglMakeCurrent(display,surface,surface,context)) }
    override fun onInputSurface(request: SurfaceRequest) {
        if (closing) { request.willNotProvideSurface(); return }
        try {
            initialize(); current(pbuffer)
            val texture = IntArray(1); GLES20.glGenTextures(1,texture,0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
            val input = SurfaceTexture(texture[0])
            input.setDefaultBufferSize(request.resolution.width,request.resolution.height)
            val surface = Surface(input); inputs++
            input.setOnFrameAvailableListener({ if (!closing) runCatching { draw(input,texture[0]) }.onFailure(failure) },handler)
            request.provideSurface(surface,executor) {
                input.setOnFrameAvailableListener(null); surface.release(); input.release()
                current(pbuffer); GLES20.glDeleteTextures(1,texture,0); inputs--; finishClose()
            }
        } catch (e: Exception) { request.willNotProvideSurface(); failure(e) }
    }
    override fun onOutputSurface(output: SurfaceOutput) {
        if (closing) { output.close(); return }
        try {
            initialize()
            val surface = output.getSurface(executor) {
                outputs.remove(output)?.let { egl -> EGL14.eglDestroySurface(display,egl) }
                output.close(); finishClose()
            }
            val egl = EGL14.eglCreateWindowSurface(display,config,surface,intArrayOf(EGL14.EGL_NONE),0)
            check(egl != EGL14.EGL_NO_SURFACE); outputs[output] = egl
        } catch (e: Exception) { output.close(); failure(e) }
    }
    private fun draw(input: SurfaceTexture, texture: Int) {
        current(pbuffer); input.updateTexImage()
        val original = FloatArray(16); input.getTransformMatrix(original)
        val params = look.parameters()
        for ((output,surface) in outputs.toMap()) {
            current(surface)
            GLES20.glViewport(0,0,output.size.width,output.size.height)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"image"),0)
            val matrix = FloatArray(16); output.updateTransformMatrix(matrix,original)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"transform"),1,false,matrix,0)
            GLES20.glUniform3f(GLES20.glGetUniformLocation(program,"gain"),params.red,params.green,params.blue)
            GLES20.glUniform4f(GLES20.glGetUniformLocation(program,"tone"),params.contrast,params.saturation,params.gamma,params.shadow)
            val pos = GLES20.glGetAttribLocation(program,"position")
            val uv = GLES20.glGetAttribLocation(program,"texcoord")
            vertices.position(0); GLES20.glEnableVertexAttribArray(pos); GLES20.glVertexAttribPointer(pos,2,GLES20.GL_FLOAT,false,16,vertices)
            vertices.position(2); GLES20.glEnableVertexAttribArray(uv); GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,16,vertices)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "영상 효과 GPU 처리 실패" }
            EGLExt.eglPresentationTimeANDROID(display,surface,input.timestamp)
            check(EGL14.eglSwapBuffers(display,surface)) { "영상 효과 출력 실패" }
        }
    }
    fun close() { executor.execute { closing = true; outputs.keys.toList().forEach { it.close() }; finishClose() } }
    private fun finishClose() {
        if (!closing || inputs > 0) return
        if (display != EGL14.EGL_NO_DISPLAY) {
            outputs.values.forEach { EGL14.eglDestroySurface(display,it) }; outputs.clear()
            current(pbuffer); GLES20.glDeleteProgram(program)
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display,pbuffer); EGL14.eglDestroyContext(display,context); EGL14.eglTerminate(display)
            display = EGL14.EGL_NO_DISPLAY
        }
        thread.quitSafely()
    }
}
