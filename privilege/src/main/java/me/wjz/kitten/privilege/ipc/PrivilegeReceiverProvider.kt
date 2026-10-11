package me.wjz.kitten.privilege.ipc

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import me.wjz.kitten.privilege.PrivilegeManager

/**
 * 运行在 App 主进程里，专门用来接收特权进程交回的 binder。
 *
 * 不提供任何真正的数据访问能力——query/insert/... 全部为空实现。
 * 访问控制靠 Manifest 里的 INTERACT_ACROSS_USERS_FULL 权限，
 * 只有 shell/系统能调到这里。
 */
class PrivilegeReceiverProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        // Provider 在 Application.onCreate 前后很早就会创建，适合在这里拿到 appContext。
        context?.applicationContext?.let { PrivilegeManager.attach(it) }
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != PrivilegeContract.METHOD_SEND_BINDER || extras == null) return null

        extras.classLoader = BinderContainer::class.java.classLoader
        @Suppress("DEPRECATION")
        val container = extras.getParcelable<BinderContainer>(PrivilegeContract.EXTRA_BINDER)
        val token = extras.getString(PrivilegeContract.EXTRA_TOKEN)
        val accepted = container?.binder?.let { PrivilegeManager.onBinderReceived(it, token) } == true

        // 必须把结果回给特权进程：被拒绝的话它要自己退出，
        // 否则就成了一个 App 不认识、却还占着 shell 权限的孤儿进程。
        return Bundle().apply { putBoolean(PrivilegeContract.KEY_ACCEPTED, accepted) }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
