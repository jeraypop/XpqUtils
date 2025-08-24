package com.google.android.accessibility.ext.donate

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import androidx.annotation.NonNull
import androidx.core.content.ContextCompat
import com.google.android.accessibility.ext.utils.AliveUtils.toast
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions
import com.hjq.permissions.permission.PermissionLists
import com.hjq.permissions.permission.base.IPermission


/**
 * Created by qiang on 2025/8/9.
 */
class ScreenshotHelper {
    companion object {
        /**
         * 截取指定View并保存到相册（ModernStorage推荐方式）
         */
        @JvmStatic
        fun captureAndSaveToGallery(activity: Activity, view: View, fileName: String, p_Storage: Boolean = false) {
            // 截取View内容
            val bitmap = captureView(view) ?: return

            // 保存到相册
            saveScreenshotToGallery(activity, bitmap, fileName,p_Storage)
        }

        /**
         * 截取View内容
         */
        @JvmStatic
        fun captureView(view: View?): Bitmap? {
            if (view == null) return null
            // 使用Canvas方式截图
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            view.draw(canvas)
            return bitmap
        }

        /**
         * 使用ModernStorage方式保存截图到相册
         */
        @JvmStatic
        fun saveScreenshotToGallery(activity: Activity?, bitmap: Bitmap?, fileName: String, p_Storage: Boolean = false) {
            if (bitmap == null || activity == null) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 使用MediaStore (分区存储)
                saveToMediaStore(activity, bitmap, fileName)
            } else {
                // Android 9及以下版本
                //尽量还是不要申请权限,因为 一个软件申请读写权限就会增加用户的戒备心理
                //让用户自己手动截屏最好
                //
                  if (p_Storage) {
                      //检查清单文件中是否有权限声明
                      if (isPermissionDeclaredInManifest(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE)){
                          if (getWriteStoragePermission(activity)) {
                              saveToExternalStorage(activity, bitmap, fileName)
                          }
                      }
                      //动态授权检查是否有权限声明
                      if (hasStoragePermission(activity)){

                      }
                  }


            }
        }

        /**
         * Android 10及以上版本保存到MediaStore
         */
        @JvmStatic
         fun saveToMediaStore(context: Context, bitmap: Bitmap, fileName: String) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Screenshots")
            }

            val uri: Uri? = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                try {
                    context.contentResolver.openOutputStream(uri).use { outputStream ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream!!)
                        toast(msg = "截图已保存到相册")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    toast(msg = "请先手动截图,再扫一扫")
                }
            }
        }

        /**
         * Android 9及以下版本保存到外部存储
         */
        @JvmStatic
         fun saveToExternalStorage(context: Context, bitmap: Bitmap, fileName: String) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            }

            val uri: Uri? = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                try {
                    context.contentResolver.openOutputStream(uri).use { outputStream ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream!!)
                        toast(msg = "截图已保存到相册")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    toast(msg = "请先手动截图,再扫一扫")
                }
            }
        }


        /**
         * 检查是否有存储权限（Android 9及以下）
         */
        @JvmStatic
         fun hasStoragePermission(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return true // Android 10+不需要此权限
            }
            return (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED)
        }
        @JvmStatic
        fun isPermissionDeclaredInManifest(context: Context, permission: String): Boolean {
            try {
                val packageInfo = context.packageManager.getPackageInfo(
                    context.packageName, PackageManager.GET_PERMISSIONS
                )
                return packageInfo.requestedPermissions?.contains(permission) ?: false
            } catch (e: Exception) {
                return false
            }
        }



        @JvmStatic
        fun getWriteStoragePermission(context: Activity): Boolean {
            var isGranted = false
            XXPermissions.with(context)
                // 申请读写权限
                .permission(PermissionLists.getWriteExternalStoragePermission())
                // 设置不触发错误检测机制（局部设置）
                //.unchecked()
                .request(object : OnPermissionCallback {

                     fun onGranted(permissions: MutableList<IPermission>, allGranted: Boolean) {
                        if (!allGranted) {
                            isGranted = false
                            toast(appContext,"获取部分权限成功，但部分权限未正常授予")
                            return
                        }
                        isGranted = true
                        toast(appContext,"获取外部存储权限成功")
                    }

                     fun onDenied(permissions: MutableList<IPermission>, doNotAskAgain: Boolean) {
                        if (doNotAskAgain) {
                            isGranted = false
                            toast(appContext,"被永久拒绝授权，请手动授予外部存储权限")
                            // 如果是被永久拒绝就跳转到应用权限系统设置页面
                            XXPermissions.startPermissionActivity(context, permissions)
                        } else {
                            isGranted = false
                            toast(appContext,"获取外部存储权限失败")
                        }
                    }

                    override fun onResult(
                        grantedList: MutableList<IPermission>,
                        deniedList: MutableList<IPermission>
                    ) {
                        val allGranted = deniedList.isEmpty()
                        if (!allGranted) {
                            isGranted = false
                            // 判断请求失败的权限是否被用户勾选了不再询问的选项
                            val doNotAskAgain = XXPermissions.isDoNotAskAgainPermissions(context, deniedList)
                            // 在这里处理权限请求失败的逻辑
                            // ......
                            if (doNotAskAgain) {
                                toast(appContext,"被永久拒绝授权，请手动授予外部存储权限")
                                // 如果是被永久拒绝就跳转到应用权限系统设置页面
                                XXPermissions.startPermissionActivity(context,deniedList)
                            }else{
                                toast(appContext,"获取外部存储权限失败")
                            }
                            return
                        }else{
                            isGranted = true
                            toast(appContext,"获取外部存储权限成功")
                        }

                        // 在这里处理权限请求成功的逻辑
                        // ......
                    }
                })
            return isGranted

        }

    }
}