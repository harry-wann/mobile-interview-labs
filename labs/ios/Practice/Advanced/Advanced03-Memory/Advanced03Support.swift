// Advanced03 共用的小工具。型別與函式名稱都以 A03 開頭：同一個 module 裡還有其他單元，不能重名。
import Foundation

/// 從 log 找出以 prefix 開頭的第一行，取出後面接的數字
func a03Number(in lines: [String], prefix: String) -> Double? {
    guard let line = lines.first(where: { $0.hasPrefix(prefix) }) else { return nil }
    let digits = line.dropFirst(prefix.count).prefix { $0.isNumber || $0 == "." || $0 == "-" }
    return Double(digits)
}

/// 目前 App 的記憶體用量（MB）：phys_footprint 是系統用來決定要不要終止 App（jetsam）的那個數字
func a03FootprintMB() -> Double {
    var info = task_vm_info_data_t()
    var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
    let result = withUnsafeMutablePointer(to: &info) {
        $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
            task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
        }
    }
    return result == KERN_SUCCESS ? Double(info.phys_footprint) / 1_048_576 : 0
}
