// L03：block 裡直接用 self，物件永遠不會被釋放
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

// MARK: - Bug 版
@interface SOLabL03BugPriceListener : NSObject
/// block 屬性用 copy，物件持有它
@property (nonatomic, copy, nullable) void (^handler)(double price);
@property (nonatomic, assign) double lastPrice;
/// 還活著的 PriceListener 有幾個
+ (NSInteger)aliveCount;
/// 設定 handler：收到價格時更新 lastPrice
- (void)start;
@end

// MARK: - 修正版
@interface SOLabL03FixedPriceListener : NSObject
@property (nonatomic, copy, nullable) void (^handler)(double price);
@property (nonatomic, assign) double lastPrice;
+ (NSInteger)aliveCount;
- (void)start;
@end

NS_ASSUME_NONNULL_END
