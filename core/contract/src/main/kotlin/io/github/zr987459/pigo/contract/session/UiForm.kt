package io.github.zr987459.pigo.contract.session

/**
 * 两种形态（DESIGN-r3 §5）。只被顶层 FormHost 与 App 设置读取；
 * `ChatController`、[SessionHost] 的公开方法不得带这个类型的参数（FormSwitchContractTest）。
 */
enum class UiForm { TERM, CALM }
