// L04 的 Bug 版：舊 SDK 的 header，完全沒有 nullability 標註
#import <Foundation/Foundation.h>

@interface PRLabL04LegacyOrderService : NSObject
@property (nonatomic, copy) NSString *symbol;
/// 這筆委託沒有備註時回傳 nil（但 header 上沒有寫）
- (NSString *)memoForOrder:(NSString *)orderID;
@end
