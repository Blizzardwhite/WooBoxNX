package com.bw.wooboxnx.hook.app

import com.bw.wooboxnx.hook.app.mtpFileBrowser.HideMTPCategoryBrowse
import com.bw.wooboxnx.hook.app.mtpFileBrowser.RenameRootName
import com.bw.wooboxnx.util.xposed.base.AppRegister
import de.robv.android.xposed.callbacks.XC_LoadPackage

object MtpFileBrowser : AppRegister() {
    override val packageName: List<String> = listOf("cn.nubia.filebrowser")
    override val processName: List<String> = emptyList()
    override val logTag: String = "WooBox-MtpFileBrowser"
    override val loadDexkit: Boolean = false
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        autoInitHooks(
            lpparam,
            HideMTPCategoryBrowse,//隐藏MTP分类浏览
            RenameRootName,//重命名路径浏览名称
        )
    }
}