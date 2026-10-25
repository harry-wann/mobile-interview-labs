// L04 的修正版
#import <Foundation/Foundation.h>

// TODO ②：照 PRLabL04Legacy.h 補上 nullability 標註：
//        ① 用 NS_ASSUME_NONNULL_BEGIN／NS_ASSUME_NONNULL_END 包住下面的 @interface，沒有特別標的指標都當作 nonnull；
//        ② memoForOrder: 沒有備註時會回傳 nil，要把它的回傳型別標成 nullable。
@interface PRLabL04OrderService : NSObject
@property (nonatomic, copy) NSString *symbol;                                    // 目標：Swift 看到 String
- (NSString *)memoForOrder:(NSString *)orderID;                                  // 目標：Swift 看到 memo(forOrder:) -> String?
@end
