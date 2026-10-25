package me.wjz.kitten.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 各家 provider 共用的 HTTP 收发与错误归类。
 *
 * 抽出来是因为两家在"怎么发请求、怎么认错"上完全一致，差异只在报文结构。
 * 把 HTTP 细节重复两遍，将来加第三家就是三遍。
 */
internal object HttpTransport {

    private const val TAG = "KittenLlm"

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * 超时给得比较宽松。
     *
     * Agent 的一轮请求要带整屏节点树（实测微信一页约 400 token，加上历史轮次更多），
     * 模型思考 + 生成要十几秒很正常。读超时太短会在模型正常工作时误杀。
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /**
     * POST 一段 JSON，返回解析好的响应体。
     *
     * 失败一律归类成 [LlmError] 而不是抛异常：这些情况（key 过期、限流、断网）都是
     * 预期内的，调用方要据此分流处理。
     */
    suspend fun postJson(url: String, headers: Headers, body: JSONObject): Result<JSONObject> =
        execute(
            Request.Builder()
                .url(url)
                .headers(headers)
                .post(body.toString().toRequestBody(JSON))
                .build(),
        )

    /** GET 一个 JSON 接口（比如模型列表），失败的归类和 [postJson] 一样。 */
    suspend fun getJson(url: String, headers: Headers): Result<JSONObject> =
        execute(
            Request.Builder()
                .url(url)
                .headers(headers)
                .get()
                .build(),
        )

