// L04 的修正版：用 NS_ASSUME_NONNULL_BEGIN／END 包住整個 header，只替真的可能是空的標 nullable
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface SOLabL04OrderService : NSObject
@property (nonatomic, copy) NSString *symbol;                                    // Swift：String
- (nullable NSString *)memoForOrder:(NSString *)orderID;                         // Swift：memo(forOrder:) -> String?
@end

NS_ASSUME_NONNULL_END
