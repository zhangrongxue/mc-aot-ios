#import <Foundation/Foundation.h>

extern int horizon_ios_run(const char *resources, const char *game_dir);

// The engine app has no UIKit shell: winit's iOS backend calls
// `UIApplicationMain` itself and creates the window from Rust.
int main(int argc, char *argv[]) {
    @autoreleasepool {
        NSFileManager *manager = [NSFileManager defaultManager];
        NSArray<NSString *> *documents =
            NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES);
        NSString *gameDir = documents.firstObject ?: NSTemporaryDirectory();
        [manager createDirectoryAtPath:gameDir
           withIntermediateDirectories:YES
                            attributes:nil
                                 error:nil];

        NSString *resources =
            [[[NSBundle mainBundle] resourcePath] stringByAppendingPathComponent:@"horizon-client/26.3"];
        [manager createDirectoryAtPath:resources
           withIntermediateDirectories:YES
                            attributes:nil
                                 error:nil];

        setenv("HORIZON_HOME", gameDir.UTF8String, 1);
        setenv("HORIZON_NO_AUDIO", "1", 1);
        setenv("HORIZON_NO_DISCORD", "1", 1);

        return horizon_ios_run(resources.UTF8String, gameDir.UTF8String);
    }
}