    /**
     * POST 一段 JSON，把回复按 SSE 一条条交出来：流式输出用。
     *
     * ## SSE 是什么
     *
     * Server-Sent Events，一种"服务器边生成边往回写"的 HTTP 响应：响应头先回来，正文不一次
     * 给完，而是一段段地推。每段是几行 `字段: 值`，空行隔开一条事件。两家的流式接口都用它：
     * 模型每生成一小段字就推一条 `data: {JSON}`。
     *
     * ## 失败和取消
     *
     * HTTP 层面的失败（状态码、网页、断网）和 [postJson] 归类得一样，以 [LlmException] 结束这个
     * Flow。收集方取消（比如页面关了）时取消这次请求，连接立刻断开，不会在后台接着收、接着
     * 计费。
     *
     * 有的兼容服务不支持流式，不理会请求里的 stream、照常回一整个 JSON：这时交出一条
     * [StreamChunk.Whole]，由 provider 按非流式的格式解析。
     */
    fun postSse(url: String, headers: Headers, body: JSONObject): Flow<StreamChunk> = callbackFlow {
        val request = Request.Builder()
            .url(url)
            .headers(headers)
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody(JSON))
            .build()
        val call = client.newCall(request)
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // 收集方取消时 OkHttp 也走这里，那不是失败，安静结束就好。
                    if (call.isCanceled()) {
                        this@callbackFlow.close()
                        return
                    }
                    Log.w(TAG, "请求失败：$url", e)
                    this@callbackFlow.close(LlmException(LlmError.Network(e.message)))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        this@callbackFlow.close(readStream(call, response, url))
                    }
                }

                /** 读完整个响应。正常读完返回 null，出错返回原因。 */
                private fun readStream(call: Call, response: Response, url: String): Throwable? {
                    val contentType = response.header("Content-Type")
                    if (!response.isSuccessful) {
                        val text = response.body?.string().orEmpty()
                        return LlmException(classify(response.code, text, response.headers, url))
                    }
                    val source = response.body?.source() ?: return LlmException(LlmError.MalformedResponse("响应没有正文"))
                    return try {
                        if (contentType?.contains("text/event-stream", ignoreCase = true) == true) {
                            readEvents(source) { this@callbackFlow.trySendBlocking(it).isSuccess }
                            null
                        } else {
                            // 没标成 SSE：要么是不支持流式、回了一整个 JSON，要么是打到了网页上（同 postJson），
                            // 也有中转站推的明明是 SSE、类型却标成 text/plain 的——看正文认。
                            val text = source.readUtf8()
                            val trimmed = text.trimStart()
                            when {
                                trimmed.startsWith("data:") || trimmed.startsWith("event:") -> {
                                    readEvents(Buffer().writeUtf8(text)) { this@callbackFlow.trySendBlocking(it).isSuccess }
                                    null
                                }
                                looksLikeHtml(text, contentType) -> LlmException(LlmError.WrongEndpoint(url))
                                else -> runCatching { JSONObject(text) }.fold(
                                    onSuccess = {
                                        this@callbackFlow.trySendBlocking(StreamChunk.Whole(it))
                                        null
                                    },
                                    onFailure = { LlmException(LlmError.MalformedResponse("不是合法 JSON：${text.take(200)}")) },
                                )
                            }
                        }
                    } catch (e: IOException) {
                        if (call.isCanceled()) {
                            null
                        } else {
                            Log.w(TAG, "读回复时断开：$url", e)
                            LlmException(LlmError.Network(e.message))
                        }
                    }
                }
            },
        )
        awaitClose { call.cancel() }
    }

    /**
     * 按 SSE 的格式一行行读，攒够一条事件（遇到空行）就交给 [emit]。[emit] 返回 false 表示
     * 收集方已经不要了，停止读。
     *
     * 只认 `event:` 和 `data:`；`id:`、`retry:` 用不上，冒号开头的是注释（有的服务拿它当心跳）。
     * 一条事件可以有好几行 data，按规范用换行接起来。
     */
    private fun readEvents(source: BufferedSource, emit: (StreamChunk.Event) -> Boolean) {
        var event: String? = null
        val data = StringBuilder()
        fun dispatch(): Boolean {
            if (data.isEmpty()) {
                event = null
                return true
            }
            val keepGoing = emit(StreamChunk.Event(event, data.toString()))
            event = null
            data.setLength(0)
            return keepGoing
        }
        while (true) {
            val line = source.readUtf8Line() ?: break
            when {
                line.isEmpty() -> if (!dispatch()) return
                line.startsWith(":") -> Unit
                line.startsWith("event:") -> event = line.substring("event:".length).trim()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substring("data:".length).removePrefix(" "))
                }
            }
        }
        // 最后一条后面没有空行就结束了，也交出去。
        dispatch()
    }

    private suspend fun execute(request: Request): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            val url = request.url.toString()
            try {
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            LlmException(classify(response.code, text, response.headers, url)),
                        )
                    }
                    // 状态码是 200 但回的是网页：地址打到了网站页面上（比如中转站的控制台对不认识的
                    // 路径一律回首页），而不是接口。把一大段 HTML 甩给用户没用，告诉他该查什么。
                    if (looksLikeHtml(text, response.header("Content-Type"))) {
                        return@withContext Result.failure(LlmException(LlmError.WrongEndpoint(url)))
                    }
                    runCatching { JSONObject(text) }.fold(
                        onSuccess = { Result.success(it) },
                        onFailure = {
                            Result.failure(
                                LlmException(
                                    LlmError.MalformedResponse(
                                        "不是合法 JSON：${text.take(200)}",
                                    ),
                                ),
                            )
                        },
                    )
                }
            } catch (e: IOException) {
                // 断网、DNS 失败、超时都走这里。不打 error 级日志——这是常态，不是故障。
                Log.w(TAG, "请求失败：$url", e)
                Result.failure(LlmException(LlmError.Network(e.message)))
            }
        }

    /**
     * 按 HTTP 状态码归类错误。
     *
     * 各家的错误体结构不同（OpenAI 是 `error.message`，Anthropic 也是 `error.message`，
     * 国产兼容端点五花八门），所以尽力提取，提不到就把原文截一段带上——排查时
     * 有原文比一句"服务异常"有用得多。
     */
    private fun classify(code: Int, body: String, headers: Headers, url: String): LlmError {
        val html = looksLikeHtml(body, headers["Content-Type"])
        // 网页原文对用户没用，说一句"回的是网页"就够了。
        val detail = if (html) "返回的是一张网页" else extractMessage(body) ?: body.take(200).ifBlank { null }
        return when {
            code == 401 || code == 403 -> LlmError.Unauthorized(detail)
            code == 429 -> LlmError.RateLimited(
                headers["retry-after"]?.toIntOrNull(),
            )
            code >= 500 -> LlmError.ServerError(code, detail)
            // 404 之类的客户端错误、还回了一张网页：地址打到网站页面上了。
            html -> LlmError.WrongEndpoint(url)
            else -> LlmError.ServerError(code, detail)
        }
    }

    /** 回的是网页而不是 JSON。 */
    private fun looksLikeHtml(body: String, contentType: String?): Boolean =
        contentType?.contains("text/html", ignoreCase = true) == true || body.trimStart().startsWith("<")

    /** 从错误体里捞一句人话。两家主流格式都是 `{"error": {"message": "..."}}`。 */
    private fun extractMessage(body: String): String? = runCatching {
        val json = JSONObject(body)
        json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            ?: json.optString("message").takeIf { it.isNotBlank() }
    }.getOrNull()
}

/** 流式请求读回来的一块。 */
internal sealed interface StreamChunk {
    /** SSE 的一条事件：`event:` 那一行（没有就是 null）和 `data:` 的内容。 */
    data class Event(val event: String?, val data: String) : StreamChunk

    /** 服务端没理会 stream，回的是一整个 JSON。 */
    data class Whole(val json: JSONObject) : StreamChunk
}

/** 内部用的异常，只为了借 [Result] 的失败通道把 [LlmError] 带出来。 */
internal class LlmException(val error: LlmError) : Exception(error.describe())

/** 把 [Result] 的失败还原成 [LlmResult]，统一两家 provider 的收尾处理。 */
internal fun <T> Result<T>.toLlmFailure(): LlmResult.Failed {
    val error = (exceptionOrNull() as? LlmException)?.error
        ?: LlmError.Network(exceptionOrNull()?.message)
    return LlmResult.Failed(error)
}
