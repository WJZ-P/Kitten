package me.wjz.kitten.privilege.server

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * 有人在看副屏时的画面管线：副屏画进这里的 [SurfaceTexture]，GL 线程每来一帧就把它画到预览的 Surface
 * 上（App 里 TextureView 交过来的）。按那边的大小画：缩略图就画一张小图，全屏就画整张，缩放是 GPU 在
 * 画的时候顺手做的，不经过 CPU。
 *
 * ## 为什么只在有人看时才有
 *
 * 没人看时副屏直接画进 ImageReader（见 [VirtualDisplayHost]），和以前一模一样，没有任何额外开销；
 * 后台的任务、以后的定时任务都走那条路。有人看时才把副屏的输出切到这里，多出来的只有往预览上画的
 * 那一份。
 *
 * ## 有人看的时候怎么截图
 *
 * 这时副屏不往 ImageReader 写了。截图从这里拿：把手里最新那一帧按截图要的大小画进一块离屏缓冲（FBO），
 * 读回来就是一张图（[capture]）。只在截图那一下画一次，平时不往 ImageReader 里同步。
 *
 * ## 换进来、换出去的那一下
 *
 * - 换进来时，页面要是静止的，系统未必马上给一帧新画面，预览会黑着。所以拿 ImageReader 手里最新的那
 *   一帧当开场画面（[start] 的 seed），新帧一来就盖掉。
 * - 换出去之前，把手里最新的一帧写回 ImageReader（[writeTo]）。不然切回去之后页面不动的话，ImageReader
 *   手里还是换进来之前那张旧画面，模型会截到过时的界面。
 *
 * ## 线程
 *
 * GL 上下文绑在自己的线程上，所有 GL 调用都在这条线程里做；别的线程调进来的方法把活扔过去、等它做完
 * （[onGl]）。
 *
 * @param width 副屏的宽，像素。
 * @param height 副屏的高，像素。
 */
internal class PreviewPipeline(private val width: Int, private val height: Int) {

    private val thread = HandlerThread("KittenPreviewGL").apply { start() }
    private val handler = Handler(thread.looper)

    // ---- 下面这些只在 GL 线程上碰 ----

    private var egl: Egl? = null
    private var oesProgram = 0
    private var textureProgram = 0
    private var frameTexture = 0
    private var seedTexture = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null

    /** 预览画到哪：App 交过来的 Surface，和在它上面建的 EGL surface。 */
    private var output: EGLSurface? = null
    private var outputWindow: Surface? = null

    /** 最新一帧在纹理里的坐标变换，[SurfaceTexture.getTransformMatrix] 给的。 */
    private val frameTransform = FloatArray(16)
    private var hasFrame = false
    private var hasSeed = false

    /** 截图用的离屏缓冲，按尺寸复用。 */
    private var fbo = 0
    private var fboTexture = 0
    private var fboWidth = 0
    private var fboHeight = 0

    /**
     * 建好 GL 上下文和输入的 Surface，返回副屏该画进去的那个 Surface；建不起来返回 null。
     *
     * @param seed 开场画面：ImageReader 手里最新的那一帧，见类注释。没有就是 null。
     */
    fun start(seed: Bitmap?): Surface? = onGl { setUp(seed) }

    /**
     * 预览画到 [surface] 上；传 null 不画了。换一个 surface（缩略图 → 全屏）时先收掉旧的。换上去立刻把手里
     * 最新的一帧画上去，页面静止时也不用等新帧。
     */
    fun setOutput(surface: Surface?) {
        onGl { replaceOutput(surface) }
    }

    /** 把手里最新的一帧按长边 [maxEdge] 缩好读出来；一帧都没有返回 null。 */
    fun capture(maxEdge: Int): Bitmap? = onGl { readBack(maxEdge) }

    /** 把手里最新的一帧整张画到 [target] 上（换出去之前写回 ImageReader，见类注释）。 */
    fun writeTo(target: Surface) {
        onGl { drawOnce(target) }
    }

    /** 收掉 GL 上下文、输入的 Surface 和线程。之后这个对象就不能用了。 */
    fun release() {
        onGl { tearDown() }
        thread.quitSafely()
    }

