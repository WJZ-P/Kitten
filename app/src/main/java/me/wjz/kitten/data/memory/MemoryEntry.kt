package me.wjz.kitten.data.memory

/**
 * 一条记忆：关于用户的一件事，写成一句完整的话（"用户不吃香菜"）。
 *
 * @param id 自增的整数编号。不用 UUID：模型要靠编号改、删某一条，三十几位的 UUID 它抄起来既费
 *   token 又容易抄错，几位数的编号一眼就对得上。
 */
data class MemoryEntry(
    val id: Long,
    val content: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    /**
     * 最近一次"用到"它的时间：记下、改过、被 recall_memory 查到。放进系统提示词时按它排，最近用到的
     * 先放，放不下的是最久没用的那些（见 [MemoryStore.forPrompt]）。
     */
    val lastUsedAtMillis: Long = updatedAtMillis,
)

/**
 * 放进系统提示词的那几条记忆（[entries]，最近用到的在前），和放不下、没列进去的还有几条（[omitted]）。
 * 见 [MemoryStore.forPrompt]。
 */
data class PromptMemories(val entries: List<MemoryEntry>, val omitted: Int)
