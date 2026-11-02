package me.wjz.kitten.data.session

import android.content.Context
import me.wjz.kitten.data.session.sqlite.SqliteSessionRepository

/**
 * 整个进程共用一个 [SessionRepository]。
 *
 * 不止对话页在写会话：提醒响了也要往排它的那段对话里写一条（见 Reminders.announce）。两边要是各 new 一个，
 * 库是同一个库，"有变化"的信号却各管各的：提醒写进去了，侧边栏和打开着的对话都不知道。共用一个，谁写了大家
 * 都收得到。
 */
object Sessions {

    @Volatile
    private var instance: SessionRepository? = null

    fun get(context: Context): SessionRepository =
        instance ?: synchronized(this) {
            instance ?: SqliteSessionRepository(context.applicationContext).also { instance = it }
        }
}
