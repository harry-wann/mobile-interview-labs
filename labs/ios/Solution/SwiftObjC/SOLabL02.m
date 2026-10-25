#import "SOLabL02.h"

@implementation SOLabL02BugOrder

- (void)loadSymbolFromBuffer:(NSMutableString *)buffer {
    self.symbol = buffer;
}

@end

@implementation SOLabL02FixedOrder

- (void)loadSymbolFromBuffer:(NSMutableString *)buffer {
    self.symbol = buffer;
}

@end
