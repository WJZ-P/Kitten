package me.wjz.kitten.a11y

/**
 * 抓到的节点要保留到什么程度。
 *
 * **这三档是三种用途，不是精度递增的三级。** 别把它们理解成"粗、中、细"——
 * 每一档回答的是不同的问题：
 *
 * | 档位          | 回答的问题           | 用在哪 |
 * |---------------|---------------------|--------|
 * | [ALL]         | 这一刻究竟有什么     | 排查问题 |
 * | [VISIBLE]     | 这一页写了什么       | 读内容 |
 * | [INTERACTIVE] | 我能点什么           | 做决策 |
 *
 * 真机实测的量级（副屏上的 QQ 主界面，234 个节点）：ALL 234 → VISIBLE 187 →
 * INTERACTIVE 26。微信聊天列表则是 399 → 240 → 12，可见比例跟应用实现强相关，
 * 不能按一个应用去调参。
 *
 * ## 默认为什么是 ALL
 *
 * [ScreenReader.capture] 不传 scope 时给的是 ALL，这是有意的：默认值不该做有损
 * 变换。排查"模型为什么没看到那个按钮"时，第一步就是 `capture()` 看全貌，如果
 * 连默认档都是裁过的，这一步先骗了自己一次。
 *
 * 真正该默认 [INTERACTIVE] 的是上层——将来给模型用的那层封装，9:1 的 token 差距
 * 摆在那，不能指望每次调用都记得传参。底层保守、上层激进，两层各有各的默认。
 */
enum class CaptureScope {

    /**
     * 全都要，原样返回。
     *
     * 包含用户看不见的节点：滚动区域外的、被遮住的、其它 tab 预加载的。
     * 它们的 bounds 往往还是坏的（实测微信有 `[509,0][571,-1345]` 这种 bottom 小于 top 的），
     * 所以别拿 ALL 的结果去算坐标。调试和排查用它，喂模型别用。
     */
    ALL,

    /**
     * 只要用户此刻真的看得见的。**要读页面内容时用这一档。**
     *
     * 它的不可替代之处在于：这是唯一不依赖"文本必须挂在某个可交互祖先下面"这个
     * 前提的档。[INTERACTIVE] 的文本全靠聚合到可点节点上，一段纯展示的文字如果
     * 不在任何可交互节点内（文章正文、聊天气泡、设置页说明），那边就会丢，这边不会。
     *
     * 省得不多——QQ 主界面 234 砍到 187，只少 20%（微信那页能砍掉 49%，差别很大）。
     * 但砍掉的都是实打实的噪音：滚动区外、被遮住、其它 tab 预加载的节点，会让模型
     * 以为页面上有某些入口，实际根本点不到。
     *
     * 结构保留：某个节点不可见但后代可见时（罕见但存在），后代会被提到最近的可见
     * 祖先下面，不会连带丢掉。
     */
    VISIBLE,

    /**
     * 只要能操作的节点，并把各自子树里的文本聚合成 [UiNode.label]。**做决策时用这一档。**
     *
     * 模型要知道的是"屏幕上有哪些东西能点，分别是什么"，而不是完整布局树。
     * 实测 QQ 主界面 234 压到 26（9:1），微信 399 压到 12。
     *
     * 聚合是必需的而不是锦上添花——可交互的是容器、有文本的是子节点，不聚合的话
     * 返回的全是空壳，详见 [UiNode.label]。
     *
     * **已知局限：不在任何可交互节点内的文本会丢。** QQ 主界面实测 28 段可见文本
     * 一段没丢，但那是因为它每段文字都恰好落在某个可点容器里。换成文章页、聊天
     * 详情页这类大段纯展示文本的场景就不成立了——那种时候用 [VISIBLE]。
     */
    INTERACTIVE,
}

/**
 * 按 [scope] 裁剪这份快照。
 *
 * 纯函数，不碰原快照。所以同一次抓取可以产出多种视图——反正真正的开销在抓取时的
 * 跨进程遍历，裁剪只是本地数据变换。
 */
fun UiSnapshot.filter(scope: CaptureScope): UiSnapshot {
    if (scope == CaptureScope.ALL) return this
    val windows = windows.map { window ->
        window.copy(roots = window.roots.flatMap { filterTree(it, scope) })
    }
    return copy(
        scope = scope,
        windows = windows,
        nodeCount = windows.sumOf { it.nodeCount },
    )
}

