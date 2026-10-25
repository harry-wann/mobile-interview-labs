#import "PRLabL02.h"

@implementation PRLabL02BugOrder

- (void)loadSymbolFromBuffer:(NSMutableString *)buffer {
    self.symbol = buffer;
}

@end

@implementation PRLabL02FixedOrder

- (void)loadSymbolFromBuffer:(NSMutableString *)buffer {
    self.symbol = buffer;
}

@end
