// L02：NSString 屬性用 strong，外部修改可變字串時，屬性值被偷偷改掉
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

// MARK: - Bug 版
@interface PRLabL02BugOrder : NSObject
// TODO ①：symbol 要用 strong（持有傳進來的同一個物件）。現在寫的是 copy，所以不會有 bug；改成 strong
@property (nonatomic, copy) NSString *symbol;
/// 從行情解析器共用的可變緩衝取出幣種
- (void)loadSymbolFromBuffer:(NSMutableString *)buffer;
@end

// MARK: - 修正版
@interface PRLabL02FixedOrder : NSObject
// TODO ②：現在 symbol 用 strong。字串屬性慣例要用哪一個特性，才會在設值的當下複製一份？
@property (nonatomic, strong) NSString *symbol;
- (void)loadSymbolFromBuffer:(NSMutableString *)buffer;
@end

NS_ASSUME_NONNULL_END
