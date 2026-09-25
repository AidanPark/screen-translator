package com.galaxy.airviewdictionary.data.local.vision.kit.paddle

import android.content.Context
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * PP-OCRv5 모델 파일을 찾는다.
 *
 * 스토어 설치에서는 Play Asset Delivery 팩(`paddle_models`, fast-follow)에 있다 — 앱 설치가 끝나면 Play 가 받아 준다.
 * installDebug 로 깐 디버그 빌드에는 같은 파일이 APK 에셋으로 들어 있다(app/build.gradle.kts). 팩을 먼저 보고, 없으면 에셋을 본다.
 */
open class PaddleModelFiles(private val context: Context) {

    private val TAG = javaClass.simpleName

    private val packs by lazy { AssetPackManagerFactory.getInstance(context) }

    /** 팩이 내려와 있는 디렉터리. 아직 없거나(받는 중) 스토어 설치가 아니면 null. */
    private fun packDir(): File? =
        runCatching { packs.getPackLocation(PACK)?.assetsPath() }.getOrNull()?.let { File(it, DIR) }

    open fun has(name: String): Boolean =
        packDir()?.let { File(it, name).isFile } == true ||
            runCatching { context.assets.list(DIR)?.contains(name) == true }.getOrDefault(false)

    /** [name] 의 내용. 어디에도 없으면 null. 기기 시험이 깨진 모델을 넘기려고 연다. */
    open fun read(name: String): ByteArray? {
        packDir()?.let { dir -> File(dir, name).takeIf { it.isFile }?.let { return it.readBytes() } }
        return try {
            context.assets.open("$DIR/$name").use { it.readBytes() }
        } catch (e: IOException) {
            null
        }
    }

    /**
     * 팩이 아직 없으면 받기를 청한다. fast-follow 는 Play 가 알아서 받지만, 설치 직후 바로 앱을 열면 아직일 수 있다.
     * 스토어 밖 설치(디버그)에서는 실패하는데, 그때는 에셋으로 돈다.
     */
    fun requestIfMissing() {
        if (packDir() != null) return
        runCatching {
            packs.fetch(listOf(PACK))
                .addOnFailureListener { Timber.tag(TAG).i("paddle_models fetch 못 함: ${it.message}") }
        }
    }

    companion object {
        const val PACK = "paddle_models"
        const val DIR = "paddle"
    }
}
