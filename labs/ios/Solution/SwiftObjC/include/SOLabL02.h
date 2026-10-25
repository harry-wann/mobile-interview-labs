// L02：NSString 屬性用 strong，外部修改可變字串時，屬性值被偷偷改掉
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

// MARK: - Bug 版
@interface SOLabL02BugOrder : NSObject
/// 用 strong：持有的就是傳進來的那個物件本身。傳進來的如果是 NSMutableString，之後被外部修改，這個屬性的值也跟著變
@property (nonatomic, strong) NSString *symbol;
/// 從行情解析器共用的可變緩衝取出幣種
- (void)loadSymbolFromBuffer:(NSMutableString *)buffer;
@end

// MARK: - 修正版
@interface SOLabL02FixedOrder : NSObject
/// 用 copy：設值的當下複製一份，之後外部怎麼改都不影響
@property (nonatomic, copy) NSString *symbol;
- (void)loadSymbolFromBuffer:(NSMutableString *)buffer;
@end

NS_ASSUME_NONNULL_END
