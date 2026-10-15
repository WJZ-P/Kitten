package me.wjz.kitten.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .headers(headers)
                .post(body.toString().toRequestBody(JSON))
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            LlmException(classify(response.code, text, response.headers)),
                        )
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
    private fun classify(code: Int, body: String, headers: Headers): LlmError {
        val detail = extractMessage(body) ?: body.take(200).ifBlank { null }
        return when {
            code == 401 || code == 403 -> LlmError.Unauthorized(detail)
            code == 429 -> LlmError.RateLimited(
                headers["retry-after"]?.toIntOrNull(),
            )
            code >= 500 -> LlmError.ServerError(code, detail)
            else -> LlmError.ServerError(code, detail)
        }
    }

    /** 从错误体里捞一句人话。两家主流格式都是 `{"error": {"message": "..."}}`。 */
    private fun extractMessage(body: String): String? = runCatching {
        val json = JSONObject(body)
        json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            ?: json.optString("message").takeIf { it.isNotBlank() }
    }.getOrNull()
}

/** 内部用的异常，只为了借 [Result] 的失败通道把 [LlmError] 带出来。 */
internal class LlmException(val error: LlmError) : Exception(error.describe())

/** 把 [Result] 的失败还原成 [LlmResult]，统一两家 provider 的收尾处理。 */
internal fun <T> Result<T>.toLlmFailure(): LlmResult.Failed {
    val error = (exceptionOrNull() as? LlmException)?.error
        ?: LlmError.Network(exceptionOrNull()?.message)
    return LlmResult.Failed(error)
}
