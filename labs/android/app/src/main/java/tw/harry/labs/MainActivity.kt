package tw.harry.labs

import tw.harry.labs.kit.LabRootActivity

/**
 * 首頁依區列出每個單元與演練。進入演練後可以切換「練習／解答」與「Bug 版／修正版」。
 *
 * 截圖檢查用：adb shell am start -n tw.harry.labs/.MainActivity --es open Kotlin03Coroutines/L05 --es source solution
 */
class MainActivity : LabRootActivity() {
    override val practice = practiceSections
    override val solution = solutionSections
}
