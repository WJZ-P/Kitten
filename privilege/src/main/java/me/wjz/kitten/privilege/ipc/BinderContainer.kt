package me.wjz.kitten.privilege.ipc

import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable

/**
 * 在 Bundle 里安全携带一个 IBinder 的包装。
 *
 * 直接 Bundle.putBinder 在 Bundle 被重新序列化时可能丢失 binder；
 * 用一个显式以 writeStrongBinder/readStrongBinder 读写的 Parcelable 更稳妥。
 */
class BinderContainer : Parcelable {

    @JvmField
    val binder: IBinder?

    constructor(binder: IBinder?) {
        this.binder = binder
    }

    private constructor(parcel: Parcel) {
        binder = parcel.readStrongBinder()
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeStrongBinder(binder)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<BinderContainer> {
        override fun createFromParcel(parcel: Parcel): BinderContainer = BinderContainer(parcel)
        override fun newArray(size: Int): Array<BinderContainer?> = arrayOfNulls(size)
    }
}