    private fun setUp(seed: Bitmap?): Surface {
        val egl = Egl()
        this.egl = egl
        egl.makeCurrent(egl.pbuffer)
        oesProgram = buildProgram(VERTEX_SHADER, OES_FRAGMENT_SHADER)
        textureProgram = buildProgram(VERTEX_SHADER, TEXTURE_FRAGMENT_SHADER)
        frameTexture = newTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        val texture = SurfaceTexture(frameTexture).apply {
            // 缓冲区按副屏原尺寸：画到预览上时再按那边的大小缩，截图也能拿到全分辨率。
            setDefaultBufferSize(width, height)
            setOnFrameAvailableListener({ onFrame() }, handler)
        }
        surfaceTexture = texture
        if (seed != null) {
            seedTexture = newTexture(GLES20.GL_TEXTURE_2D)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, seed, 0)
            hasSeed = true
        }
        return Surface(texture).also { inputSurface = it }
    }

    /** 副屏画好了一帧：收进纹理，画到预览上。 */
    private fun onFrame() {
        val egl = egl ?: return
        val texture = surfaceTexture ?: return
        egl.makeCurrent(egl.pbuffer)
        runCatching { texture.updateTexImage() }
            .onFailure { Log.w(TAG, "收新帧失败", it) }
            .onSuccess {
                texture.getTransformMatrix(frameTransform)
                hasFrame = true
                drawToOutput()
            }
    }

    private fun replaceOutput(surface: Surface?) {
        dropOutput(reason = null)
        if (surface == null) return
        val egl = egl ?: return
        val target = runCatching { egl.windowSurface(surface) }
            .onFailure { Log.w(TAG, "在预览的 Surface 上建 EGL surface 失败", it) }
            .getOrNull()
        if (target == null) {
            surface.release()
            return
        }
        output = target
        outputWindow = surface
        drawToOutput()
    }

    private fun drawToOutput() {
        val egl = egl ?: return
        val target = output ?: return
        if (!egl.makeCurrent(target)) {
            dropOutput("切到预览的 Surface 失败（${egl.lastError()}）")
            return
        }
        val (w, h) = egl.size(target)
        GLES20.glViewport(0, 0, w, h)
        drawLatest(flipY = false)
        // App 那边的 Surface 已经没了（TextureView 销毁了、还没来得及告诉这边），swap 会失败：收掉就好。
        if (!egl.swap(target)) dropOutput("预览的 Surface 已经没了（${egl.lastError()}）")
    }

    /** 收掉预览的输出。[reason] 不为 null 时是出了问题才收的，记一笔。 */
    private fun dropOutput(reason: String?) {
        if (reason != null) Log.w(TAG, reason)
        val egl = egl
        val surface = output
        if (egl != null && surface != null) {
            egl.makeCurrent(egl.pbuffer)
            egl.destroy(surface)
        }
        output = null
        // 这是 App 交过来的 Surface 在本进程里的那份引用，放掉它，不影响 App 那边。
        outputWindow?.release()
        outputWindow = null
    }

    private fun readBack(maxEdge: Int): Bitmap? {
        val egl = egl ?: return null
        if (!hasFrame && !hasSeed) return null
        val longest = maxOf(width, height)
        val scale = if (maxEdge in 1 until longest) maxEdge.toFloat() / longest else 1f
        val w = (width * scale).roundToInt().coerceAtLeast(1)
        val h = (height * scale).roundToInt().coerceAtLeast(1)
        egl.makeCurrent(egl.pbuffer)
        bindFbo(w, h)
        GLES20.glViewport(0, 0, w, h)
        // GL 读回来是从最下面一行开始的，画的时候上下翻一下，读回来正好是正的。
        drawLatest(flipY = true)
        val pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        pixels.rewind()
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(pixels) }
    }

    private fun drawOnce(target: Surface) {
        val egl = egl ?: return
        // 一帧新的都没来过：ImageReader 手里那张（开场画面就是从它来的）就是最新的，不用写。
        if (!hasFrame) return
        val surface = runCatching { egl.windowSurface(target) }
            .onFailure { Log.w(TAG, "写回 ImageReader 失败，切回去之后到下次画面变化前，截到的可能是旧画面", it) }
            .getOrNull() ?: return
        try {
            egl.makeCurrent(surface)
            GLES20.glViewport(0, 0, width, height)
            drawLatest(flipY = false)
            egl.swap(surface)
        } finally {
            // 必须收掉：同一个 Surface 同一时间只能有一个生产者，接下来副屏要切回来往里画。
            egl.makeCurrent(egl.pbuffer)
            egl.destroy(surface)
        }
    }

    /** 画手里最新的一帧：有新帧画新帧，没有就画开场画面，都没有就是一片黑。 */
    private fun drawLatest(flipY: Boolean) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        when {
            hasFrame -> drawQuad(oesProgram, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, frameTexture, frameTransform, flipY)
            hasSeed -> drawQuad(textureProgram, GLES20.GL_TEXTURE_2D, seedTexture, SEED_TRANSFORM, flipY)
        }
    }

    private fun drawQuad(program: Int, target: Int, texture: Int, texTransform: FloatArray, flipY: Boolean) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTexture"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, texTransform, 0)
        GLES20.glUniformMatrix4fv(
            GLES20.glGetUniformLocation(program, "uPosMatrix"),
            1,
            false,
            if (flipY) FLIP_Y else IDENTITY,
            0,
        )
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val coord = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, QuadPositions)
        GLES20.glEnableVertexAttribArray(coord)
        GLES20.glVertexAttribPointer(coord, 2, GLES20.GL_FLOAT, false, 0, QuadTexCoords)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(coord)
        GLES20.glBindTexture(target, 0)
    }

    private fun bindFbo(w: Int, h: Int) {
        if (fbo != 0 && (fboWidth != w || fboHeight != h)) deleteFbo()
        if (fbo == 0) {
            fboTexture = newTexture(GLES20.GL_TEXTURE_2D)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
            )
            val ids = IntArray(1)
            GLES20.glGenFramebuffers(1, ids, 0)
            fbo = ids[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTexture, 0,
            )
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            check(status == GLES20.GL_FRAMEBUFFER_COMPLETE) { "截图用的离屏缓冲建不起来：0x${Integer.toHexString(status)}" }
            fboWidth = w
            fboHeight = h
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
    }

    private fun deleteFbo() {
        if (fbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        if (fboTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(fboTexture), 0)
        fbo = 0
        fboTexture = 0
    }

    private fun tearDown() {
        val egl = egl ?: return
        dropOutput(reason = null)
        egl.makeCurrent(egl.pbuffer)
        surfaceTexture?.setOnFrameAvailableListener(null)
        surfaceTexture?.release()
        surfaceTexture = null
        inputSurface?.release()
        inputSurface = null
        deleteFbo()
        GLES20.glDeleteTextures(2, intArrayOf(frameTexture, seedTexture), 0)
        GLES20.glDeleteProgram(oesProgram)
        GLES20.glDeleteProgram(textureProgram)
        egl.release()
        this.egl = null
    }

    /**
     * 把 [block] 扔到 GL 线程上做完再回来，做不完（超时、出错）返回 null。已经在 GL 线程上就直接做。
     */
    private fun <T> onGl(block: () -> T): T? {
        if (Looper.myLooper() == thread.looper) return block()
        val done = CountDownLatch(1)
        var result: T? = null
        val posted = handler.post {
            try {
                result = block()
            } catch (t: Throwable) {
                Log.e(TAG, "预览管线出错", t)
            } finally {
                done.countDown()
            }
        }
        if (!posted) return null
        if (!done.await(GL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "GL 线程 ${GL_TIMEOUT_MS}ms 没做完")
            return null
        }
        return result
    }

    /**
     * 一个 GL 上下文，外加一块 1×1 的 pbuffer：没有预览输出时（收新帧、截图）也得有个 surface 才能让上下文
     * 生效。
     *
     * 不调 eglTerminate：默认的 EGLDisplay 是整个进程共用的，好几块副屏同时有人看的话，一个 terminate 会
     * 把别人的上下文一起废掉。
     */
    private class Egl {
        val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val config: EGLConfig
        private val context: EGLContext
        val pbuffer: EGLSurface

        init {
            check(display != EGL14.EGL_NO_DISPLAY) { "取不到 EGL display" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize 失败：${lastError()}" }
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            EGL14.eglChooseConfig(
                display,
                intArrayOf(
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE,
                ),
                0, configs, 0, 1, count, 0,
            )
            config = configs[0]?.takeIf { count[0] > 0 } ?: error("没有合适的 EGL 配置：${lastError()}")
            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            check(context != EGL14.EGL_NO_CONTEXT) { "建 GL 上下文失败：${lastError()}" }
            pbuffer = EGL14.eglCreatePbufferSurface(
                display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
            )
            check(pbuffer != EGL14.EGL_NO_SURFACE) { "建 pbuffer 失败：${lastError()}" }
        }

        fun windowSurface(surface: Surface): EGLSurface =
            EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
                .also { check(it != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface 失败：${lastError()}" } }

        fun makeCurrent(surface: EGLSurface): Boolean = EGL14.eglMakeCurrent(display, surface, surface, context)

        fun swap(surface: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, surface)

        fun size(surface: EGLSurface): Pair<Int, Int> {
            val value = IntArray(1)
            EGL14.eglQuerySurface(display, surface, EGL14.EGL_WIDTH, value, 0)
            val w = value[0]
            EGL14.eglQuerySurface(display, surface, EGL14.EGL_HEIGHT, value, 0)
            return w to value[0]
        }

        fun destroy(surface: EGLSurface) {
            EGL14.eglDestroySurface(display, surface)
        }

        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, pbuffer)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
        }

        fun lastError(): String = "0x" + Integer.toHexString(EGL14.eglGetError())
    }

    private companion object {
        const val TAG = "KittenPreview"

        /**
         * 等 GL 线程做完一件事最多等多久。正常是几毫秒（截图读回一张缩过的图十几毫秒）；等这么久还没好，
         * 多半是 GL 线程卡死了，别让 binder 调用跟着一直挂着。
         */
        const val GL_TIMEOUT_MS = 2_000L

        const val VERTEX_SHADER = """
            uniform mat4 uPosMatrix;
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uPosMatrix * aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        /** 副屏的帧：SurfaceTexture 给的是外部纹理（OES）。 */
        const val OES_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        /** 开场画面：从位图传上去的普通纹理。 */
        const val TEXTURE_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        /** 铺满整个画面的两个三角形（条带）：左下、右下、左上、右上。 */
        val QuadPositions: FloatBuffer = floatBufferOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val QuadTexCoords: FloatBuffer = floatBufferOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        val IDENTITY = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )

        /** 上下翻：画进离屏缓冲、读回来正好是正的，见 [readBack]。 */
        val FLIP_Y = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, -1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )

        /**
         * 开场画面的纹理坐标变换：位图的第一行（最上面）传上去落在 t=0，而 t=0 在画面的最下面，所以把 t
         * 翻过来（t → 1 - t）。列主序。
         */
        val SEED_TRANSFORM = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, -1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 1f, 0f, 1f,
        )

        fun floatBufferOf(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(values)
                position(0)
            }

        fun newTexture(target: Int): Int {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            GLES20.glBindTexture(target, ids[0])
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            return ids[0]
        }

        fun buildProgram(vertexSource: String, fragmentSource: String): Int {
            val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource.trimIndent())
            val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource.trimIndent())
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertex)
            GLES20.glAttachShader(program, fragment)
            GLES20.glLinkProgram(program)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
            check(linked[0] == GLES20.GL_TRUE) {
                "着色器程序链接失败：${GLES20.glGetProgramInfoLog(program)}".also { GLES20.glDeleteProgram(program) }
            }
            return program
        }

        fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            check(compiled[0] == GLES20.GL_TRUE) {
                "着色器编译失败：${GLES20.glGetShaderInfoLog(shader)}".also { GLES20.glDeleteShader(shader) }
            }
            return shader
        }
    }
}
