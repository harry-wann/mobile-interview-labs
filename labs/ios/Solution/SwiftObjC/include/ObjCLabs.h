// Swift11 的 Objective-C 程式放在這個 target：SPM 的一個 target 不能同時有 Swift 和 Objective-C。
// 在這裡 #import 新增的 .h，Swift 端用 `import SolutionSwiftObjC` 就能呼叫。
#import <Foundation/Foundation.h>

// 每個演練的 Objective-C header
#import "SOLabL01.h"
#import "SOLabL02.h"
#import "SOLabL03.h"
#import "SOLabL04Legacy.h"
#import "SOLabL04.h"
#import "SOLabL05.h"
