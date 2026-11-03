package io.github.zr987459.pigo.feature.files.platform

import androidx.core.content.FileProvider

/** 独立子类，避免与 App 或其他库声明的 androidx FileProvider 在清单合并时冲突。 */
class PigoFilesProvider : FileProvider()
