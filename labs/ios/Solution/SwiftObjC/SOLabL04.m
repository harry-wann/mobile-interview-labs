#import "SOLabL04Legacy.h"
#import "SOLabL04.h"

@implementation SOLabL04LegacyOrderService

- (instancetype)init {
    if ((self = [super init])) { _symbol = @"BTC/JPY"; }
    return self;
}

- (NSString *)memoForOrder:(NSString *)orderID {
    return nil;
}

@end

@implementation SOLabL04OrderService

- (instancetype)init {
    if ((self = [super init])) { _symbol = @"BTC/JPY"; }
    return self;
}

- (nullable NSString *)memoForOrder:(NSString *)orderID {
    return nil;
}

@end