/**
 * 裁剪一棵子树，返回"保留下来的顶层节点"。
 *
 * 返回列表而不是单个节点，是因为一个节点被砍掉时它的后代要顶上来——比如不可见的
 * 容器里套着可见的内容，容器没了，内容得接到更上面的祖先去，不能跟着一起消失。
 */
private fun filterTree(node: UiNode, scope: CaptureScope): List<UiNode> {
    val keptChildren = node.children.flatMap { filterTree(it, scope) }

    // 只给候选节点算标签：collectLabel 要遍历子树，对每个节点都算就是 O(n²)。
    val interactiveCandidate = node.visibleToUser && node.interactive
    val label = if (scope == CaptureScope.INTERACTIVE && interactiveCandidate) {
        node.collectLabel()
    } else {
        node.label
    }

    val keep = when (scope) {
        CaptureScope.ALL -> true
        CaptureScope.VISIBLE -> node.visibleToUser
        // 看不见的东西操作不了，所以这一档隐含了可见性要求。
        //
        // 额外砍掉"纯包装层"：没有标签、而且子孙里已经有可交互节点顶上的那些。
        // 实测 QQ 一个会话项会套出三层可点节点，只有中间那层带文本，另两层是空壳，
        // 41 个节点里这种占了一多半，对模型是纯噪音。
        //
        // 两个例外必须留：
        // - 可滚动的容器：它就是滚动这个操作的入口，没文本也得留，否则 Agent 没法翻页；
        // - 没有可交互子孙的叶子：比如没设 contentDescription 的图标按钮，它自己
        //   就是最终目标，砍了就真的点不到了。
        //
        // 风险是有些应用把点击处理挂在外层容器上，点子节点可能不响应。等动作层真跑
        // 起来如果撞上，再把这条规则放宽。
        CaptureScope.INTERACTIVE -> interactiveCandidate &&
            (node.scrollable || label != null || keptChildren.isEmpty())
    }
    if (!keep) return keptChildren

    return listOf(node.copy(children = keptChildren, label = label))
}

/** 聚合时各段文本之间的分隔符，选它是因为在中文里不容易和内容本身混淆。 */
private const val LABEL_SEPARATOR = " · "

/** 聚合标签的长度上限。列表项里塞着整段聊天记录的情况是有的，不能让它撑爆上下文。 */
private const val MAX_LABEL_LENGTH = 120

/**
 * 把这个节点子树里的文本收上来，拼成一句可读的标签。
 *
 * 遇到另一个可交互节点就停——那部分文本归它自己，不然一个列表容器会把整页的字
 * 都吃进去，而且模型也分不清哪段字对应哪个可点的东西。
 */
private fun UiNode.collectLabel(): String? {
    val parts = LinkedHashSet<String>()

    fun collect(node: UiNode, isRoot: Boolean) {
        // 子树里另一个可交互节点的文本归它自己。
        if (!isRoot && node.interactive) return
        // 密码框的内容一个字都不能带出来。
        if (!node.password) {
            node.text?.trim()?.takeIf { it.isNotEmpty() }?.let(parts::add)
            node.contentDescription?.trim()?.takeIf { it.isNotEmpty() }?.let(parts::add)
            node.hintText?.trim()?.takeIf { it.isNotEmpty() }?.let(parts::add)
        }
        node.children.forEach { collect(it, isRoot = false) }
    }
    collect(this, isRoot = true)

    if (parts.isEmpty()) return null

    // 去掉被别的段落包含的短段。很多应用会给整行设一个完整的 contentDescription，
    // 同时每个子节点又各自带 text，两份内容大面积重叠。实测 QQ 的会话项：
    //
    //   "我们最厉害, ,Trimethylamin: 我感觉可以休息了,晚上11:15,消息免打扰,置顶聊天
    //    · Trimethylamin · : · 我感觉可以休息了"
    //                ↑ 后半截全是前面那句 contentDescription 里已经有的
    //
    // 保留长的那段即可，短的是它的子集。段数很少（一个列表项撑死十几段），
    // 这里的 O(n²) 可以忽略。
    val deduped = parts.filter { part -> parts.none { it !== part && it.contains(part) } }

    val joined = deduped.joinToString(LABEL_SEPARATOR).replace('\n', ' ')
    return if (joined.length <= MAX_LABEL_LENGTH) joined else joined.take(MAX_LABEL_LENGTH) + "…"
}
