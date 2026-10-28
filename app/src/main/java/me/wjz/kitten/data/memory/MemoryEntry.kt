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
)
